package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.ProducerSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Earlier pinned producer stages; each retains its full class/attribute and archive audit. */
final class ProducerLegacyBuild {
    private ProducerLegacyBuild() {}

    static void bytearrays(ProducerBuild b, Path output, Path cache, Map<String, Object> seeds, Map<String, Object> lock) throws Exception {
        ProducerSupport c = b.c;
        byte[] repairBytes = Files.readAllBytes(c.p(s(lock.get("bytearray_repair"))));
        check(digest(repairBytes).equals(lock.get("bytearray_repair_sha256")), "Constructor lock mismatch");
        Map<String, Object> repair = m(Json.parse(new String(repairBytes, java.nio.charset.StandardCharsets.UTF_8)));
        check(!Files.exists(output), "Build output must be new"); Files.createDirectories(output); Files.createDirectories(cache); Files.createDirectory(output.resolve("tmp")); b.licenses(lock);
        Map<String, Path> tools = b.tools(lock, cache, seeds);
        Path parent = tools.get(s(m(repair.get("base_component")).get("name")));
        check(sha(parent).equals(m(repair.get("base_component")).get("sha256")), "Parent mismatch");
        List<Path> worker = b.worker(lock, tools, parent), bootstrap = b.bootstrap(lock, tools, worker, true);
        check(worker.size() == 16, "Worker closure changed"); String cp = classpath(worker);
        Map<String, Path> sources = b.sources(output, cache, seeds, lock, tools.get("swift-export-embeddable-2.4.20.jar"));
        for (Map.Entry<String, Path> source : sources.entrySet()) {
            Path unit = output.resolve(source.getKey() + "/constructors"), inputs = unit.resolve("inputs"); Files.createDirectories(inputs);
            List<Path> files = new ArrayList<>();
            for (String name : strings(repair.get("compile_sources"))) { Path file = safe(inputs, name); write(file, normalizeSource(Files.readString(source.getValue().resolve(name)))); files.add(file); }
            List<String> command = b.compiler(bootstrap, cp, parent, output.resolve("tmp"), "org.jetbrains.kotlin:sir-light-classes", files, unit.resolve("classes"), true);
            writeJson(unit.resolve("invocation.json"), obj("args", command)); c.run(command, unit.resolve("compile.log"));
        }
        Path base = output.resolve("baseline/constructors/classes"), candidate = output.resolve("candidate/constructors/classes");
        check(classInventory(base).equals(strings(repair.get("baseline_inventory"))), "Baseline inventory changed");
        check(classInventory(candidate).equals(strings(repair.get("candidate_inventory"))), "Candidate inventory changed");
        String auditCp = b.auditor(output, cp);
        List<String> baselineSelected = strings(repair.get("baseline_selected"));
        Path parentClasses = output.resolve("parent-classes"); Files.createDirectory(parentClasses);
        for (String name : baselineSelected) write(safe(parentClasses, name), entry(parent, name));
        Map<String, Object> selected = m(repair.get("selected_classes"));
        Set<String> excluded = difference(new TreeSet<>(strings(repair.get("baseline_inventory"))), new TreeSet<>(baselineSelected));
        List<String> changedExcluded = new ArrayList<>();
        for (String name : excluded) if (Files.mismatch(base.resolve(name), candidate.resolve(name)) != -1) changedExcluded.add(name);
        check(changedExcluded.equals(strings(repair.get("changed_excluded_classes"))), "Excluded compiler delta changed");
        b.canonical(auditCp, parentClasses, output.resolve("parent-canonical"), baselineSelected, output.resolve("parent-attributes.log"));
        b.canonical(auditCp, base, output.resolve("baseline-canonical"), new ArrayList<>(union(new TreeSet<>(baselineSelected), new TreeSet<>(changedExcluded))), output.resolve("baseline-attributes.log"));
        b.canonical(auditCp, candidate, output.resolve("candidate-canonical"), new ArrayList<>(union(selected.keySet(), new TreeSet<>(changedExcluded))), output.resolve("candidate-attributes.log"));
        for (String name : baselineSelected) check(Files.mismatch(output.resolve("parent-canonical/" + name), output.resolve("baseline-canonical/" + name)) == -1, "Baseline attributes differ: " + name);
        for (String name : changedExcluded) check(Files.mismatch(output.resolve("baseline-canonical/" + name), output.resolve("candidate-canonical/" + name)) == -1, "Excluded source changed: " + name);
        Map<String, byte[]> replacements = new TreeMap<>();
        for (Map.Entry<String, Object> row : selected.entrySet()) {
            check(sha(output.resolve("candidate-canonical/" + row.getKey())).equals(m(row.getValue()).get("full_non_debug_sha256")), "Reviewed candidate differs: " + row.getKey());
            replacements.put(row.getKey(), Files.readAllBytes(candidate.resolve(row.getKey())));
        }
        replacements.put("META-INF/kotlinmania/inputs.lock.json", Files.readAllBytes(c.p("inputs.lock.json")));
        replacements.put("META-INF/kotlinmania/bytearrays.lock.json", repairBytes);
        Map<String, byte[]> original = entries(parent), contents = new TreeMap<>(original); contents.putAll(replacements);
        check(difference(selected.keySet(), original.keySet()).equals(new TreeSet<>(strings(repair.get("added_classes")))), "New class classification changed");
        Path result = output.resolve(lock.get("artifact") + "-" + lock.get("version") + ".jar"); archive(result, contents);
        Map<String, byte[]> rebuilt = entries(result); check(rebuilt.keySet().containsAll(original.keySet()), "Parent file removed");
        check(replacements.keySet().containsAll(changed(original, rebuilt)), "Excluded parent entry changed");
        b.closure(output, result, worker, new ArrayList<>(union(new TreeSet<>(baselineSelected), selected.keySet())));
        writeJson(output.resolve("build-result.json"), obj("sha256", sha(result), "artifact", result.getFileName().toString(), "parent_sha256", m(repair.get("base_component")).get("sha256"),
                "changed_entries", new ArrayList<>(replacements.keySet()), "added_entries", new ArrayList<>(difference(contents.keySet(), original.keySet())), "removed_file_entries", List.of(),
                "baseline_classes_checked", baselineSelected.size(), "all_non_debug_class_attributes_match_reviewed_candidate", true, "excluded_parent_entries_byte_identical", true,
                "reference_closure_sha256", sha(output.resolve("reference-closure.json")), "native_header_sha256", m(lock.get("native_header")).get("candidate_sha256")));
        System.out.println(result + " " + sha(result));
    }

    static void arrays(ProducerBuild b, Path output, Path cache, Map<String, Object> seeds, Map<String, Object> lock) throws Exception {
        ProducerSupport c = b.c;
        byte[] repairBytes = Files.readAllBytes(c.p(s(lock.get("array_repair"))));
        check(digest(repairBytes).equals(lock.get("array_repair_sha256")), "Array repair lock mismatch");
        Map<String, Object> repair = m(Json.parse(new String(repairBytes, java.nio.charset.StandardCharsets.UTF_8)));
        check(!Files.exists(output), "Build output must be a new directory"); Files.createDirectories(output); Files.createDirectories(cache); b.licenses(lock);
        for (Map.Entry<String, Object> file : m(repair.get("owned_sources")).entrySet()) check(sha(c.p(file.getKey())).equals(file.getValue()), "Owned source checksum mismatch");
        Map<String, Path> tools = b.tools(lock, cache, seeds);
        Path parent = tools.get("swift-export-embeddable-2.4.20-km.1.jar"), official = tools.get("swift-export-embeddable-2.4.20.jar");
        check(sha(parent).equals(m(repair.get("base_component")).get("sha256")), "Parent mismatch");
        List<Path> worker = b.worker(lock, tools, parent), bootstrap = b.bootstrap(lock, tools, worker, false); String cp = classpath(worker);
        Map<String, Path> sources = b.sources(output, cache, seeds, lock, official);
        Map<String, Object> baseline = m(repair.get("baseline_type_bridging")); Path patch = c.p(s(baseline.get("patch")));
        check(sha(patch).equals(baseline.get("patch_sha256")), "Baseline patch mismatch");
        patch(sources.get("baseline").resolve("TypeBridging.kt"), patch, output.resolve("baseline-type-bridging.patch.log"));
        check(sha(sources.get("baseline").resolve("TypeBridging.kt")).equals(baseline.get("sha256")), "Published source mismatch");
        for (String name : List.of("AsStringArray.kt", "AsStringArrayObjC.kt")) write(sources.get("candidate").resolve(name), Files.readAllBytes(c.p("producer/arrays/" + name)));
        Map<String, Object> selected = new TreeMap<>();
        m(lock.get("selected_classes")).forEach((name, row) -> { if ("array".equals(m(row).get("kind"))) selected.put(name, row); });
        for (Map.Entry<String, Path> source : sources.entrySet()) {
            Path unit = output.resolve(source.getKey() + "/array"), inputs = unit.resolve("inputs"); Files.createDirectories(inputs); Files.createDirectory(unit.resolve("tmp"));
            List<String> names = new ArrayList<>(List.of("TypeBridging.kt", "SirCustomTypeTranslatorImpl.kt", "SirSession.kt", "Types.kt"));
            if (source.getKey().equals("candidate")) names.addAll(List.of("AsStringArray.kt", "AsStringArrayObjC.kt"));
            List<Path> files = new ArrayList<>();
            for (String name : names) { Path target = inputs.resolve(name); write(target, normalizeSource(Files.readString(source.getValue().resolve(name)))); files.add(target); }
            List<String> command = b.compiler(bootstrap, cp, parent, unit.resolve("tmp"), "org.jetbrains.kotlin_sir-providers", files, unit.resolve("classes"), false);
            writeJson(unit.resolve("invocation.json"), obj("args", command)); c.run(command, unit.resolve("compile.log"));
        }
        Path baselineClasses = output.resolve("baseline/array/classes"), classes = output.resolve("candidate/array/classes");
        List<String> baselineEntries = classInventory(baselineClasses); check(baselineEntries.size() == 159, "Unexpected complete baseline class inventory");
        c.run(List.of("ruby", c.p("verification/audit-baseline.rb").toString(), parent.toString(), baselineClasses.toString(), Json.stringify(baselineEntries)), output.resolve("array-baseline-audit.json"));
        Set<String> added = new TreeSet<>(); selected.forEach((name, row) -> { if (Boolean.TRUE.equals(m(row).get("added"))) added.add(name); });
        check(new TreeSet<>(classInventory(classes)).equals(union(new TreeSet<>(baselineEntries), added)) && added.size() == 9, "Candidate inventory mismatch");
        Map<String, byte[]> original = entries(parent);
        for (Map.Entry<String, Object> row : selected.entrySet()) check(!original.containsKey(row.getKey()) == Boolean.TRUE.equals(m(row.getValue()).get("added")), "Wrong new-class classification");
        String auditCp = b.auditor(output, cp); Path canonical = output.resolve("candidate/array/canonical");
        b.canonical(auditCp, classes, canonical, new ArrayList<>(selected.keySet()), output.resolve("array-runtime-attributes.log"));
        Map<String, byte[]> replacements = new TreeMap<>();
        for (Map.Entry<String, Object> row : selected.entrySet()) { check(sha(canonical.resolve(row.getKey())).equals(m(row.getValue()).get("runtime_class_sha256")), "Non-debug class attributes differ: " + row.getKey()); replacements.put(row.getKey(), Files.readAllBytes(classes.resolve(row.getKey()))); }
        for (Map.Entry<String, Object> resource : m(lock.get("resources")).entrySet()) {
            Map<String, Object> source = rows(lock.get("sources")).stream().filter(row -> resource.getKey().equals(row.get("archive_entry"))).findFirst().orElseThrow();
            byte[] data = Files.readAllBytes(sources.get("candidate").resolve(s(source.get("name")))); check(digest(data).equals(resource.getValue()), "Resource mismatch: " + resource.getKey()); replacements.put(resource.getKey(), data);
        }
        replacements.put("META-INF/kotlinmania/inputs.lock.json", Files.readAllBytes(c.p("inputs.lock.json"))); replacements.put("META-INF/kotlinmania/arrays.lock.json", repairBytes);
        check(difference(replacements.keySet(), original.keySet()).equals(union(added, Set.of("META-INF/kotlinmania/arrays.lock.json"))), "Unexpected producer addition");
        Map<String, byte[]> contents = new TreeMap<>(original); contents.putAll(replacements);
        Path result = output.resolve(lock.get("artifact") + "-" + lock.get("version") + ".jar"); archive(result, contents);
        Map<String, byte[]> rebuilt = entries(result); check(rebuilt.keySet().containsAll(original.keySet()), "Removed parent file"); List<String> changed = changed(original, rebuilt);
        check(replacements.keySet().containsAll(changed), "Unselected parent entry changed");
        writeJson(output.resolve("build-result.json"), obj("sha256", sha(result), "artifact", result.getFileName().toString(), "changed_entries", changed,
                "added_entries", new ArrayList<>(difference(replacements.keySet(), original.keySet())), "removed_file_entries", List.of(), "parent_sha256", m(repair.get("base_component")).get("sha256"),
                "all_non_debug_class_attributes_match_reviewed_candidate", true, "baseline_classes_checked", baselineEntries.size(), "native_header_sha256", m(lock.get("native_header")).get("candidate_sha256")));
        System.out.println(result + " " + sha(result));
    }

    static void baseline(ProducerBuild b, Path output, Path cache, Map<String, Object> seeds, Map<String, Object> lock) throws Exception {
        ProducerSupport c = b.c; b.licenses(lock); check(!Files.exists(output), "Build output must be a new directory"); Files.createDirectories(output); Files.createDirectories(cache);
        Map<String, Path> tools = b.tools(lock, cache, seeds); List<Path> worker = b.worker(lock, tools, null), bootstrap = b.bootstrap(lock, tools, worker, false);
        Path official = tools.get("swift-export-embeddable-2.4.20.jar"); String cp = classpath(worker);
        Map<String, Path> sources = b.sources(output, cache, seeds, lock, official);
        String auditCp = b.auditor(output, cp); Map<String, Object> deltas = new TreeMap<>();
        for (String kind : List.of("providers", "printer", "enum")) {
            List<Map<String, Object>> rows = rows(lock.get("sources")).stream().filter(row -> kind.equals(row.get("kind")) || kind.equals("enum") && "enum-inline".equals(row.get("kind"))).toList();
            for (Map.Entry<String, Path> source : sources.entrySet()) {
                Path unit = output.resolve(source.getKey() + "/" + kind), normalized = unit.resolve("inputs"); Files.createDirectories(normalized); Files.createDirectory(unit.resolve("tmp"));
                List<Map<String, Object>> selectedSources = new ArrayList<>(rows);
                if (kind.equals("printer") && source.getKey().equals("baseline")) selectedSources.addAll(rows(lock.get("sources")).stream().filter(row -> "printer-baseline-inline".equals(row.get("kind"))).toList());
                List<Path> files = new ArrayList<>();
                for (Map<String, Object> row : selectedSources) { Path path = normalized.resolve(s(row.get("name"))); write(path, normalizeSource(Files.readString(source.getValue().resolve(s(row.get("name")))))); files.add(path); }
                String module = switch (kind) { case "providers" -> "org.jetbrains.kotlin_sir-providers"; case "printer" -> "org.jetbrains.kotlin_sir-printer"; default -> "org.jetbrains.kotlin_sir-light-classes"; };
                List<String> command = b.compiler(kind.equals("enum") ? bootstrap : worker, cp, official, unit.resolve("tmp"), module, files, unit.resolve("classes"), kind.equals("enum"));
                if (kind.equals("printer")) command.remove("-opt-in=org.jetbrains.kotlin.analysis.api.KaContextParameterApi");
                writeJson(unit.resolve("invocation.json"), obj("args", command)); c.run(command, unit.resolve("compile.log"));
            }
            Map<String, Object> selected = new TreeMap<>(); m(lock.get("selected_classes")).forEach((name, row) -> { if (kind.equals(m(row).get("kind"))) selected.put(name, row); });
            c.run(List.of("ruby", c.p("verification/audit-baseline.rb").toString(), official.toString(), output.resolve("baseline/" + kind + "/classes").toString(), Json.stringify(selected.keySet())), output.resolve(kind + "-baseline-audit.json"));
            Path normalized = output.resolve("candidate/" + kind + "/canonical"), classes = output.resolve("candidate/" + kind + "/classes");
            b.canonical(auditCp, classes, normalized, new ArrayList<>(selected.keySet()), output.resolve(kind + "-runtime-attributes.log"));
            for (Map.Entry<String, Object> entry : selected.entrySet()) {
                Map<String, Object> row = m(entry.getValue()); Path rebuilt = classes.resolve(entry.getKey()); check(Files.isRegularFile(rebuilt), "Candidate class is missing: " + entry.getKey());
                check(sha(normalized.resolve(entry.getKey())).equals(row.get("runtime_class_sha256")), "Candidate non-debug attributes differ from reviewed class: " + entry.getKey());
                deltas.put(entry.getKey(), obj("reviewed_raw_sha256", row.get("candidate_sha256"), "rebuilt_raw_sha256", sha(rebuilt), "runtime_class_sha256", row.get("runtime_class_sha256"), "runtime_attributes_equal", true));
            }
        }
        Map<String, byte[]> replacements = new TreeMap<>(), additions = new TreeMap<>();
        for (Map.Entry<String, Object> entry : m(lock.get("selected_classes")).entrySet()) replacements.put(entry.getKey(), Files.readAllBytes(output.resolve("candidate/" + m(entry.getValue()).get("kind") + "/classes/" + entry.getKey())));
        for (String resource : m(lock.get("resources")).keySet()) {
            Map<String, Object> row = rows(lock.get("sources")).stream().filter(source -> resource.equals(source.get("archive_entry"))).findFirst().orElseThrow();
            replacements.put(resource, Files.readAllBytes(sources.get("candidate").resolve(s(row.get("name")))));
        }
        additions.put("META-INF/LICENSE", Files.readAllBytes(c.p("LICENSE"))); additions.put("META-INF/NOTICE", Files.readAllBytes(c.p("NOTICE"))); additions.put("META-INF/kotlinmania/inputs.lock.json", Files.readAllBytes(c.p("inputs.lock.json")));
        try (var paths = Files.list(c.p("licenses/upstream"))) { for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) additions.put("META-INF/upstream/" + path.getFileName(), Files.readAllBytes(path)); }
        Map<String, byte[]> original = entries(official), contents = new TreeMap<>(original);
        check(difference(additions.keySet(), original.keySet()).equals(additions.keySet()), "Release notice collides with existing entry"); check(original.keySet().containsAll(replacements.keySet()), "Unexpected added producer entry");
        contents.putAll(replacements); contents.putAll(additions);
        Path result = output.resolve(lock.get("artifact") + "-" + lock.get("version") + ".jar"); archive(result, contents);
        Map<String, byte[]> rebuilt = entries(result); check(new TreeSet<>(changed(original, rebuilt)).equals(replacements.keySet()), "Unexpected final archive delta"); check(difference(rebuilt.keySet(), original.keySet()).equals(additions.keySet()), "Wrong release additions");
        writeJson(output.resolve("build-result.json"), obj("sha256", sha(result), "artifact", result.getFileName().toString(), "changed_entries", new ArrayList<>(replacements.keySet()), "added_entries", new ArrayList<>(additions.keySet()),
                "removed_file_entries", List.of(), "reviewed_diagnostic_reference", lock.get("diagnostic_reference_sha256"), "class_deltas", deltas, "all_non_debug_class_attributes_match_reviewed_candidate", true,
                "allowed_debug_difference", "Source/line/local-variable tables and validated invisible SourceDebugExtension SMAP; canonical constant pool, every other attribute retained", "native_header_sha256", m(lock.get("native_header")).get("candidate_sha256")));
        System.out.println(result + " " + sha(result));
    }
}
