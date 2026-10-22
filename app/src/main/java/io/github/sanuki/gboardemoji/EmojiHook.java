package io.github.sanuki.gboardemoji;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class EmojiHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    public static final String TARGET = "com.google.android.inputmethod.latin";
    private static final String TAG = "[GboardRimeEmoji] ";
    private static String modulePath;
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final AtomicBoolean runtimeErrorReported = new AtomicBoolean();
    private final AtomicBoolean deliveryReported = new AtomicBoolean();
    private final AtomicBoolean insertionReported = new AtomicBoolean();
    private volatile DiagnosticReporter diagnostics;
    private volatile boolean enabled;
    private final ThreadLocal<Deque<Batch>> batches = ThreadLocal.withInitial(ArrayDeque::new);

    private static final class Batch {
        final Object processor;
        final boolean first;
        Batch(Object processor, boolean first) { this.processor = processor; this.first = first; }
    }

    @Override public void initZygote(StartupParam param) { modulePath = param.modulePath; }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (!TARGET.equals(param.packageName) || !TARGET.equals(param.processName)) return;
        log("模块 " + DiagnosticReporter.VERSION + " 已进入 Gboard 进程");
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        XposedBridge.hookMethod(attach, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (hook.hasThrowable() || !initialized.compareAndSet(false, true)) return;
                Context context = (Context) hook.args[0];
                diagnostics = new DiagnosticReporter(context);
                diagnostics.phase("正在初始化", "正在读取 Gboard 适配配置");
                new Thread(() -> {
                    try { install(context, param.classLoader); }
                    catch (Throwable failure) {
                        diagnostics.phase("初始化失败", error(failure));
                        log("初始化失败；保留 Gboard 原行为：" + failure);
                    }
                }, "GboardRimeEmoji-init").start();
            }
        });
    }

    private void install(Context context, ClassLoader loader) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(TARGET, 0);
        long version = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        HookProfile profile;
        EmojiDictionary dictionary;
        if (modulePath == null) modulePath = context.getPackageManager()
                .getApplicationInfo(DiagnosticReporter.MODULE, 0).sourceDir;
        try (ZipFile apk = new ZipFile(modulePath)) {
            ZipEntry entry = apk.getEntry("assets/hooks/" + version + ".properties");
            if (entry == null) {
                List<File> targetApks = new ArrayList<>();
                targetApks.add(new File(info.applicationInfo.sourceDir));
                if (info.applicationInfo.splitSourceDirs != null) {
                    for (String path : info.applicationInfo.splitSourceDirs) targetApks.add(new File(path));
                }
                log("正在识别 Gboard " + info.versionName + " (" + version + ") 的候选结构");
                profile = DexProfileResolver.resolve(targetApks, info.versionName);
                log("已识别候选结构：" + profile.get("candidateClass") + "，选择方法 " + profile.get("selectMethod"));
            } else {
                try (InputStream input = apk.getInputStream(entry)) { profile = HookProfile.read(input); }
            }
            try (InputStream input = apk.getInputStream(apk.getEntry("assets/emoji-map.tsv"))) {
                dictionary = EmojiDictionary.read(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        }
        ReflectiveCandidateAccess access = new ReflectiveCandidateAccess(loader, profile);
        CandidateAugmenter augmenter = new CandidateAugmenter(dictionary, access);
        Class<?> chinese = Class.forName(profile.get("chineseProcessor"), false, loader);
        Class<?> hmm = Class.forName(profile.get("hmmProcessor"), false, loader);
        Method select = chinese.getDeclaredMethod(profile.get("selectMethod"), access.candidateClass(), boolean.class);
        Method delete = chinese.getDeclaredMethod(profile.get("deleteMethod"), access.candidateClass());
        Method commit = hmm.getDeclaredMethod(profile.get("commitMethod"), CharSequence.class);
        Method reset = hmm.getDeclaredMethod(profile.get("resetMethod"));
        if (select.getReturnType() != boolean.class || delete.getReturnType() != boolean.class
                || commit.getReturnType() != void.class || reset.getReturnType() != void.class) {
            throw new IllegalArgumentException("Decoder method signature changed");
        }
        commit.setAccessible(true);
        reset.setAccessible(true);
        Method produce = hmm.getDeclaredMethod(profile.get("batchMethod"), int.class);
        Field iterator = hmm.getDeclaredField(profile.get("iteratorField"));
        Class<?> deliveryClass = Class.forName(profile.get("deliveryClass"), false, loader);
        Method deliver = deliveryClass.getDeclaredMethod(profile.get("deliveryMethod"),
                List.class, access.candidateClass(), boolean.class, Object.class);
        if (produce.getReturnType() != boolean.class || iterator.getType() != Iterator.class
                || !Modifier.isStatic(deliver.getModifiers()) || deliver.getReturnType() != deliveryClass) {
            throw new IllegalArgumentException("Candidate delivery signature changed");
        }
        iterator.setAccessible(true);
        CandidateBatchGate gate = new CandidateBatchGate();

        // Install selection hooks first. Partial installation cannot produce unhandled emoji candidates.
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            // Deoptimize the producer before hooking it, so its saved original body cannot retain
            // an inlined copy of the tiny factory. This is an LSPosed extension to legacy API 82.
            Method deopt = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
            deopt.invoke(null, produce);
            addHook(hooks, select, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    String emoji = access.emoji(hook.args[0]);
                    if (emoji == null) return;
                    // Consume the event even on failure, preventing the source Chinese word from committing.
                    hook.setResult(true);
                    try {
                        EmojiSelection.handle(emoji, (Boolean) hook.args[1], new EmojiSelection.Operations() {
                            @Override public void commit(String value) throws Exception { commit.invoke(hook.thisObject, value); }
                            @Override public void reset() throws Exception {
                                reset.invoke(hook.thisObject);
                                diagnostics.count("selected");
                            }
                        });
                    } catch (Throwable failure) { runtimeFailure(failure); }
                }
            });
            addHook(hooks, delete, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (access.injected(hook.args[0])) hook.setResult(true);
                }
            });
            addHook(hooks, produce, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!chinese.isInstance(hook.thisObject)) return;
                    boolean first = false;
                    if (enabled) {
                        diagnostics.count("batches");
                        try { first = gate.first(hook.thisObject, iterator.get(hook.thisObject)); }
                        catch (Throwable failure) { runtimeFailure(failure); }
                    }
                    batches.get().push(new Batch(hook.thisObject, first));
                }
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    if (!chinese.isInstance(hook.thisObject)) return;
                    Deque<Batch> stack = batches.get();
                    if (!stack.isEmpty()) stack.pop();
                    if (stack.isEmpty()) batches.remove();
                }
            });
            addHook(hooks, deliver, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!enabled || !chinese.isInstance(hook.args[3])) return;
                    Deque<Batch> stack = batches.get();
                    Batch batch = stack.peek();
                    if (batch == null || batch.processor != hook.args[3]) {
                        if (stack.isEmpty()) batches.remove();
                        return;
                    }
                    if (!(hook.args[0] instanceof List)) return;
                    try {
                        List<?> original = (List<?>) hook.args[0];
                        Object first = original.isEmpty() ? null : original.get(0);
                        boolean eligible = first != null && access.eligible(first);
                        boolean mapped = eligible && !dictionary.lookup(access.text(first)).isEmpty();
                        List<?> result = batch.first ? augmenter.augment(original) : original;
                        hook.args[0] = result;
                        // args[1] is Gboard's default candidate. It stays exactly the same object.
                        String metadata = access.metadata(first) + "，首批=" + batch.first + "，有映射=" + mapped;
                        diagnostics.candidate(metadata, eligible, mapped, result != original);
                        if (deliveryReported.compareAndSet(false, true)) {
                            log("中文候选事件已进入：" + metadata);
                        }
                        if (result != original && insertionReported.compareAndSet(false, true)) {
                            log("已向候选列表插入 emoji；默认候选保持原对象");
                        }
                    } catch (Throwable failure) { runtimeFailure(failure); }
                }
            });
            enabled = true;
            diagnostics.phase("候选 Hook 已安装", "Gboard " + info.versionName + "\n中文候选发送入口；已关闭入口方法内联");
            log("候选 Hook 已安装：模块 " + DiagnosticReporter.VERSION + "，Gboard " + info.versionName
                    + "，候选发送 " + deliver + "，映射 " + dictionary.size() + " 条");
        } catch (Throwable failure) {
            enabled = false;
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            throw new IllegalStateException("Hook installation failed", failure);
        }
    }

    private void runtimeFailure(Throwable failure) {
        enabled = false;
        if (diagnostics != null) diagnostics.phase("候选 Hook 运行失败", error(failure));
        if (runtimeErrorReported.compareAndSet(false, true)) log("已停止新增 emoji 候选：" + failure);
    }

    private static void addHook(List<XC_MethodHook.Unhook> hooks, Method method, XC_MethodHook callback) {
        XC_MethodHook.Unhook handle = XposedBridge.hookMethod(method, callback);
        if (handle == null) throw new IllegalStateException("Hook rejected: " + method);
        hooks.add(handle);
    }

    private static String error(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause();
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    private static void log(String value) { XposedBridge.log(TAG + value); }
}
