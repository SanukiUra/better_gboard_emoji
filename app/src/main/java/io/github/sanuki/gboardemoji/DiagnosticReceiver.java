package io.github.sanuki.gboardemoji;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

public final class DiagnosticReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!DiagnosticReporter.ACTION.equals(intent.getAction())) return;
        Bundle status = intent.getBundleExtra("status");
        if (status == null) return;
        SharedPreferences.Editor editor = context.getSharedPreferences(DiagnosticReporter.PREFS, 0).edit();
        for (String key : new String[]{"module", "phase", "detail"}) {
            editor.putString(key, status.getString(key, ""));
        }
        for (String key : new String[]{"pid", "aux_hits", "aux_selected", "emoji_hits", "emoji_selected"}) {
            editor.putInt(key, status.getInt(key, 0));
        }
        editor.putLong("updated", System.currentTimeMillis()).apply();
    }
}
