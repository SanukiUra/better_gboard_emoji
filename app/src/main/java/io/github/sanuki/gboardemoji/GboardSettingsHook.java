package io.github.sanuki.gboardemoji;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Integrates two native settings directly into Gboard's Chinese Pinyin settings screen:
 * 1. Adds "双拼 - 自然码辅助码" (shuangpin_ziranma_aux) alongside "双拼 - 自然码" in the Pinyin scheme list,
 *    bridging Gboard's internal resId preference reads to "shuangpin_ziranma" so native keycaps/gestures/HMM work.
 * 2. Adds an "Emoji 第 2 候选增强" SwitchPreferenceCompat directly below the Pinyin scheme selector.
 */
final class GboardSettingsHook {
    static final String KEY_PINYIN_SCHEME = "pinyin_scheme";
    static final String VALUE_ZIRANMA = "shuangpin_ziranma";
    static final String VALUE_ZIRANMA_AUX = "shuangpin_ziranma_aux";
    static final String LABEL_ZIRANMA_AUX = "双拼 - 自然码辅助码";

    static final String KEY_EMOJI_ENABLED = "wanxiang_emoji_enabled";
    private static final String TITLE_EMOJI = "Emoji 第 2 候选增强";
    private static final String SUMMARY_EMOJI = "首位中文候选命中万象词表时，在第 2 位插入对应 Emoji";

    private GboardSettingsHook() {}

    static void install(ClassLoader loader, HookProfile profile) throws Exception {
        Class<?> prefStoreClass = Class.forName(profile.get("prefStoreClass"), false, loader);
        Method getStringByRes = prefStoreClass.getDeclaredMethod(
                profile.get("prefGetStringByResMethod"), int.class, String.class);

        Class<?> prefClass = Class.forName("androidx.preference.Preference", false, loader);
        Class<?> prefGroupClass = Class.forName("androidx.preference.PreferenceGroup", false, loader);
        Class<?> listPrefClass = Class.forName("androidx.preference.ListPreference", false, loader);
        Class<?> twoStateClass = Class.forName("androidx.preference.TwoStatePreference", false, loader);
        Class<?> switchPrefClass = Class.forName("androidx.preference.SwitchPreferenceCompat", false, loader);

        Method pgAdd = prefGroupClass.getDeclaredMethod(profile.get("pgAddMethod"), prefClass);
        Method pgFind = prefGroupClass.getDeclaredMethod(profile.get("pgFindMethod"), CharSequence.class);
        Field keyField = prefClass.getDeclaredField(profile.get("prefKeyField"));
        Field contextField = prefClass.getDeclaredField(profile.get("prefContextField"));
        Method setKey = prefClass.getDeclaredMethod(profile.get("prefSetKeyMethod"), String.class);
        Method setTitle = prefClass.getDeclaredMethod(profile.get("prefSetTitleMethod"), CharSequence.class);
        Method setSummary = prefClass.getDeclaredMethod(profile.get("prefSetSummaryMethod"), CharSequence.class);
        Field entriesField = listPrefClass.getDeclaredField(profile.get("listEntriesField"));
        Field entryValuesField = listPrefClass.getDeclaredField(profile.get("listEntryValuesField"));
        Method setChecked = twoStateClass.getDeclaredMethod(profile.get("twoStateSetCheckedMethod"), boolean.class);
        Constructor<?> switchCtor = switchPrefClass.getDeclaredConstructor(Context.class);

        pgAdd.setAccessible(true);
        pgFind.setAccessible(true);
        keyField.setAccessible(true);
        contextField.setAccessible(true);
        setKey.setAccessible(true);
        setTitle.setAccessible(true);
        setSummary.setAccessible(true);
        entriesField.setAccessible(true);
        entryValuesField.setAccessible(true);
        setChecked.setAccessible(true);
        switchCtor.setAccessible(true);

        // 1. Map "shuangpin_ziranma_aux" -> "shuangpin_ziranma" for Gboard's internal engine/layout/gesture readers.
        XposedBridge.hookMethod(getStringByRes, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (VALUE_ZIRANMA_AUX.equals(hook.getResult())) {
                    hook.setResult(VALUE_ZIRANMA);
                }
            }
        });

        // 2. Inject "双拼 - 自然码辅助码" into the pinyin_scheme ListPreference before it attaches,
        //    and append the "Emoji 第 2 候选增强" switch right after it in the same PreferenceGroup.
        XposedBridge.hookMethod(pgAdd, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                Object pref = hook.args[0];
                if (!listPrefClass.isInstance(pref)) return;
                try {
                    if (!KEY_PINYIN_SCHEME.equals(keyField.get(pref))) return;
                    CharSequence[] entries = (CharSequence[]) entriesField.get(pref);
                    CharSequence[] values = (CharSequence[]) entryValuesField.get(pref);
                    if (entries == null || values == null || contains(values, VALUE_ZIRANMA_AUX)) return;

                    CharSequence[] newEntries = Arrays.copyOf(entries, entries.length + 1);
                    CharSequence[] newValues = Arrays.copyOf(values, values.length + 1);
                    newEntries[entries.length] = LABEL_ZIRANMA_AUX;
                    newValues[values.length] = VALUE_ZIRANMA_AUX;
                    entriesField.set(pref, newEntries);
                    entryValuesField.set(pref, newValues);
                } catch (Throwable t) {
                    XposedBridge.log("[GboardWanxiang] 注入自然码辅助码方案失败：" + t);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam hook) {
                Object pref = hook.args[0];
                if (!listPrefClass.isInstance(pref)) return;
                try {
                    if (!KEY_PINYIN_SCHEME.equals(keyField.get(pref))) return;
                    if (pgFind.invoke(hook.thisObject, KEY_EMOJI_ENABLED) != null) return;

                    Context ctx = (Context) contextField.get(pref);
                    if (ctx == null) return;
                    Object toggle = switchCtor.newInstance(ctx);
                    setKey.invoke(toggle, KEY_EMOJI_ENABLED);
                    setTitle.invoke(toggle, TITLE_EMOJI);
                    setSummary.invoke(toggle, SUMMARY_EMOJI);
                    setChecked.invoke(toggle, true);
                    pgAdd.invoke(hook.thisObject, toggle);
                } catch (Throwable t) {
                    XposedBridge.log("[GboardWanxiang] 注入 Emoji 开关失败：" + t);
                }
            }
        });
    }

    private static boolean contains(CharSequence[] items, String target) {
        for (CharSequence item : items) {
            if (target.equals(item == null ? null : item.toString())) return true;
        }
        return false;
    }
}
