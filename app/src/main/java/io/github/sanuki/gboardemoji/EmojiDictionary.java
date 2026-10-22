package io.github.sanuki.gboardemoji;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact candidate-text lookup; independent of the Pinyin keyboard layout. */
public final class EmojiDictionary {
    private final Map<String, List<String>> entries;

    private EmojiDictionary(Map<String, List<String>> entries) {
        this.entries = Collections.unmodifiableMap(entries);
    }

    public static EmojiDictionary read(Reader input) throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        BufferedReader reader = new BufferedReader(input);
        String line;
        int number = 0;
        while ((line = reader.readLine()) != null) {
            number++;
            if (number == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) throw new IOException("Invalid emoji mapping at line " + number);
            String key = normalize(line.substring(0, tab));
            if (key.isEmpty()) throw new IOException("Empty key at line " + number);
            // Rime uses a real tab after the key, but literal backslash-t between values.
            String[] values = line.substring(tab + 1).replace("\\t", "\t").split("\t");
            List<String> old = result.get(key);
            List<String> emojis = old == null ? new ArrayList<>() : new ArrayList<>(old);
            for (String value : values) {
                value = value.trim();
                if (containsEmoji(value) && !emojis.contains(value)) emojis.add(value);
            }
            if (!emojis.isEmpty()) result.put(key, Collections.unmodifiableList(emojis));
        }
        return new EmojiDictionary(result);
    }

    public List<String> lookup(CharSequence text) {
        if (text == null) return Collections.emptyList();
        List<String> found = entries.get(normalize(text.toString()));
        return found == null ? Collections.emptyList() : found;
    }

    public int size() { return entries.size(); }

    private static String normalize(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
    }

    private static boolean containsEmoji(String value) {
        for (int offset = 0; offset < value.length();) {
            int code = value.codePointAt(offset);
            offset += Character.charCount(code);
            if ((code >= 0x1F000 && code <= 0x1FAFF)
                    || (code >= 0x2600 && code <= 0x27BF)
                    || code == 0xFE0F || code == 0x20E3
                    || code == 0x231A || code == 0x231B || code == 0x23F0
                    || code == 0x23F3 || (code >= 0x23E9 && code <= 0x23EF)
                    || code == 0x2328 || code == 0x23CF || code == 0x23F8
                    || code == 0x23F9 || code == 0x23FA || code == 0x3030
                    || code == 0x303D || code == 0x3297 || code == 0x3299) return true;
        }
        return false;
    }
}
