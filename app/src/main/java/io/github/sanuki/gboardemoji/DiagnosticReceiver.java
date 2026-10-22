package io.github.sanuki.gboardemoji;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** An explicit broadcast avoids needing to alter Gboard's package-visibility declarations. */
public final class DiagnosticReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!DiagnosticReporter.ACTION.equals(intent.getAction())) return;
        try {
            PendingIntent identity = intent.getParcelableExtra("identity");
            int uid = context.getPackageManager().getApplicationInfo(EmojiHook.TARGET, 0).uid;
            if (identity == null || !EmojiHook.TARGET.equals(identity.getCreatorPackage())
                    || identity.getCreatorUid() != uid) return;
            Bundle status = intent.getBundleExtra("status");
            if (status == null) return;
            SharedPreferences.Editor editor = context.getSharedPreferences(DiagnosticReporter.PREFS, 0).edit();
            for (String key : new String[]{"module", "phase", "detail", "candidate"}) {
                String value = status.getString(key, "");
                editor.putString(key, value.substring(0, Math.min(value.length(), 1000)));
            }
            for (String key : new String[]{"pid", "batches", "events", "eligible", "mapped", "inserted", "selected"}) {
                editor.putInt(key, Math.max(0, status.getInt(key)));
            }
            editor.putLong("updated", System.currentTimeMillis()).apply();
        } catch (RuntimeException | android.content.pm.PackageManager.NameNotFoundException ignored) {
            // Invalid senders/reports cannot affect the keyboard or expose diagnostic data.
        }
    }
}
