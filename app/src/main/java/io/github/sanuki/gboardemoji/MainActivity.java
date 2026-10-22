package io.github.sanuki.gboardemoji;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Button;
import android.content.SharedPreferences;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import java.text.DateFormat;
import java.util.Date;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private TextView diagnosticText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            diagnosticText.setText(diagnosticSummary());
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        CharSequence appName = getApplicationInfo().loadLabel(getPackageManager());
        addText(content, appName + " " + DiagnosticReporter.VERSION, 25);
        CharSequence description = getApplicationInfo().loadDescription(getPackageManager());
        if (description != null) addText(content, description.toString(), 16);
        diagnosticText = addText(content, diagnosticSummary(), 15);
        Button copy = new Button(this);
        copy.setText("复制运行诊断");
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(appName, diagnosticSummary()));
        });
        content.addView(copy, new LinearLayout.LayoutParams(-1, -2));
        String status;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(EmojiHook.TARGET, 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            boolean supported = java.util.Arrays.asList(getAssets().list("hooks")).contains(code + ".properties");
            status = "Gboard：" + info.versionName + "\n" + (supported
                    ? "已内置适配配置；实际是否生效请看上方运行诊断。"
                    : "模块会尝试自动识别此版本；结果显示在运行诊断。");
        } catch (Exception failure) { status = "未找到已安装的 Gboard。"; }
        addText(content, status, 16);
        addText(content, "安装后在 LSPosed 启用本模块，作用域勾选 Gboard，然后强行停止并重新打开 Gboard。", 16);
        addText(content, "预期：xiaoku（自然码双拼 xcku）\n1. 笑哭　2. 😂　3. 原第二候选\n第一候选和空格默认上屏保持原行为。", 18);
        try (InputStreamReader input = new InputStreamReader(getAssets().open("emoji-map.tsv"), StandardCharsets.UTF_8)) {
            EmojiDictionary dictionary = EmojiDictionary.read(input);
            addText(content, "映射来自本地万象 Rime，共 " + dictionary.size() + " 条含 emoji 的映射。\n笑哭：" + dictionary.lookup("笑哭"), 16);
        } catch (Exception failure) { addText(content, "映射加载失败：" + failure.getMessage(), 16); }
        addText(content, "在下面测试：输入 xiaoku / xcku，点击第二候选；应只上屏 😂，继续输入应开始新一轮拼写。", 16);
        EditText test = new EditText(this);
        test.setHint("输入测试");
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

    private String diagnosticSummary() {
        SharedPreferences data = getSharedPreferences(DiagnosticReporter.PREFS, 0);
        long updated = data.getLong("updated", 0);
        if (updated == 0) return "运行诊断：尚未收到 Gboard 的上报。\n启用模块并强停、重开 Gboard 后，在下面输入测试。";
        String version = data.getString("module", "未知");
        String status = "运行诊断（来自 Gboard 进程）\n模块 " + version + "，进程 " + data.getInt("pid", 0)
                + "，上报时间 " + DateFormat.getTimeInstance().format(new Date(updated))
                + "\n" + data.getString("phase", "未知状态") + "\n" + data.getString("detail", "")
                + "\n候选生成 " + data.getInt("batches", 0) + "，发送事件 " + data.getInt("events", 0)
                + "\n符合条件 " + data.getInt("eligible", 0) + "，映射命中 " + data.getInt("mapped", 0)
                + "，插入 " + data.getInt("inserted", 0) + "，上屏 " + data.getInt("selected", 0)
                + "\n" + data.getString("candidate", "尚未收到候选事件");
        if (!DiagnosticReporter.VERSION.equals(version)) status += "\n这是旧版模块的上报，请强停并重开 Gboard。";
        return status;
    }

    private TextView addText(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setPadding(0, 0, 0, 20);
        parent.addView(text);
        return text;
    }
}
