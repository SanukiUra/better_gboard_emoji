package io.github.sanuki.gboardemoji;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Creates synthetic Gboard Candidate instances via Gboard's own builder without mutating existing ones. */
public final class ReflectiveCandidateAccess implements CandidateAugmenter.Access {
    private final Class<?> candidateClass;
    private final Constructor<?> builder;
    private final Method copy;
    private final Method build;
    private final Field candidateText;
    private final Field kind;
    private final Field data;
    private final Field commitText;
    private final Field displayText;
    private final Field description;
    private final Field annotation;
    private final Field removable;
    private final Field highlighted;
    private final Field decoration;

    private final Map<Object, String> injectedEmoji = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, ZiranmaAuxEngine.AuxItem> injectedAux = Collections.synchronizedMap(new WeakHashMap<>());

    public ReflectiveCandidateAccess(ClassLoader loader, HookProfile profile) throws Exception {
        candidateClass = Class.forName(profile.get("candidateClass"), false, loader);
        Class<?> builderClass = Class.forName(profile.get("builderClass"), false, loader);
        builder = builderClass.getDeclaredConstructor();
        builder.setAccessible(true);
        copy = builderClass.getDeclaredMethod(profile.get("builderCopy"), candidateClass);
        build = builderClass.getDeclaredMethod(profile.get("builderBuild"));
        copy.setAccessible(true);
        build.setAccessible(true);

        candidateText = field(candidateClass, profile.get("commitTextField"));
        kind = field(candidateClass, profile.get("kindField"));
        data = field(candidateClass, profile.get("dataField"));
        commitText = field(builderClass, profile.builderField("commitTextField"));
        displayText = field(builderClass, profile.builderField("displayTextField"));
        description = field(builderClass, profile.builderField("descriptionField"));
        annotation = field(builderClass, profile.builderField("annotationField"));
        removable = field(builderClass, profile.builderField("removableField"));
        highlighted = field(builderClass, profile.builderField("highlightField"));
        decoration = field(builderClass, profile.builderField("decorationField"));
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public Class<?> candidateClass() { return candidateClass; }

    @Override public String text(Object candidate) throws Exception {
        if (!candidateClass.isInstance(candidate)) return null;
        Object text = candidateText.get(candidate);
        return text == null ? null : text.toString();
    }

    @Override public boolean eligible(Object candidate) throws Exception {
        if (!candidateClass.isInstance(candidate)) return false;
        Object value = kind.get(candidate);
        if (!(value instanceof Enum) || !"RECOMMENDATION".equals(((Enum<?>) value).name())) {
            return false;
        }
        Object index = data.get(candidate);
        if (!(index instanceof Integer) || (Integer) index < 0) return false;
        String text = text(candidate);
        if (text == null) return false;
        for (int i = 0; i < text.length();) {
            int point = text.codePointAt(i);
            i += Character.charCount(point);
            if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    @Override public boolean injected(Object candidate) {
        return emoji(candidate) != null || auxItem(candidate) != null;
    }

    public String emoji(Object candidate) {
        return candidate == null ? null : injectedEmoji.get(candidate);
    }

    public ZiranmaAuxEngine.AuxItem auxItem(Object candidate) {
        return candidate == null ? null : injectedAux.get(candidate);
    }

    @Override public Object create(Object source, String emoji) throws Exception {
        Object candidate = buildCopy(source, emoji, false);
        injectedEmoji.put(candidate, emoji);
        return candidate;
    }

    public Object createAux(Object source, ZiranmaAuxEngine.AuxItem item, boolean highlight) throws Exception {
        Object candidate = buildCopy(source, item.text, highlight);
        injectedAux.put(candidate, item);
        return candidate;
    }

    private Object buildCopy(Object source, String text, boolean highlight) throws Exception {
        Object value = builder.newInstance();
        copy.invoke(value, source);
        commitText.set(value, text);
        displayText.set(value, text);
        description.set(value, text);
        annotation.set(value, null);
        removable.setBoolean(value, false);
        highlighted.setBoolean(value, highlight);
        decoration.set(value, null);
        return build.invoke(value);
    }
}
