package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.ProducerSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Rebuilds the reviewed producer deltas and retains source, class, closure and archive proofs. */
final class ProducerBuild {
    final ProducerSupport c;
    ProducerBuild(ProducerSupport context) { c = context; }
    private static final Set<String> BOOTSTRAP_WORKER = Set.of("annotations-23.0.0.jar", "kotlin-reflect-1.6.10.jar", "kotlinx-coroutines-core-jvm-1.8.0.jar");
    private static final List<String> COLLECTION_SOURCES = List.of("TypeBridging.kt", "Types.kt", "SirSession.kt", "SirCustomTypeTranslatorImpl.kt", "AsStringArray.kt", "AsStringArrayObjC.kt");

    static List<String> collectionSourceOrder(Map<String, Object> repair) {
        // Preserve the reviewed compiler recipe independently of the JSON parser's object iteration order.
        check(m(repair.get("compile_sources")).keySet().equals(Set.copyOf(COLLECTION_SOURCES)), "Collection compiler source inventory changed");
        return COLLECTION_SOURCES;
    }

    void licenses(Map<String, Object> lock) throws Exception {
        for (Map.Entry<String, Object> file : m(lock.get("licenses")).entrySet()) check(sha(c.p(file.getKey())).equals(file.getValue()), "License checksum mismatch: " + file.getKey());
    }
    Map<String, Path> tools(Map<String, Object> lock, Path cache, Map<String, Object> seeds) throws Exception {
        Map<String, Path> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(lock.get("tools"))) result.put(s(row.get("name")), c.fetch(row, cache, seeds));
        return result;
    }
    List<Path> worker(Map<String, Object> lock, Map<String, Path> tools, Path parent) {
        List<Path> result = new ArrayList<>();
        for (Map<String, Object> row : rows(lock.get("tools"))) if ("worker".equals(row.get("scope"))) result.add(parent != null && "swift-export-embeddable".equals(row.get("artifact")) ? parent : tools.get(s(row.get("name"))));
        return result;
    }
    List<Path> bootstrap(Map<String, Object> lock, Map<String, Path> tools, List<Path> worker, boolean includeFirstRelease) {
        List<Path> result = new ArrayList<>();
        for (Map<String, Object> row : rows(lock.get("tools"))) if ("bootstrap".equals(row.get("scope"))) result.add(tools.get(s(row.get("name"))));
        if (includeFirstRelease) result.add(tools.get("swift-export-embeddable-2.4.20-km.1.jar"));
        for (Path path : worker) if (BOOTSTRAP_WORKER.contains(path.getFileName().toString())) result.add(path);
        return result;
    }
    String auditor(Path output, String classpath) throws Exception {
        Path auditor = output.resolve("auditor");
        c.run(List.of("java", "--enable-native-access=ALL-UNNAMED", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", classpath,
                "-jvm-target", "1.8", "-Werror", c.p("verification/ClassAudit.kt").toString(), c.p("verification/ClassAuditTests.kt").toString(), "-d", auditor.toString()), output.resolve("auditor-compile.log"));
        String auditClasspath = auditor + java.io.File.pathSeparator + classpath;
        c.run(List.of("java", "-cp", auditClasspath, "ClassAuditTestsKt"), output.resolve("auditor-tests.log"));
        return auditClasspath;
    }
    void canonical(String classpath, Path classes, Path output, List<String> entries, Path log) throws Exception {
        List<String> command = new ArrayList<>(List.of("java", "-cp", classpath, "ClassAuditKt", classes.toString(), output.toString()));
        command.addAll(entries); c.run(command, log);
    }
    List<String> compiler(List<Path> bootstrap, String classpath, Path parent, Path tmp, String module, List<Path> inputs, Path output, boolean experimental) {
        List<String> result = new ArrayList<>(List.of("java", "--enable-native-access=ALL-UNNAMED", "-Xmx4g", "-Djava.io.tmpdir=" + tmp, "-cp", classpath(bootstrap),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", classpath, "-Xfriend-paths=" + parent,
                "-module-name", module, "-jvm-target", "1.8", "-jvm-default=no-compatibility", "-Werror", "-opt-in=org.jetbrains.kotlin.analysis.api.KaContextParameterApi"));
        if (experimental) result.add("-opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi");
        inputs.forEach(path -> result.add(path.toString())); result.addAll(List.of("-d", output.toString())); return result;
    }
    Map<String, Path> sources(Path output, Path cache, Map<String, Object> seeds, Map<String, Object> lock, Path official) throws Exception {
        Map<String, Path> directories = new LinkedHashMap<>();
        for (String variant : List.of("baseline", "candidate")) { Path directory = output.resolve(variant + "/sources"); Files.createDirectories(directory); directories.put(variant, directory); }
        for (Map<String, Object> row : rows(lock.get("sources"))) {
            byte[] original = row.containsKey("archive_entry") ? entry(official, s(row.get("archive_entry"))) : Files.readAllBytes(c.fetch(row, cache, seeds));
            check(digest(original).equals(row.get("baseline_sha256")), "Source baseline mismatch: " + row.get("name"));
            for (Path directory : directories.values()) write(safe(directory, s(row.get("name"))), original);
            Path candidate = safe(directories.get("candidate"), s(row.get("name")));
            if (row.containsKey("patch")) {
                Path patch = c.p(s(row.get("patch")));
                check(sha(patch).equals(row.get("patch_sha256")), "Patch checksum mismatch");
                patch(candidate, patch, output.resolve(row.get("name") + ".patch.log"));
            }
            check(sha(candidate).equals(row.get("candidate_sha256")), "Candidate source mismatch: " + row.get("name"));
        }
        return directories;
    }
    void closure(Path output, Path result, List<Path> worker, List<String> entries) throws Exception {
        writeJson(output.resolve("closure-entries.json"), entries);
        List<Map<String, Object>> records = new ArrayList<>();
        for (Path path : worker) records.add(obj("path", path.toString(), "sha256", sha(path)));
        writeJson(output.resolve("worker.json"), records);
        ProducerClosureAudit.audit(c, result, output.resolve("worker.json"), output.resolve("closure-entries.json"), Path.of(System.getProperty("java.home")), output.resolve("reference-closure.json"));
        write(output.resolve("reference-closure.log"), "PASS exact member descriptors/access/static linkage; Java closure audit\n");
    }
    void build(Path output, Path cache, Map<String, Object> seeds) throws Exception {
        Map<String, Object> lock = c.lock();
        if (lock.containsKey("collection_repair")) collections(output, cache, seeds, lock);
        else if (lock.containsKey("bytearray_repair")) bytearrays(output, cache, seeds, lock);
        else if (lock.containsKey("array_repair")) arrays(output, cache, seeds, lock);
        else baseline(output, cache, seeds, lock);
    }

    void collectionCandidate(Path root, Path cache, Path parent, Path publishedSources) throws Exception {
        collectionCandidate(root, cache, parent, publishedSources, Map.of());
    }
    private void collectionCandidate(Path root, Path cache, Path parent, Path publishedSources, Map<String, Path> existingTools) throws Exception {
        check(!Files.exists(root), "New output required"); Files.createDirectories(root); Files.createDirectory(root.resolve("tmp"));
        Path sourceLock = c.p("producer/collections/source-lock.json");
        Map<String, Object> repair = m(Json.read(sourceLock)), lock = c.lock();
        List<String> sourceOrder = collectionSourceOrder(repair);
        check(sha(parent).equals(repair.get("base_component_sha256")), "Published parent mismatch");
        check(sha(publishedSources).equals(repair.get("published_sources_sha256")), "Published sources mismatch");
        Path patch = c.p(s(repair.get("patch")));
        check(sha(patch).equals(repair.get("patch_sha256")), "Patch mismatch");
        Map<String, Path> tools = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(lock.get("tools"))) {
            Path path = existingTools.getOrDefault(s(row.get("name")), safe(cache, s(row.get("name"))));
            check(Files.isRegularFile(path) && !Files.isSymbolicLink(path) && sha(path).equals(row.get("sha256")), "Frozen compiler input mismatch: " + row.get("name"));
            tools.put(s(row.get("name")), path);
        }
        List<Path> worker = worker(lock, tools, parent), bootstrap = bootstrap(lock, tools, worker, true);
        check(worker.size() == 16, "Worker closure changed");
        String cp = classpath(worker);
        List<Path> fixed = new ArrayList<>(List.of(c.p("buildSrc/src/main/java/io/github/kotlinmania/build/ProducerBuild.java"), sourceLock, patch, c.p("inputs.lock.json"), parent, publishedSources,
                c.p("buildSrc/src/main/java/io/github/kotlinmania/build/ProducerSupport.java"), c.p("verification/ClassAudit.kt"), c.p("verification/ClassAuditTests.kt"), c.p("buildSrc/src/main/java/io/github/kotlinmania/build/ProducerClosureAudit.java")));
        fixed.addAll(tools.values());
        for (String variant : List.of("baseline", "candidate")) {
            Path directory = root.resolve(variant + "/sources"); Files.createDirectories(directory);
            for (String source : sourceOrder) {
                Map<String, Object> row = m(m(repair.get("compile_sources")).get(source)); byte[] data = entry(publishedSources, s(row.get("archive_entry")));
                check(digest(data).equals(row.get("sha256")), "Published source differs: " + source); write(safe(directory, source), data);
            }
        }
        patch(root.resolve("candidate/sources/TypeBridging.kt"), patch, root.resolve("patch.log"));
        check(sha(root.resolve("candidate/sources/TypeBridging.kt")).equals(repair.get("candidate_source_sha256")), "Candidate source differs");
        String callbackArrayBridge = Files.readString(root.resolve("candidate/sources/TypeBridging.kt"));
        check(callbackArrayBridge.contains("val elementConversion = elementBridge.inSwiftSources.kotlinToSwift(typeNamer, \"__element as! Swift.Int\")")
                        && callbackArrayBridge.contains("return \"$valueExpression.map { __element in $elementConversion }\"")
                        && !callbackArrayBridge.contains("return \"($valueExpression as! [Any]).map { __element in $elementConversion }\""),
                "Callback NSArray bridge must preserve per-element pointer conversion without recasting imported [Any]");
        Map<String, List<Path>> inputs = new LinkedHashMap<>();
        for (String variant : List.of("baseline", "candidate")) {
            Path directory = root.resolve(variant + "/inputs"); Files.createDirectory(directory);
            List<Path> files = new ArrayList<>();
            for (String name : sourceOrder) {
                Path source = root.resolve(variant + "/sources/" + name), target = safe(directory, name);
                write(target, normalizeSource(Files.readString(source))); files.add(target); fixed.addAll(List.of(source, target));
            }
            inputs.put(variant, files);
        }
        Map<String, String> pins = pins(fixed), classPins = new TreeMap<>();
        Map<Path, List<String>> inventories = new LinkedHashMap<>();
        writeJson(root.resolve("input-pins.json"), pins);
        Map<String, String> modules = new LinkedHashMap<>(); modules.put("published", "org.jetbrains.kotlin:sir-providers"); modules.put("rebuilt", "org.jetbrains.kotlin_sir-providers");
        for (String variant : List.of("baseline", "candidate")) for (Map.Entry<String, String> module : modules.entrySet()) {
            Path unit = root.resolve(variant + "/" + module.getKey()); Files.createDirectory(unit);
            List<String> command = compiler(bootstrap, cp, parent, root.resolve("tmp"), module.getValue(), inputs.get(variant), unit.resolve("classes"), false);
            verify(pins, classPins, inventories); writeJson(unit.resolve("invocation.json"), obj("args", command)); c.run(command, unit.resolve("compile.log"));
            Path directory = unit.resolve("classes"); inventories.put(directory, classInventory(directory));
            classPins.putAll(pins(files(directory).stream().filter(path -> path.toString().endsWith(".class")).toList())); verify(pins, classPins, inventories);
        }
        List<String> entries = classInventory(root.resolve("baseline/published/classes"));
        check(inventories.values().stream().allMatch(entries::equals), "Class inventory changed"); writeJson(root.resolve("class-inventory.json"), entries);
        String auditCp = auditor(root, cp);
        Path parentClasses = root.resolve("parent-classes"); Files.createDirectory(parentClasses);
        for (String entry : entries) write(safe(parentClasses, entry), entry(parent, entry));
        Map<String, Path> directories = new LinkedHashMap<>(); directories.put("parent", parentClasses);
        for (String variant : List.of("baseline", "candidate")) for (String module : modules.keySet()) directories.put(variant + "-" + module, root.resolve(variant + "/" + module + "/classes"));
        for (Map.Entry<String, Path> directory : directories.entrySet()) canonical(auditCp, directory.getValue(), root.resolve(directory.getKey() + "-canonical"), entries, root.resolve(directory.getKey() + "-attributes.log"));
        List<String> expected = List.of("org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsContravariantBlock$inSwiftSources$1.class",
                "org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSArray$inSwiftSources$1.class", "org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSArray.class", "org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSCollection$InSwiftSources.class",
                "org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSCollection.class", "org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSDictionary.class");
        check(m(repair.get("class_modules")).keySet().stream().sorted().toList().equals(expected), "Reviewed class selection changed");
        for (String module : modules.keySet()) check(differences(entries, root.resolve("baseline-" + module + "-canonical"), root.resolve("candidate-" + module + "-canonical")).equals(expected), "Unexpected non-debug class delta");
        Map<String, String> selectedModule = new LinkedHashMap<>();
        for (Map.Entry<String, Object> selection : m(repair.get("class_modules")).entrySet()) {
            String module = modules.entrySet().stream().filter(row -> row.getValue().equals(selection.getValue())).map(Map.Entry::getKey).findFirst().orElseThrow();
            selectedModule.put(selection.getKey(), module);
            check(Files.mismatch(root.resolve("parent-canonical/" + selection.getKey()), root.resolve("baseline-" + module + "-canonical/" + selection.getKey())) == -1, "Published baseline differs with exact module identity: " + selection.getKey());
        }
        Map<String, Object> parentDifferences = new LinkedHashMap<>();
        for (String module : modules.keySet()) parentDifferences.put(module, differences(entries, root.resolve("parent-canonical"), root.resolve("baseline-" + module + "-canonical")));
        writeJson(root.resolve("parent-baseline-differences.json"), parentDifferences);
        Map<String, byte[]> original = entries(parent), contents = new TreeMap<>(original);
        for (String entry : expected) contents.put(entry, Files.readAllBytes(root.resolve("candidate/" + selectedModule.get(entry) + "/classes/" + entry)));
        Path result = root.resolve("swift-export-foundation-collection-diagnostic.jar"); archive(result, contents);
        Map<String, byte[]> rebuilt = entries(result);
        check(rebuilt.keySet().equals(original.keySet()), "Archive inventory changed");
        check(new TreeSet<>(expected).containsAll(changed(original, rebuilt)), "Unselected parent bytes changed");
        closure(root, result, worker, expected); verify(pins, classPins, inventories);
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String entry : expected) attributes.put(entry, sha(root.resolve("candidate-" + selectedModule.get(entry) + "-canonical/" + entry)));
        check(attributes.equals(repair.get("selected_non_debug_sha256")), "Reviewed collection attributes changed");
        writeJson(root.resolve("verified-host-result.json"), obj("host_source_class_audit_passed", true, "native_or_swift_behavior_executed", false,
                "component", result.toString(), "sha256", sha(result), "changed_classes", expected, "class_inventory_count", entries.size(), "parent_baseline_differences", parentDifferences,
                "unselected_parent_bytes_identical", true, "added_or_removed_entries", List.of(), "reference_closure_sha256", sha(root.resolve("reference-closure.json")),
                "class_modules", repair.get("class_modules"), "candidate_attributes", attributes));
        System.out.println("PASS isolated host source/class audit; Native and Swift behavior remain unexecuted");
    }

    private static List<String> differences(List<String> entries, Path before, Path after) throws Exception {
        List<String> result = new ArrayList<>(); for (String entry : entries) if (Files.mismatch(before.resolve(entry), after.resolve(entry)) != -1) result.add(entry); return result;
    }
    private static void verify(Map<String, String> pins, Map<String, String> classes, Map<Path, List<String>> inventories) throws Exception {
        verifyPins(pins, "Frozen source/tool input changed"); verifyPins(classes, "Compiled class changed after its phase");
        for (Map.Entry<Path, List<String>> inventory : inventories.entrySet()) check(classInventory(inventory.getKey()).equals(inventory.getValue()), "Compiled class inventory changed after its phase");
    }

    private void collections(Path output, Path cache, Map<String, Object> seeds, Map<String, Object> lock) throws Exception {
        Path repairPath = c.p(s(lock.get("collection_repair")));
        check(sha(repairPath).equals(lock.get("collection_repair_sha256")), "Collection lock mismatch");
        Map<String, Object> repair = m(Json.read(repairPath));
        check(!Files.exists(output), "Build output must be new"); Files.createDirectories(output); Files.createDirectories(cache); licenses(lock);
        Map<String, Path> tools = tools(lock, cache, seeds);
        Path parent = tools.get("swift-export-embeddable-2.4.20-km.3.jar"), published = tools.get("swift-export-embeddable-2.4.20-km.3-sources.jar"), auditRoot = output.resolve("collections");
        collectionCandidate(auditRoot, cache, parent, published, tools);
        Map<String, Object> audit = m(Json.read(auditRoot.resolve("verified-host-result.json")));
        check(audit.get("candidate_attributes").equals(repair.get("selected_non_debug_sha256")), "Reviewed class attributes changed");
        Path diagnostic = auditRoot.resolve("swift-export-foundation-collection-diagnostic.jar"); check(sha(diagnostic).equals(audit.get("sha256")), "Audited archive changed");
        Path sourceDirectory = output.resolve("candidate/sources"); Files.createDirectories(sourceDirectory);
        for (Map<String, Object> row : rows(lock.get("sources"))) {
            byte[] data = row.get("name").equals("TypeBridging.kt") ? Files.readAllBytes(auditRoot.resolve("candidate/sources/TypeBridging.kt")) : entry(published, s(row.get("path")));
            check(digest(data).equals(row.get("candidate_sha256")), "Complete release source mismatch: " + row.get("name")); write(safe(sourceDirectory, s(row.get("name"))), data);
        }
        Map<String, byte[]> contents = entries(diagnostic);
        contents.put("META-INF/kotlinmania/inputs.lock.json", Files.readAllBytes(c.p("inputs.lock.json")));
        contents.put("META-INF/kotlinmania/collection-source-lock.json", Files.readAllBytes(repairPath));
        Path result = output.resolve(lock.get("artifact") + "-" + lock.get("version") + ".jar"); archive(result, contents);
        Map<String, byte[]> original = entries(parent), rebuilt = entries(result);
        check(rebuilt.keySet().containsAll(original.keySet()), "Parent archive entry removed");
        Set<String> added = difference(rebuilt.keySet(), original.keySet()); check(added.equals(Set.of("META-INF/kotlinmania/collection-source-lock.json")), "Unexpected release additions");
        List<String> changed = changed(original, rebuilt);
        check(new TreeSet<>(changed).equals(union(m(repair.get("class_modules")).keySet(), Set.of("META-INF/kotlinmania/inputs.lock.json"))), "Unexpected release delta");
        writeJson(output.resolve("build-result.json"), obj("artifact", result.getFileName().toString(), "sha256", sha(result), "changed_entries", changed, "added_entries", new ArrayList<>(added), "removed_file_entries", List.of(),
                "base_component_sha256", repair.get("base_component_sha256"), "class_deltas", audit.get("candidate_attributes"), "all_non_debug_class_attributes_match_reviewed_candidate", true,
                "collection_audit_sha256", sha(auditRoot.resolve("verified-host-result.json")), "native_header_sha256", m(lock.get("native_header")).get("candidate_sha256")));
        System.out.println(result + " " + sha(result));
    }

    // Earlier reviewed repair stages remain supported for their corresponding pinned input locks.
    private void baseline(Path output, Path cache, Map<String, Object> seeds, Map<String, Object> lock) throws Exception { ProducerLegacyBuild.baseline(this, output, cache, seeds, lock); }
    private void arrays(Path output, Path cache, Map<String, Object> seeds, Map<String, Object> lock) throws Exception { ProducerLegacyBuild.arrays(this, output, cache, seeds, lock); }
    private void bytearrays(Path output, Path cache, Map<String, Object> seeds, Map<String, Object> lock) throws Exception { ProducerLegacyBuild.bytearrays(this, output, cache, seeds, lock); }
}
