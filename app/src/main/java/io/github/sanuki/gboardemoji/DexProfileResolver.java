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

/** Resolves Gboard hook targets by bytecode structure, without hardcoded obfuscated names. */
public final class DexProfileResolver {
    private static final String CHINESE = "Lcom/google/android/apps/inputmethod/libs/chinese/ime/hmm/AbstractHmmChineseDecodeProcessor;";
    private static final String PINYIN = "Lcom/google/android/apps/inputmethod/libs/pinyin/AbstractHmmPinyinDecodeProcessor;";
    private static final String HMM = "Lcom/google/android/apps/inputmethod/libs/hmm/AbstractHmmDecodeProcessor;";
    private static final String HMM_IMPL = "Lcom/google/android/apps/inputmethod/libs/hmm/HmmEngineInterfaceImpl;";
    private static final String RANGE = "Lcom/google/android/apps/inputmethod/libs/hmm/Range;";
    private static final String BASE = "Lcom/google/android/libraries/inputmethod/ime/processor/core/BaseDecodeProcessor;";
    private static final String READING = "Lcom/google/android/libraries/inputmethod/keyboard/widget/ReadingTextCandidateHolderView;";
    private static final String PREF = "Landroidx/preference/Preference;";
    private static final String PREF_GROUP = "Landroidx/preference/PreferenceGroup;";
    private static final String LIST_PREF = "Landroidx/preference/ListPreference;";
    private static final String TWO_STATE_PREF = "Landroidx/preference/TwoStatePreference;";
    private static final String CHAR = "Ljava/lang/CharSequence;";
    private static final String CHAR_ARR = "[Ljava/lang/CharSequence;";
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
        Definition pinyin = required(definitions, PINYIN);
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
        List<MethodDef> baseCommits = new ArrayList<>();
        for (MethodDef call : commit.refs().methods) {
            if (call.owner.equals(BASE) && call.args.equals(Arrays.asList(CHAR, "I")) && call.result.equals("V")) {
                baseCommits.add(call);
            }
        }
        MethodDef baseCommit = one(uniqueMethods(baseCommits), "BaseDecodeProcessor commitText");

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
                        && method.refs().calls(HMM_IMPL, Arrays.asList("I"), STRING)) {
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

        Definition renderer = findByClinitString(definitions, "com/google/android/libraries/inputmethod/widgets/CandidateViewBuilder",
                "Candidate view builder");
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

        // --- Ziranma auxiliary code hooks & HMM bridge anchors ---
        List<MethodDef> acceptKeys = new ArrayList<>();
        for (MethodDef method : pinyin.methods) {
            if (method.result.equals("Z") && method.args.size() == 1 && method.refs().strings.contains(";")) {
                acceptKeys.add(method);
            }
        }
        MethodDef acceptKey = one(acceptKeys, "Pinyin acceptKey");
        String keyDataName = acceptKey.args.get(0);

        List<MethodDef> keyEvents = new ArrayList<>();
        for (MethodDef method : pinyin.methods) {
            if (method.result.equals("Z") && method.args.size() == 1
                    && method.refs().calls(PINYIN, Arrays.asList(keyDataName), "Z")) {
                keyEvents.add(method);
            }
        }
        MethodDef keyEvent = one(keyEvents, "Pinyin keyEvent");
        String eventName = keyEvent.args.get(0);

        List<MethodDef> keyDataGetters = new ArrayList<>();
        for (MethodDef call : keyEvent.refs().methods) {
            if (call.owner.equals(eventName) && call.args.isEmpty() && call.result.equals(keyDataName)) {
                keyDataGetters.add(call);
            }
        }
        MethodDef keyDataGetter = one(uniqueMethods(keyDataGetters), "Event keyData getter");
        FieldRef keyCodeField = one(fields(keyEvent.refs(), keyDataName, "I"), "KeyData keyCode field");
        FieldRef keyPayloadField = one(fields(acceptKey.refs(), keyDataName, OBJECT), "KeyData payload field");

        List<MethodDef> spaces = new ArrayList<>();
        for (MethodDef method : pinyin.methods) {
            if (method.result.equals("Z") && method.args.isEmpty() && method.refs().strings.contains("SPACE")) {
                spaces.add(method);
            }
        }
        MethodDef space = one(spaces, "Pinyin space commit");

        List<MethodDef> refreshes = new ArrayList<>();
        for (MethodDef method : chinese.methods) {
            if (method.result.equals("V") && method.args.equals(Arrays.asList("J"))
                    && method.refs().calls(HMM, Arrays.asList("Ljava/util/Iterator;"), "V")
                    && method.refs().calls(HMM, Arrays.asList(CHAR), "V")) {
                refreshes.add(method);
            }
        }
        MethodDef refresh = one(refreshes, "Chinese candidate refresh");

        List<MethodDef> composings = new ArrayList<>();
        for (MethodDef call : refresh.refs().methods) {
            if (call.owner.equals(HMM) && call.args.equals(Arrays.asList(CHAR)) && call.result.equals("V")) {
                composings.add(call);
            }
        }
        MethodDef composing = one(uniqueMethods(composings), "Composing text update");

        Definition engineWrapper = findByClinitString(definitions,
                "com/google/android/apps/inputmethod/libs/hmm/AbstractHmmEngineWrapper", "HMM engine wrapper");
        List<String> wrapperIfaces = new ArrayList<>();
        for (MethodDef call : select.refs().methods) {
            if (call.args.equals(Arrays.asList(candidateName)) && call.result.equals("V")
                    && !call.owner.equals(CHINESE) && !call.owner.equals(HMM)) {
                wrapperIfaces.add(call.owner);
            }
        }
        String wrapperIface = one(uniqueStrings(wrapperIfaces), "HMM engine wrapper interface");
        List<FieldRef> wrapperFields = new ArrayList<>();
        for (FieldRef f : hmm.fields) {
            if (f.owner.equals(HMM) && f.type.equals(wrapperIface)) wrapperFields.add(f);
        }
        FieldRef engineWrapperField = one(wrapperFields, "HMM engine wrapper field");

        List<FieldRef> contextEndFields = new ArrayList<>();
        for (MethodDef method : engineWrapper.methods) {
            if (method.result.equals("I") && method.args.isEmpty()
                    && method.refs().calls(HMM_IMPL, Collections.emptyList(), RANGE)) {
                contextEndFields.addAll(fields(method.refs(), engineWrapper.name, "I"));
            }
        }
        FieldRef contextEndVertex = one(unique(contextEndFields), "Engine wrapper contextEndVertex field");

        Definition pinyinFactory = findByClinitString(definitions,
                "com/google/android/apps/inputmethod/libs/pinyin/PinyinHmmEngineFactory", "Pinyin HMM engine factory");
        List<MethodDef> tokenDictMethods = new ArrayList<>();
        for (MethodDef method : pinyinFactory.methods) {
            if (method.result.equals(STRING) && method.args.isEmpty()
                    && method.refs().strings.contains("zh_t_i0_pinyin_android_token_dictionary")) {
                tokenDictMethods.add(method);
            }
        }
        MethodDef tokenDict = one(tokenDictMethods, "Pinyin active token dictionary");

        List<MethodDef> factoryGetters = new ArrayList<>();
        for (MethodDef method : pinyin.methods) {
            if (method.args.isEmpty() && !method.result.equals("V") && !method.result.equals("Z") && !method.result.equals("I")) {
                for (MethodDef call : method.refs().methods) {
                    if (call.owner.equals(pinyinFactory.name) && call.result.equals(pinyinFactory.name)) {
                        factoryGetters.add(method);
                        break;
                    }
                }
            }
        }
        MethodDef factoryGetter = one(factoryGetters, "Pinyin engine factory getter");

        // --- Gboard Settings & PreferenceDataStore anchors ---
        List<MethodDef> prefResCalls = new ArrayList<>();
        for (MethodDef call : tokenDict.refs().methods) {
            if (call.args.equals(Arrays.asList("I", STRING)) && call.result.equals(STRING)) {
                prefResCalls.add(call);
            }
        }
        MethodDef prefGetByRes = one(uniqueMethods(prefResCalls), "PrefStore getStringByRes");
        Definition prefStore = required(definitions, prefGetByRes.owner);
        MethodDef prefGetString = one(find(prefStore, null, Arrays.asList(STRING, STRING), STRING, 2), "PrefStore getString");
        MethodDef prefGetBool = one(find(prefStore, null, Arrays.asList(STRING, "Z"), "Z", 2), "PrefStore getBoolean");

        Definition prefDef = required(definitions, PREF);
        Definition prefGroupDef = required(definitions, PREF_GROUP);
        Definition listPrefDef = required(definitions, LIST_PREF);
        Definition twoStateDef = required(definitions, TWO_STATE_PREF);

        MethodDef pgFind = one(find(prefGroupDef, null, Arrays.asList(CHAR), PREF, 1), "PreferenceGroup findPreference");
        List<MethodDef> pgAdds = new ArrayList<>();
        for (MethodDef m : prefGroupDef.methods) {
            if (m.args.equals(Arrays.asList(PREF)) && m.result.equals("V")
                    && m.refs().calls("Ljava/util/List;", Arrays.asList("I", OBJECT), "V")) {
                pgAdds.add(m);
            }
        }
        MethodDef pgAdd = one(pgAdds, "PreferenceGroup addPreference");

        FieldRef prefKeyField = one(fields(pgFind.refs(), PREF, STRING), "Preference key field");
        FieldRef prefContextField = one(findFields(prefDef, "Landroid/content/Context;"), "Preference context field");
        List<MethodDef> prefSetKeys = new ArrayList<>();
        for (MethodDef m : prefDef.methods) {
            if (m.args.equals(Arrays.asList(STRING)) && m.result.equals("V")
                    && hasField(m.refs(), PREF, prefKeyField.name)) {
                prefSetKeys.add(m);
            }
        }
        MethodDef prefSetKey = one(prefSetKeys, "Preference setKey");

        List<MethodDef> listSetSummaries = find(listPrefDef, null, Arrays.asList(CHAR), "V", 1);
        MethodDef prefSetSummary = one(listSetSummaries, "Preference setSummary");
        List<MethodDef> prefSetTitles = new ArrayList<>();
        for (MethodDef m : find(prefDef, null, Arrays.asList(CHAR), "V", 1)) {
            if (!m.name.equals(prefSetSummary.name)) prefSetTitles.add(m);
        }
        MethodDef prefSetTitle = one(prefSetTitles, "Preference setTitle");

        MethodDef listSetEntries = one(find(listPrefDef, null, Arrays.asList(CHAR_ARR), "V", 1), "ListPreference setEntries");
        FieldRef listEntriesField = one(fields(listSetEntries.refs(), LIST_PREF, CHAR_ARR), "ListPreference entries field");
        List<FieldRef> listValuesFields = new ArrayList<>();
        for (FieldRef f : listPrefDef.fields) {
            if (f.owner.equals(LIST_PREF) && f.type.equals(CHAR_ARR) && !f.name.equals(listEntriesField.name)) {
                listValuesFields.add(f);
            }
        }
        FieldRef listEntryValuesField = one(listValuesFields, "ListPreference entryValues field");
        MethodDef twoStateSetChecked = one(find(twoStateDef, null, Arrays.asList("Z"), "V", 1), "TwoStatePreference setChecked");

        Properties properties = new Properties();
        properties.setProperty("versionName", version);
        properties.setProperty("candidateClass", javaName(candidateName));
        properties.setProperty("builderClass", javaName(builderName));
        properties.setProperty("builderCopy", copy.name);
        properties.setProperty("builderBuild", build.name);
        properties.setProperty("chineseProcessor", javaName(CHINESE));
        properties.setProperty("pinyinProcessor", javaName(PINYIN));
        properties.setProperty("hmmProcessor", javaName(HMM));
        properties.setProperty("selectMethod", select.name);
        properties.setProperty("deleteMethod", delete.name);
        properties.setProperty("commitMethod", commit.name);
        properties.setProperty("baseCommitMethod", baseCommit.name);
        properties.setProperty("resetMethod", reset.name);
        properties.setProperty("batchMethod", batch.name);
        properties.setProperty("iteratorField", iterator.name);
        properties.setProperty("deliveryClass", javaName(factory.owner));
        properties.setProperty("deliveryMethod", factory.name);
        properties.setProperty("keyEventMethod", keyEvent.name);
        properties.setProperty("eventClass", javaName(eventName));
        properties.setProperty("keyDataGetter", keyDataGetter.name);
        properties.setProperty("keyCodeField", keyCodeField.name);
        properties.setProperty("keyPayloadField", keyPayloadField.name);
        properties.setProperty("spaceMethod", space.name);
        properties.setProperty("refreshMethod", refresh.name);
        properties.setProperty("composingMethod", composing.name);
        properties.setProperty("engineWrapperField", engineWrapperField.name);
        properties.setProperty("engineWrapperClass", javaName(engineWrapper.name));
        properties.setProperty("contextEndVertexField", contextEndVertex.name);
        properties.setProperty("pinyinEngineFactoryClass", javaName(pinyinFactory.name));
        properties.setProperty("tokenDictMethod", tokenDict.name);
        properties.setProperty("engineFactoryGetter", factoryGetter.name);
        properties.setProperty("prefStoreClass", javaName(prefStore.name));
        properties.setProperty("prefGetStringByResMethod", prefGetByRes.name);
        properties.setProperty("prefGetStringMethod", prefGetString.name);
        properties.setProperty("prefGetBoolMethod", prefGetBool.name);
        properties.setProperty("pgAddMethod", pgAdd.name);
        properties.setProperty("pgFindMethod", pgFind.name);
        properties.setProperty("prefKeyField", prefKeyField.name);
        properties.setProperty("prefContextField", prefContextField.name);
        properties.setProperty("prefSetKeyMethod", prefSetKey.name);
        properties.setProperty("prefSetTitleMethod", prefSetTitle.name);
        properties.setProperty("prefSetSummaryMethod", prefSetSummary.name);
        properties.setProperty("listEntriesField", listEntriesField.name);
        properties.setProperty("listEntryValuesField", listEntryValuesField.name);
        properties.setProperty("twoStateSetCheckedMethod", twoStateSetChecked.name);

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

    private static Definition findByClinitString(Map<String, Definition> definitions, String marker, String label) {
        List<Definition> matches = new ArrayList<>();
        for (Definition definition : definitions.values()) {
            for (MethodDef method : definition.methods) {
                if (method.name.equals("<clinit>") && method.refs().strings.contains(marker)) {
                    matches.add(definition);
                    break;
                }
            }
        }
        return one(matches, label);
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

    private static List<MethodDef> uniqueMethods(List<MethodDef> values) {
        Map<String, MethodDef> unique = new LinkedHashMap<>();
        for (MethodDef m : values) unique.put(m.owner + "/" + m.name + m.args + m.result, m);
        return new ArrayList<>(unique.values());
    }

    private static List<String> uniqueStrings(List<String> values) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(values));
    }

    private static List<FieldRef> fields(Refs refs, String owner, String type) {
        List<FieldRef> result = new ArrayList<>();
        for (FieldRef field : refs.fields) if (field.owner.equals(owner) && field.type.equals(type)) result.add(field);
        return unique(result);
    }

    private static List<FieldRef> findFields(Definition owner, String type) {
        List<FieldRef> result = new ArrayList<>();
        for (FieldRef f : owner.fields) if (f.owner.equals(owner.name) && f.type.equals(type)) result.add(f);
        return unique(result);
    }

    private static boolean hasField(Refs refs, String owner, String name) {
        for (FieldRef f : refs.fields) if (f.owner.equals(owner) && f.name.equals(name)) return true;
        return false;
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

    /** Minimal DEX bytecode reader for resolving method/field references on device. */
    private static final class Dex {
        final byte[] bytes;
        final String[] strings, types;
        final String[] results;
        final List<List<String>> arguments = new ArrayList<>();
        final FieldRef[] fields;
        final MethodDef[] methods;

        Dex(byte[] bytes) {
            this.bytes = bytes;
            if (bytes.length < 112 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x' || bytes[3] != '\n') {
                throw new IllegalArgumentException("Not a DEX file");
            }
            strings = new String[u32(56)];
            int stringOff = u32(60);
            for (int i = 0; i < strings.length; i++) strings[i] = mutf8(u32(stringOff + i * 4));
            types = new String[u32(64)];
            int typeOff = u32(68);
            for (int i = 0; i < types.length; i++) types[i] = strings[u32(typeOff + i * 4)];
            int protoCount = u32(72), protoOff = u32(76);
            results = new String[protoCount];
            for (int i = 0; i < protoCount; i++) {
                results[i] = types[u32(protoOff + i * 12 + 4)];
                int paramsOff = u32(protoOff + i * 12 + 8);
                if (paramsOff == 0) arguments.add(Collections.emptyList());
                else {
                    int size = u32(paramsOff);
                    List<String> list = new ArrayList<>(size);
                    for (int j = 0; j < size; j++) list.add(types[u16(paramsOff + 4 + j * 2)]);
                    arguments.add(list);
                }
            }
            fields = new FieldRef[u32(80)];
            int fieldOff = u32(84);
            for (int i = 0; i < fields.length; i++) {
                fields[i] = new FieldRef(types[u16(fieldOff + i * 8)], strings[u32(fieldOff + i * 8 + 4)],
                        types[u16(fieldOff + i * 8 + 2)]);
            }
            methods = new MethodDef[u32(88)];
            int methodOff = u32(92);
            for (int i = 0; i < methods.length; i++) {
                int proto = u16(methodOff + i * 8 + 2);
                methods[i] = new MethodDef(this, types[u16(methodOff + i * 8)], strings[u32(methodOff + i * 8 + 4)],
                        results[proto], arguments.get(proto));
            }
        }

        List<Definition> definitions() {
            int count = u32(96), off = u32(100);
            List<Definition> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int base = off + i * 32;
                Definition definition = new Definition(types[u32(base)], u32(base + 4));
                int data = u32(base + 24);
                if (data != 0) {
                    int[] pos = {data};
                    int staticFields = uleb(pos), instanceFields = uleb(pos), directMethods = uleb(pos), virtualMethods = uleb(pos);
                    readFields(definition, pos, staticFields);
                    readFields(definition, pos, instanceFields);
                    readMethods(definition, pos, directMethods);
                    readMethods(definition, pos, virtualMethods);
                }
                list.add(definition);
            }
            return list;
        }

        private void readFields(Definition definition, int[] pos, int count) {
            int index = 0;
            for (int i = 0; i < count; i++) {
                index += uleb(pos);
                uleb(pos);
                definition.fields.add(fields[index]);
            }
        }

        private void readMethods(Definition definition, int[] pos, int count) {
            int index = 0;
            for (int i = 0; i < count; i++) {
                index += uleb(pos);
                uleb(pos);
                int code = uleb(pos);
                MethodDef method = methods[index];
                method.code = code;
                definition.methods.add(method);
            }
        }

        Refs references(int codeOffset) {
            Refs refs = new Refs();
            int size = u32(codeOffset + 12), start = codeOffset + 16;
            for (int i = 0; i < size;) {
                int unit = u16(start + i * 2), opcode = unit & 0xff;
                if (opcode == 0 && unit != 0) {
                    if (unit == 0x0100) i += u16(start + (i + 1) * 2) * 2 + 4;
                    else if (unit == 0x0200) i += u16(start + (i + 1) * 2) * 4 + 2;
                    else if (unit == 0x0300) {
                        int width = u16(start + (i + 1) * 2), count = u32(start + (i + 2) * 2);
                        i += (int) (((long) width * count + 1) / 2 + 4);
                    } else throw new IllegalArgumentException("Unknown DEX payload: 0x" + Integer.toHexString(unit));
                    continue;
                }
                if (opcode == 0x1a) refs.strings.add(strings[u16(start + (i + 1) * 2)]);
                else if (opcode == 0x1b) refs.strings.add(strings[u32(start + (i + 1) * 2)]);
                else if (opcode >= 0x52 && opcode <= 0x6d) {
                    FieldRef field = fields[u16(start + (i + 1) * 2)];
                    refs.fields.add(field);
                    refs.ordered.add(new Reference(opcode, opcode <= 0x5f ? (unit >> 8) & 0xf : (unit >> 8) & 0xff, field));
                } else if ((opcode >= 0x6e && opcode <= 0x72) || (opcode >= 0x74 && opcode <= 0x78)) {
                    refs.methods.add(methods[u16(start + (i + 1) * 2)]);
                }
                i += length(opcode);
            }
            return refs;
        }

        private static int length(int op) {
            if (op == 0x00 || op == 0x01 || op == 0x04 || op == 0x07 || (op >= 0x0a && op <= 0x12)
                    || op == 0x1d || op == 0x1e || op == 0x21 || op == 0x27 || op == 0x28
                    || (op >= 0x3e && op <= 0x43) || (op >= 0x7b && op <= 0x8f) || (op >= 0xb0 && op <= 0xcf)) return 1;
            if (op == 0x02 || op == 0x05 || op == 0x08 || op == 0x13 || op == 0x15 || op == 0x16 || op == 0x19 || op == 0x1a
                    || op == 0x1c || (op >= 0x1f && op <= 0x23) || op == 0x29 || (op >= 0x2d && op <= 0x3d)
                    || (op >= 0x44 && op <= 0x6d) || (op >= 0x90 && op <= 0xaf) || (op >= 0xd0 && op <= 0xe2)) return 2;
            if (op == 0x03 || op == 0x06 || op == 0x09 || op == 0x14 || op == 0x17 || op == 0x1b || op == 0x24 || op == 0x25
                    || op == 0x26 || (op >= 0x2a && op <= 0x2c) || (op >= 0x6e && op <= 0x78) || op == 0xfa || op == 0xfb
                    || op == 0xfc || op == 0xfd) return 3;
            if (op == 0x18) return 5;
            if (op == 0xfe || op == 0xff) return 2;
            throw new IllegalArgumentException("Unsupported DEX opcode: 0x" + Integer.toHexString(op));
        }

        private int u16(int off) { return (bytes[off] & 0xff) | ((bytes[off + 1] & 0xff) << 8); }
        private int u32(int off) { return u16(off) | (u16(off + 2) << 16); }

        private int uleb(int[] pos) {
            int result = 0, shift = 0;
            while (true) {
                int value = bytes[pos[0]++] & 0xff;
                result |= (value & 0x7f) << shift;
                if ((value & 0x80) == 0) return result;
                shift += 7;
            }
        }

        private String mutf8(int off) {
            int[] pos = {off};
            uleb(pos);
            int start = pos[0], end = start;
            while (bytes[end] != 0) end++;
            return new String(bytes, start, end - start, StandardCharsets.UTF_8);
        }
    }
}
