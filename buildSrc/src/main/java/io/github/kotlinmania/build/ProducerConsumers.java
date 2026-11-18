package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.ProducerSupport.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Audited real Native/Swift consumers. All generated projects, logs and caches stay in this producer's build/. */
public final class ProducerConsumers {
    private final ProducerSupport support;
    ProducerConsumers(ProducerSupport support) { this.support = support; }
    private static final String SOURCE = "buildSrc/src/main/java/io/github/kotlinmania/build/";
    private static final String STRICT = "SWIFT_VERSION = 5\nSWIFT_TREAT_WARNINGS_AS_ERRORS = YES\nGCC_TREAT_WARNINGS_AS_ERRORS = YES\n";
    static final String CONSUMER_HEAD = "3d8718c2412be6aa99175ab88663edec8dbfa6db";
    static final List<String> BYTE_TESTS = List.of("sizeConstructorInitializesZerosAndEmptyArray", "initializerRunsOncePerIndexAndKeepsSignedBytes", "nativeAliasObservesSwiftMutation",
        "retainedSwiftArrayKeepsNativeRootAndThenReleasesIt", "arrayDoesNotRetainCompletedInitializerCapture", "temporaryConstructedArraySurvivesNativeCall");
    static final List<String> COLLECTION_TESTS = List.of("readsKotlinHeadersWithoutLosingOrderOrUnicode", "roundTripsSwiftHeaderDictionary", "distinguishesEmptyValuesFromMissingKeys", "optionalDictionaryDistinguishesNilFromEmpty",
        "recursivelyRoundTripsNestedHeaderDictionaries", "nestedHeaderListsKeepConcreteValues", "mutationKeepsDictionaryValueSemantics", "temporaryOwnerValuesSurviveAutoreleasePool",
        "synchronousDictionaryCallbackKeepsTypedValues", "nullableDictionaryCallbackKeepsNilAndValuesDistinct", "invokesKotlinReturnedDictionaryClosure");
    static final List<String> REVERSE_TESTS = List.of("explicitExportNamesPreservePropertiesAndCanonicalMethods", "swiftProviderCallsThroughKotlinAndBackWithTypedIdentity", "swiftSuccessfulNullIsNotFailure",
        "independentSwiftFailureKeepsOriginalIdentity", "kotlinOwnerRetainsProviderBeyondSwiftLocalScope");
    static final List<String> NULLABLE_UNSIGNED_TESTS = List.of("nullableUBytePreservesEveryBoundary", "nullableUShortPreservesEveryBoundary", "nullableUIntPreservesEveryBoundary", "nullableULongPreservesEveryBoundary", "mixedUnsignedWidthsRemainIndependent");
    private static final String UNSIGNED_BASE_SHA = "d51da8510b78e94aa90b2def49ead640a39b386fb1c295821bc586b9b3e82f0b";
    private static final String UNSIGNED_BRIDGE = "org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSNumber";
    static final String ITERATOR_TEST = "iteratorRetainsArrayAndReleasesItsNativeAndObjCRoots";
    private record Profile(String command, String fixture, String module, String harness, String testModule, List<String> tests, Set<String> requiredModules) {}
    private static Profile profile(String command) {
        return switch (command) {
            case "nullable-unsigned-consumer" -> new Profile(command, "nullable-unsigned-fixture", "NullableUnsignedProof", "NullableUnsigned", "NullableUnsignedTests", NULLABLE_UNSIGNED_TESTS, Set.of("NullableUnsignedTests", "NullableUnsignedProof", "KotlinRuntimeSupport", "ExportedKotlinPackages"));
            case "array-consumer" -> new Profile(command, "array-fixture", "ArrayIntegration", "AutomaticArray", "AutomaticArrayTests", List.of(), Set.of("AutomaticArrayTests", "ArrayIntegration", "KotlinRuntimeSupport", "KotlinCoroutineSupport"));
            case "bytearray-consumer" -> new Profile(command, "bytearray-fixture", "ByteArrayProof", "ByteArrayConstructor", "ByteArrayTests", BYTE_TESTS, Set.of("ByteArrayTests", "ByteArrayProof", "KotlinRuntimeSupport", "KotlinStdlib"));
            case "collection-consumer" -> new Profile(command, "collection-fixture", "FoundationCollectionProof", "FoundationCollectionConstructor", "FoundationCollectionTests", COLLECTION_TESTS, Set.of("FoundationCollectionTests", "FoundationCollectionProof", "KotlinRuntimeSupport", "ExportedKotlinPackages"));
            case "reverse-import-consumer" -> new Profile(command, "reverse-import-fixture", "ReverseImportProof", "ReverseImport", "ReverseImportTests", REVERSE_TESTS, Set.of("SwiftProvider", "ReverseImportTests", "ReverseImportProof", "KotlinRuntimeSupport"));
            default -> throw new IllegalArgumentException("Unknown fixture consumer: " + command);
        };
    }
    public int run(String command, Map<String, String> options) throws Exception {
        validateOptions(command, options);
        return switch (command) {
            case "consumer" -> procMacro(options);
            case "iterator-consumer" -> iterator(options);
            case "array-consumer", "bytearray-consumer", "collection-consumer", "reverse-import-consumer", "nullable-unsigned-consumer" -> fixture(profile(command), options);
            default -> throw new IllegalArgumentException("Unknown consumer command: " + command);
        };
    }

    static void validateOptions(String command, Map<String, String> options) {
        Set<String> required = new LinkedHashSet<>(List.of("output", "sdk", "sdk-audit"));
        Set<String> allowed = new LinkedHashSet<>();
        if (command.equals("iterator-consumer")) required.addAll(List.of("package", "archive"));
        else if (command.equals("nullable-unsigned-consumer")) {
            required.addAll(List.of("component", "candidate-audit", "cache", "gradle", "gradle-cache-seed", "konan-cache-seed", "configuration"));
        } else {
            check(Set.of("consumer", "array-consumer", "bytearray-consumer", "collection-consumer", "reverse-import-consumer").contains(command), "Unknown consumer command: " + command);
            required.addAll(List.of("distribution", "cache"));
            if (command.equals("consumer") || command.equals("array-consumer")) {
                required.addAll(List.of("sdk-download-audit", "helper"));
                allowed.addAll(List.of("gradle", "gradle-cache-seed", "konan-cache-seed"));
                if (command.equals("consumer")) required.add("consumer");
            } else {
                required.addAll(List.of("gradle", "gradle-cache-seed", "konan-cache-seed", "configuration"));
                if (command.equals("reverse-import-consumer")) allowed.add("host-tests");
                else allowed.addAll(List.of("candidate-audit", "sdk-download-audit", "helper"));
            }
        }
        allowed.addAll(required);
        check(allowed.containsAll(options.keySet()), "Unrecognized consumer options: " + difference(options.keySet(), allowed));
        required.forEach(name -> required(options, name));
        if (command.equals("nullable-unsigned-consumer")) required.forEach(name -> check(!required(options, name).isBlank(), "--" + name + " must not be blank"));
        if (options.containsKey("configuration")) config(options, true);
        if (options.containsKey("host-tests")) check(Set.of("true", "false").contains(options.get("host-tests")), "host-tests must be true or false");
    }

    static String consumerInitGuard() {
        return """

            // The pinned checkout stays immutable. Its selected SPM policy actions have
            // already been replaced above with the audited Swift 5 dependency/Werror
            // policy; authored test verification is performed by ProducerConsumers in Java.
            gradle.taskGraph.whenReady { graph ->
              def unexpected = graph.allTasks.findAll { task ->
                task.name == 'swiftExportSmokeTest' ||
                  (task.name.toLowerCase().endsWith('generatespmpackage') && task.name != 'macosArm64DebugGenerateSPMPackage')
              }
              if (!unexpected.empty) throw new GradleException('Pinned consumer attempted an unported legacy route: '+unexpected*.path)
              def directInterpreter = graph.allTasks.findAll { task ->
                task instanceof org.gradle.api.tasks.Exec && task.commandLine.any { argument ->
                  new File(argument.toString()).name ==~ /python(?:[23](?:[.][0-9]+)?)?/
                }
              }
              if (!directInterpreter.empty) throw new GradleException('Pinned consumer attempted a Python process: '+directInterpreter*.path)
              new File(root,'logs/java-consumer-policy.json').text=JsonOutput.prettyPrint(JsonOutput.toJson([
                source_checkout_unchanged:true,
                generated_package_policy:'reviewed Swift 5 dependency/Werror action overlay',
                authored_test_verifier:'io.github.kotlinmania.build.SwiftTestVerifier',
                task_paths:graph.allTasks*.path,
                legacy_python_routes_executed:false
              ]))+'\\n'
            }
            """;
    }

    private Path newOutput(Map<String, String> options) throws IOException {
        Path root = path(options, "output"); Path allowed = support.p("build").toAbsolutePath().normalize();
        check(root.startsWith(allowed) && !root.equals(allowed), "Consumer output must be a new directory inside producer build/");
        for (Path cursor = root; cursor != null && cursor.startsWith(support.repo); cursor = cursor.getParent()) check(!Files.isSymbolicLink(cursor), "Consumer output cannot use symbolic links");
        check(!Files.exists(root, LinkOption.NOFOLLOW_LINKS), "New consumer output directory required"); Files.createDirectories(root); return root;
    }
    private static String quote(Path path) { return Json.stringify(path.toString()); }
    private static Map<String, String> stringMap(Object value) { Map<String, String> result = new TreeMap<>(); m(value).forEach((key, item) -> result.put(key, s(item))); return result; }
    private static String config(Map<String, String> options, boolean required) { String result = required ? required(options, "configuration") : "Debug"; check(Set.of("Debug", "Release").contains(result), "Configuration must be Debug or Release"); return result; }
    static void copyTree(Path source, Path target) throws IOException {
        check(Files.isDirectory(source) && !Files.isSymbolicLink(source), "Fixture directory required: " + source);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                check(!Files.isSymbolicLink(path), "Fixture symlink is unsupported: " + path); Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else { check(Files.isRegularFile(path), "Fixture special file is unsupported: " + path); Files.createDirectories(destination.getParent()); Files.copy(path, destination); }
            }
        }
    }
    private void seed(Map<String, String> options, Path root, boolean required) throws Exception {
        for (String[] entry : List.of(new String[] {"gradle-cache-seed", "gradle-home"}, new String[] {"konan-cache-seed", "konan-data"})) {
            if (required) required(options, entry[0]);
            if (options.containsKey(entry[0])) {
                Path source = path(options, entry[0]); check(Files.isDirectory(source), "Missing closed binary cache seed: " + source);
                support.run(List.of("cp", "-cRL", source.toString(), root.resolve(entry[1]).toString()), root.resolve("logs/seed-" + entry[1] + ".log"));
            }
        }
    }
    private List<Path> implementationInputs() { return List.of(support.p(SOURCE + "ProducerConsumers.java"), support.p(SOURCE + "ProducerSupport.java"), support.p(SOURCE + "ProducerDistribution.java"), support.p(SOURCE + "SwiftToolingPreparer.java"), support.p(SOURCE + "SwiftTestVerifier.java")); }
    private static Map<String, Object> readObject(Path path) throws IOException { return m(Json.read(path)); }
    static Map<String, String> expectedSdk(Map<String, Object> lock, Map<String, Object> audit) {
        Map<String, Object> header = m(lock.get("native_header")); String name = s(header.get("relative_path")); Map<String, String> original = stringMap(audit.get("source_files"));
        check(l(audit.get("changed_files")).equals(List.of(name)), "SDK audit must identify exactly the reviewed header change");
        check(Boolean.TRUE.equals(audit.get("unchanged_native_binaries")) && Boolean.FALSE.equals(audit.get("installed_source_modified")), "SDK audit changed native binaries or installed source");
        for (var identity : stringMap(lock.get("native_identity")).entrySet()) check(identity.getValue().equals(original.get(identity.getKey())), "Native SDK identity mismatch: " + identity.getKey());
        check(s(header.get("baseline_sha256")).equals(original.get(name)), "SDK header baseline mismatch");
        Map<String, String> expected = new TreeMap<>(original); expected.put(name, s(header.get("candidate_sha256"))); return expected;
    }
    static void checkSdk(Path sdk, Map<String, String> expected, Path root) throws IOException {
        Map<String, String> actual = SwiftToolingPreparer.inventory(sdk);
        if (!actual.equals(expected)) {
            writeJson(root.resolve("sdk-observed.json"), actual); Map<String, Object> changed = new TreeMap<>();
            for (String name : expected.keySet()) if (actual.containsKey(name) && !expected.get(name).equals(actual.get(name))) changed.put(name, obj("before", expected.get(name), "after", actual.get(name)));
            writeJson(root.resolve("sdk-inventory-delta.json"), obj("added", difference(actual.keySet(), expected.keySet()), "removed", difference(expected.keySet(), actual.keySet()), "changed", changed));
        }
        check(actual.equals(expected), "SDK inventory set or hashes changed");
    }
    private List<Map<String, Object>> workerTools(Map<String, Object> lock, Path component, Path cache, boolean exactCount) throws IOException {
        List<Map<String, Object>> tools = new ArrayList<>(); String componentDigest = sha(component);
        for (Map<String, Object> row : rows(lock.get("tools"))) if ("worker".equals(row.get("scope"))) {
            Path path = "swift-export-embeddable".equals(row.get("artifact")) ? component : cache.resolve(s(row.get("name")));
            String expected = path.equals(component) ? componentDigest : s(row.get("sha256")); check(sha(path).equals(expected), "Worker input checksum mismatch: " + path);
            tools.add(obj("path", path.toString(), "sha256", expected));
        }
        check(!exactCount || tools.size() == 16 && tools.stream().filter(row -> row.get("path").equals(component.toString())).count() == 1, "Expected exactly sixteen worker components and one selected exporter");
        return tools;
    }
    private final class Session {
        final Path root; final Path sdk; final Map<String, String> sdkPins; final List<Map<String, Object>> tools; final Map<String, String> pins = new TreeMap<>(); final Map<String, String> environment;
        Verification extraVerification;
        Session(Path root, Path sdk, Map<String, String> sdkPins, List<Map<String, Object>> tools, String configuration, boolean proc) throws IOException {
            this.root = root; this.sdk = sdk; this.sdkPins = sdkPins; this.tools = tools;
            for (String directory : List.of("logs", "tmp", "products", "process-home", "module-cache", "clang-cache", "idea-system", "idea-config", "swift-cache/clang", "swift-cache/module")) Files.createDirectories(root.resolve(directory));
            environment = new LinkedHashMap<>();
            environment.put(proc ? "PROCMACRO2_ENUM_DIAGNOSTIC_ROOT" : "ARRAY_DIAGNOSTIC_ROOT", root.toString());
            environment.put("GRADLE_USER_HOME", root.resolve("gradle-home").toString()); environment.put("KONAN_DATA_DIR", root.resolve("konan-data").toString()); environment.put("TMPDIR", root.resolve("tmp") + "/");
            String javaOptions = "-Djava.io.tmpdir=" + root.resolve("tmp");
            javaOptions += proc ? " -Didea.system.path=" + root.resolve("idea-system") + " -Didea.config.path=" + root.resolve("idea-config") : " -Duser.home=" + root.resolve("process-home");
            environment.put("JAVA_TOOL_OPTIONS", javaOptions);
            environment.put("CLANG_MODULE_CACHE_PATH", root.resolve(proc ? "swift-cache/clang" : "clang-cache").toString());
            environment.put("SWIFT_MODULECACHE_PATH", root.resolve(proc ? "swift-cache/module" : "module-cache").toString()); environment.put("SWIFTPM_MODULECACHE_OVERRIDE", environment.get("SWIFT_MODULECACHE_PATH"));
            environment.put("SDK_NAME", "macosx"); environment.put("CONFIGURATION", configuration); environment.put("ARCHS", "arm64"); environment.put("MACOSX_DEPLOYMENT_TARGET", "15.0"); environment.put("DEPLOYMENT_TARGET_SETTING_NAME", "MACOSX_DEPLOYMENT_TARGET");
            environment.put("BUILT_PRODUCTS_DIR", root.resolve("products").toString()); environment.put("TARGET_BUILD_DIR", root.resolve("products").toString()); environment.put("FRAMEWORKS_FOLDER_PATH", "Frameworks"); environment.put("XCODE_XCCONFIG_FILE", root.resolve("strict.xcconfig").toString());
        }
        void pin(Collection<Path> paths) throws IOException { pins.putAll(ProducerSupport.pins(new ArrayList<>(paths))); }
        void freeze() throws IOException { writeJson(root.resolve("input-pins.json"), pins); pins.put(root.resolve("input-pins.json").toString(), sha(root.resolve("input-pins.json"))); }
        void verify() throws Exception {
            List<String> changed = new ArrayList<>(); for (var pin : pins.entrySet()) if (!Files.isRegularFile(Path.of(pin.getKey())) || !sha(Path.of(pin.getKey())).equals(pin.getValue())) changed.add(pin.getKey());
            if (!changed.isEmpty()) writeJson(root.resolve("changed-source-inputs.json"), changed);
            check(changed.isEmpty(), "Source/recipe input changed");
            for (Map<String, Object> tool : tools) check(sha(Path.of(s(tool.get("path")))).equals(tool.get("sha256")), "Worker changed");
            checkSdk(sdk, sdkPins, root);
            if (extraVerification != null) extraVerification.verify();
        }
        int raw(String name, List<String> args, Path cwd, int seconds) throws Exception {
            writeJson(root.resolve(name + "-invocation.json"), obj("args", args, "cwd", cwd.toString(), "artifact_environment", environment));
            return execute(args, cwd, environment, root.resolve("logs/" + name + ".log"), root.resolve("active-process.json"), name, seconds);
        }
        String run(String name, List<String> args, Path cwd) throws Exception {
            verify(); int code = raw(name, args, cwd, 0); verify(); check(code == 0, "Actual failure: " + name + "; see " + root.resolve("logs/" + name + ".log")); return Files.readString(root.resolve("logs/" + name + ".log"));
        }
    }
    @FunctionalInterface private interface Verification { void verify() throws Exception; }
    static int execute(List<String> args, Path cwd, Map<String, String> environment, Path log, Path active, String phase, int seconds) throws Exception {
        Files.createDirectories(log.getParent()); ProcessBuilder builder = new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()); builder.environment().putAll(environment);
        Process process = builder.start(); int exit = -1;
        try {
            writeJson(active, obj("phase", phase, "pid", process.pid(), "active", true, "command", args));
            if (seconds > 0 && !process.waitFor(seconds, TimeUnit.SECONDS)) throw new IllegalArgumentException("Command timed out: " + phase);
            exit = process.waitFor(); return exit;
        } finally {
            String descendantFailure = null;
            if (process.isAlive()) {
                try { process.descendants().forEach(ProcessHandle::destroyForcibly); }
                catch (RuntimeException unavailable) { descendantFailure = unavailable.toString(); }
                finally { process.destroyForcibly(); process.waitFor(); }
            }
            Map<String, Object> terminal = obj("phase", phase, "pid", process.pid(), "active", false, "exit", exit);
            if (descendantFailure != null) terminal.put("descendant_enumeration_failure", descendantFailure);
            writeJson(active, terminal);
        }
    }
    private String gradle(Map<String, String> options, Session session) throws Exception {
        String command = options.containsKey("gradle") ? path(options, "gradle").toString() : findExecutable("gradle");
        check(command != null, "Gradle 9.8.2 required on PATH or via --gradle");
        String version = session.run("gradle-version", List.of(command, "--version"), support.repo);
        check(Pattern.compile("^Gradle 9\\.8\\.2$", Pattern.MULTILINE).matcher(version).find(), "Wrong Gradle version"); return command;
    }
    private static String findExecutable(String name) {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(Pattern.quote(java.io.File.pathSeparator))) { Path path = Path.of(directory).resolve(name); if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toAbsolutePath().toString(); } return null;
    }
    private static List<String> gradleArgs(String gradle, Session session, boolean offline, boolean proc) {
        List<String> result = new ArrayList<>(List.of(gradle)); if (offline) result.add("--offline");
        result.addAll(List.of("--no-daemon", "--max-workers=2", "--no-configuration-cache", "--no-build-cache", "--console=plain", "--project-cache-dir", session.root.resolve("project-cache").toString(), "-I", session.root.resolve("isolation.init.gradle").toString(),
            "-Pkotlin.native.home=" + session.sdk, "-Pkotlin.project.persistent.dir=" + session.root.resolve("kotlin-project"), "-Pkotlin.compiler.execution.strategy=in-process",
            "-Dorg.gradle.jvmargs=" + (proc ? "-Xmx6g -XX:MaxMetaspaceSize=1g" : "-Xmx4g") + " -Djava.io.tmpdir=" + session.root.resolve("tmp")));
        return result;
    }
    private static List<String> append(List<String> args, String... extra) { List<String> result = new ArrayList<>(args); result.addAll(List.of(extra)); return result; }
    private static List<String> swiftArgs(Path root, Path harness, String configuration, boolean proc) {
        List<String> args = new ArrayList<>(List.of("/usr/bin/xcrun", "swift", "test", "-v", "--build-system", "native"));
        if (configuration != null) args.addAll(List.of("-c", configuration.toLowerCase(Locale.ROOT)));
        args.addAll(List.of("--package-path", harness.toString(), "--scratch-path", root.resolve("swift-build").toString(), "--cache-path", root.resolve("swiftpm/cache").toString(), "--config-path", root.resolve("swiftpm/config").toString(), "--security-path", root.resolve("swiftpm/security").toString(), "--jobs", "2", "-Xswiftc", "-warnings-as-errors", "-Xswiftc", "-v", "-Xswiftc", "-target", "-Xswiftc", "arm64-apple-macosx15.0"));
        return args;
    }

    record Frontends(List<Map<String, Object>> products, List<String> manifests) {}
    static List<String> commandWords(String text) {
        List<String> words = new ArrayList<>(); StringBuilder word = new StringBuilder(); char quote = 0; boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == '\'') { if (c == quote) quote = 0; else word.append(c); }
            else if (c == '\\') { check(i + 1 < text.length(), "Unterminated frontend command escape"); char next = text.charAt(i + 1); if (quote == 0 || next == '"' || next == '\\' || next == '$' || next == '`' || next == '\n') { if (next != '\n') word.append(next); i++; } else word.append(c); started = true; }
            else if (quote != 0) { if (c == quote) quote = 0; else word.append(c); }
            else if (c == '\'' || c == '"') { quote = c; started = true; }
            else if (Character.isWhitespace(c)) { if (started || !word.isEmpty()) { words.add(word.toString()); word.setLength(0); started = false; } }
            else { word.append(c); started = true; }
        }
        check(quote == 0, "Unterminated frontend command quote"); if (started || !word.isEmpty()) words.add(word.toString()); return words;
    }
    private static String lastOption(List<String> arguments, String option) {
        String result = null;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (argument.equals(option)) {
                check(i + 1 < arguments.size() && !arguments.get(i + 1).startsWith("-"), "Missing frontend option value: " + option);
                result = arguments.get(++i);
            } else if (argument.startsWith(option + "=")) result = argument.substring(option.length() + 1);
        }
        return result;
    }
    static Frontends frontends(String text, Set<String> authored, Set<String> required, boolean strictRunner, boolean proc) {
        List<Map<String, Object>> products = new ArrayList<>(); List<String> manifests = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.contains("swift-frontend")) continue;
            List<String> arguments = commandWords(line); String module = lastOption(arguments, "-module-name");
            if (module == null) continue;
            boolean manifest = "main".equals(module) && arguments.stream().anyMatch(argument -> Path.of(argument).getFileName() != null && Path.of(argument).getFileName().toString().equals("Package.swift"));
            if (manifest) { manifests.add(line); continue; }
            check(!line.contains(" -package-description-version "), "Unexpected package manifest frontend");
            String mode = lastOption(arguments, "-swift-version");
            String target = lastOption(arguments, "-target");
            boolean mac15 = "arm64-apple-macosx15.0".equals(target) || "arm64-apple-macos15.0".equals(target);
            check(arguments.contains("-warnings-as-errors") && mac15, "Missing Werror/effective mac15 product frontend: " + module);
            if (module.endsWith("PackageTests")) {
                if (strictRunner) check(mode == null || mode.equals("6") || mode.equals("6.0"), "Unexpected generated runner mode: " + mode);
            } else {
                String expected = authored.contains(module) ? "6" : "5";
                check(expected.equals(mode) || (expected + ".0").equals(mode), "Unexpected module language mode: " + module + ": " + mode);
            }
            products.add(obj("module", module, "mode", mode, "swift_version", mode, "werror", true, "mac15", true, "target", true, "effective_target", target, "command", line));
        }
        Set<String> modules = new LinkedHashSet<>(); for (Map<String, Object> row : products) modules.add(s(row.get("module")));
        check(!products.isEmpty() && modules.containsAll(required), "Missing actual dependency/test frontend proof: " + difference(required, modules));
        return new Frontends(products, manifests);
    }
    static Map<String, Integer> testCounts(String text, List<String> expected) {
        check(new TreeSet<>(expected).size() == expected.size() && !expected.isEmpty(), "Expected unique named Swift behaviors");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : expected) {
            int count = (int) Pattern.compile("Test " + Pattern.quote(name) + "\\(\\)(?: \\(aka [^\\n]*?\\))? passed").matcher(text).results().count();
            check(count == 1, "Expected exactly one passing Swift behavior: " + name + ": " + count); counts.put(name, count);
        }
        String plural = expected.size() == 1 ? "test" : "tests";
        check(Pattern.compile("Test run with " + expected.size() + " " + plural + "(?: in \\d+ suites?)? passed").matcher(text).find(), "Missing complete Swift test summary");
        check(!Pattern.compile("(?:Test|Suite) .* (?:failed|skipped)").matcher(text).find(), "Actual Swift test failed or was skipped"); return counts;
    }
    static void collectionConversions(String swift, String header) {
        check(swift.replaceAll("\\s+", "").contains("Dictionary<Swift.String,Swift.Array<Swift.String>>"), "Generated recursive Dictionary type is missing");
        check(header.contains("NSDictionary<NSString *, NSArray<NSString *> *> *"), "Generated Objective-C nested collection type is missing");
        List<String> returns = swift.lines().filter(line -> Pattern.compile("^\\s*return foundationproof_").matcher(line).find()).toList();
        check(returns.size() >= 6 && returns.stream().noneMatch(line -> line.contains(" as!")), "Redundant direct conversion retained or direct return coverage missing");
        check(Pattern.compile("let _arg0: .* = arg0 as! ").matcher(swift).find(), "Necessary erased implementation callback conversion missing");
    }
    static List<String> declarations(String text) {
        List<String> result = new ArrayList<>(); String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length;) {
            if (lines[i].stripLeading().startsWith("public ")) {
                StringBuilder block = new StringBuilder(lines[i++].strip()); while (block.indexOf("{") < 0 && i < lines.length) block.append(' ').append(lines[i++].strip());
                String declaration = block.toString(); if (declaration.contains("{")) declaration = declaration.substring(0, declaration.indexOf('{')); result.add(declaration.replaceAll("\\s+", " ").strip());
            } else i++;
        }
        return result;
    }
    private static Map<String, String> emitted(Path packageRoot, Path archive) throws IOException { Map<String, String> result = pins(files(packageRoot)); result.put(archive.toString(), sha(archive)); return result; }
    private static void unchangedEmitted(Path packageRoot, Path archive, Map<String, String> expected) throws IOException { check(emitted(packageRoot, archive).equals(expected), "Generated inventory changed"); }
    private static String manifest(Profile profile, Path packageRoot, Path products) {
        String product = ".product(name: \"" + profile.module() + "Library\", package: \"" + profile.module() + "\")";
        String provider = profile.command().equals("reverse-import-consumer") ? ".target(name: \"SwiftProvider\", dependencies: [" + product + "], swiftSettings: [.swiftLanguageMode(.v6)]),\n" : "";
        String dependencies = profile.command().equals("reverse-import-consumer") ? "\"SwiftProvider\", " + product : product;
        return "// swift-tools-version: 6.0\nimport PackageDescription\nlet package = Package(name: \"" + profile.harness() + "\", platforms: [.macOS(.v15)], dependencies: [.package(name: \"" + profile.module() + "\", path: " + quote(packageRoot)
            + ")], targets: [\n" + provider + ".testTarget(name: \"" + profile.testModule() + "\", dependencies: [" + dependencies + "], swiftSettings: [.swiftLanguageMode(.v6)], linkerSettings: [.unsafeFlags([\"-L\", " + quote(products) + ", \"-l" + profile.module() + "\"])])])\n";
    }
    private record Selected(Path component, List<Path> evidence) {}
    private Selected selectComponent(Profile profile, Path parent, Path distribution, Map<String, String> options, Map<String, Object> release) throws IOException {
        List<Path> evidence = new ArrayList<>(List.of(parent, distribution.resolve("distribution.json")));
        check(sha(parent).equals(readObject(distribution.resolve("distribution.json")).get("main_component_sha256")), "Parent distribution checksum mismatch");
        if (profile.command().equals("reverse-import-consumer")) { check(sha(parent).equals(release.get("main_component_sha256")), "Reverse-import consumer requires the frozen release component"); return new Selected(parent, evidence); }
        if (profile.command().equals("array-consumer")) return new Selected(parent, evidence);
        if (!options.containsKey("candidate-audit")) {
            check(options.containsKey("sdk-download-audit") && options.containsKey("helper"), "Release validation requires packaged helper provenance"); return new Selected(parent, evidence);
        }
        check(sha(parent).equals(release.get("main_component_sha256")), "Candidate parent differs from frozen release");
        Path auditPath = path(options, "candidate-audit"); Map<String, Object> audit = readObject(auditPath); Path closure = auditPath.getParent().resolve("reference-closure.json");
        check(sha(closure).equals(audit.get("reference_closure_sha256")) && Boolean.TRUE.equals(readObject(closure).get("accepted")), "Candidate reference closure was not accepted");
        evidence.add(auditPath); evidence.add(closure); Path component;
        if (profile.command().equals("bytearray-consumer")) {
            validateByteCandidate(audit); component = Path.of(s(audit.get("jar"))).toAbsolutePath().normalize();
        } else {
            Path sourceLock = support.p("producer/collections/source-lock.json"); Map<String, Object> source = readObject(sourceLock);
            validateCollectionCandidate(audit, source);
            Path hostPins = auditPath.getParent().resolve("input-pins.json"); Map<String, String> pins = stringMap(Json.read(hostPins)); verifyPins(pins, "Host audit input changed");
            Path candidateSource = auditPath.getParent().resolve("candidate/sources/TypeBridging.kt");
            check(sha(candidateSource).equals(source.get("candidate_source_sha256")), "Candidate TypeBridging source changed");
            check(sha(support.p(s(source.get("patch")))).equals(source.get("patch_sha256")), "Collection patch changed");
            evidence.add(hostPins); evidence.add(candidateSource); for (String path : pins.keySet()) evidence.add(Path.of(path));
            evidence.add(sourceLock); component = Path.of(s(audit.get("component"))).toAbsolutePath().normalize();
        }
        check(sha(component).equals(audit.get("sha256")), "Audited candidate component changed"); evidence.add(component); return new Selected(component, evidence);
    }
    private Selected selectNullableUnsignedComponent(Map<String, String> options) throws IOException {
        Path component = path(options, "component"); Path auditPath = path(options, "candidate-audit");
        Path sourceLock = support.p("producer/nullable-unsigned/source-lock.json"); Map<String, Object> source = readObject(sourceLock);
        Path parent = Path.of(s(source.get("baseProducer"))).toAbsolutePath().normalize();
        Map<String, Object> audit = readObject(auditPath);
        validateNullableUnsignedManifest(audit);
        check(component.equals(Path.of(s(audit.get("component"))).toAbsolutePath().normalize()), "Nullable unsigned audit selected another component");
        check(sha(component).equals(audit.get("sha256")), "Nullable unsigned component checksum mismatch");
        check(sha(parent).equals(UNSIGNED_BASE_SHA) && UNSIGNED_BASE_SHA.equals(source.get("baseProducerSha256")), "Nullable unsigned parent checksum mismatch");
        List<String> changed = List.of(UNSIGNED_BRIDGE + ".class"); List<String> added = List.of(UNSIGNED_BRIDGE + "$inKotlinSources$2.class");
        Map<String, byte[]> original = entries(parent), candidate = entries(component);
        check(difference(original.keySet(), candidate.keySet()).isEmpty() && difference(candidate.keySet(), original.keySet()).equals(Set.copyOf(added)), "Nullable unsigned candidate added or removed unexpected entries");
        List<String> observed = original.keySet().stream().filter(name -> !java.util.Arrays.equals(original.get(name), candidate.get(name))).toList();
        check(observed.equals(changed), "Nullable unsigned candidate changed unrelated parent bytes");
        Map<String, String> pins = stringMap(audit.get("input_pins"));
        verifyPins(pins, "Nullable unsigned host input changed");
        Path patch = support.p("producer/nullable-unsigned/TypeBridging.kt.patch");
        check(sha(patch).equals(source.get("patchSha256")), "Nullable unsigned source patch changed");
        List<Path> evidence = new ArrayList<>(List.of(component, parent, auditPath, sourceLock, patch, support.p(SOURCE + "ProducerCli.java")));
        for (String input : pins.keySet()) evidence.add(Path.of(input));
        return new Selected(component, evidence);
    }
    static void validateNullableUnsignedManifest(Map<String, Object> audit) {
        check(Path.of(s(audit.get("component"))).isAbsolute(), "Nullable unsigned component must be an absolute path");
        check(s(audit.get("sha256")).matches("[0-9a-f]{64}"), "Malformed nullable unsigned component SHA-256");
        check(UNSIGNED_BASE_SHA.equals(audit.get("base_component_sha256")), "Nullable unsigned parent checksum mismatch");
        check(Boolean.TRUE.equals(audit.get("host_source_class_audit_passed")) && Boolean.TRUE.equals(audit.get("unselected_parent_bytes_identical")), "Nullable unsigned host source/class audit failed");
        check(strings(audit.get("changed_classes")).equals(List.of(UNSIGNED_BRIDGE + ".class"))
            && strings(audit.get("added_classes")).equals(List.of(UNSIGNED_BRIDGE + "$inKotlinSources$2.class"))
            && l(audit.get("removed_classes")).isEmpty(), "Unexpected nullable unsigned class allowlist");
        Map<String, Object> pins = m(audit.get("input_pins"));
        check(!pins.isEmpty(), "Nullable unsigned host input pins must not be empty");
        pins.forEach((input, hash) -> check(Path.of(input).isAbsolute() && s(hash).matches("[0-9a-f]{64}"), "Malformed nullable unsigned host input pin"));
    }
    static void validateByteCandidate(Map<String, Object> audit) {
        check(Boolean.TRUE.equals(audit.get("accepted_for_isolated_fixture")) && Boolean.TRUE.equals(audit.get("all_unselected_parent_entries_byte_identical")), "ByteArray candidate was not accepted or changed unrelated parent entries");
        check(audit.get("baseline_classes_fullflags_instructions_attributes_equal") instanceof Number count && new BigDecimal(count.toString()).compareTo(BigDecimal.valueOf(8)) == 0, "ByteArray baseline class audit must cover exactly eight classes");
        check(l(audit.get("removed_parent_entries")).isEmpty(), "ByteArray candidate removed parent entries");
    }
    static void validateCollectionCandidate(Map<String, Object> audit, Map<String, Object> source) {
        check(Boolean.TRUE.equals(audit.get("host_source_class_audit_passed")) && Boolean.TRUE.equals(audit.get("unselected_parent_bytes_identical")), "Collection host source/class audit failed");
        Object raw = source.get("class_modules"); Set<String> expected = raw instanceof Map<?, ?> ? m(raw).keySet() : new TreeSet<>(strings(raw));
        check(new TreeSet<>(strings(audit.get("changed_classes"))).equals(expected), "Unexpected changed collection classes");
        check(l(audit.get("added_or_removed_entries")).isEmpty(), "Collection candidate added or removed archive entries");
    }

    static String nullableUnsignedManifestPolicy(Path root, String configuration, String javaExecutable, String classpath) {
        check(Set.of("Debug", "Release").contains(configuration), "Unexpected nullable unsigned configuration");
        List<String> command = List.of(javaExecutable, "-cp", classpath, SwiftManifestEnforcer.class.getName(),
            root.resolve("build/SPMPackage/macosArm64/" + configuration + "/Package.swift").toString(),
            "--receipt", root.resolve("manifest-policy.json").toString());
        String arguments = command.stream().map(Json::stringify).collect(java.util.stream.Collectors.joining(", "));
        return "\ngradle.projectsEvaluated {\n  gradle.rootProject.allprojects { p ->\n"
            + "    p.tasks.matching { it.name == 'macosArm64" + configuration + "GenerateSPMPackage' }.configureEach { t ->\n"
            + "      t.doLast {\n        def process = new ProcessBuilder([" + arguments + "]).redirectErrorStream(true).start()\n"
            + "        def output = process.inputStream.getText('UTF-8')\n"
            + "        new File(" + quote(root.resolve("logs/manifest-policy.log")) + ").text = output\n"
            + "        if (process.waitFor() != 0) throw new GradleException('Nullable unsigned manifest policy failed: ' + output)\n"
            + "      }\n    }\n  }\n}\n";
    }

    private int fixture(Profile profile, Map<String, String> options) throws Exception {
        boolean array = profile.command().equals("array-consumer"); boolean collection = profile.command().equals("collection-consumer"); boolean reverse = profile.command().equals("reverse-import-consumer");
        boolean nullableUnsigned = profile.command().equals("nullable-unsigned-consumer");
        String configuration = config(options, !array); Path root = newOutput(options); Map<String, Object> lock = support.lock(); Map<String, Object> release = support.frozen();
        if (array) { required(options, "sdk-download-audit"); required(options, "helper"); }
        if (options.containsKey("helper")) new ProducerDistribution(support).frozenHelper(lock, path(options, "helper"));
        Selected selected;
        if (nullableUnsigned) selected = selectNullableUnsignedComponent(options);
        else {
            Path distribution = path(options, "distribution"); Path parent = distribution.resolve(s(lock.get("artifact")) + "-" + s(lock.get("version")) + ".jar");
            selected = selectComponent(profile, parent, distribution, options, release);
        }
        List<Map<String, Object>> tools = workerTools(lock, selected.component(), path(options, "cache"), true); writeJson(root.resolve("compiler-classpath.json"), tools);
        Path fixture = support.p("verification/" + profile.fixture()); Map<String, String> fixturePins = pins(files(fixture));
        Path repairPath = null;
        if (array) {
            repairPath = support.p(s(lock.get("array_repair"))); check(sha(repairPath).equals(lock.get("array_repair_sha256")), "Array repair recipe checksum mismatch");
            for (var pin : stringMap(readObject(repairPath).get("fixture_files")).entrySet()) check(sha(support.p(pin.getKey())).equals(pin.getValue()), "Array fixture differs from reviewed repair: " + pin.getKey());
            copyTree(fixture.resolve("src"), root.resolve("fixture/src"));
            for (String name : List.of("build.gradle", "settings.gradle", "gradle.properties")) Files.copy(fixture.resolve(name), root.resolve("fixture/" + name));
        } else copyTree(fixture, root.resolve("fixture"));
        Path harness = root.resolve("harness"); copyTree(fixture.resolve("Tests"), harness.resolve("Tests")); if (reverse) copyTree(fixture.resolve("Sources"), harness.resolve("Sources"));
        seed(options, root, !array);
        Path initSource = support.p("verification/array-consumer.init.gradle");
        String init = Files.readString(initSource).replace("it.name == 'macosArm64DebugSwiftExport'", "it.name == 'macosArm64" + configuration + "SwiftExport'")
            .replace("it.name == 'macosArm64DebugGenerateSPMPackage'", "it.name == 'macosArm64" + configuration + "GenerateSPMPackage'")
            .replace("build/SPMPackage/macosArm64/Debug/Package.swift", "build/SPMPackage/macosArm64/" + configuration + "/Package.swift");
        List<Path> manifestPolicyClasspath = nullableUnsigned ? java.util.Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(java.io.File.pathSeparator)))
            .map(entry -> Path.of(entry).toAbsolutePath().normalize()).filter(Files::exists).toList() : List.of();
        if (nullableUnsigned) {
            check(!manifestPolicyClasspath.isEmpty(), "Missing producer manifest policy classpath");
            String classpath = manifestPolicyClasspath.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            init += nullableUnsignedManifestPolicy(root, configuration, Path.of(System.getProperty("java.home"), "bin", "java").toString(), classpath);
        }
        write(root.resolve("isolation.init.gradle"), init); write(root.resolve("strict.xcconfig"), STRICT);
        Path packageRoot = root.resolve("build/SPMPackage/macosArm64/" + configuration); write(harness.resolve("Package.swift"), manifest(profile, packageRoot, root.resolve("products")));
        Path sdk = path(options, "sdk");
        if (collection || nullableUnsigned) { Path supplied = sdk; sdk = root.resolve("native-sdk"); support.run(List.of("cp", "-cRL", supplied.toString(), sdk.toString()), root.resolve("logs/sdk-clone.log")); }
        Map<String, String> expectedSdk = expectedSdk(lock, readObject(path(options, "sdk-audit"))); checkSdk(sdk, expectedSdk, root);
        Session session = new Session(root, sdk, expectedSdk, tools, configuration, false); session.pins.putAll(fixturePins);
        List<Path> fixed = new ArrayList<>(implementationInputs()); fixed.addAll(selected.evidence()); fixed.addAll(List.of(support.p("inputs.lock.json"), support.p("verification/release-artifacts.json"), initSource,
            path(options, "sdk-audit"), root.resolve("compiler-classpath.json"), root.resolve("isolation.init.gradle"), root.resolve("strict.xcconfig")));
        if (nullableUnsigned) {
            fixed.add(support.p(SOURCE + "SwiftManifestEnforcer.java"));
            fixed.add(support.p(SOURCE + "Json.java"));
            for (Path path : manifestPolicyClasspath) {
                if (Files.isDirectory(path)) fixed.addAll(files(path)); else fixed.add(path);
            }
        }
        if (repairPath != null) fixed.add(repairPath);
        if (array) fixed.addAll(List.of(support.p("verification/array-expected-tests.json"), support.p("verification/iterator-fixture/IteratorLifetimeTests.swift")));
        if (collection) fixed.addAll(List.of(support.p("producer/collections/source-lock.json"), support.p("producer/collections/TypeBridging.kt.patch"), support.p(SOURCE + "ProducerBuild.java"), support.p(SOURCE + "ProducerClosureAudit.java")));
        for (String name : List.of("sdk-download-audit", "helper")) if (options.containsKey(name)) fixed.add(path(options, name));
        fixed.addAll(files(root.resolve("fixture"))); fixed.addAll(files(harness));
        if (array) { fixed.addAll(files(root.resolve("gradle-home/caches/modules-2/files-2.1"))); fixed.addAll(files(root.resolve("konan-data")).stream().filter(p -> !p.getFileName().toString().endsWith(".lock")).toList()); }
        session.pin(fixed); session.freeze(); session.verify(); String gradle = gradle(options, session); List<String> gradleArgs = gradleArgs(gradle, session, collection || nullableUnsigned, false);
        session.run("embed", append(gradleArgs, "embedSwiftExportForXcode"), root.resolve("fixture")); Path archive = root.resolve("products/lib" + profile.module() + ".a"); check(Files.isRegularFile(archive), "Missing actual Native library archive: " + archive);
        Map<String, String> emitted = emitted(packageRoot, archive); writeJson(root.resolve("emitted-pins.json"), emitted);
        List<String> swiftCommand = swiftArgs(root, harness, array ? null : configuration, false);
        String output = session.run("swift", swiftCommand, root);
        List<String> names = array ? strings(Json.read(support.p("verification/array-expected-tests.json"))) : profile.tests(); check(!array || names.size() == 27, "Expected twenty-seven Array behaviors");
        Map<String, Integer> counts = testCounts(output, names); Set<String> authored = reverse ? Set.of("ReverseImportTests", "SwiftProvider") : Set.of(profile.testModule());
        Frontends frontends = frontends(output, authored, profile.requiredModules(), array, false); unchangedEmitted(packageRoot, archive, emitted);
        Map<String, Object> result = obj("accepted", true, "configuration", configuration, "tests", counts, "frontends", frontends.products(), "manifest_frontends", frontends.manifests(),
            "native_archive_sha256", sha(archive), "component_sha256", sha(selected.component()), "sdk_files_unchanged", expectedSdk.size(), "candidate_audit_sha256", options.containsKey("candidate-audit") ? sha(path(options, "candidate-audit")) : null);
        if (array || profile.command().equals("bytearray-consumer")) result.put("native_boundary", boundary(session, fixture, profile.module(), packageRoot, !array));
        if (array) {
            session.verify(); unchangedEmitted(packageRoot, archive, emitted);
            Map<String, String> iteratorOptions = new LinkedHashMap<>(Map.of("package", packageRoot.toString(), "archive", archive.toString(), "output", root.resolve("iterator").toString(), "sdk", sdk.toString(), "sdk-audit", path(options, "sdk-audit").toString()));
            iterator(iteratorOptions); Path iteratorResult = root.resolve("iterator/verified-result.json"); check(Boolean.TRUE.equals(readObject(iteratorResult).get("accepted")), "Iterator lifetime result was not accepted"); result.put("iterator_result_sha256", sha(iteratorResult));
        }
        if (collection) {
            Path swift = packageRoot.resolve("Sources/FoundationCollectionProof/FoundationCollectionProof.swift");
            List<Path> headers = files(packageRoot.resolve("Sources")).stream().filter(p -> p.getFileName().toString().equals("FoundationCollectionProof.h") && p.getParent().getFileName().toString().equals("include")).toList();
            check(!headers.isEmpty(), "Missing FoundationCollectionProof header"); Path header = headers.getFirst(); collectionConversions(Files.readString(swift), Files.readString(header));
            result.put("generated_swift_sha256", sha(swift)); result.put("generated_header_sha256", sha(header)); result.put("scope", "String/List and String-keyed Dictionary Foundation identity; no general generic or concurrent-access proof");
        } else if (!array) { result.put("generic_result_fidelity", "not claimed"); result.put("swift_error_catch_or_actor_safety", "not claimed"); }
        if (reverse) {
            List<Map<String, Object>> hostResults = new ArrayList<>();
            if (Boolean.parseBoolean(options.getOrDefault("host-tests", "false"))) {
                List<String> targets = List.of("jvmTest", "macosArm64Test", "jsNodeTest", "wasmJsNodeTest", "wasmWasiNodeTest");
                session.run("host-tests", append(gradleArgs, targets.toArray(String[]::new)), root.resolve("fixture"));
                for (String target : targets) hostResults.add(hostResults(root.resolve("build/test-results/" + target), target));
            }
            result.put("host_results", hostResults);
        }
        session.verify(); unchangedEmitted(packageRoot, archive, emitted); writeJson(root.resolve("verified-result.json"), result);
        System.out.println("PASS " + names.size() + " real " + profile.module() + " behaviors in " + configuration); return 0;
    }

    static void boundaryResult(String name, int exit, String output, String shellDiagnostic, boolean bytes) {
        if (name.equals("positive")) { check(exit == 0, "Positive native boundary failed: " + exit); return; }
        // Process exposes Unix signal exits as 128 + signal; the shell's own diagnostic
        // distinguishes a real SIGABRT from a normal program returning status 134.
        check(exit == 134 && Pattern.compile("(?m)^.*?: line \\d+:\\s+\\d+\\s+(?:Abort trap:\\s*6|Aborted(?:\\s+\\(core dumped\\))?)\\s+.*$").matcher(shellDiagnostic).find(), "Unexpected non-deliberate native termination: " + name + ": " + exit);
        String family = bytes && name.equals("negativeSize") ? "NegativeArraySizeException|IllegalArgumentException"
            : Set.of("getNegative", "getUpper", "setNegative", "setUpper").contains(name) ? "IndexOutOfBoundsException|ArrayIndexOutOfBoundsException" : "ClassCastException";
        check(Pattern.compile(family).matcher(output).find(), "Wrong native failure family: " + name);
    }
    private Map<String, Object> boundary(Session session, Path fixture, String module, Path packageRoot, boolean bytes) throws Exception {
        Path root = session.root; Path boundary = root.resolve("native-boundary"); Files.createDirectories(boundary);
        List<String> clang = new ArrayList<>(List.of("/usr/bin/xcrun", "clang", "-x", "objective-c", "-fobjc-arc"));
        if (bytes) clang.add("-fblocks"); clang.addAll(List.of("-Wall", "-Wextra", "-Werror", "-target", "arm64-apple-macosx15.0", "-fmodules-cache-path=" + boundary.resolve("module-cache")));
        if (bytes) {
            try (var paths = Files.list(packageRoot.resolve("Sources"))) { for (Path path : paths.sorted().toList()) if (Files.isDirectory(path.resolve("include"))) clang.addAll(List.of("-I", path.resolve("include").toString())); }
        } else for (String include : List.of("SharedBridge_ArrayIntegration", "KotlinRuntimeSupportBridge")) clang.addAll(List.of("-I", packageRoot.resolve("Sources/" + include + "/include").toString()));
        clang.addAll(List.of(fixture.resolve(bytes ? "NativeByteArrayBoundary.m" : "NativeArrayBoundary.m").toString(), "-L", root.resolve("products").toString(), "-l" + module, "-framework", "Foundation", "-lc++", "-o", boundary.resolve("probe").toString()));
        session.run("boundary-compile", clang, root);
        List<String> cases = bytes ? List.of("positive", "negativeSize", "getNegative", "getUpper", "setNegative", "setUpper", "wrongReceiver")
            : List.of("positive", "getNegative", "getUpper", "setNegative", "setUpper", "wrongReceiver", "wrongValue", "fromString", "fromNull", "fromObject", "wrongHashReceiver", "wrongEqualsReceiver", "wrongCopyReceiver", "wrongConsumeReceiver");
        Map<String, Object> results = new LinkedHashMap<>();
        for (String name : cases) {
            Path log = boundary.resolve(name + ".log"), shellLog = boundary.resolve(name + "-process.log");
            List<String> command = boundaryCommand(boundary.resolve("probe").toString(), name);
            Map<String, String> environment = new LinkedHashMap<>(session.environment); environment.put("KOTLINMANIA_BOUNDARY_OUTPUT", log.toString());
            writeJson(boundary.resolve(name + "-invocation.json"), obj("args", command, "cwd", root.toString(), "timeout_seconds", 60));
            int exit = execute(command, root, environment, shellLog, root.resolve("active-process.json"), "boundary-" + name, 60);
            boundaryResult(name, exit, Files.readString(log), Files.readString(shellLog), bytes); results.put(name, obj("exit", name.equals("positive") ? 0 : -6, "process_exit", exit, "log_sha256", sha(log), "process_log_sha256", sha(shellLog)));
        }
        session.verify(); return results;
    }
    static List<String> boundaryCommand(String... probe) {
        List<String> command = new ArrayList<>(List.of("/bin/bash", "-c", "ulimit -c 0; \"$@\" > \"$KOTLINMANIA_BOUNDARY_OUTPUT\" 2>&1; exit \"$?\"", "--")); command.addAll(List.of(probe)); return command;
    }

    static Map<String, Object> hostResults(Path directory, String target) throws Exception {
        List<Path> reports;
        try (var paths = Files.list(directory)) { reports = paths.filter(path -> path.getFileName().toString().startsWith("TEST-") && path.toString().endsWith(".xml") && Files.isRegularFile(path)).sorted().toList(); }
        check(!reports.isEmpty(), "Missing actual test report: " + target); List<String> cases = new ArrayList<>();
        for (Path report : reports) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(Files.readAllBytes(report))).getDocumentElement();
            for (String key : List.of("failures", "errors")) check(!root.hasAttribute(key) || Integer.parseInt(root.getAttribute(key)) == 0, "Host test failures or errors in " + report);
            for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element testcase && testcase.getTagName().equals("testcase")) {
                for (var child = testcase.getFirstChild(); child != null; child = child.getNextSibling()) check(!Set.of("skipped", "failure", "error").contains(child.getNodeName()), "Unsuccessful host testcase: " + target);
                check(testcase.hasAttribute("name"), "Unnamed host testcase"); cases.add(testcase.getAttribute("name"));
            }
        }
        check(cases.size() == 4, "Expected four host behaviors: " + target + ": " + cases);
        for (String expected : List.of("exportNamesDoNotReplaceKotlinOperations", "callbackPreservesConcretePayloadIdentity", "successfulNullRemainsSuccess", "failurePreservesOriginalObject"))
            check(cases.stream().filter(name -> name.startsWith(expected)).count() == 1, "Missing or duplicate host behavior: " + target + ": " + expected);
        return obj("target", target, "count", 4, "cases", cases, "reports_sha256", pins(reports));
    }

    private int iterator(Map<String, String> options) throws Exception {
        Path root = newOutput(options); Path packageRoot = path(options, "package"); Path archive = path(options, "archive"); check(Files.isRegularFile(archive), "Actual iterator Native archive required");
        Path source = support.p("verification/iterator-fixture/IteratorLifetimeTests.swift"); Path tests = root.resolve("harness/Tests/IteratorLifetimeTests"); Files.createDirectories(tests); Files.copy(source, tests.resolve(source.getFileName()));
        Profile profile = new Profile("iterator-consumer", "iterator-fixture", "ArrayIntegration", "IteratorLifetime", "IteratorLifetimeTests", List.of(ITERATOR_TEST), Set.of("IteratorLifetimeTests", "ArrayIntegration", "KotlinRuntimeSupport"));
        write(root.resolve("harness/Package.swift"), manifest(profile, packageRoot, archive.getParent()));
        Map<String, Object> lock = support.lock(); Path sdk = path(options, "sdk"); Map<String, String> expected = expectedSdk(lock, readObject(path(options, "sdk-audit")));
        Session session = new Session(root, sdk, expected, List.of(), "Debug", false); List<Path> fixed = new ArrayList<>(files(packageRoot)); fixed.addAll(implementationInputs());
        fixed.addAll(List.of(archive, source, support.p("inputs.lock.json"), path(options, "sdk-audit"))); fixed.addAll(files(root.resolve("harness"))); session.pin(fixed); session.freeze();
        String output = session.run("swift", swiftArgs(root, root.resolve("harness"), null, false), root);
        testCounts(output, List.of(ITERATOR_TEST)); Frontends frontends = frontends(output, Set.of("IteratorLifetimeTests"), profile.requiredModules(), true, false);
        session.verify(); writeJson(root.resolve("verified-result.json"), obj("accepted", true, "test", ITERATOR_TEST, "count", 1, "frontends", frontends.products(), "sdk_files_unchanged", expected.size(), "native_archive_sha256", sha(archive)));
        System.out.println("PASS iterator Native/ObjC lifetime"); return 0;
    }

    private int procMacro(Map<String, String> options) throws Exception {
        Path repo = path(options, "consumer"); Path root = newOutput(options); Path distribution = path(options, "distribution"); Path sdk = path(options, "sdk");
        Path sdkAudit = path(options, "sdk-audit"); Path sdkDownloadAudit = path(options, "sdk-download-audit"); Path helper = path(options, "helper");
        seed(options, root, false); Map<String, Object> lock = support.lock(); new ProducerDistribution(support).frozenHelper(lock, helper);
        Path component = distribution.resolve("swift-export-embeddable-" + s(lock.get("version")) + ".jar");
        check(sha(component).equals(readObject(distribution.resolve("distribution.json")).get("main_component_sha256")), "Consumer component checksum mismatch");
        List<Map<String, Object>> tools = workerTools(lock, component, path(options, "cache"), false); writeJson(root.resolve("compiler-classpath.json"), tools);
        Map<String, String> sourcePins = new TreeMap<>(); for (var pin : stringMap(Json.read(support.p("verification/consumer-source-pins.json"))).entrySet()) sourcePins.put(repo.resolve(pin.getKey()).toString(), pin.getValue());
        writeJson(root.resolve("source-pins.json"), sourcePins); Map<String, String> expectedSdk = expectedSdk(lock, readObject(sdkAudit)); checkSdk(sdk, expectedSdk, root);
        Map<String, String> absoluteSdk = new TreeMap<>(); expectedSdk.forEach((name, hash) -> absoluteSdk.put(sdk.resolve(name).toString(), hash)); writeJson(root.resolve("sdk-pins.json"), absoluteSdk);
        List<Path> producerInputs = new ArrayList<>(implementationInputs()); producerInputs.addAll(List.of(component, distribution.resolve("distribution.json"), support.p("inputs.lock.json"), support.p("verification/consumer.init.gradle"),
            support.p("verification/consumer-source-pins.json"), support.p("verification/consumer-public-declarations.json"), sdkAudit, sdkDownloadAudit, helper));
        Map<String, String> producerPins = pins(producerInputs); writeJson(root.resolve("producer-inputs.json"), producerPins); writeJson(root.resolve("stock-inputs.json"), Map.of());
        write(root.resolve("isolation.init.gradle"), Files.readString(support.p("verification/consumer.init.gradle")) + consumerInitGuard());
        Path testSource = repo.resolve("swift-test-harness/Tests/SwiftTestHarnessTests/ProcMacro2ExportTests.swift"); Path copiedTest = root.resolve("harness/Tests/SwiftTestHarnessTests/ProcMacro2ExportTests.swift");
        Files.createDirectories(copiedTest.getParent()); Files.copy(testSource, copiedTest);
        String manifest = "// swift-tools-version: 6.0\nimport PackageDescription\nlet package = Package(name: \"SwiftTestHarness\", platforms: [.macOS(\"15.0\")], dependencies: [.package(name: \"ProcMacro2\", path: "
            + quote(root.resolve("build/SPMPackage/macosArm64/Debug")) + ")], targets: [.testTarget(name: \"SwiftTestHarnessTests\", dependencies: [.product(name: \"ProcMacro2Library\", package: \"ProcMacro2\")], linkerSettings: [.unsafeFlags([\"-L\", "
            + quote(root.resolve("products")) + ", \"-lProcMacro2\"])])], swiftLanguageModes: [.v6])\n";
        write(root.resolve("harness/Package.swift"), manifest); write(root.resolve("strict.xcconfig"), STRICT);
        Session session = new Session(root, sdk, expectedSdk, tools, "Debug", true); session.pins.putAll(sourcePins); session.pins.putAll(producerPins);
        session.pin(List.of(root.resolve("compiler-classpath.json"), root.resolve("source-pins.json"), root.resolve("producer-inputs.json"), root.resolve("stock-inputs.json"), root.resolve("sdk-pins.json"), root.resolve("isolation.init.gradle"), root.resolve("strict.xcconfig"), root.resolve("harness/Package.swift"), copiedTest));
        session.extraVerification = () -> {
            check(sha(copiedTest).equals(sha(testSource)), "Authored consumer test changed");
            ProducerSupport.Result head = support.capture(List.of("git", "rev-parse", "HEAD"), repo, Map.of("GIT_OPTIONAL_LOCKS", "0"));
            check(head.exit() == 0 && head.output().strip().equals(CONSUMER_HEAD), "Consumer source HEAD differs from the reviewed revision");
            ProducerSupport.Result status = support.capture(List.of("git", "status", "--porcelain"), repo, Map.of("GIT_OPTIONAL_LOCKS", "0"));
            check(status.exit() == 0 && status.output().isEmpty(), "Consumer checkout must remain unchanged and clean");
        };
        session.verify(); List<String> expectedDeclarations = strings(Json.read(support.p("verification/consumer-public-declarations.json"))); writeJson(root.resolve("baseline-public-declarations.json"), expectedDeclarations);
        String gradle = gradle(options, session); List<String> gradleArgs = append(gradleArgs(gradle, session, false, true), "embedSwiftExportForXcode"); List<String> swift = swiftArgs(root, root.resolve("harness"), null, true);
        Map<String, Object> request = obj("dependency_swift_mode", "5", "authored_consumer_swift_mode", "6", "native_and_all_swift_werror", true, "source_head", CONSUMER_HEAD,
            "gradle", gradleArgs, "swift_if_embed_passes", swift, "expected_tests", List.of("tokenStreamParsingAndFormatting", "tokenStreamParseFailure", "literalAndPunctExport"), "component_sha256", sha(component),
            "stop_on_first_product_or_integrity_failure", true, "scope", "One audited Native/Swift consumer with dependency Swift 5, authored Swift 6, and warnings as errors. No publication or Array/reverse claim.");
        writeJson(root.resolve("request.json"), request); session.pin(List.of(root.resolve("request.json"), root.resolve("baseline-public-declarations.json"))); session.freeze(); writeJson(root.resolve("recipe-pins.json"), session.pins); session.pin(List.of(root.resolve("recipe-pins.json")));
        System.out.println("FROZEN REQUEST SHA256 " + sha(root.resolve("request.json")));
        Map<String, Object> result = obj("source_head", CONSUMER_HEAD, "swift_test_cases_executed", 0, "accepted", false, "started_at", Instant.now().toString());
        try {
            session.verify(); int gradleExit = session.raw("gradle", gradleArgs, repo, 0); result.put("gradle_exit", gradleExit); session.verify();
            Path generated = root.resolve("build/SwiftExport/macosArm64/Debug/files/ProcMacro2/ProcMacro2.swift");
            if (Files.exists(generated)) { List<String> actual = declarations(Files.readString(generated)); result.put("generated_public_declarations_unchanged", actual.equals(expectedDeclarations)); result.put("generated_public_declarations_count", actual.size()); writeJson(root.resolve("actual-public-declarations.json"), actual); check(actual.equals(expectedDeclarations), "Unexpected generated public API change"); }
            List<Map<String, Object>> archives = new ArrayList<>(); for (Path archive : files(root.resolve("build"))) if (archive.getFileName().toString().equals("libProcMacro2.a")) archives.add(obj("path", archive.toString(), "sha256", sha(archive), "architecture", support.capture(List.of("/usr/bin/xcrun", "lipo", "-info", archive.toString())).strip())); result.put("native_archives", archives);
            check(sha(generated.resolveSibling("ProcMacro2.h")).equals("2af18efb0dcbb574b26e378bfe3260c5559688b9b82ad775c1c06e4586d673f1"), "C header changed");
            check(gradleExit == 0, "Actual Native/Swift embed failed; preserve diagnostics and stop"); check(Files.isRegularFile(root.resolve("products/libProcMacro2.a")), "Missing actual Native library archive");
            Map<String, String> emitted = procEmitted(root); List<String> headers = emitted.keySet().stream().filter(name -> name.endsWith("/KotlinBase.h")).toList();
            check(headers.size() >= 2 && headers.stream().allMatch(name -> emitted.get(name).equals("99186d1dd029a8ba5b66dae390a3aec0d6d0e5293eff641d85daab5e212723c3")), "Incoherent generated Native runtime header"); writeJson(root.resolve("generated-inputs.json"), emitted);
            int swiftExit = session.raw("swift", swift, root.resolve("harness"), 0); result.put("swift_exit", swiftExit); check(procEmitted(root).equals(emitted), "Emitted source/header/archive changed during tests"); session.verify();
            String output = Files.readString(root.resolve("logs/swift.log")); Frontends frontends = frontends(output, Set.of("SwiftTestHarnessTests"), Set.of("ProcMacro2", "KotlinRuntimeSupport", "KotlinCoroutineSupport", "SwiftTestHarnessTests"), true, true);
            result.put("product_frontends", frontends.products()); result.put("configuration_frontends_count", frontends.manifests().size());
            result.put("generated_runner_mode_explicit", frontends.products().stream().filter(row -> row.get("module").equals("SwiftTestHarnessPackageTests")).allMatch(row -> row.get("mode") != null));
            check(swiftExit == 0, "Actual Swift tests failed"); SwiftTestVerifier.verify("proc-macro2", output); result.put("swift_test_cases_executed", 3); result.put("accepted", true);
        } catch (Exception error) { result.put("failure", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); System.out.println("STOP " + result.get("failure")); }
        finally {
            try { session.verify(); result.put("source_and_tool_inputs_unchanged", true); }
            catch (Exception error) { result.put("source_and_tool_inputs_unchanged", false); result.put("input_failure", String.valueOf(error.getMessage())); result.put("accepted", false); }
            result.put("finished_at", Instant.now().toString()); writeJson(root.resolve("result.json"), result); writeJson(root.resolve("active-process.json"), obj("active", false, "all_owned_commands_terminal", true)); System.out.println(Json.stringify(result));
        }
        return Boolean.TRUE.equals(result.get("accepted")) ? 0 : 1;
    }
    private static Map<String, String> procEmitted(Path root) throws IOException {
        List<Path> paths = new ArrayList<>(); for (String base : List.of("build/SwiftExport", "build/SPMPackage", "products")) for (Path path : files(root.resolve(base))) {
            String name = path.getFileName().toString(); if (name.equals("Package.swift") || name.matches(".*\\.(swift|kt|h|a)$")) paths.add(path);
        }
        return pins(paths);
    }
}
