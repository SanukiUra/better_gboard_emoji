package io.github.sanuki.gboardemoji;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Uses Gboard's own builder; never mutates the original candidate. */
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
    // Weak keys: candidates from previous compositions must not accumulate.
    private final Map<Object, String> injected = Collections.synchronizedMap(new WeakHashMap<>());

    public ReflectiveCandidateAccess(ClassLoader loader, HookProfile profile) throws Exception {
        candidateClass = Class.forName(profile.get("candidateClass"), false, loader);
        Class<?> builderClass = Class.forName(profile.get("builderClass"), false, loader);
        builder = builderClass.getDeclaredConstructor();
        builder.setAccessible(true);
        copy = builderClass.getDeclaredMethod(profile.get("builderCopy"), candidateClass);
        build = builderClass.getDeclaredMethod(profile.get("builderBuild"));
        if (copy.getReturnType() != void.class || build.getReturnType() != candidateClass) {
            throw new IllegalArgumentException("Candidate builder signature changed");
        }
        copy.setAccessible(true);
        build.setAccessible(true);
        candidateText = field(candidateClass, profile.get("commitTextField"), CharSequence.class);
        kind = candidateClass.getDeclaredField(profile.get("kindField"));
        if (!kind.getType().isEnum()) throw new IllegalArgumentException("Candidate kind changed");
        kind.setAccessible(true);
        data = field(candidateClass, profile.get("dataField"), Object.class);
        commitText = field(builderClass, profile.builderField("commitTextField"), CharSequence.class);
        displayText = field(builderClass, profile.builderField("displayTextField"), CharSequence.class);
        description = field(builderClass, profile.builderField("descriptionField"), String.class);
        annotation = field(builderClass, profile.builderField("annotationField"), CharSequence.class);
        removable = field(builderClass, profile.builderField("removableField"), boolean.class);
        highlighted = field(builderClass, profile.builderField("highlightField"), boolean.class);
        decoration = builderClass.getDeclaredField(profile.builderField("decorationField"));
        decoration.setAccessible(true);
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws Exception {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type) throw new IllegalArgumentException("Changed field: " + name);
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
        // Native IDs are decoder indices, not visible list positions. Pagination is gated upstream.
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

    @Override public boolean injected(Object candidate) { return emoji(candidate) != null; }
    public String emoji(Object candidate) { return candidate == null ? null : injected.get(candidate); }

    /** Structural diagnostics only; never return the user's candidate text. */
    public String metadata(Object candidate) throws Exception {
        if (!candidateClass.isInstance(candidate)) return "非候选对象";
        Object value = kind.get(candidate), index = data.get(candidate);
        return "类型=" + (value instanceof Enum ? ((Enum<?>) value).name() : "未知")
                + "，内部编号=" + (index instanceof Integer ? index : "非整数");
    }

    @Override public Object create(Object source, String emoji) throws Exception {
        Object value = builder.newInstance();
        copy.invoke(value, source);
        commitText.set(value, emoji);
        displayText.set(value, emoji);
        description.set(value, emoji);
        annotation.set(value, null);
        removable.setBoolean(value, false);
        highlighted.setBoolean(value, false);
        decoration.set(value, null);
        Object candidate = build.invoke(value);
        injected.put(candidate, emoji);
        return candidate;
    }
}
