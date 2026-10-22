package io.github.sanuki.gboardemoji;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Sends coalesced structural counters off the input thread, without typed words. */
final class DiagnosticReporter {
    static final String MODULE = "io.github.sanuki.gboardemoji";
    static final String VERSION = BuildConfig.VERSION_NAME;
    static final String ACTION = MODULE + ".REPORT";
    static final String PREFS = "runtime-diagnostics";
    private final Context context;
    private final Bundle state = new Bundle();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "GboardRimeEmoji-diagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private boolean pending;

    DiagnosticReporter(Context context) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        state.putString("module", VERSION);
        state.putInt("pid", android.os.Process.myPid());
    }

    synchronized void phase(String phase, String detail) {
        state.putString("phase", phase);
        state.putString("detail", detail);
        schedule();
    }

    synchronized void count(String key) {
        state.putInt(key, state.getInt(key) + 1);
        schedule();
    }

    synchronized void candidate(String metadata, boolean eligible, boolean mapped, boolean changed) {
        state.putInt("events", state.getInt("events") + 1);
        if (eligible) state.putInt("eligible", state.getInt("eligible") + 1);
        if (mapped) state.putInt("mapped", state.getInt("mapped") + 1);
        if (changed) state.putInt("inserted", state.getInt("inserted") + 1);
        state.putString("candidate", metadata);
        schedule();
    }

    private void schedule() {
        if (pending) return;
        pending = true;
        worker.schedule(() -> {
            Bundle snapshot;
            synchronized (DiagnosticReporter.this) {
                snapshot = new Bundle(state);
                pending = false;
            }
            try {
                ComponentName receiver = new ComponentName(MODULE, MODULE + ".DiagnosticReceiver");
                Intent identity = new Intent(MODULE + ".IDENTITY").setComponent(receiver);
                PendingIntent token = PendingIntent.getBroadcast(context, 0, identity, PendingIntent.FLAG_IMMUTABLE);
                Intent report = new Intent(ACTION).setComponent(receiver);
                report.putExtra("identity", token);
                report.putExtra("status", snapshot);
                context.sendBroadcast(report);
            } catch (RuntimeException failure) {
                android.util.Log.w("GboardRimeEmoji", "诊断上报失败：" + failure.getClass().getSimpleName());
            }
        }, 500, TimeUnit.MILLISECONDS);
    }
}
