package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.ProducerSupport.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rebuilds the Swift Export protocol SPI default witness exclusion repair from pinned Kotlin source and validates runtime and kotlinx.serialization behavior. */
final class ProducerProtocolSpiDefaults {
    private final ProducerSupport c;
    ProducerProtocolSpiDefaults(ProducerSupport context) { c = context; }

    void build(Path output, Path cache, Path component, Path nativeHome, Map<String, Object> seeds) throws Exception {
        check(!Files.exists(output), "New output required: " + output);
        Files.createDirectories(output.resolve("tmp"));
        Path sourceLock = c.p("producer/protocol-spi-defaults/source-lock.json");
        Map<String, Object> repair = m(Json.read(sourceLock)), lock = c.lock();
        Path compiler = nativeHome.resolve("konan/lib/kotlin-native-compiler-embeddable.jar");
        check(sha(component).equals(repair.get("base_component_sha256")), "Published producer input differs");
        check(sha(compiler).equals(repair.get("native_compiler_sha256")), "Native compiler input differs");
        Path patchFile = c.p(s(repair.get("patch")));
        check(sha(patchFile).equals(repair.get("patch_sha256")), "Producer patch differs");
        Path variablePatchFile = c.p(s(repair.get("variable_patch")));
        check(sha(variablePatchFile).equals(repair.get("variable_patch_sha256")), "Producer variable patch differs");
        Path functionPatchFile = c.p(s(repair.get("function_patch")));
        check(sha(functionPatchFile).equals(repair.get("function_patch_sha256")), "Producer function patch differs");
        Path serializationKlib = c.fetch(m(repair.get("serialization_klib")), cache, seeds);

        ProducerBuild builder = new ProducerBuild(c);
        Map<String, Path> tools = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(lock.get("tools"))) {
            String name = s(row.get("name"));
            if ("worker".equals(row.get("scope")) && "swift-export-embeddable".equals(row.get("artifact"))) tools.put(name, component);
            else if ("worker".equals(row.get("scope")) || "bootstrap".equals(row.get("scope")) || name.equals("swift-export-embeddable-2.4.20-km.1.jar"))
                tools.put(name, c.fetch(row, cache, seeds));
        }
        List<Path> worker = builder.worker(lock, tools, component), bootstrap = builder.bootstrap(lock, tools, worker, true);
        List<Path> fixed = new ArrayList<>(List.of(
                sourceLock,
                patchFile,
                variablePatchFile,
                functionPatchFile,
                compiler,
                component,
                serializationKlib,
                c.p("inputs.lock.json"),
                c.p("verification/ClassAudit.kt"),
                c.p("verification/ClassAuditTests.kt")));
        fixed.addAll(files(c.p("verification/protocol-spi-defaults-fixture")));
        fixed.addAll(files(c.p("buildSrc/src/main/java")));
        fixed.addAll(tools.values());
        Map<String, List<Path>> inputs = new LinkedHashMap<>();
        for (String variant : List.of("baseline", "candidate")) {
            List<Path> sources = new ArrayList<>();
            for (Map<String, Object> row : rows(repair.get("sources"))) {
                Path original = c.fetch(row, cache, seeds);
                fixed.add(original);
                Path target = output.resolve(variant + "/sources/" + s(row.get("name")));
                write(target, Files.readAllBytes(original));
                if (variant.equals("candidate") && row.get("name").equals("SirProtocolFromKtSymbol.kt")) {
                    patch(target, patchFile, output.resolve("patch.log"));
                    check(sha(target).equals(repair.get("candidate_upstream_syntax_sha256")), "Patched source differs");
                }
                if (variant.equals("candidate") && row.get("name").equals("SirVariableFromKtSymbol.kt")) {
                    patch(target, variablePatchFile, output.resolve("variable-patch.log"));
                    check(sha(target).equals(repair.get("variable_candidate_upstream_syntax_sha256")), "Patched variable source differs");
                }
                if (variant.equals("candidate") && row.get("name").equals("SirFunctionFromKtSymbol.kt")) {
                    patch(target, functionPatchFile, output.resolve("function-patch.log"));
                    check(sha(target).equals(repair.get("function_candidate_upstream_syntax_sha256")), "Patched function source differs");
                }
                write(target, normalizeSource(Files.readString(target)));
                if (variant.equals("candidate") && row.get("name").equals("SirProtocolFromKtSymbol.kt"))
                    check(sha(target).equals(repair.get("candidate_normalized_sha256")), "Compiler-normalized source differs");
                if (variant.equals("candidate") && row.get("name").equals("SirVariableFromKtSymbol.kt"))
                    check(sha(target).equals(repair.get("variable_candidate_normalized_sha256")), "Compiler-normalized variable source differs");
                if (variant.equals("candidate") && row.get("name").equals("SirFunctionFromKtSymbol.kt"))
                    check(sha(target).equals(repair.get("function_candidate_normalized_sha256")), "Compiler-normalized function source differs");
                sources.add(target);
                fixed.add(target);
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
        check(changed.equals(strings(repair.get("changed_classes"))) && added.equals(strings(repair.get("added_classes"))),
                "Producer class delta differs: changed=" + changed + " added=" + added);
        Map<String, byte[]> originalEntries = entries(component), patchedEntries = new TreeMap<>(originalEntries);
        List<String> selected = new ArrayList<>(changed);
        selected.addAll(added);
        Map<String, Object> expectedAttributes = m(repair.get("candidate_attributes"));
        Map<String, String> observedAttributes = new TreeMap<>();
        for (String name : selected) {
            String canonicalHash = sha(output.resolve("candidate/canonical/" + name));
            observedAttributes.put(name, canonicalHash);
            check(canonicalHash.equals(expectedAttributes.get(name)), "Producer attributes differ: " + name);
            patchedEntries.put(name, Files.readAllBytes(output.resolve("candidate/classes/" + name)));
        }
        Path producer = output.resolve("swift-export-protocol-spi-defaults.jar");
        archive(producer, patchedEntries);
        check(new TreeSet<>(selected).containsAll(changed(originalEntries, entries(producer))), "Unselected producer bytes changed");
        builder.closure(output, producer, worker, selected);

        Map<String, Object> behaviorReceipt = verifyBehavior(
                output.resolve("behavior"),
                component,
                producer,
                nativeHome,
                compiler,
                serializationKlib,
                classpath(bootstrap),
                classpath(worker));

        verifyPins(inputPins, "A source or tool input changed during protocol-spi-defaults verification");
        writeJson(output.resolve("result.json"), obj(
                "producer", producer.toString(),
                "producer_sha256", sha(producer),
                "changed_classes", changed,
                "added_classes", added,
                "candidate_attributes", observedAttributes,
                "baseline_non_debug_identical", true,
                "unselected_entries_byte_identical", true,
                "behavior_verification", behaviorReceipt,
                "input_pins", inputPins));
        System.out.println("PASS source-built protocol-spi-defaults producer candidate and behavioral/serialization verification: " + producer + " (" + sha(producer) + ")");
    }

    private Map<String, Object> verifyBehavior(
            Path behavior,
            Path baselineProducer,
            Path candidateProducer,
            Path nativeHome,
            Path nativeCompiler,
            Path serializationKlib,
            String compilerClasspath,
            String workerJarsClasspath) throws Exception {
        Files.createDirectories(behavior.resolve("tmp"));
        Path fixtureDir = c.p("verification/protocol-spi-defaults-fixture");
        Path fixtureKt = behavior.resolve("ProtocolSpiDefaults.kt");
        Path driverKt = behavior.resolve("ProtocolSpiExportDriver.kt");
        Path mainSwift = behavior.resolve("main.swift");
        write(fixtureKt, Files.readAllBytes(fixtureDir.resolve("ProtocolSpiDefaults.kt")));
        write(driverKt, Files.readAllBytes(fixtureDir.resolve("ProtocolSpiExportDriver.kt")));
        write(mainSwift, Files.readAllBytes(fixtureDir.resolve("main.swift")));

        String konanc = nativeHome.resolve("bin/konanc").toString();
        Path fixtureKlib = behavior.resolve("protocol_spi_defaults.klib");
        c.run(List.of(
                konanc,
                fixtureKt.toString(),
                "-library", serializationKlib.toString(),
                "-produce", "library",
                "-target", "macos_arm64",
                "-o", behavior.resolve("protocol_spi_defaults").toString(),
                "-Werror"),
                behavior.resolve("compile-klib.log"),
                behavior,
                Map.of());
        check(Files.isRegularFile(fixtureKlib), "Expected compiled fixture klib: " + fixtureKlib);

        Path driverClasses = behavior.resolve("driver-classes");
        String driverCompileCp = String.join(File.pathSeparator, List.of(candidateProducer.toString(), nativeCompiler.toString(), workerJarsClasspath));
        c.run(List.of(
                "java", "--enable-native-access=ALL-UNNAMED", "-Xmx2g", "-Djava.io.tmpdir=" + behavior.resolve("tmp"),
                "-cp", compilerClasspath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect",
                "-classpath", driverCompileCp,
                "-jvm-target", "21",
                "-Werror",
                driverKt.toString(),
                "-d", driverClasses.toString()),
                behavior.resolve("compile-driver.log"),
                behavior,
                Map.of());

        for (String variant : List.of("baseline", "candidate")) {
            Path exporterJar = variant.equals("baseline") ? baselineProducer : candidateProducer;
            Path exportRoot = behavior.resolve(variant + "-export");
            Path exportFiles = exportRoot.resolve("files");
            Files.createDirectories(exportFiles);
            String runtimeCp = String.join(File.pathSeparator, List.of(
                    driverClasses.toString(),
                    exporterJar.toString(),
                    nativeCompiler.toString(),
                    workerJarsClasspath));
            c.run(List.of(
                    "java", "--enable-native-access=ALL-UNNAMED", "-Xmx2g", "-Djava.io.tmpdir=" + behavior.resolve("tmp"),
                    "-cp", runtimeCp,
                    "ProtocolSpiExportDriverKt",
                    fixtureKlib.toString(),
                    serializationKlib.toString(),
                    nativeHome.toString(),
                    exportFiles.toString()),
                    behavior.resolve(variant + "-export.log"),
                    behavior,
                    Map.of());
        }

        // 1. Audit Baseline vs Candidate generated Swift for ProtocolSpiDefaults and OrgJetbrainsKotlinxKotlinxSerializationCore
        String baselineSpiSwift = Files.readString(behavior.resolve("baseline-export/files/ProtocolSpiDefaults/ProtocolSpiDefaults.swift"));
        String candidateSpiSwift = Files.readString(behavior.resolve("candidate-export/files/ProtocolSpiDefaults/ProtocolSpiDefaults.swift"));
        String baselineSerdeSwift = Files.readString(behavior.resolve("baseline-export/files/OrgJetbrainsKotlinxKotlinxSerializationCore/OrgJetbrainsKotlinxKotlinxSerializationCore.swift"));
        String candidateSerdeSwift = Files.readString(behavior.resolve("candidate-export/files/OrgJetbrainsKotlinxKotlinxSerializationCore/OrgJetbrainsKotlinxKotlinxSerializationCore.swift"));

        String baselineAuxSpiBlock = extractExtensionBlock(baselineSpiSwift, "extension ExportedKotlinPackages.spidefaults.SpiDefaultProtocol {");
        String candidateAuxSpiBlock = extractExtensionBlock(candidateSpiSwift, "extension ExportedKotlinPackages.spidefaults.SpiDefaultProtocol {");

        // Baseline duplicates default @_spi methods (fatalError trap + _direct witness)
        check(countOccurrences(baselineAuxSpiBlock, "public func defaultSpiFlag() -> Swift.Bool") == 2,
                "Baseline must reproduce duplicate defaultSpiFlag() in auxiliary protocol extension");
        check(countOccurrences(baselineAuxSpiBlock, "public func defaultSpiWithParams(") == 2,
                "Baseline must reproduce duplicate defaultSpiWithParams(label:index:) in auxiliary protocol extension");
        check(countOccurrences(baselineAuxSpiBlock, "public func defaultSpiVoid() -> Swift.Void") == 2,
                "Baseline must reproduce duplicate defaultSpiVoid() in auxiliary protocol extension");

        // Candidate emits each abstract and default @_spi method/property exactly once in auxiliary protocol extension
        check(countOccurrences(candidateAuxSpiBlock, "public func abstractSpiFlag() -> Swift.Bool") == 1,
                "Candidate must emit abstractSpiFlag() once in auxiliary protocol extension");
        check(countOccurrences(candidateAuxSpiBlock, "public func abstractSpiWithParams(") == 1,
                "Candidate must emit abstractSpiWithParams(label:index:) once in auxiliary protocol extension");
        check(countOccurrences(candidateAuxSpiBlock, "public func abstractSpiVoid() -> Swift.Void") == 1,
                "Candidate must emit abstractSpiVoid() once in auxiliary protocol extension");
        check(countOccurrences(candidateAuxSpiBlock, "public func defaultSpiFlag() -> Swift.Bool") == 1,
                "Candidate must emit defaultSpiFlag() once in auxiliary protocol extension");
        check(countOccurrences(candidateAuxSpiBlock, "public func defaultSpiWithParams(") == 1,
                "Candidate must emit defaultSpiWithParams(label:index:) once in auxiliary protocol extension");
        check(countOccurrences(candidateAuxSpiBlock, "public func defaultSpiVoid() -> Swift.Void") == 1,
                "Candidate must emit defaultSpiVoid() once in auxiliary protocol extension");
        check(countOccurrences(candidateAuxSpiBlock, "public var abstractSpiProp: Swift.Int32") == 1,
                "Candidate must emit abstractSpiProp once in auxiliary protocol extension");
        check(countOccurrences(candidateAuxSpiBlock, "public var defaultSpiProp: Swift.String") == 1,
                "Candidate must emit defaultSpiProp once in auxiliary protocol extension");

        // Verify abstract @_spi members retain fatalError traps while default @_spi members dispatch via _direct
        for (String abstractMember : List.of("abstractSpiFlag", "abstractSpiWithParams", "abstractSpiVoid", "abstractSpiProp")) {
            check(candidateAuxSpiBlock.contains("'" + abstractMember + "' is an @_spi requirement"),
                    "Candidate must retain @_spi fatalError trap for abstract member " + abstractMember);
        }
        for (String defaultMember : List.of("defaultSpiFlag", "defaultSpiWithParams", "defaultSpiVoid", "defaultSpiProp")) {
            check(!candidateAuxSpiBlock.contains("'" + defaultMember + "' is an @_spi requirement"),
                    "Candidate must omit @_spi fatalError trap for default member " + defaultMember);
            check(candidateAuxSpiBlock.contains(defaultMember) && candidateAuxSpiBlock.contains("_direct("),
                    "Candidate must emit witness-backed _direct implementation for default member " + defaultMember);
        }
        verifyUniqueExtensionSignatures(candidateAuxSpiBlock, "ProtocolSpiDefaults.SpiDefaultProtocol");

        // Verify uninhabited Swift.Never unavailable property setter body (empty in candidate vs unreachable fatalError() in baseline)
        check(baselineSpiSwift.contains("public var unsupportedModifier: Swift.Never {\n            get {\n                fatalError()\n            }\n            set {\n                fatalError()\n            }\n        }"),
                "Baseline must reproduce unreachable fatalError() in Swift.Never unavailable setter");
        check(candidateSpiSwift.contains("public var unsupportedModifier: Swift.Never {\n            get {\n                fatalError()\n            }\n            set {\n            }\n        }"),
                "Candidate must emit empty setter body for Swift.Never unavailable property");

        // Verify deprecated forward bridge call suppression in generated Kotlin bridge
        String baselineSpiKt = Files.readString(behavior.resolve("baseline-export/files/ProtocolSpiDefaults/ProtocolSpiDefaults.kt"));
        String candidateSpiKt = Files.readString(behavior.resolve("candidate-export/files/ProtocolSpiDefaults/ProtocolSpiDefaults.kt"));
        check(!baselineSpiKt.contains("@kotlin.Suppress(\"DEPRECATION\") run {"),
                "Baseline must reproduce unsuppressed deprecated forward bridge calls");
        check(candidateSpiKt.contains("@kotlin.Suppress(\"DEPRECATION\") run { __self.deprecatedSkip }")
                && candidateSpiKt.contains("@kotlin.Suppress(\"DEPRECATION\") run { __self.deprecatedSkip = __newValue }")
                && candidateSpiKt.contains("@kotlin.Suppress(\"DEPRECATION\") run { __self.deprecatedSetSkip(__skip) }"),
                "Candidate must suppress DEPRECATION at deprecated forward bridge call sites");

        // Verify kotlinx.serialization 1.11.0 protocol extensions
        String baselineCompositeDecoder = extractExtensionBlock(baselineSerdeSwift, "extension ExportedKotlinPackages.kotlinx.serialization.encoding.CompositeDecoder {");
        String candidateCompositeDecoder = extractExtensionBlock(candidateSerdeSwift, "extension ExportedKotlinPackages.kotlinx.serialization.encoding.CompositeDecoder {");
        String baselineCompositeEncoder = extractExtensionBlock(baselineSerdeSwift, "extension ExportedKotlinPackages.kotlinx.serialization.encoding.CompositeEncoder {");
        String candidateCompositeEncoder = extractExtensionBlock(candidateSerdeSwift, "extension ExportedKotlinPackages.kotlinx.serialization.encoding.CompositeEncoder {");
        String baselineEncoder = extractExtensionBlock(baselineSerdeSwift, "extension ExportedKotlinPackages.kotlinx.serialization.encoding.Encoder {");
        String candidateEncoder = extractExtensionBlock(candidateSerdeSwift, "extension ExportedKotlinPackages.kotlinx.serialization.encoding.Encoder {");

        check(countOccurrences(baselineCompositeDecoder, "public func decodeSequentially() -> Swift.Bool") == 2,
                "Baseline must reproduce duplicate CompositeDecoder.decodeSequentially()");
        check(countOccurrences(baselineCompositeEncoder, "public func shouldEncodeElementDefault(") == 2,
                "Baseline must reproduce duplicate CompositeEncoder.shouldEncodeElementDefault(descriptor:index:)");
        check(countOccurrences(baselineEncoder, "public func encodeNotNullMark() -> Swift.Void") == 2,
                "Baseline must reproduce duplicate Encoder.encodeNotNullMark()");

        check(countOccurrences(candidateCompositeDecoder, "public func decodeSequentially() -> Swift.Bool") == 1
                && candidateCompositeDecoder.contains("kotlinx_serialization_encoding_CompositeDecoder_decodeSequentially_direct(self.__externalRCRef())")
                && !candidateCompositeDecoder.contains("'decodeSequentially' is an @_spi requirement"),
                "Candidate must emit CompositeDecoder.decodeSequentially() once via _direct");
        check(countOccurrences(candidateCompositeEncoder, "public func shouldEncodeElementDefault(") == 1
                && candidateCompositeEncoder.contains("shouldEncodeElementDefault")
                && candidateCompositeEncoder.contains("_direct(")
                && !candidateCompositeEncoder.contains("'shouldEncodeElementDefault' is an @_spi requirement"),
                "Candidate must emit CompositeEncoder.shouldEncodeElementDefault(descriptor:index:) once via _direct");
        check(countOccurrences(candidateEncoder, "public func encodeNotNullMark() -> Swift.Void") == 1
                && candidateEncoder.contains("kotlinx_serialization_encoding_Encoder_encodeNotNullMark_direct(self.__externalRCRef())")
                && !candidateEncoder.contains("'encodeNotNullMark' is an @_spi requirement"),
                "Candidate must emit Encoder.encodeNotNullMark() once via _direct");

        verifyUniqueExtensionSignatures(candidateCompositeDecoder, "CompositeDecoder");
        verifyUniqueExtensionSignatures(candidateCompositeEncoder, "CompositeEncoder");
        verifyUniqueExtensionSignatures(candidateEncoder, "Encoder");

        // 2. Compile generated Swift modules under strict -warnings-as-errors for Baseline (expect redeclaration failure) and Candidate (expect success + runtime dispatch)
        Path baselineBuild = behavior.resolve("baseline-swift-build");
        Result baselineSerdeCompile = compileSwiftExportTree(baselineBuild, behavior.resolve("baseline-export"), nativeHome, null, null);
        check(baselineSerdeCompile.exit() != 0
                && baselineSerdeCompile.output().contains("invalid redeclaration of 'decodeSequentially()'")
                && baselineSerdeCompile.output().contains("invalid redeclaration of 'shouldEncodeElementDefault(descriptor:index:)'")
                && baselineSerdeCompile.output().contains("invalid redeclaration of 'encodeNotNullMark()'"),
                "Baseline Swift compilation did not fail with expected kotlinx.serialization redeclarations");

        // Compile candidate Kotlin bridges + klibs into static archive libProtocolSpiDefaults.a
        Path candidateBuild = behavior.resolve("candidate-swift-build");
        Files.createDirectories(candidateBuild);
        Path staticArchive = candidateBuild.resolve("libProtocolSpiDefaults.a");
        Path candidateExport = behavior.resolve("candidate-export");
        List<String> konancStaticCmd = new ArrayList<>(List.of(
                konanc,
                candidateExport.resolve("KotlinRuntimeSupport/KotlinRuntimeSupport/KotlinRuntimeSupport.kt").toString()));
        if (Files.isRegularFile(candidateExport.resolve("files/KotlinStdlib/KotlinStdlib.kt"))) {
            konancStaticCmd.add(candidateExport.resolve("files/KotlinStdlib/KotlinStdlib.kt").toString());
        }
        konancStaticCmd.addAll(List.of(
                candidateExport.resolve("files/OrgJetbrainsKotlinxKotlinxSerializationCore/OrgJetbrainsKotlinxKotlinxSerializationCore.kt").toString(),
                candidateExport.resolve("files/ProtocolSpiDefaults/ProtocolSpiDefaults.kt").toString(),
                "-library", fixtureKlib.toString(),
                "-library", serializationKlib.toString(),
                "-produce", "static",
                "-target", "macos_arm64",
                "-g",
                "-Xbinary=swiftExport=true",
                "-opt-in=kotlin.experimental.ExperimentalNativeApi",
                "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
                "-opt-in=kotlin.native.internal.InternalForKotlinNative",
                "-Xwarning-level=UNCHECKED_CAST:disabled",
                "-Werror",
                "-o", staticArchive.toString()));
        c.run(konancStaticCmd,
                candidateBuild.resolve("konanc-static.log"),
                candidateBuild,
                Map.of());
        check(Files.isRegularFile(staticArchive), "Missing candidate static archive: " + staticArchive);

        Result candidateCompile = compileSwiftExportTree(candidateBuild, candidateExport, nativeHome, mainSwift, staticArchive);
        check(candidateCompile.exit() == 0, "Candidate strict Swift compilation failed; see " + candidateBuild);

        Path verifyBinary = candidateBuild.resolve("verify-protocol-spi-defaults");
        Result runtimeRun = c.capture(List.of(verifyBinary.toString()), candidateBuild, Map.of());
        write(candidateBuild.resolve("runtime.log"), runtimeRun.output());
        check(runtimeRun.exit() == 0 && runtimeRun.output().contains("PASS protocol-spi-defaults runtime dispatch and serialization protocol resolution"),
                "Candidate runtime dispatch verification failed");

        return obj(
                "baseline_reproduced_spi_duplicates", true,
                "baseline_reproduced_serialization_duplicates", true,
                "candidate_single_declaration_per_signature", true,
                "candidate_strict_swift_werror_passed", true,
                "candidate_runtime_dispatch_passed", true,
                "kotlinx_serialization_1_11_0_verified", true,
                "static_archive_sha256", sha(staticArchive),
                "runtime_binary_sha256", sha(verifyBinary));
    }

    private Result compileSwiftExportTree(
            Path buildDir,
            Path exportRoot,
            Path nativeHome,
            Path mainSwift,
            Path staticArchive) throws Exception {
        Path modulesDir = buildDir.resolve("modules");
        Path objsDir = buildDir.resolve("objs");
        Path bridgesDir = buildDir.resolve("bridges");
        Path moduleCacheDir = buildDir.resolve("module-cache");
        Files.createDirectories(modulesDir);
        Files.createDirectories(objsDir);
        Files.createDirectories(bridgesDir);
        Files.createDirectories(moduleCacheDir);

        Path runtimeDir = bridgesDir.resolve("KotlinRuntime");
        Files.createDirectories(runtimeDir);
        write(runtimeDir.resolve("KotlinBase.h"), Files.readAllBytes(nativeHome.resolve("konan/swift_export/kotlin_runtime/KotlinBase.h")));
        patch(runtimeDir.resolve("KotlinBase.h"), c.p("patches/KotlinBase.h.patch"), buildDir.resolve("patch-kotlinbase.log"));
        write(runtimeDir.resolve("module.modulemap"), Files.readAllBytes(nativeHome.resolve("konan/swift_export/kotlin_runtime/module.modulemap")));

        Map<String, Path> headerSources = new LinkedHashMap<>();
        headerSources.put("KotlinRuntimeSupportBridge", exportRoot.resolve("KotlinRuntimeSupport/KotlinRuntimeSupport/KotlinRuntimeSupport.h"));
        if (Files.isRegularFile(exportRoot.resolve("files/KotlinStdlib/KotlinStdlib.h"))) {
            headerSources.put("KotlinBridges_KotlinStdlib", exportRoot.resolve("files/KotlinStdlib/KotlinStdlib.h"));
        }
        headerSources.put("SharedBridge_OrgJetbrainsKotlinxKotlinxSerializationCore", exportRoot.resolve("files/OrgJetbrainsKotlinxKotlinxSerializationCore/OrgJetbrainsKotlinxKotlinxSerializationCore.h"));
        headerSources.put("SharedBridge_ProtocolSpiDefaults", exportRoot.resolve("files/ProtocolSpiDefaults/ProtocolSpiDefaults.h"));

        List<String> ccFlags = new ArrayList<>();
        ccFlags.addAll(List.of("-Xcc", "-fmodule-map-file=" + runtimeDir.resolve("module.modulemap"), "-Xcc", "-I" + runtimeDir));
        for (Map.Entry<String, Path> entry : headerSources.entrySet()) {
            Path dir = bridgesDir.resolve(entry.getKey());
            Files.createDirectories(dir);
            String headerName = entry.getValue().getFileName().toString();
            write(dir.resolve(headerName), Files.readAllBytes(entry.getValue()));
            write(dir.resolve("module.modulemap"), ("module \"" + entry.getKey() + "\" {\n    umbrella \".\"\n    export *\n}\n").getBytes(StandardCharsets.UTF_8));
            ccFlags.addAll(List.of("-Xcc", "-fmodule-map-file=" + dir.resolve("module.modulemap"), "-Xcc", "-I" + dir));
        }

        record SwiftModuleSpec(String name, Path source) {}
        List<SwiftModuleSpec> specs = new ArrayList<>();
        specs.add(new SwiftModuleSpec("ExportedKotlinPackages", exportRoot.resolve("ExportedKotlinPackages/ExportedKotlinPackages.swift")));
        specs.add(new SwiftModuleSpec("KotlinRuntimeSupport", exportRoot.resolve("KotlinRuntimeSupport/KotlinRuntimeSupport/KotlinRuntimeSupport.swift")));
        if (Files.isRegularFile(exportRoot.resolve("files/KotlinStdlib/KotlinStdlib.swift"))) {
            specs.add(new SwiftModuleSpec("KotlinStdlib", exportRoot.resolve("files/KotlinStdlib/KotlinStdlib.swift")));
        }
        specs.add(new SwiftModuleSpec("OrgJetbrainsKotlinxKotlinxSerializationCore", exportRoot.resolve("files/OrgJetbrainsKotlinxKotlinxSerializationCore/OrgJetbrainsKotlinxKotlinxSerializationCore.swift")));
        specs.add(new SwiftModuleSpec("ProtocolSpiDefaults", exportRoot.resolve("files/ProtocolSpiDefaults/ProtocolSpiDefaults.swift")));

        List<Path> objectFiles = new ArrayList<>();
        for (SwiftModuleSpec spec : specs) {
            Path objFile = objsDir.resolve(spec.name() + ".o");
            List<String> cmd = new ArrayList<>(List.of(
                    "/usr/bin/xcrun", "swiftc",
                    "-parse-as-library",
                    "-module-name", spec.name(),
                    "-package-name", "ProtocolSpiDefaults",
                    "-swift-version", "5",
                    "-warnings-as-errors",
                    "-target", "arm64-apple-macos15.0",
                    "-module-cache-path", moduleCacheDir.toString(),
                    "-I", modulesDir.toString()));
            cmd.addAll(ccFlags);
            cmd.addAll(List.of(
                    "-emit-module",
                    "-emit-module-path", modulesDir.resolve(spec.name() + ".swiftmodule").toString(),
                    "-c", spec.source().toString(),
                    "-o", objFile.toString()));
            Result res = c.capture(cmd, buildDir, Map.of());
            write(buildDir.resolve("swiftc-" + spec.name() + ".log"), res.output());
            if (res.exit() != 0) {
                return res;
            }
            objectFiles.add(objFile);
        }

        if (mainSwift != null && staticArchive != null) {
            Path binary = buildDir.resolve("verify-protocol-spi-defaults");
            List<String> linkCmd = new ArrayList<>(List.of(
                    "/usr/bin/xcrun", "swiftc",
                    "-package-name", "ProtocolSpiDefaults",
                    "-swift-version", "6",
                    "-warnings-as-errors",
                    "-target", "arm64-apple-macos15.0",
                    "-module-cache-path", moduleCacheDir.toString(),
                    "-I", modulesDir.toString()));
            linkCmd.addAll(ccFlags);
            linkCmd.add(mainSwift.toString());
            for (Path obj : objectFiles) linkCmd.add(obj.toString());
            linkCmd.addAll(List.of(
                    staticArchive.toString(),
                    "-framework", "Foundation",
                    "-lc++",
                    "-o", binary.toString()));
            Result res = c.capture(linkCmd, buildDir, Map.of());
            write(buildDir.resolve("swiftc-main.log"), res.output());
            return res;
        }
        return new Result(0, "");
    }

    private static String extractExtensionBlock(String swift, String header) {
        int start = swift.indexOf(header);
        check(start >= 0, "Missing Swift extension block: " + header);
        int depth = 0;
        for (int i = start; i < swift.length(); i++) {
            char ch = swift.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return swift.substring(start, i + 1);
            }
        }
        throw new IllegalArgumentException("Unterminated Swift extension block: " + header);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static void verifyUniqueExtensionSignatures(String block, String label) {
        Pattern sigPattern = Pattern.compile("(?m)^\\s*public\\s+(?:func|var)\\s+([^{]+)\\{");
        Matcher matcher = sigPattern.matcher(block);
        Set<String> seen = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String normalized = matcher.group(1).replaceAll("\\s+", " ").strip();
            check(seen.add(normalized), "Duplicate Swift member signature in " + label + ": " + normalized);
        }
        check(!seen.isEmpty(), "Expected at least one public member signature in " + label);
    }
}
