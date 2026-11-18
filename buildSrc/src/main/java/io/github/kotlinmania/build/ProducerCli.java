package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.ProducerSupport.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repository-local command entry point used by Gradle and the producer launcher. */
public final class ProducerCli {
    private ProducerCli() {}
    public static int run(String... arguments) throws Exception {
        List<String> args = new ArrayList<>(List.of(arguments));
        Path root = Path.of("").toAbsolutePath().normalize();
        if (args.size() >= 2 && args.getFirst().equals("--root")) { root = Path.of(args.get(1)).toAbsolutePath().normalize(); args.subList(0, 2).clear(); }
        check(!args.isEmpty(), "Expected producer command; see tools/producer help");
        String command = args.removeFirst();
        if (command.equals("help") || command.equals("--help")) {
            System.out.println("Commands: build, build-collection-candidate, build-native-forwarding-candidate, build-protocol-spi-defaults-candidate, build-native-writable-type-info-candidate, distribution, check-distribution, stage-resolution, download-native-sdk, prepare-native-sdk, audit-closure, consumer, array-consumer, bytearray-consumer, collection-consumer, nullable-unsigned-consumer, iterator-consumer, reverse-import-consumer, verify-published, publish.\nUse the original named arguments with hyphenated command names; --helper is the immutable published helper ZIP. All generated paths belong under this repository's build/. tools/producer check runs the Java regression checks.");
            return 0;
        }
        int booleanFlag = args.indexOf("--host-tests");
        if (booleanFlag >= 0 && (booleanFlag + 1 == args.size() || args.get(booleanFlag + 1).startsWith("--"))) args.add(booleanFlag + 1, "true");
        Map<String, String> options = options(args.toArray(String[]::new));
        Set<String> allowed = switch (command) {
            case "build" -> Set.of("output", "cache", "seeds");
            case "build-collection-candidate" -> Set.of("output", "cache", "component", "sources");
            case "build-native-forwarding-candidate" -> Set.of("output", "cache", "component", "native-home", "seeds");
            case "build-protocol-spi-defaults-candidate" -> Set.of("output", "cache", "component", "native-home", "seeds");
            case "build-native-writable-type-info-candidate" -> Set.of("output", "native-home", "source");
            case "distribution" -> Set.of("build", "output", "helper-archive");
            case "check-distribution" -> Set.of("distribution", "comparison");
            case "stage-resolution" -> Set.of("distribution", "repository");
            case "download-native-sdk", "verify-published" -> Set.of("output");
            case "prepare-native-sdk" -> Set.of("source", "destination", "receipt", "helper");
            case "audit-closure" -> Set.of("jar", "worker", "entries", "jdk", "output");
            case "publish" -> Set.of("distribution", "output", "pr-ci-run", "main-ci-run", "existing-secrets-dir");
            case "nullable-unsigned-consumer" -> Set.of("output", "component", "candidate-audit", "cache", "sdk", "sdk-audit", "gradle", "gradle-cache-seed", "konan-cache-seed", "configuration");
            case "consumer", "array-consumer", "bytearray-consumer", "collection-consumer", "iterator-consumer", "reverse-import-consumer" -> Set.of("consumer", "output", "distribution", "cache", "sdk", "sdk-audit", "sdk-download-audit", "helper", "gradle", "gradle-cache-seed", "konan-cache-seed", "configuration", "candidate-audit", "host-tests", "package", "archive");
            default -> throw new IllegalArgumentException("Unknown producer command: " + command);
        };
        check(allowed.containsAll(options.keySet()), "Unsupported options for " + command + ": " + difference(options.keySet(), allowed));
        check(Files.isRegularFile(root.resolve("inputs.lock.json")), "Producer repository inputs.lock.json is missing");
        for (String name : List.of("output", "cache", "repository", "destination", "receipt")) if (options.containsKey(name)) owned(root, path(options, name));
        ProducerSupport context = new ProducerSupport(root); ProducerDistribution distribution = new ProducerDistribution(context);
        switch (command) {
            case "build" -> new ProducerBuild(context).build(path(options, "output"), path(options, "cache"), options.containsKey("seeds") ? m(Json.read(path(options, "seeds"))) : Map.of());
            case "build-collection-candidate" -> new ProducerBuild(context).collectionCandidate(path(options, "output"), path(options, "cache"), path(options, "component"), path(options, "sources"));
            case "build-native-forwarding-candidate" -> new ProducerNativeForwarding(context).build(path(options, "output"), path(options, "cache"), path(options, "component"), path(options, "native-home"), options.containsKey("seeds") ? m(Json.read(path(options, "seeds"))) : Map.of());
            case "build-protocol-spi-defaults-candidate" -> new ProducerProtocolSpiDefaults(context).build(path(options, "output"), path(options, "cache"), path(options, "component"), path(options, "native-home"), options.containsKey("seeds") ? m(Json.read(path(options, "seeds"))) : Map.of());
            case "build-native-writable-type-info-candidate" -> new ProducerNativeWritableTypeInfo(context).build(path(options, "output"), path(options, "native-home"), path(options, "source"));
            case "distribution" -> System.out.println(json(distribution.prepare(path(options, "build"), path(options, "output"), options.containsKey("helper-archive") ? path(options, "helper-archive") : null)));
            case "check-distribution" -> distribution.validate(path(options, "distribution"), options.containsKey("comparison") ? path(options, "comparison") : null);
            case "stage-resolution" -> distribution.stage(path(options, "distribution"), path(options, "repository"));
            case "download-native-sdk" -> distribution.downloadSdk(path(options, "output"));
            case "prepare-native-sdk" -> distribution.prepareSdk(path(options, "source"), path(options, "destination"), path(options, "receipt"), options.containsKey("helper") ? path(options, "helper") : null);
            case "audit-closure" -> ProducerClosureAudit.audit(context, path(options, "jar"), path(options, "worker"), path(options, "entries"), path(options, "jdk"), path(options, "output"));
            case "consumer", "array-consumer", "bytearray-consumer", "collection-consumer", "iterator-consumer", "reverse-import-consumer", "nullable-unsigned-consumer" -> { return new ProducerConsumers(context).run(command, options); }
            case "verify-published" -> distribution.verifyPublished(path(options, "output"));
            case "publish" -> distribution.publish(options);
            default -> throw new IllegalArgumentException("Unknown producer command: " + command);
        }
        return 0;
    }
    static void owned(Path root, Path path) throws Exception {
        Path base = root.resolve("build").toAbsolutePath().normalize(), target = path.toAbsolutePath().normalize();
        check(target.startsWith(base) && !target.equals(base), "Generated paths must be inside the producer repository's build/: " + target);
        Path current = target;
        while (current != null && current.startsWith(base)) { check(!Files.isSymbolicLink(current), "Generated path must not traverse a symlink: " + current); current = current.getParent(); }
    }
    public static void main(String[] args) throws Exception { int result = run(args); if (result != 0) System.exit(result); }
}
