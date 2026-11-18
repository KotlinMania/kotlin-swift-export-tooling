package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.ProducerSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Source-built compiler forwarding for generated Swift Export bridges. */
final class ProducerNativeForwarding {
    private final ProducerSupport c;
    ProducerNativeForwarding(ProducerSupport context) { c = context; }

    void build(Path output, Path cache, Path component, Path nativeHome, Map<String, Object> seeds) throws Exception {
        check(!Files.exists(output), "New output required");
        Files.createDirectories(output.resolve("tmp"));
        Path sourceLock = c.p("producer/native-forwarding/source-lock.json");
        Map<String, Object> repair = m(Json.read(sourceLock)), lock = c.lock();
        Path compiler = nativeHome.resolve("konan/lib/kotlin-native-compiler-embeddable.jar");
        check(sha(component).equals(repair.get("base_component_sha256")), "Published producer input differs");
        check(sha(compiler).equals(repair.get("native_compiler_sha256")), "Native compiler input differs");
        Path patchFile = c.p(s(repair.get("patch"))), pluginSource = c.p(s(repair.get("plugin_source")));
        check(sha(patchFile).equals(repair.get("patch_sha256")), "Producer patch differs");
        check(sha(pluginSource).equals(repair.get("plugin_source_sha256")), "Forwarding extension differs");
        ProducerBuild builder = new ProducerBuild(c);
        Map<String, Path> tools = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(lock.get("tools"))) {
            String name = s(row.get("name"));
            if ("worker".equals(row.get("scope")) && "swift-export-embeddable".equals(row.get("artifact"))) tools.put(name, component);
            else if ("worker".equals(row.get("scope")) || "bootstrap".equals(row.get("scope")) || name.equals("swift-export-embeddable-2.4.20-km.1.jar"))
                tools.put(name, c.fetch(row, cache, seeds));
        }
        List<Path> worker = builder.worker(lock, tools, component), bootstrap = builder.bootstrap(lock, tools, worker, true);
        List<Path> fixed = new ArrayList<>(List.of(sourceLock, patchFile, pluginSource, compiler, component, c.p("inputs.lock.json")));
        fixed.addAll(tools.values());
        Map<String, List<Path>> inputs = new LinkedHashMap<>();
        for (String variant : List.of("baseline", "candidate")) {
            List<Path> sources = new ArrayList<>();
            for (Map<String, Object> row : rows(repair.get("sources"))) {
                Path original = c.fetch(row, cache, seeds); fixed.add(original);
                Path target = output.resolve(variant + "/sources/" + s(row.get("name")));
                write(target, Files.readAllBytes(original));
                if (variant.equals("candidate") && row.get("name").equals("SirFunctionFromKtSymbol.kt")) {
                    patch(target, patchFile, output.resolve("patch.log"));
                    check(sha(target).equals(repair.get("candidate_upstream_syntax_sha256")), "Patched source differs");
                }
                write(target, normalizeSource(Files.readString(target)));
                if (variant.equals("candidate") && row.get("name").equals("SirFunctionFromKtSymbol.kt"))
                    check(sha(target).equals(repair.get("candidate_normalized_sha256")), "Compiler-normalized source differs");
                sources.add(target); fixed.add(target);
            }
            inputs.put(variant, sources);
        }
        Map<String, String> inputPins = pins(fixed);
        for (String variant : List.of("baseline", "candidate")) {
            List<String> command = builder.compiler(bootstrap, classpath(worker), component, output.resolve("tmp"),
                    "org.jetbrains.kotlin:sir-light-classes", inputs.get(variant), output.resolve(variant + "/classes"), true);
            writeJson(output.resolve(variant + "/invocation.json"), obj("args", command));
            c.run(command, output.resolve(variant + "/compile.log"));
        }
        List<String> baseline = classInventory(output.resolve("baseline/classes")), candidate = classInventory(output.resolve("candidate/classes"));
        check(difference(new TreeSet<>(baseline), new TreeSet<>(candidate)).isEmpty(), "A producer class was removed");
        String auditClasspath = builder.auditor(output, classpath(worker));
        for (String name : baseline) write(safe(output.resolve("original/classes"), name), entry(component, name));
        for (String variant : List.of("original", "baseline", "candidate"))
            builder.canonical(auditClasspath, output.resolve(variant + "/classes"), output.resolve(variant + "/canonical"),
                    variant.equals("candidate") ? candidate : baseline, output.resolve(variant + "/audit.log"));
        List<String> changed = new ArrayList<>();
        for (String name : baseline) {
            check(Files.mismatch(output.resolve("original/canonical/" + name), output.resolve("baseline/canonical/" + name)) == -1,
                    "Rebuilt upstream baseline differs: " + name);
            if (Files.mismatch(output.resolve("baseline/canonical/" + name), output.resolve("candidate/canonical/" + name)) != -1) changed.add(name);
        }
        List<String> added = new ArrayList<>(difference(new TreeSet<>(candidate), new TreeSet<>(baseline)));
        check(changed.equals(strings(repair.get("changed_classes"))) && added.equals(strings(repair.get("added_classes"))), "Producer class delta differs");
        Map<String, byte[]> originalEntries = entries(component), patchedEntries = new TreeMap<>(originalEntries);
        List<String> selected = new ArrayList<>(changed); selected.addAll(added);
        for (String name : selected) {
            check(sha(output.resolve("candidate/canonical/" + name)).equals(m(repair.get("candidate_attributes")).get(name)), "Producer attributes differ: " + name);
            patchedEntries.put(name, Files.readAllBytes(output.resolve("candidate/classes/" + name)));
        }
        Path producer = output.resolve("swift-export-native-forwarding.jar"); archive(producer, patchedEntries);
        check(new TreeSet<>(changed).containsAll(changed(originalEntries, entries(producer))), "Unselected producer bytes changed");
        builder.closure(output, producer, worker, selected);

        Path pluginClasses = output.resolve("plugin/classes");
        List<String> pluginCommand = new ArrayList<>(List.of("java", "--enable-native-access=ALL-UNNAMED", "-Xmx4g", "-Djava.io.tmpdir=" + output.resolve("tmp"),
                "-cp", classpath(bootstrap), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath",
                classpath(List.of(compiler, tools.get("kotlin-stdlib-2.4.20-dev-7885.jar"), tools.get("annotations-23.0.0.jar"))),
                "-Werror", "-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi", "-jvm-target", "1.8", pluginSource.toString(), "-d", pluginClasses.toString()));
        writeJson(output.resolve("plugin/invocation.json"), obj("args", pluginCommand)); c.run(pluginCommand, output.resolve("plugin/compile.log"));
        Map<String, byte[]> pluginEntries = new TreeMap<>();
        for (Path file : files(pluginClasses)) pluginEntries.put(pluginClasses.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        pluginEntries.put("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar",
                "io.github.kotlinmania.swiftexport.compiler.NativeForwardingPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path plugin = output.resolve("native-forwarding-plugin.jar"); archive(plugin, pluginEntries);
        verifyBehavior(output.resolve("behavior"), nativeHome, plugin);
        verifyPins(inputPins, "A source or tool input changed during Native forwarding verification");
        writeJson(output.resolve("result.json"), obj("producer", producer.toString(), "producer_sha256", sha(producer), "plugin", plugin.toString(), "plugin_sha256", sha(plugin),
                "changed_classes", changed, "added_classes", added, "baseline_non_debug_identical", true, "unselected_entries_byte_identical", true,
                "strict_native_behavior_passed", true, "deprecation_suppressed", false, "private_source_metadata_retained", true,
                "consumer_swift_validation_required", true, "input_pins", inputPins));
        System.out.println("PASS source-built producer and Native forwarding proof; run the full consumer gate with both candidate artifacts.");
    }

    private void verifyBehavior(Path output, Path nativeHome, Path plugin) throws Exception {
        Files.createDirectories(output);
        for (Path file : files(c.p("verification/native-forwarding-fixture"))) write(output.resolve(file.getFileName()), Files.readAllBytes(file));
        String compiler = nativeHome.resolve("bin/konanc").toString();
        c.run(List.of(compiler, "Original.kt", "-produce", "library", "-o", "original", "-Werror"), output.resolve("original.log"), output, Map.of());
        c.run(List.of(compiler, "LibraryBridge.kt", "-library", "original.klib", "-Xplugin=" + plugin, "-produce", "library", "-o", "bridge", "-Werror"), output.resolve("bridge.log"), output, Map.of());
        c.run(List.of(compiler, "Consumer.kt", "-library", "original.klib", "-library", "bridge.klib", "-o", "consumer", "-Werror"), output.resolve("link.log"), output, Map.of());
        c.run(List.of(output.resolve("consumer.kexe").toString()), output.resolve("run.log"), output, Map.of());
        Map<String, String> negative = new LinkedHashMap<>();
        negative.put("DirectCall", "is deprecated"); negative.put("BadSignature", "requires exactly one matching target");
        negative.put("ResidualReference", "Unresolved Swift Export target binding reference"); negative.put("ResidualMarker", "Removed Swift Export marker annotation remains");
        for (Map.Entry<String, String> test : negative.entrySet()) {
            Result result = c.capture(List.of(compiler, test.getKey() + ".kt", "-library", "original.klib", "-Xplugin=" + plugin, "-produce", "library", "-o", test.getKey(), "-Werror"), output, Map.of());
            write(output.resolve(test.getKey() + ".log"), result.output());
            check(result.exit() != 0 && result.output().contains(test.getValue()), "Negative compiler control did not reject " + test.getKey());
        }
        Result missing = c.capture(List.of(compiler, "Bridge.kt", "-library", "original.klib", "-o", "missing-plugin", "-Werror"), output, Map.of());
        write(output.resolve("missing-plugin.log"), missing.output());
        check(missing.exit() != 0 && missing.output().contains("external function"), "Missing plugin did not fail at the link boundary");
        for (String kind : List.of("dump-ir", "dump-metadata", "dump-abi"))
            c.run(List.of(nativeHome.resolve("bin/klib").toString(), kind, "bridge.klib"), output.resolve(kind + ".log"), output, Map.of());
        String ir = Files.readString(output.resolve("dump-ir.log")), abi = Files.readString(output.resolve("dump-abi.log"));
        check(ir.contains("CALL 'original/originalCallback") && ir.contains("CALL 'original/mutate|mutate(original.Box;kotlin.Int)"), "Original target calls missing from IR");
        for (String helper : List.of("callbackTarget", "mutateTarget", "__KotlinSwiftExportTarget"))
            check(!ir.contains(helper) && !abi.contains(helper), "Private binding leaked into executable IR or public ABI");
    }
}
