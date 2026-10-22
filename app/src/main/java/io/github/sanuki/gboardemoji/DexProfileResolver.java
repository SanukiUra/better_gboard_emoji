package io.github.sanuki.gboardemoji;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Resolves the inspected candidate flow by bytecode features, without fixed obfuscated names. */
public final class DexProfileResolver {
    private static final String CHINESE = "Lcom/google/android/apps/inputmethod/libs/chinese/ime/hmm/AbstractHmmChineseDecodeProcessor;";
    private static final String HMM = "Lcom/google/android/apps/inputmethod/libs/hmm/AbstractHmmDecodeProcessor;";
    private static final String BASE = "Lcom/google/android/libraries/inputmethod/ime/processor/core/BaseDecodeProcessor;";
    private static final String READING = "Lcom/google/android/libraries/inputmethod/keyboard/widget/ReadingTextCandidateHolderView;";
    private static final String CHAR = "Ljava/lang/CharSequence;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String STRING = "Ljava/lang/String;";

    private DexProfileResolver() {}

    public static HookProfile resolve(List<File> apks, String version) throws Exception {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        for (File file : apks) {
            try (ZipFile apk = new ZipFile(file)) {
                for (ZipEntry entry : Collections.list(apk.entries())) {
                    if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                    try (InputStream input = apk.getInputStream(entry)) {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] chunk = new byte[16384];
                        int size;
                        while ((size = input.read(chunk)) != -1) output.write(chunk, 0, size);
                        Dex dex = new Dex(output.toByteArray());
                        for (Definition definition : dex.definitions()) {
                            if (definitions.put(definition.name, definition) != null) {
                                throw new IllegalArgumentException("Duplicate DEX class: " + definition.name);
                            }
                        }
                    }
                }
            }
        }
        Definition chinese = required(definitions, CHINESE);
        Definition hmm = required(definitions, HMM);
        List<MethodDef> selectors = new ArrayList<>();
        for (MethodDef method : chinese.methods) {
            if (method.result.equals("Z") && method.args.size() == 2 && method.args.get(1).equals("Z")
                    && method.refs().strings.containsAll(Arrays.asList("TEXT", "PREDICT", "SELECT_CANDIDATE"))) {
                selectors.add(method);
            }
        }
        MethodDef select = one(selectors, "Chinese candidate selection");
        String candidateName = select.args.get(0);
        Definition candidate = required(definitions, candidateName);
        MethodDef constructor = one(find(candidate, "<init>", null, "V", 1), "Candidate constructor");
        String builderName = constructor.args.get(0);
        Definition builder = required(definitions, builderName);
        MethodDef copy = one(find(builder, null, Arrays.asList(candidateName), "V", 1), "Candidate copy builder");
        MethodDef build = one(find(builder, null, Collections.emptyList(), candidateName, 0), "Candidate build");

        List<MethodDef> commits = new ArrayList<>();
        List<MethodDef> resets = new ArrayList<>();
        for (MethodDef method : hmm.methods) {
            if (method.result.equals("V") && method.args.equals(Arrays.asList(CHAR))
                    && method.refs().calls(BASE, Arrays.asList(CHAR, "I"), "V")
                    && method.refs().calls(HMM, Arrays.asList(CHAR), "V")
                    && method.refs().strings.contains("")) commits.add(method);
            if (method.result.equals("V") && method.args.isEmpty()
                    && method.refs().calls(BASE, Arrays.asList("Z"), "V")
                    && method.refs().calls(HMM, Arrays.asList("Z"), "V")) resets.add(method);
        }
        MethodDef commit = one(commits, "Commit and clear preedit");
        MethodDef reset = one(resets, "Decoder reset");
        List<MethodDef> batches = new ArrayList<>();
        for (MethodDef method : hmm.methods) {
            if (method.args.equals(Arrays.asList("I")) && method.result.equals("Z")
                    && method.refs().strings.contains("candidate.data is not an Integer")) batches.add(method);
        }
        MethodDef batch = one(batches, "Candidate batch producer");
        FieldRef iterator = one(fields(batch.refs(), HMM, "Ljava/util/Iterator;"), "Candidate batch iterator");
        List<MethodDef> factories = new ArrayList<>();
        for (MethodDef method : batch.refs().methods) {
            if (method.args.equals(Arrays.asList("Ljava/util/List;", candidateName, "Z", OBJECT))
                    && method.result.equals(method.owner)) factories.add(method);
        }
        MethodDef factoryRef = one(factories, "Candidate delivery factory");
        MethodDef factory = one(find(required(definitions, factoryRef.owner), factoryRef.name,
                factoryRef.args, factoryRef.result, 4), "Candidate delivery factory definition");
        // The event must retain both the full list and the native default candidate.
        one(fields(factory.refs(), factory.owner, "Ljava/util/List;"), "Delivered candidate list");
        one(fields(factory.refs(), factory.owner, candidateName), "Delivered default candidate");
        List<MethodDef> deletes = new ArrayList<>();
        for (MethodDef method : chinese.methods) {
            if (method.args.equals(Arrays.asList(candidateName)) && method.result.equals("Z")
                    && method.refs().calls("Lcom/google/android/apps/inputmethod/libs/hmm/MutableDictionaryAccessorImpl;",
                    Arrays.asList(STRING), "Z")) deletes.add(method);
        }
        MethodDef delete = one(deletes, "Candidate deletion");

        FieldRef text = one(fields(select.refs(), candidateName, CHAR), "Commit text field");
        List<FieldRef> dataFields = new ArrayList<>();
        for (Definition definition : definitions.values()) {
            for (MethodDef method : definition.methods) {
                if (method.args.equals(Arrays.asList(candidateName)) && method.result.equals(STRING)
                        && method.refs().calls(HMM.replace("AbstractHmmDecodeProcessor", "HmmEngineInterfaceImpl"), Arrays.asList("I"), STRING)) {
                    dataFields.addAll(fields(method.refs(), candidateName, OBJECT));
                }
            }
        }
        FieldRef data = one(unique(dataFields), "Candidate data field");
        List<FieldRef> kinds = new ArrayList<>();
        for (FieldRef field : candidate.fields) {
            Definition kind = definitions.get(field.type);
            if (field.owner.equals(candidateName) && kind != null && (kind.flags & 0x4000) != 0) kinds.add(field);
        }
        FieldRef kind = one(unique(kinds), "Candidate kind field");
        List<FieldRef> displays = new ArrayList<>();
        for (MethodDef method : required(definitions, READING).methods) {
            displays.addAll(fields(method.refs(), candidateName, CHAR));
        }
        FieldRef display = one(unique(displays), "Displayed candidate text");
        List<FieldRef> annotations = new ArrayList<>();
        List<FieldRef> descriptions = new ArrayList<>();
        for (FieldRef field : candidate.fields) {
            if (field.type.equals(CHAR) && !field.name.equals(text.name) && !field.name.equals(display.name)) annotations.add(field);
            if (field.type.equals(STRING)) descriptions.add(field);
        }
        FieldRef annotation = one(annotations, "Candidate annotation");
        FieldRef description = one(descriptions, "Candidate accessibility description");

        List<Definition> renderers = new ArrayList<>();
        for (Definition definition : definitions.values()) {
            // Source-path string survives method/class obfuscation in inspected Gboard builds.
            for (MethodDef method : definition.methods) {
                if (!method.name.equals("<clinit>")) continue;
                if (method.refs().strings.contains("com/google/android/libraries/inputmethod/widgets/CandidateViewBuilder")) {
                    renderers.add(definition);
                }
            }
        }
        Definition renderer = one(renderers, "Candidate view builder");
        List<FieldRef> removableFields = new ArrayList<>();
        List<FieldRef> highlightFields = new ArrayList<>();
        for (MethodDef method : renderer.methods) {
            if (!method.args.contains(candidateName)) continue;
            if (method.result.equals("V")) removableFields.addAll(fields(method.refs(), candidateName, "Z"));
            if (method.result.equals("I") && method.args.equals(Arrays.asList(candidateName, "Z"))) {
                highlightFields.addAll(fields(method.refs(), candidateName, "Z"));
            }
        }
        FieldRef removable = one(unique(removableFields), "Removal flag");
        FieldRef highlight = one(unique(highlightFields), "Highlight flag");
        List<FieldRef> decorations = new ArrayList<>();
        for (FieldRef field : candidate.fields) {
            Definition owner = definitions.get(field.type);
            if (owner != null && !find(owner, "<init>", Arrays.asList("I", "I", "I", "I", "I"), "V", 5).isEmpty()) {
                decorations.add(field);
            }
        }
        FieldRef decoration = one(decorations, "Candidate stroke/tone decoration");

        Map<String, String> builderFields = new HashMap<>();
        Map<Integer, FieldRef> registers = new HashMap<>();
        for (Reference reference : constructor.refs().ordered) {
            if (reference.field == null) continue;
            if (reference.field.owner.equals(builderName) && reference.opcode >= 0x52 && reference.opcode <= 0x58) {
                registers.put(reference.register, reference.field);
            } else if (reference.field.owner.equals(candidateName) && reference.opcode >= 0x59 && reference.opcode <= 0x5f) {
                FieldRef from = registers.get(reference.register);
                if (from != null && from.type.equals(reference.field.type)) builderFields.put(reference.field.name, from.name);
            }
        }

        Properties properties = new Properties();
        properties.setProperty("versionName", version);
        properties.setProperty("candidateClass", javaName(candidateName));
        properties.setProperty("builderClass", javaName(builderName));
        properties.setProperty("builderCopy", copy.name);
        properties.setProperty("builderBuild", build.name);
        properties.setProperty("chineseProcessor", javaName(CHINESE));
        properties.setProperty("hmmProcessor", javaName(HMM));
        properties.setProperty("selectMethod", select.name);
        properties.setProperty("deleteMethod", delete.name);
        properties.setProperty("commitMethod", commit.name);
        properties.setProperty("resetMethod", reset.name);
        properties.setProperty("batchMethod", batch.name);
        properties.setProperty("iteratorField", iterator.name);
        properties.setProperty("deliveryClass", javaName(factory.owner));
        properties.setProperty("deliveryMethod", factory.name);
        String[] keys = {"commitTextField", "displayTextField", "descriptionField", "annotationField",
                "kindField", "dataField", "removableField", "highlightField", "decorationField"};
        FieldRef[] found = {text, display, description, annotation, kind, data, removable, highlight, decoration};
        for (int i = 0; i < keys.length; i++) {
            String mapped = builderFields.get(found[i].name);
            if (mapped == null) throw new IllegalArgumentException("Builder assignment changed: " + keys[i]);
            properties.setProperty(keys[i], found[i].name);
            properties.setProperty("builder." + keys[i], mapped);
        }
        return HookProfile.from(properties);
    }

    private static String javaName(String type) { return type.substring(1, type.length() - 1).replace('/', '.'); }

    private static Definition required(Map<String, Definition> definitions, String name) {
        Definition value = definitions.get(name);
        if (value == null) throw new IllegalArgumentException("Gboard structure not recognized: " + name);
        return value;
    }

    private static <T> T one(List<T> values, String label) {
        if (values.size() != 1) throw new IllegalArgumentException(label + ": expected 1 match, found " + values.size());
        return values.get(0);
    }

    private static List<FieldRef> unique(List<FieldRef> values) {
        Map<String, FieldRef> unique = new LinkedHashMap<>();
        for (FieldRef field : values) unique.put(field.owner + "/" + field.name + ":" + field.type, field);
        return new ArrayList<>(unique.values());
    }

    private static List<FieldRef> fields(Refs refs, String owner, String type) {
        List<FieldRef> result = new ArrayList<>();
        for (FieldRef field : refs.fields) if (field.owner.equals(owner) && field.type.equals(type)) result.add(field);
        return unique(result);
    }

    private static List<MethodDef> find(Definition owner, String name, List<String> args, String result, int count) {
        List<MethodDef> found = new ArrayList<>();
        for (MethodDef method : owner.methods) {
            if ((name == null || name.equals(method.name)) && (args == null || args.equals(method.args))
                    && method.args.size() == count && method.result.equals(result) && !method.name.equals("<clinit>")) {
                found.add(method);
            }
        }
        return found;
    }

    private static final class Definition {
        final String name;
        final int flags;
        final List<FieldRef> fields = new ArrayList<>();
        final List<MethodDef> methods = new ArrayList<>();
        Definition(String name, int flags) { this.name = name; this.flags = flags; }
    }

    private static final class FieldRef {
        final String owner, name, type;
        FieldRef(String owner, String name, String type) { this.owner = owner; this.name = name; this.type = type; }
    }

    private static final class MethodDef {
        final Dex dex;
        final String owner, name, result;
        final List<String> args;
        int code;
        Refs refs;
        MethodDef(Dex dex, String owner, String name, String result, List<String> args) {
            this.dex = dex; this.owner = owner; this.name = name; this.result = result; this.args = args;
        }
        Refs refs() {
            if (refs == null) refs = code == 0 ? new Refs() : dex.references(code);
            return refs;
        }
    }

    private static final class Reference {
        final int opcode, register;
        final FieldRef field;
        Reference(int opcode, int register, FieldRef field) { this.opcode = opcode; this.register = register; this.field = field; }
    }

    private static final class Refs {
        final Set<String> strings = new HashSet<>();
        final List<FieldRef> fields = new ArrayList<>();
        final List<MethodDef> methods = new ArrayList<>();
        final List<Reference> ordered = new ArrayList<>();
        boolean calls(String owner, List<String> args, String result) {
            for (MethodDef method : methods) if (method.owner.equals(owner) && method.args.equals(args) && method.result.equals(result)) return true;
            return false;
        }
    }

    /** Standard (non-quickened) APK DEX reader. All instruction boundaries are checked. */
    private static final class Dex {
        final byte[] bytes;
        final String[] strings, types;
        final String[] results;
        final List<List<String>> arguments = new ArrayList<>();
        final FieldRef[] fields;
        final MethodDef[] methods;

        Dex(byte[] bytes) {
            this.bytes = bytes;
            if (bytes.length < 112 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x' || bytes[3] != '\n'
                    || u32(40) != 0x12345678) throw new IllegalArgumentException("Unsupported DEX header");
            strings = new String[u32(56)];
            int stringTable = u32(60);
            for (int i = 0; i < strings.length; i++) {
                Cursor cursor = new Cursor(u32(stringTable + i * 4));
                uleb(cursor);
                int start = cursor.offset;
                while (bytes[cursor.offset] != 0) cursor.offset++;
                strings[i] = new String(bytes, start, cursor.offset - start, StandardCharsets.UTF_8);
            }
            types = new String[u32(64)];
            for (int i = 0; i < types.length; i++) types[i] = strings[u32(u32(68) + i * 4)];
            results = new String[u32(72)];
            for (int i = 0; i < results.length; i++) {
                int offset = u32(76) + i * 12;
                results[i] = types[u32(offset + 4)];
                int list = u32(offset + 8);
                List<String> args = new ArrayList<>();
                if (list != 0) for (int j = 0; j < u32(list); j++) args.add(types[u16(list + 4 + j * 2)]);
                arguments.add(args);
            }
            fields = new FieldRef[u32(80)];
            for (int i = 0; i < fields.length; i++) {
                int offset = u32(84) + i * 8;
                fields[i] = new FieldRef(types[u16(offset)], strings[u32(offset + 4)], types[u16(offset + 2)]);
            }
            methods = new MethodDef[u32(88)];
            for (int i = 0; i < methods.length; i++) {
                int offset = u32(92) + i * 8;
                int proto = u16(offset + 2);
                methods[i] = new MethodDef(this, types[u16(offset)], strings[u32(offset + 4)], results[proto], arguments.get(proto));
            }
        }

        List<Definition> definitions() {
            List<Definition> found = new ArrayList<>();
            for (int i = 0; i < u32(96); i++) {
                int offset = u32(100) + i * 32;
                Definition definition = new Definition(types[u32(offset)], u32(offset + 4));
                int data = u32(offset + 24);
                if (data != 0) {
                    Cursor cursor = new Cursor(data);
                    int[] counts = {uleb(cursor), uleb(cursor), uleb(cursor), uleb(cursor)};
                    for (int group = 0; group < 4; group++) {
                        int index = 0;
                        for (int n = 0; n < counts[group]; n++) {
                            index += uleb(cursor);
                            uleb(cursor);
                            if (group < 2) definition.fields.add(fields[index]);
                            else {
                                MethodDef method = methods[index];
                                method.code = uleb(cursor);
                                definition.methods.add(method);
                            }
                        }
                    }
                }
                found.add(definition);
            }
            return found;
        }

        Refs references(int code) {
            Refs found = new Refs();
            int size = u32(code + 12), base = code + 16;
            for (int point = 0; point < size;) {
                int at = base + point * 2, word = u16(at), opcode = word & 255;
                int width = width(opcode);
                if (opcode == 0 && word != 0) {
                    if (word == 0x100) width = 4 + u16(at + 2) * 2;
                    else if (word == 0x200) width = 2 + u16(at + 2) * 4;
                    else if (word == 0x300) width = 4 + (int) (((long) u16(at + 2) * u32(at + 4) + 1) / 2);
                    else throw new IllegalArgumentException("Unknown DEX payload");
                }
                if (width <= 0 || point + width > size) throw new IllegalArgumentException("Invalid DEX instruction");
                if (opcode == 0x1a || opcode == 0x1b) found.strings.add(strings[opcode == 0x1a ? u16(at + 2) : u32(at + 2)]);
                if (opcode >= 0x52 && opcode <= 0x6d) {
                    FieldRef field = fields[u16(at + 2)];
                    found.fields.add(field);
                    found.ordered.add(new Reference(opcode, (word >> 8) & 15, field));
                }
                if ((opcode >= 0x6e && opcode <= 0x72) || (opcode >= 0x74 && opcode <= 0x78)) {
                    found.methods.add(methods[u16(at + 2)]);
                }
                point += width;
            }
            return found;
        }

        private int width(int opcode) {
            if (opcode == 0x18) return 5;
            if (opcode == 0xfa || opcode == 0xfb) return 4;
            if (opcode == 3 || opcode == 6 || opcode == 9 || opcode == 0x14 || opcode == 0x17
                    || opcode == 0x1b || opcode == 0x24 || opcode == 0x25 || opcode == 0x26
                    || opcode == 0x2a || opcode == 0x2b || opcode == 0x2c
                    || (opcode >= 0x6e && opcode <= 0x72) || (opcode >= 0x74 && opcode <= 0x78)
                    || opcode == 0xfc || opcode == 0xfd) return 3;
            if (opcode == 2 || opcode == 5 || opcode == 8 || opcode == 0x13 || opcode == 0x15
                    || opcode == 0x16 || opcode == 0x19 || opcode == 0x1a || opcode == 0x1c
                    || opcode == 0x1f || opcode == 0x20 || opcode == 0x22 || opcode == 0x23 || opcode == 0x29
                    || (opcode >= 0x2d && opcode <= 0x3d) || (opcode >= 0x44 && opcode <= 0x6d)
                    || (opcode >= 0x90 && opcode <= 0xaf) || (opcode >= 0xd0 && opcode <= 0xe2)
                    || opcode == 0xfe || opcode == 0xff) return 2;
            return 1;
        }

        int u16(int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
        int u32(int offset) { return u16(offset) | (u16(offset + 2) << 16); }
        int uleb(Cursor cursor) {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int next = bytes[cursor.offset++] & 255;
                value |= (next & 127) << shift;
                if ((next & 128) == 0) return value;
            }
            throw new IllegalArgumentException("Invalid DEX integer");
        }
    }

    private static final class Cursor { int offset; Cursor(int offset) { this.offset = offset; } }
}
