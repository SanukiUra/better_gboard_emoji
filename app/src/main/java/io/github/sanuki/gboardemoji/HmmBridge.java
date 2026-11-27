package io.github.sanuki.gboardemoji;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bridge to Gboard's unobfuscated HMM JNI boundary (EngineFactory / HmmEngineInterfaceImpl / Range / ScoredInput)
 * plus a minimal set of resolved processor/wrapper fields.
 */
final class HmmBridge {
    static final String ZIRANMA_TOKEN_DICT = "zh_t_i0_shuangpin_ziranma_android_token_dictionary";
    private static final String PINYIN_ENGINE_ID = "zh-t-i0-pinyin-x-f0-delight";
    private static final int MAX_SHADOW_CANDIDATES = 48;

    static final class CompositionState {
        final String convertedPrefix;
        final String rawTail;
        final boolean primaryHasFullSpanWord;

        CompositionState(String convertedPrefix, String rawTail, boolean primaryHasFullSpanWord) {
            this.convertedPrefix = convertedPrefix;
            this.rawTail = rawTail;
            this.primaryHasFullSpanWord = primaryHasFullSpanWord;
        }
    }

    private final Class<?> engineFactoryClass;
    private final Method engineFactoryInstance;
    private final Field engineFactoryPtrField;
    private final Method nativeCreateEngine;

    private final Class<?> hmmImplClass;
    private final Field implPtrField;
    private final Method implPtrGetter; // non-null if implPtrField is a wrapper like Lncb;

    private final Class<?> rangeClass;
    private final Constructor<?> rangeCtor;
    private final Field rangeStart;
    private final Field rangeEnd;

    private final Class<?> scoredInputClass;
    private final Constructor<?> scoredInputCtor;

    private final Method nativeReset;
    private final Method nativeAppend;
    private final Method nativeFillCandidateList;
    private final Method nativeGetCandidateCount;
    private final Method nativeGetCandidateString;
    private final Method nativeGetCandidateRange;
    private final Method nativeGetDecodingRange;
    private final Method nativeGetSegmentCount;
    private final Method nativeGetSegment;
    private final Method nativeGetSegmentRange;
    private final Method nativeIsSegmentConverted;
    private final Method nativeGetSegmentConvertedString;
    private final Method nativeGetSegmentTokenCount;
    private final Method nativeGetSegmentToken;
    private final Method nativeGetTokenInputUnitCount;
    private final Method nativeGetTokenInputUnit;
    private final Method nativeGetInputUnitString;
    private final Method nativeClose;

    private final Field wrapperField;           // AbstractHmmDecodeProcessor.m
    private final Field wrapperImplField;       // AbstractHmmEngineWrapper.j (HmmEngineInterfaceImpl)
    private final Field wrapperContextEndField; // AbstractHmmEngineWrapper.e (int)
    private final Method wrapperAppendMethod;   // AbstractHmmEngineWrapper.I/L([KeyData;[F)Z
    private final Class<?> keyDataClass;
    private final Constructor<?> keyDataCtor;

    private final Class<?> pinyinFactoryClass;
    private final Method pinyinFactoryGetMethod; // static n(Context) -> PinyinHmmEngineFactory
    private final Method tokenDictMethod;        // o() -> String
    private final Method processorFactoryGetter; // AbstractHmmPinyinDecodeProcessor.e() -> AbstractHmmEngineFactory
    private final Field prefStoreField;          // AbstractHmmEngineFactory.g -> PreferenceDataStore (Lcei;)
    private final Method prefGetStringMethod;    // Lcei.d(String, String) -> String
    private final Method prefGetBoolMethod;      // Lcei.k(String, boolean) -> boolean

    private final Method commitMethod;
    private final Method resetMethod;
    private final Method refreshMethod;

    private long shadowPtr;
    private final Object shadowLock = new Object();

    HmmBridge(ClassLoader loader, HookProfile profile, Method commitMethod, Method resetMethod) throws Exception {
        this.commitMethod = commitMethod;
        this.resetMethod = resetMethod;

        Class<?> hmmProcessor = Class.forName(profile.get("hmmProcessor"), false, loader);
        Class<?> chineseProcessor = Class.forName(profile.get("chineseProcessor"), false, loader);
        Class<?> pinyinProcessor = Class.forName(profile.get("pinyinProcessor"), false, loader);

        this.refreshMethod = chineseProcessor.getDeclaredMethod(profile.get("refreshMethod"), long.class);
        this.refreshMethod.setAccessible(true);

        this.wrapperField = hmmProcessor.getDeclaredField(profile.get("engineWrapperField"));
        this.wrapperField.setAccessible(true);

        Class<?> wrapperClass = Class.forName(profile.get("engineWrapperClass"), false, loader);
        this.hmmImplClass = Class.forName("com.google.android.apps.inputmethod.libs.hmm.HmmEngineInterfaceImpl", false, loader);
        this.wrapperImplField = findFieldOfType(wrapperClass, hmmImplClass);
        this.wrapperContextEndField = wrapperClass.getDeclaredField(profile.get("contextEndVertexField"));
        this.wrapperContextEndField.setAccessible(true);
        this.wrapperAppendMethod = findWrapperAppendMethod(wrapperClass);
        this.keyDataClass = wrapperAppendMethod == null ? null : wrapperAppendMethod.getParameterTypes()[0].getComponentType();
        this.keyDataCtor = keyDataClass == null ? null : findKeyDataCtor(keyDataClass);

        this.pinyinFactoryClass = Class.forName(profile.get("pinyinEngineFactoryClass"), false, loader);
        this.tokenDictMethod = pinyinFactoryClass.getDeclaredMethod(profile.get("tokenDictMethod"));
        this.tokenDictMethod.setAccessible(true);
        this.processorFactoryGetter = pinyinProcessor.getDeclaredMethod(profile.get("engineFactoryGetter"));
        this.processorFactoryGetter.setAccessible(true);
        this.pinyinFactoryGetMethod = findStaticFactoryGetter(pinyinFactoryClass);

        Class<?> prefStoreClass = Class.forName(profile.get("prefStoreClass"), false, loader);
        this.prefStoreField = findAssignableField(pinyinFactoryClass, prefStoreClass);
        this.prefGetStringMethod = declared(prefStoreClass, profile.get("prefGetStringMethod"), String.class, String.class);
        this.prefGetBoolMethod = declared(prefStoreClass, profile.get("prefGetBoolMethod"), String.class, boolean.class);

        this.engineFactoryClass = Class.forName("com.google.android.apps.inputmethod.libs.hmm.EngineFactory", false, loader);
        this.engineFactoryInstance = findSingletonGetter(engineFactoryClass);
        this.engineFactoryPtrField = findLongField(engineFactoryClass);
        this.nativeCreateEngine = declared(engineFactoryClass, "nativeCreateEngine", long.class, String.class, String.class);

        this.rangeClass = Class.forName("com.google.android.apps.inputmethod.libs.hmm.Range", false, loader);
        this.rangeCtor = rangeClass.getDeclaredConstructor(int.class, int.class);
        this.rangeCtor.setAccessible(true);
        this.rangeStart = rangeClass.getDeclaredField("startVertexIndex");
        this.rangeEnd = rangeClass.getDeclaredField("endVertexIndex");
        this.rangeStart.setAccessible(true);
        this.rangeEnd.setAccessible(true);

        this.scoredInputClass = Class.forName("com.google.android.apps.inputmethod.libs.hmm.ScoredInput", false, loader);
        this.scoredInputCtor = scoredInputClass.getDeclaredConstructor(String.class, float.class);
        this.scoredInputCtor.setAccessible(true);
        Class<?> scoredInputArrayClass = Array.newInstance(scoredInputClass, 0).getClass();

        Field ptrF = null;
        Method ptrGet = null;
        for (Field f : hmmImplClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == long.class) {
                ptrF = f;
                break;
            }
            for (Method m : f.getType().getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && m.getReturnType() == long.class) {
                    ptrF = f;
                    ptrGet = m;
                    ptrGet.setAccessible(true);
                    break;
                }
            }
            if (ptrF != null) break;
        }
        if (ptrF == null) throw new NoSuchFieldException("Native pointer field not found in HmmEngineInterfaceImpl");
        ptrF.setAccessible(true);
        this.implPtrField = ptrF;
        this.implPtrGetter = ptrGet;

        this.nativeReset = declared(hmmImplClass, "nativeReset", long.class);
        this.nativeAppend = declared(hmmImplClass, "nativeAppend", long.class, scoredInputArrayClass, int.class);
        this.nativeFillCandidateList = declared(hmmImplClass, "nativeFillCandidateList", long.class, rangeClass);
        this.nativeGetCandidateCount = declared(hmmImplClass, "nativeGetCandidateCount", long.class);
        this.nativeGetCandidateString = declared(hmmImplClass, "nativeGetCandidateString", long.class, int.class);
        this.nativeGetCandidateRange = declared(hmmImplClass, "nativeGetCandidateRange", long.class, int.class);
        this.nativeGetDecodingRange = declared(hmmImplClass, "nativeGetDecodingRange", long.class);
        this.nativeGetSegmentCount = declared(hmmImplClass, "nativeGetSegmentCount", long.class);
        this.nativeGetSegment = declared(hmmImplClass, "nativeGetSegment", long.class, int.class);
        this.nativeGetSegmentRange = declared(hmmImplClass, "nativeGetSegmentRange", long.class, long.class);
        this.nativeIsSegmentConverted = declared(hmmImplClass, "nativeIsSegmentConverted", long.class, long.class);
        this.nativeGetSegmentConvertedString = declared(hmmImplClass, "nativeGetSegmentConvertedString", long.class, long.class);
        this.nativeGetSegmentTokenCount = declared(hmmImplClass, "nativeGetSegmentTokenCount", long.class, long.class);
        this.nativeGetSegmentToken = declared(hmmImplClass, "nativeGetSegmentToken", long.class, long.class, int.class);
        this.nativeGetTokenInputUnitCount = declared(hmmImplClass, "nativeGetTokenInputUnitCount", long.class, long.class);
        this.nativeGetTokenInputUnit = declared(hmmImplClass, "nativeGetTokenInputUnit", long.class, long.class, int.class);
        this.nativeGetInputUnitString = declared(hmmImplClass, "nativeGetInputUnitString", long.class, long.class);
        this.nativeClose = declared(hmmImplClass, "nativeClose", long.class);
    }

    boolean isZiranmaAuxActive(Object processor) {
        try {
            Object store = prefStore(processor);
            if (store == null) return false;
            Object scheme = prefGetStringMethod.invoke(store, GboardSettingsHook.KEY_PINYIN_SCHEME, "");
            return GboardSettingsHook.VALUE_ZIRANMA_AUX.equals(scheme);
        } catch (Throwable ignored) {
            return false;
        }
    }

    boolean isEmojiEnabled(Object processor) {
        try {
            Object store = prefStore(processor);
            if (store == null) return true;
            return Boolean.TRUE.equals(prefGetBoolMethod.invoke(store, GboardSettingsHook.KEY_EMOJI_ENABLED, true));
        } catch (Throwable ignored) {
            return true;
        }
    }

    private Object prefStore(Object processor) throws Exception {
        Object factory = processorFactoryGetter.invoke(processor);
        if (factory == null) return null;
        return prefStoreField.get(factory);
    }

    CompositionState readComposition(Object processor) {
        try {
            Object wrapper = wrapperField.get(processor);
            if (wrapper == null) return null;
            Object impl = wrapperImplField.get(wrapper);
            if (impl == null) return null;
            long ptr = nativePtr(impl);
            if (ptr == 0) return null;

            int contextEnd = wrapperContextEndField.getInt(wrapper);
            Object decRange = nativeGetDecodingRange.invoke(null, ptr);
            int totalEnd = decRange == null ? contextEnd : rangeEnd.getInt(decRange);
            if (totalEnd <= contextEnd) return null;

            int segCount = (Integer) nativeGetSegmentCount.invoke(null, ptr);
            StringBuilder converted = new StringBuilder();
            StringBuilder rawTail = new StringBuilder();

            for (int s = 0; s < segCount; s++) {
                long seg = (Long) nativeGetSegment.invoke(null, ptr, s);
                Object segRange = nativeGetSegmentRange.invoke(null, ptr, seg);
                if (segRange == null) continue;
                int segStart = rangeStart.getInt(segRange);
                if (segStart < contextEnd) continue;

                boolean isConverted = (Boolean) nativeIsSegmentConverted.invoke(null, ptr, seg);
                if (isConverted) {
                    String convText = (String) nativeGetSegmentConvertedString.invoke(null, ptr, seg);
                    if (convText != null) converted.append(convText);
                } else {
                    int tokCount = (Integer) nativeGetSegmentTokenCount.invoke(null, ptr, seg);
                    for (int t = 0; t < tokCount; t++) {
                        long tok = (Long) nativeGetSegmentToken.invoke(null, ptr, seg, t);
                        int unitCount = (Integer) nativeGetTokenInputUnitCount.invoke(null, ptr, tok);
                        for (int u = 0; u < unitCount; u++) {
                            long unit = (Long) nativeGetTokenInputUnit.invoke(null, ptr, tok, u);
                            String str = (String) nativeGetInputUnitString.invoke(null, ptr, unit);
                            if (str != null) rawTail.append(str);
                        }
                    }
                }
            }

            boolean fullSpanWord = false;
            int candCount = (Integer) nativeGetCandidateCount.invoke(null, ptr);
            if (candCount > 0) {
                String top = (String) nativeGetCandidateString.invoke(null, ptr, 0);
                Object topRange = nativeGetCandidateRange.invoke(null, ptr, 0);
                if (top != null && topRange != null) {
                    int endV = rangeEnd.getInt(topRange);
                    int cpCount = top.codePointCount(0, top.length());
                    fullSpanWord = (endV >= totalEnd) && cpCount >= 2;
                }
            }
            return new CompositionState(converted.toString(), rawTail.toString().toLowerCase(), fullSpanWord);
        } catch (Throwable ignored) {
            return null;
        }
    }

    ZiranmaAuxEngine.ShadowDecoder shadowDecoder() {
        return this::decodeShadow;
    }

    private List<ZiranmaAuxEngine.DecodedCandidate> decodeShadow(String pureShuangpin) {
        if (pureShuangpin == null || pureShuangpin.isEmpty()) return Collections.emptyList();
        synchronized (shadowLock) {
            try {
                long ptr = ensureShadowEngine();
                if (ptr == 0) return Collections.emptyList();
                nativeReset.invoke(null, ptr);
                Object singleInput = Array.newInstance(scoredInputClass, 1);
                for (int i = 0; i < pureShuangpin.length(); i++) {
                    String ch = String.valueOf(pureShuangpin.charAt(i));
                    Array.set(singleInput, 0, scoredInputCtor.newInstance(ch, 0.0f));
                    int appended = (Integer) nativeAppend.invoke(null, ptr, singleInput, 0);
                    if (appended <= 0) return Collections.emptyList();
                }
                Object decRange = nativeGetDecodingRange.invoke(null, ptr);
                if (decRange == null) return Collections.emptyList();
                int totalEnd = rangeEnd.getInt(decRange);
                if (totalEnd <= 0) return Collections.emptyList();

                Object fullRange = rangeCtor.newInstance(0, totalEnd);
                boolean filled = (Boolean) nativeFillCandidateList.invoke(null, ptr, fullRange);
                if (!filled) return Collections.emptyList();

                int count = Math.min((Integer) nativeGetCandidateCount.invoke(null, ptr), MAX_SHADOW_CANDIDATES);
                List<ZiranmaAuxEngine.DecodedCandidate> out = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String text = (String) nativeGetCandidateString.invoke(null, ptr, i);
                    if (text == null || text.isEmpty()) continue;
                    Object r = nativeGetCandidateRange.invoke(null, ptr, i);
                    boolean full = r != null && rangeEnd.getInt(r) >= totalEnd;
                    out.add(new ZiranmaAuxEngine.DecodedCandidate(text, full));
                }
                return out;
            } catch (Throwable ignored) {
                return Collections.emptyList();
            }
        }
    }

    void commitAux(Object processor, String commitText, String remainingRawTail) throws Exception {
        CompositionState state = readComposition(processor);
        String prefix = state == null ? "" : state.convertedPrefix;
        commitMethod.invoke(processor, prefix + commitText);
        resetMethod.invoke(processor);
        if (remainingRawTail != null && !remainingRawTail.isEmpty()) {
            replayRawTail(processor, remainingRawTail);
        }
    }

    void resetAndReplay(Object processor, String rawTail) throws Exception {
        resetMethod.invoke(processor);
        if (rawTail != null && !rawTail.isEmpty()) {
            replayRawTail(processor, rawTail);
        }
    }

    void refresh(Object processor) throws Exception {
        refreshMethod.invoke(processor, 0L);
    }

    private void replayRawTail(Object processor, String rawTail) throws Exception {
        Object wrapper = wrapperField.get(processor);
        if (wrapper == null || wrapperAppendMethod == null || keyDataCtor == null) return;

        Object keyDataArr = Array.newInstance(keyDataClass, 1);
        float[] scores = new float[]{0.0f};
        for (int i = 0; i < rawTail.length(); i++) {
            char ch = rawTail.charAt(i);
            Object kd = newKeyData(ch);
            Array.set(keyDataArr, 0, kd);
            wrapperAppendMethod.invoke(wrapper, keyDataArr, scores);
        }
        refreshMethod.invoke(processor, 0L);
    }

    private Object newKeyData(char ch) throws Exception {
        Class<?>[] p = keyDataCtor.getParameterTypes();
        Object[] args = new Object[p.length];
        for (int i = 0; i < p.length; i++) {
            if (p[i] == int.class) args[i] = (i == 0) ? (int) ch : 0;
            else if (p[i] == Object.class || p[i] == String.class) args[i] = String.valueOf(ch);
            else args[i] = null;
        }
        return keyDataCtor.newInstance(args);
    }

    private static Constructor<?> findKeyDataCtor(Class<?> cls) {
        Constructor<?> best = null;
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            boolean hasInt = false, hasObj = false;
            for (Class<?> t : p) {
                if (t == int.class) hasInt = true;
                if (t == Object.class || t == String.class) hasObj = true;
            }
            if (hasInt && hasObj && (best == null || p.length < best.getParameterCount())) {
                c.setAccessible(true);
                best = c;
            }
        }
        return best;
    }

    private long ensureShadowEngine() throws Exception {
        if (shadowPtr != 0) return shadowPtr;
        Object factory = engineFactoryInstance.invoke(null);
        if (factory == null) return 0;
        long factoryPtr = engineFactoryPtrField.getLong(factory);
        if (factoryPtr == 0) return 0;
        shadowPtr = (Long) nativeCreateEngine.invoke(null, factoryPtr, PINYIN_ENGINE_ID, "");
        return shadowPtr;
    }

    private long nativePtr(Object impl) throws Exception {
        if (implPtrGetter == null) return implPtrField.getLong(impl);
        Object holder = implPtrField.get(impl);
        return holder == null ? 0 : (Long) implPtrGetter.invoke(holder);
    }

    private static Method declared(Class<?> owner, String name, Class<?>... params) throws NoSuchMethodException {
        Method m = owner.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private static Field findAssignableField(Class<?> owner, Class<?> superType) throws NoSuchFieldException {
        for (Class<?> cur = owner; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && superType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new NoSuchFieldException("No field assignable to " + superType.getName() + " in " + owner.getName());
    }

    private static Field findFieldOfType(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        for (Class<?> cur = owner; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new NoSuchFieldException("No field of type " + type.getName() + " in " + owner.getName());
    }

    private static Field findLongField(Class<?> owner) throws NoSuchFieldException {
        for (Field f : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == long.class) {
                f.setAccessible(true);
                return f;
            }
        }
        throw new NoSuchFieldException("No long field in " + owner.getName());
    }

    private static Method findSingletonGetter(Class<?> owner) throws NoSuchMethodException {
        for (Method m : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && m.getReturnType() == owner) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException("No singleton getter in " + owner.getName());
    }

    private static Method findStaticFactoryGetter(Class<?> owner) {
        for (Method m : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1 && m.getReturnType() == owner) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static Method findWrapperAppendMethod(Class<?> wrapperClass) {
        for (Method m : wrapperClass.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) && m.getReturnType() == boolean.class
                    && p.length == 2 && p[0].isArray() && p[1] == float[].class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
