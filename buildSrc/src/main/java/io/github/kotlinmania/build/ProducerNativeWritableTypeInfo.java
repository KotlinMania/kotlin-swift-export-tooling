package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.ProducerSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Rebuilds complementary ObjC metadata composition from the pinned Native source. */
final class ProducerNativeWritableTypeInfo {
    private final ProducerSupport c;
    ProducerNativeWritableTypeInfo(ProducerSupport context) { c = context; }

    void build(Path output, Path nativeHome, Path source) throws Exception {
        check(!Files.exists(output), "New output required");
        Files.createDirectories(output.resolve("tmp"));
        Path lockFile = c.p("producer/native-writable-type-info/source-lock.json");
        Map<String, Object> lock = m(Json.read(lockFile));
        Path original = nativeHome.resolve("konan/lib/kotlin-native-compiler-embeddable.jar");
        Path patchFile = c.p(s(lock.get("patch")));
        check(sha(original).equals(lock.get("native_compiler_sha256")), "Native compiler differs");
        check(sha(source).equals(m(lock.get("source")).get("sha256")), "Upstream source differs");
        check(sha(patchFile).equals(lock.get("patch_sha256")), "Source patch differs");
        List<Path> inputFiles = new ArrayList<>(List.of(lockFile, original, source, patchFile, c.p("verification/ClassAudit.kt"), c.p("verification/ClassAuditTests.kt")));
        inputFiles.addAll(files(c.p("verification/native-writable-type-info-fixture")));
        inputFiles.addAll(files(c.p("buildSrc/src/main/java")));
        Map<String, String> inputPins = pins(inputFiles);
        ProducerBuild builder = new ProducerBuild(c);
        for (String variant : List.of("baseline", "candidate")) {
            Path kotlin = output.resolve(variant + "/WritableTypeInfo.kt");
            write(kotlin, Files.readAllBytes(source));
            if (variant.equals("candidate")) {
                patch(kotlin, patchFile, output.resolve("patch.log"));
                check(sha(kotlin).equals(lock.get("candidate_source_sha256")), "Candidate source differs");
            }
            List<String> command = new ArrayList<>(List.of("java", "--enable-native-access=ALL-UNNAMED", "-Xmx3g", "-Djava.io.tmpdir=" + output.resolve("tmp"),
                    "-cp", original.toString(), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", original.toString(),
                    "-Xfriend-paths=" + original, "-module-name", "org.jetbrains.kotlin:backend.native", "-jvm-target", "1.8", "-jvm-default=no-compatibility", "-Werror",
                    "-opt-in=org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI", "-opt-in=org.jetbrains.kotlin.backend.konan.InternalKotlinNativeApi", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
                    kotlin.toString(), "-d", output.resolve(variant + "/classes").toString()));
            writeJson(output.resolve(variant + "/command.json"), command);
            c.run(command, output.resolve(variant + "/compile.log"));
        }
        List<String> baseline = classInventory(output.resolve("baseline/classes"));
        check(baseline.equals(classInventory(output.resolve("candidate/classes"))), "Native class inventory changed");
        for (String name : baseline) write(safe(output.resolve("original/classes"), name), entry(original, name));
        String auditor = builder.auditor(output, original.toString());
        for (String variant : List.of("original", "baseline", "candidate"))
            builder.canonical(auditor, output.resolve(variant + "/classes"), output.resolve(variant + "/canonical"), baseline, output.resolve(variant + "/audit.log"));
        List<String> changed = new ArrayList<>();
        for (String name : baseline) {
            check(Files.mismatch(output.resolve("original/canonical/" + name), output.resolve("baseline/canonical/" + name)) == -1, "Baseline attributes differ: " + name);
            if (Files.mismatch(output.resolve("candidate/canonical/" + name), output.resolve("baseline/canonical/" + name)) != -1) changed.add(name);
        }
        check(changed.equals(strings(lock.get("changed_classes"))), "Unexpected Native class delta");
        Map<String, byte[]> originalEntries = entries(original), patched = new TreeMap<>(originalEntries);
        for (String name : changed) {
            check(sha(output.resolve("candidate/canonical/" + name)).equals(m(lock.get("candidate_attributes")).get(name)), "Candidate attributes differ: " + name);
            patched.put(name, Files.readAllBytes(output.resolve("candidate/classes/" + name)));
        }
        Path candidate = output.resolve("kotlin-native-compiler-embeddable.jar");
        archive(candidate, patched);
        check(changed(originalEntries, entries(candidate)).equals(changed), "Unselected Native entries changed");
        Map<String, Object> validation = verifyBehavior(output.resolve("verification"), original, candidate, nativeHome);
        verifyPins(inputPins, "Native repair inputs changed during verification");
        writeJson(output.resolve("result.json"), obj("compiler", candidate.toString(), "compiler_sha256", sha(candidate), "changed_classes", changed,
                "baseline_non_debug_identical", true, "unselected_entries_byte_identical", true, "debug_foundation_behavior_passed", true,
                "extended_validation", validation, "full_consumer_validation_required", true, "input_pins", inputPins));
        System.out.println("PASS source/class audit and cached Debug Foundation behavior; uncached Release verifier and full consumer gates remain separate.");
    }

    private List<String> nativeCommand(Path output, Path compiler, Path nativeHome) {
        return new ArrayList<>(List.of("java", "--enable-native-access=ALL-UNNAMED", "-Xmx3g", "-Djava.io.tmpdir=" + output.resolve("tmp"), "-Dkonan.home=" + nativeHome,
                "-cp", compiler.toString(), "org.jetbrains.kotlin.cli.utilities.MainKt", "konanc"));
    }

    private Map<String, Object> verifyBehavior(Path output, Path original, Path candidate, Path nativeHome) throws Exception {
        Files.createDirectories(output.resolve("tmp"));
        for (Path file : files(c.p("verification/native-writable-type-info-fixture"))) write(output.resolve(file.getFileName()), Files.readAllBytes(file));
        List<String> flags = List.of("-produce", "static", "-target", "macos_arm64", "-g", "-Werror", "-opt-in=kotlin.native.internal.InternalForKotlinNative",
                "-opt-in=kotlin.experimental.ExperimentalNativeApi", "-opt-in=kotlinx.cinterop.ExperimentalForeignApi", "-Xbinary=swiftExport=true", "-Xverify-bitcode",
                "-Xcache-directory=" + nativeHome.resolve("klib/cache/macos_arm64-gSTATIC-system"));
        for (String variant : List.of("baseline", "candidate")) {
            Path work = output.resolve(variant); Files.createDirectories(work.resolve("llvm"));
            List<String> command = nativeCommand(output, variant.equals("baseline") ? original : candidate, nativeHome);
            command.add(output.resolve("CollectionBinding.kt").toString()); command.addAll(flags);
            command.addAll(List.of("-Xsave-llvm-ir-after=Codegen", "-Xsave-llvm-ir-directory=" + work.resolve("llvm"), "-o", work.resolve("fixture").toString()));
            writeJson(work.resolve("command.json"), command);
            Result result = c.capture(command, output, Map.of()); write(work.resolve("compile.log"), result.output());
            if (variant.equals("baseline")) check(result.exit() != 0 && result.output().contains("Global 'ktypew:kotlin.collections.MutableList' already exists"), "Baseline did not reproduce collision");
            else {
                check(result.exit() == 0, "Repaired Native reproducer failed");
                List<String> definitions = Files.readString(work.resolve("llvm/out.Codegen.ll")).lines().filter(line -> line.startsWith("@\"ktypew:kotlin.collections.MutableList\" = ")).toList();
                check(definitions.size() == 1 && definitions.getFirst().contains("ptr @Kotlin_Interop_CreateRetainedNSMutableArrayFromKList, ptr null, ptr null, ptr @"), "Complementary metadata pointers missing");
            }
        }
        List<String> runtime = nativeCommand(output, candidate, nativeHome);
        runtime.add(output.resolve("Collection.kt").toString()); runtime.addAll(flags); runtime.addAll(List.of("-o", output.resolve("runtime").toString()));
        writeJson(output.resolve("runtime-command.json"), runtime); c.run(runtime, output.resolve("runtime-compile.log"));
        List<String> swift = List.of("xcrun", "swiftc", "-swift-version", "6", "-warnings-as-errors", "-I", output.toString(), output.resolve("main.swift").toString(),
                output.resolve("libruntime.a").toString(), "-framework", "Foundation", "-lc++", "-o", output.resolve("verify").toString());
        writeJson(output.resolve("swift-command.json"), swift); c.run(swift, output.resolve("swift.log"));
        c.run(List.of(output.resolve("verify").toString()), output.resolve("run.log"));
        List<String> duplicate = nativeCommand(output, candidate, nativeHome);
        duplicate.addAll(List.of(output.resolve("file3.kt").toString(), output.resolve("class.kt").toString())); duplicate.addAll(flags);
        duplicate.addAll(List.of("-o", output.resolve("duplicate").toString()));
        Result rejected = c.capture(duplicate, output, Map.of()); write(output.resolve("duplicate.log"), rejected.output());
        check(rejected.exit() != 0 && rejected.output().contains("another ObjC class is already bound"), "Conflicting class attachment was not rejected");
        // Preserve the optional verifier failure as a separate unresolved gate.
        // Do not remove -Xverify-bitcode to get past an incomplete pre-Codegen module.
        List<String> release = nativeCommand(output, candidate, nativeHome);
        release.add(output.resolve("Collection.kt").toString());
        for (String flag : flags) if (!flag.equals("-g") && !flag.startsWith("-Xcache-directory=")) release.add(flag);
        release.addAll(List.of("-opt", "-o", output.resolve("uncached-release").toString()));
        writeJson(output.resolve("uncached-release-command.json"), release);
        Result releaseResult = c.capture(release, output, Map.of());
        write(output.resolve("uncached-release.log"), releaseResult.output());
        check(releaseResult.exit() != 0 && releaseResult.output().contains("Invalid LLVM module") && releaseResult.output().contains("runBefore"),
                "Uncached Release verifier outcome changed; inspect the full diagnostic before acceptance");
        write(output.resolve("swift-version.log"), c.capture(List.of("xcrun", "swiftc", "--version")));
        write(output.resolve("swift-path.log"), c.capture(List.of("xcrun", "--find", "swiftc")));
        write(output.resolve("java-version.log"), c.capture(List.of("java", "-version")));
        return obj("uncached_release_verifier_passed", false, "uncached_release_exit_code", releaseResult.exit(),
                "uncached_release_log", output.resolve("uncached-release.log").toString(),
                "uncached_release_log_sha256", sha(output.resolve("uncached-release.log")),
                "verification_flag_retained", true, "local_complementary_metadata_runtime_verified", false);

    }
}
