package io.github.sanuki.gboardemoji;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity {
    private TextView statusText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            statusText.setText(buildStatus());
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);

        CharSequence appName = getApplicationInfo().loadLabel(getPackageManager());
        addText(content, appName + " " + DiagnosticReporter.VERSION, 22);
        statusText = addText(content, buildStatus(), 15);

        Button copy = new Button(this);
        copy.setText("复制状态信息");
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(appName, buildStatus()));
        });
        content.addView(copy, new LinearLayout.LayoutParams(-1, -2));

        addText(content, "\n配置入口（已集成进 Gboard 原生设置）：\n"
                + "• Gboard 设置 → 语言 → 简体中文 → 拼音 → 拼音方案 → 选择「双拼 - 自然码辅助码」（与原生自然码完全独立）\n"
                + "• 同一页面内可直接开关「Emoji 第 2 候选增强」\n\n"
                + "快捷测试：\n"
                + "• 3 码单字：vfj → 镇（空格直接上屏）\n"
                + "• 4 码词优先：xcku → 1. 笑哭  2. 😂；nire/ → 1. 你\n"
                + "• 词句混输：rjhuk → rjh uk（燃/燃烧，选燃后暂存拼音区，退格可撤销）", 15);

        EditText test = new EditText(this);
        test.setHint("在这里直接打字测试");
        test.setMinLines(3);
        content.addView(test, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private String buildStatus() {
        String gboardLine;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(EmojiHook.TARGET, 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            gboardLine = "已安装 Gboard：" + info.versionName + " (" + code + ")";
        } catch (Exception e) {
            gboardLine = "未检测到 Gboard";
        }

        SharedPreferences data = getSharedPreferences(DiagnosticReporter.PREFS, 0);
        long updated = data.getLong("updated", 0);
        if (updated == 0) {
            return gboardLine + "\n模块尚未上报：请在 LSPosed 勾选 Gboard 并重启 Gboard。";
        }
        return gboardLine
                + "\n状态：" + data.getString("phase", "未知")
                + "（PID " + data.getInt("pid", 0) + "，" + DateFormat.getTimeInstance().format(new Date(updated)) + "）"
                + "\n" + data.getString("detail", "")
                + "\n辅码命中 " + data.getInt("aux_hits", 0) + " / 上屏 " + data.getInt("aux_selected", 0)
                + "　Emoji 命中 " + data.getInt("emoji_hits", 0) + " / 上屏 " + data.getInt("emoji_selected", 0);
    }

    private TextView addText(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setPadding(0, 0, 0, 16);
        parent.addView(text);
        return text;
    }
}
