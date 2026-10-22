package io.github.sanuki.gboardemoji;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Inspected version profiles and structurally resolved profiles share the same representation. */
public final class HookProfile {
    private final Properties values;

    private HookProfile(Properties values) { this.values = values; }

    static HookProfile from(Properties values) { return new HookProfile(values); }

    public String builderField(String name) {
        return values.getProperty("builder." + name, get(name));
    }

    public static HookProfile read(InputStream input) throws IOException {
        Properties values = new Properties();
        values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        return new HookProfile(values);
    }

    public String get(String name) {
        String value = values.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing hook profile property: " + name);
        }
        return value.trim();
    }
}
