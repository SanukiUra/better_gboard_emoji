package io.github.sanuki.gboardemoji;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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
    private static final String TAG = "[GboardWanxiang] ";
    private static final int KEYCODE_DEL = 0x43;

    private static String modulePath;
    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile DiagnosticReporter diagnostics;

    private final ThreadLocal<Deque<Batch>> batches = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Boolean> internalReset = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Map<Object, Boolean> slashModes = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, ZiranmaAuxEngine.AuxItem> activeDefaultAux = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Deque<StagedSegment>> stagedSegments = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class StagedSegment {
        final String text;
        final String rawConsumed;
        final int remainingLenAtStage;
        StagedSegment(String text, String rawConsumed, int remainingLenAtStage) {
            this.text = text;
            this.rawConsumed = rawConsumed;
            this.remainingLenAtStage = remainingLenAtStage;
        }
    }

    private static final class Batch {
        final Object processor;
        final boolean first;
        Batch(Object processor, boolean first) { this.processor = processor; this.first = first; }
    }

    @Override public void initZygote(StartupParam param) { modulePath = param.modulePath; }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (!TARGET.equals(param.packageName) || !TARGET.equals(param.processName)) return;
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        XposedBridge.hookMethod(attach, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (hook.hasThrowable() || !initialized.compareAndSet(false, true)) return;
                Context context = (Context) hook.args[0];
                diagnostics = new DiagnosticReporter(context);
                diagnostics.status("正在初始化", "加载 Gboard 配置与万象词表");
                new Thread(() -> {
                    try {
                        install(context, param.classLoader);
                    } catch (Throwable t) {
                        diagnostics.status("初始化失败", shortError(t));
                        log("初始化失败：" + t);
                    }
                }, "GboardWanxiang-init").start();
            }
        });
    }

    private void install(Context context, ClassLoader loader) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(TARGET, 0);
        long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        if (modulePath == null) {
            modulePath = context.getPackageManager().getApplicationInfo(DiagnosticReporter.MODULE, 0).sourceDir;
        }

        HookProfile profile = null;
        EmojiDictionary emojiDict;
        ZiranmaAuxDictionary auxDict;
        String profileSource = "内置配置";

        try (ZipFile apk = new ZipFile(modulePath)) {
            ZipEntry entry = apk.getEntry("assets/hooks/" + version + ".properties");
            if (entry != null) {
                try (InputStream in = apk.getInputStream(entry)) {
                    HookProfile candidate = HookProfile.read(in);
                    if (candidate.has("keyEventMethod") && candidate.has("pinyinEngineFactoryClass")
                            && candidate.has("composingMethod") && candidate.has("pgAddMethod")
                            && candidate.has("baseCommitMethod")) {
                        profile = candidate;
                    }
                }
            }
            if (profile == null) {
                profileSource = "动态字节码解析";
                List<File> targetApks = new ArrayList<>();
                targetApks.add(new File(info.applicationInfo.sourceDir));
                if (info.applicationInfo.splitSourceDirs != null) {
                    for (String path : info.applicationInfo.splitSourceDirs) targetApks.add(new File(path));
                }
                profile = DexProfileResolver.resolve(targetApks, info.versionName);
            }
            try (InputStream in = apk.getInputStream(apk.getEntry("assets/emoji-map.tsv"))) {
                emojiDict = EmojiDictionary.read(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            try (InputStream in = apk.getInputStream(apk.getEntry("assets/ziranma-aux.bin"))) {
                auxDict = ZiranmaAuxDictionary.read(in);
            }
        }

        ReflectiveCandidateAccess access = new ReflectiveCandidateAccess(loader, profile);
        CandidateAugmenter emojiAugmenter = new CandidateAugmenter(emojiDict, access);
        ZiranmaAuxEngine auxEngine = new ZiranmaAuxEngine(auxDict);

        Class<?> chinese = Class.forName(profile.get("chineseProcessor"), false, loader);
        Class<?> pinyin = Class.forName(profile.get("pinyinProcessor"), false, loader);
        Class<?> hmm = Class.forName(profile.get("hmmProcessor"), false, loader);
        Class<?> baseProcessor = Class.forName("com.google.android.libraries.inputmethod.ime.processor.core.BaseDecodeProcessor", false, loader);
        Class<?> eventClass = Class.forName(profile.get("eventClass"), false, loader);
        Class<?> deliveryClass = Class.forName(profile.get("deliveryClass"), false, loader);

        Method select = chinese.getDeclaredMethod(profile.get("selectMethod"), access.candidateClass(), boolean.class);
        Method delete = chinese.getDeclaredMethod(profile.get("deleteMethod"), access.candidateClass());
        Method commit = hmm.getDeclaredMethod(profile.get("commitMethod"), CharSequence.class);
        Method baseCommit = baseProcessor.getDeclaredMethod(profile.get("baseCommitMethod"), CharSequence.class, int.class);
        Method reset = hmm.getDeclaredMethod(profile.get("resetMethod"));
        Method composing = hmm.getDeclaredMethod(profile.get("composingMethod"), CharSequence.class);
        Method produce = hmm.getDeclaredMethod(profile.get("batchMethod"), int.class);
        Field iterator = hmm.getDeclaredField(profile.get("iteratorField"));
        Method deliver = deliveryClass.getDeclaredMethod(profile.get("deliveryMethod"),
                List.class, access.candidateClass(), boolean.class, Object.class);

        Method keyEvent = pinyin.getDeclaredMethod(profile.get("keyEventMethod"), eventClass);
        Method space = pinyin.getDeclaredMethod(profile.get("spaceMethod"));
        Method keyDataGetter = eventClass.getDeclaredMethod(profile.get("keyDataGetter"));
        Field keyCodeField = keyDataGetter.getReturnType().getDeclaredField(profile.get("keyCodeField"));
        Field keyPayloadField = keyDataGetter.getReturnType().getDeclaredField(profile.get("keyPayloadField"));

        commit.setAccessible(true);
        reset.setAccessible(true);
        iterator.setAccessible(true);
        keyDataGetter.setAccessible(true);
        keyCodeField.setAccessible(true);
        keyPayloadField.setAccessible(true);

        HmmBridge bridge = new HmmBridge(loader, profile, commit, reset);
        CandidateBatchGate gate = new CandidateBatchGate();
        GboardSettingsHook.install(loader, profile);

        // Deoptimize batch producer so its saved body does not retain an inlined copy of deliveryMethod.
        try {
            Method deopt = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
            deopt.invoke(null, produce);
        } catch (Throwable ignored) {}

        XposedBridge.hookMethod(select, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                Object candidate = hook.args[0];
                boolean shouldCommit = Boolean.TRUE.equals(hook.args[1]);
                String emoji = access.emoji(candidate);
                if (emoji != null) {
                    hook.setResult(true);
                    try {
                        EmojiSelection.handle(emoji, shouldCommit, new EmojiSelection.Operations() {
                            @Override public void commit(String value) throws Exception { commit.invoke(hook.thisObject, value); }
                            @Override public void reset() throws Exception {
                                reset.invoke(hook.thisObject);
                                diagnostics.count("emoji_selected");
                            }
                        });
                    } catch (Throwable t) { log("Emoji 上屏异常：" + t); }
                    return;
                }
                ZiranmaAuxEngine.AuxItem aux = access.auxItem(candidate);
                if (aux != null) {
                    hook.setResult(true);
                    if (!shouldCommit) return;
                    try {
                        slashModes.remove(hook.thisObject);
                        activeDefaultAux.remove(hook.thisObject);
                        if (aux.remainingRawTail != null && !aux.remainingRawTail.isEmpty()) {
                            HmmBridge.CompositionState state = bridge.readComposition(hook.thisObject);
                            String rawConsumed = (state != null && aux.consumedRawChars <= state.rawTail.length())
                                    ? state.rawTail.substring(0, aux.consumedRawChars) : "";
                            String stageText = (state == null ? "" : state.convertedPrefix) + aux.text;
                            pushStaged(hook.thisObject, new StagedSegment(stageText, rawConsumed, aux.remainingRawTail.length()));
                            internalReset.set(Boolean.TRUE);
                            try {
                                bridge.resetAndReplay(hook.thisObject, aux.remainingRawTail);
                            } finally {
                                internalReset.set(Boolean.FALSE);
                            }
                        } else {
                            bridge.commitAux(hook.thisObject, aux.text, "");
                        }
                        diagnostics.count("aux_selected");
                    } catch (Throwable t) { log("辅码候选上屏异常：" + t); }
                }
            }
        });

        XposedBridge.hookMethod(delete, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (access.injected(hook.args[0])) hook.setResult(true);
            }
        });

        XposedBridge.hookMethod(baseCommit, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                CharSequence text = (CharSequence) hook.args[0];
                if (text == null || text.length() == 0) return;
                String staged = consumeStagedText(hook.thisObject);
                if (!staged.isEmpty()) {
                    hook.args[0] = staged + text;
                }
            }
        });

        XposedBridge.hookMethod(reset, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                slashModes.remove(hook.thisObject);
                activeDefaultAux.remove(hook.thisObject);
                if (!Boolean.TRUE.equals(internalReset.get())) {
                    stagedSegments.remove(hook.thisObject);
                }
            }
        });

        XposedBridge.hookMethod(composing, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                CharSequence current = (CharSequence) hook.args[0];
                if (current == null || current.length() == 0) return;
                if (!pinyin.isInstance(hook.thisObject) || !bridge.isZiranmaAuxActive(hook.thisObject)) return;
                try {
                    String staged = peekStagedText(hook.thisObject);
                    HmmBridge.CompositionState state = bridge.readComposition(hook.thisObject);
                    if (state == null) return;
                    String formatted = null;
                    if (state.rawTail.length() >= 3) {
                        boolean slash = Boolean.TRUE.equals(slashModes.get(hook.thisObject));
                        formatted = auxEngine.formatComposing(
                                state.rawTail, slash, state.primaryHasFullSpanWord, bridge.shadowDecoder());
                    }
                    if (formatted != null) {
                        String tail = state.convertedPrefix + formatted;
                        hook.args[0] = staged.isEmpty() ? tail : (staged + " " + tail);
                    } else if (!staged.isEmpty()) {
                        hook.args[0] = staged + " " + current;
                    }
                } catch (Throwable t) {
                    log("拼音行分词格式化异常：" + t);
                }
            }
        });

        XposedBridge.hookMethod(space, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                ZiranmaAuxEngine.AuxItem item = activeDefaultAux.get(hook.thisObject);
                if (item == null) return;
                hook.setResult(true);
                try {
                    slashModes.remove(hook.thisObject);
                    activeDefaultAux.remove(hook.thisObject);
                    bridge.commitAux(hook.thisObject, item.text, item.remainingRawTail);
                    diagnostics.count("aux_selected");
                } catch (Throwable t) { log("空格上屏辅码异常：" + t); }
            }
        });

        XposedBridge.hookMethod(keyEvent, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (!bridge.isZiranmaAuxActive(hook.thisObject)) return;
                try {
                    Object keyData = keyDataGetter.invoke(hook.args[0]);
                    if (keyData == null) return;
                    int code = keyCodeField.getInt(keyData);
                    Object payload = keyPayloadField.get(keyData);
                    if ("/".equals(payload)) {
                        HmmBridge.CompositionState state = bridge.readComposition(hook.thisObject);
                        if (state != null && (state.rawTail.length() == 3 || state.rawTail.length() == 4)
                                && !Boolean.TRUE.equals(slashModes.get(hook.thisObject))) {
                            slashModes.put(hook.thisObject, Boolean.TRUE);
                            bridge.refresh(hook.thisObject);
                            hook.setResult(true);
                            return;
                        }
                    } else if (code == KEYCODE_DEL) {
                        if (Boolean.TRUE.equals(slashModes.get(hook.thisObject))) {
                            slashModes.remove(hook.thisObject);
                            bridge.refresh(hook.thisObject);
                            hook.setResult(true);
                            return;
                        }
                        Deque<StagedSegment> stack = stagedSegments.get(hook.thisObject);
                        if (stack != null && !stack.isEmpty()) {
                            StagedSegment top = stack.peekLast();
                            HmmBridge.CompositionState state = bridge.readComposition(hook.thisObject);
                            String curTail = state == null ? "" : state.rawTail;
                            if (top != null && curTail.length() <= top.remainingLenAtStage) {
                                stack.pollLast();
                                if (stack.isEmpty()) stagedSegments.remove(hook.thisObject);
                                String restored = top.rawConsumed + curTail;
                                if (!restored.isEmpty()) {
                                    internalReset.set(Boolean.TRUE);
                                    try {
                                        bridge.resetAndReplay(hook.thisObject, restored);
                                    } finally {
                                        internalReset.set(Boolean.FALSE);
                                    }
                                    hook.setResult(true);
                                    return;
                                }
                            }
                        }
                    }
                    slashModes.remove(hook.thisObject);
                } catch (Throwable t) {
                    log("按键拦截异常：" + t);
                }
            }
        });

        XposedBridge.hookMethod(produce, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (!chinese.isInstance(hook.thisObject)) return;
                boolean first = false;
                try {
                    first = gate.first(hook.thisObject, iterator.get(hook.thisObject));
                } catch (Throwable t) { log("批次门控异常：" + t); }
                batches.get().push(new Batch(hook.thisObject, first));
            }
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (!chinese.isInstance(hook.thisObject)) return;
                Deque<Batch> stack = batches.get();
                if (!stack.isEmpty()) stack.pop();
                if (stack.isEmpty()) batches.remove();
            }
        });

        XposedBridge.hookMethod(deliver, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (!chinese.isInstance(hook.args[3]) || !(hook.args[0] instanceof List)) return;
                Deque<Batch> stack = batches.get();
                Batch batch = stack.peek();
                if (batch == null || batch.processor != hook.args[3] || !batch.first) return;

                Object processor = hook.args[3];
                List<?> current = (List<?>) hook.args[0];
                if (current.isEmpty()) return;

                try {
                    activeDefaultAux.remove(processor);

                    // 1. Wanxiang Ziranma auxiliary code resolution (only when "双拼 - 自然码辅助码" is selected).
                    if (pinyin.isInstance(processor) && bridge.isZiranmaAuxActive(processor)) {
                        HmmBridge.CompositionState state = bridge.readComposition(processor);
                        if (state != null && state.rawTail.length() >= 3) {
                            boolean slash = Boolean.TRUE.equals(slashModes.get(processor));
                            ZiranmaAuxEngine.AuxPlan plan = auxEngine.resolve(
                                    state.rawTail, slash, state.primaryHasFullSpanWord, bridge.shadowDecoder());
                            if (plan != null && !plan.items.isEmpty()) {
                                List<Object> merged = mergeAuxCandidates(current, plan, access);
                                current = merged;
                                if (plan.promoteFirstToDefault) {
                                    hook.args[1] = merged.get(0);
                                    activeDefaultAux.put(processor, plan.items.get(0));
                                }
                                diagnostics.count("aux_hits");
                            }
                        }
                    }

                    // 2. Emoji candidate insertion at slot #2 (controlled by Gboard Pinyin settings switch).
                    if (!pinyin.isInstance(processor) || bridge.isEmojiEnabled(processor)) {
                        List<?> withEmoji = emojiAugmenter.augment(current);
                        if (withEmoji != current) {
                            current = withEmoji;
                            diagnostics.count("emoji_hits");
                        }
                    }

                    hook.args[0] = current;
                } catch (Throwable t) {
                    log("候选增强异常：" + t);
                }
            }
        });

        String summary = "Gboard " + info.versionName + " (" + profileSource + ")\n"
                + "自然码辅码：" + auxDict.charCount() + " 字 / " + auxDict.entryCount() + " 条\n"
                + "Emoji 映射：" + emojiDict.size() + " 条";
        diagnostics.status("已激活", summary);
        log("模块已就绪：" + summary.replace('\n', '；'));
    }

    private static List<Object> mergeAuxCandidates(
            List<?> original, ZiranmaAuxEngine.AuxPlan plan, ReflectiveCandidateAccess access) throws Exception {
        Object seed = original.get(0);
        List<Object> merged = new ArrayList<>(original.size() + plan.items.size());
        Set<String> seen = new HashSet<>();

        int insertAt = Math.min(plan.insertAt, original.size());
        for (int i = 0; i < insertAt; i++) {
            Object cand = original.get(i);
            merged.add(cand);
            String t = access.text(cand);
            if (t != null) seen.add(t);
        }

        for (int i = 0; i < plan.items.size(); i++) {
            ZiranmaAuxEngine.AuxItem item = plan.items.get(i);
            if (!seen.add(item.text)) continue;
            boolean highlight = (i == 0 && plan.promoteFirstToDefault);
            merged.add(access.createAux(seed, item, highlight));
        }

        for (int i = insertAt; i < original.size(); i++) {
            Object cand = original.get(i);
            String t = access.text(cand);
            if (t == null || seen.add(t)) merged.add(cand);
        }
        return merged;
    }

    private void pushStaged(Object processor, StagedSegment seg) {
        Deque<StagedSegment> stack = stagedSegments.get(processor);
        if (stack == null) {
            stack = new ArrayDeque<>();
            stagedSegments.put(processor, stack);
        }
        stack.addLast(seg);
    }

    private String peekStagedText(Object processor) {
        Deque<StagedSegment> stack = stagedSegments.get(processor);
        if (stack == null || stack.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (StagedSegment seg : stack) sb.append(seg.text);
        return sb.toString();
    }

    private String consumeStagedText(Object processor) {
        Deque<StagedSegment> stack = stagedSegments.remove(processor);
        if (stack == null || stack.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (StagedSegment seg : stack) sb.append(seg.text);
        return sb.toString();
    }

    private static String shortError(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static void log(String message) { XposedBridge.log(TAG + message); }
}
