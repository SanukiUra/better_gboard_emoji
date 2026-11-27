package io.github.sanuki.gboardemoji;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class DiagnosticReporter {
    static final String MODULE = "io.github.sanuki.gboardemoji";
    static final String VERSION = BuildConfig.VERSION_NAME;
    static final String ACTION = MODULE + ".REPORT";
    static final String PREFS = "runtime-status";

    private final Context context;
    private final Bundle state = new Bundle();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "GboardWanxiang-status");
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

    synchronized void status(String phase, String detail) {
        state.putString("phase", phase);
        state.putString("detail", detail);
        schedule();
    }

    synchronized void count(String key) {
        state.putInt(key, state.getInt(key) + 1);
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
                Intent report = new Intent(ACTION)
                        .setComponent(new ComponentName(MODULE, MODULE + ".DiagnosticReceiver"))
                        .putExtra("status", snapshot);
                context.sendBroadcast(report);
            } catch (RuntimeException ignored) {}
        }, 500, TimeUnit.MILLISECONDS);
    }
}
