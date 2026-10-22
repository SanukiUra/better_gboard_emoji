package io.github.sanuki.gboardemoji;

import java.util.ArrayList;
import java.util.List;

/** Leaves the default candidate untouched; adds one emoji at index 1. */
public final class CandidateAugmenter {
    public interface Access {
        String text(Object candidate) throws Exception;
        boolean eligible(Object candidate) throws Exception;
        boolean injected(Object candidate) throws Exception;
        Object create(Object source, String emoji) throws Exception;
    }

    private final EmojiDictionary dictionary;
    private final Access access;

    public CandidateAugmenter(EmojiDictionary dictionary, Access access) {
        this.dictionary = dictionary;
        this.access = access;
    }

    public List<?> augment(List<?> original) throws Exception {
        if (original == null || original.isEmpty()) return original;
        for (Object candidate : original) if (access.injected(candidate)) return original;
        Object first = original.get(0);
        if (first == null || !access.eligible(first)) return original;
        List<String> matches = dictionary.lookup(access.text(first));
        if (matches.isEmpty()) return original;
        String emoji = matches.get(0);
        Object inserted = null;
        int existing = -1;
        for (int i = 1; i < original.size(); i++) {
            if (emoji.equals(access.text(original.get(i)))) {
                inserted = original.get(i);
                existing = i;
                break;
            }
        }
        if (existing == 1) return original;
        if (inserted == null) inserted = access.create(first, emoji);
        List<Object> result = new ArrayList<>(original.size() + 1);
        result.add(first);
        result.add(inserted);
        for (int i = 1; i < original.size(); i++) {
            if (i != existing) result.add(original.get(i));
        }
        return result;
    }
}
