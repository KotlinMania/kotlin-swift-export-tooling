package io.github.kotlinmania.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;

/** Dependency-free regression checks for the isolated unsigned consumer contract. */
public final class NullableUnsignedConsumerTests {
    private static final String COMMAND = "nullable-unsigned-consumer";
    private static final String BASE = "d51da8510b78e94aa90b2def49ead640a39b386fb1c295821bc586b9b3e82f0b";
    private static final String HASH = "3518b5ba8077edaeb2b80aa37a81264d9e4ef6280c778fd4b50b33b85fef63e0";
    private static final String BRIDGE = "org/jetbrains/kotlin/sir/providers/impl/BridgeProvider/Bridge$AsNSNumber";
    private static int rejected;

    private NullableUnsignedConsumerTests() {}

    private static Map<String, String> validOptions() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : List.of("output", "component", "candidate-audit", "cache", "sdk", "sdk-audit", "gradle", "gradle-cache-seed", "konan-cache-seed")) result.put(name, "/fixture/" + name);
        result.put("configuration", "Debug");
        return result;
    }

    private static Map<String, Object> validManifest() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("component", "/fixture/candidate.jar");
        result.put("sha256", HASH);
        result.put("base_component_sha256", BASE);
        result.put("host_source_class_audit_passed", true);
        result.put("unselected_parent_bytes_identical", true);
        result.put("changed_classes", List.of(BRIDGE + ".class"));
        result.put("added_classes", List.of(BRIDGE + "$inKotlinSources$2.class"));
        result.put("removed_classes", List.of());
        result.put("input_pins", Map.of("/fixture/source.kt", HASH));
        return result;
    }

    private static void rejects(String name, Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { rejected++; return; }
        throw new AssertionError("Accepted invalid contract: " + name);
    }

    private static void rejectManifestValue(String key, Object value) {
        Map<String, Object> manifest = validManifest();
        manifest.put(key, value);
        rejects(key + "=" + value, () -> ProducerConsumers.validateNullableUnsignedManifest(manifest));
    }

    private static void optionContract() {
        ProducerConsumers.validateOptions(COMMAND, validOptions());
        Map<String, String> release = validOptions();
        release.put("configuration", "Release");
        ProducerConsumers.validateOptions(COMMAND, release);
        for (String name : validOptions().keySet()) {
            Map<String, String> missing = validOptions(); missing.remove(name);
            rejects("missing " + name, () -> ProducerConsumers.validateOptions(COMMAND, missing));
            for (String blank : List.of("", " ")) {
                Map<String, String> empty = validOptions(); empty.put(name, blank);
                rejects("blank " + name, () -> ProducerConsumers.validateOptions(COMMAND, empty));
            }
        }
        for (String name : List.of("distribution", "host-tests", "helper", "sdk-download-audit", "unknown")) {
            Map<String, String> extra = validOptions(); extra.put(name, "/fixture/extra");
            rejects("extra " + name, () -> ProducerConsumers.validateOptions(COMMAND, extra));
        }
        for (String configuration : List.of("debug", "release", "Profile")) {
            Map<String, String> wrong = validOptions(); wrong.put("configuration", configuration);
            rejects("configuration " + configuration, () -> ProducerConsumers.validateOptions(COMMAND, wrong));
        }
        rejects("wrong command", () -> ProducerConsumers.validateOptions("nullable-unsigned", validOptions()));
        rejects("duplicate CLI option", () -> ProducerSupport.options(new String[] {"--component", "/a", "--component", "/b"}));
    }

    private static void manifestContract() {
        ProducerConsumers.validateNullableUnsignedManifest(validManifest());
        for (String key : validManifest().keySet()) {
            Map<String, Object> missing = validManifest(); missing.remove(key);
            rejects("missing manifest " + key, () -> ProducerConsumers.validateNullableUnsignedManifest(missing));
            rejectManifestValue(key, null);
        }
        for (String flag : List.of("host_source_class_audit_passed", "unselected_parent_bytes_identical")) {
            for (Object value : List.of(false, "true", 1)) rejectManifestValue(flag, value);
        }
        for (String key : List.of("sha256", "base_component_sha256")) {
            for (Object value : List.of("", "not-a-hash", HASH.substring(1), HASH + "0", "g".repeat(64), 1)) rejectManifestValue(key, value);
        }
        rejectManifestValue("base_component_sha256", "0".repeat(64));
        rejectManifestValue("component", "candidate.jar");
        rejectManifestValue("component", "");
        rejectManifestValue("component", 1);
        for (String key : List.of("changed_classes", "added_classes")) {
            String expected = key.equals("changed_classes") ? BRIDGE + ".class" : BRIDGE + "$inKotlinSources$2.class";
            for (Object value : List.of(List.of(), List.of("Unexpected.class"), List.of(expected, expected), List.of(expected, "Unexpected.class"), expected, List.of(1))) rejectManifestValue(key, value);
        }
        rejectManifestValue("changed_classes", List.of(BRIDGE + "$inKotlinSources$2.class"));
        rejectManifestValue("added_classes", List.of(BRIDGE + ".class"));
        rejectManifestValue("removed_classes", List.of(BRIDGE + ".class"));
        rejectManifestValue("removed_classes", List.of(BRIDGE + ".class", BRIDGE + ".class"));
        rejectManifestValue("removed_classes", Map.of());
        rejectManifestValue("input_pins", Map.of());
        rejectManifestValue("input_pins", List.of());
        rejectManifestValue("input_pins", Map.of("relative.kt", HASH));
        rejectManifestValue("input_pins", Map.of("/fixture/source.kt", "invalid"));
        rejectManifestValue("input_pins", Map.of("/fixture/source.kt", 1));
        Map<String, Object> pins = new LinkedHashMap<>();
        pins.put("/fixture/valid.kt", HASH); pins.put("/fixture/invalid.kt", "invalid");
        rejectManifestValue("input_pins", pins);
    }

    private static ProducerConsumers.Frontends checkFrontends(String commands) {
        return ProducerConsumers.frontends(commands, Set.of("NullableUnsignedTests"), Set.of("NullableUnsignedTests", "NullableUnsignedProof"), false, false);
    }

    private static void frontendContract() {
        String dependency = "/tool/swift-frontend -module-name NullableUnsignedProof -warnings-as-errors -target arm64-apple-macosx15.0 -swift-version 5";
        String authored = "/tool/swift-frontend -module-name NullableUnsignedTests -warnings-as-errors -target arm64-apple-macosx15.0 -swift-version 6";
        checkFrontends(dependency + "\n" + authored);
        checkFrontends(dependency.replace("-target arm64-apple-macosx15.0", "-target arm64-apple-macosx12.0 -target arm64-apple-macosx15.0") + "\n" + authored);
        checkFrontends(dependency.replace("-swift-version 5", "-swift-version 6 -swift-version 5") + "\n" + authored);
        checkFrontends(dependency.replace("-target arm64-apple-macosx15.0", "-target \"arm64-apple-macosx15.0\"") + "\n" + authored);
        for (String suffix : List.of(" -target arm64-apple-macosx12.0", " -target=arm64-apple-macosx12.0", " -target", " -swift-version 6", " -swift-version=6", " -swift-version 5garbage", " -swift-version")) {
            rejects("later dependency override " + suffix, () -> checkFrontends(dependency + suffix + "\n" + authored));
        }
        for (String suffix : List.of(" -target arm64-apple-macosx12.0", " -swift-version 5")) {
            rejects("later authored override " + suffix, () -> checkFrontends(dependency + "\n" + authored + suffix));
        }
        rejects("missing frontend proof", () -> checkFrontends("builtin-SwiftDriver NullableUnsignedTests"));
        rejects("missing dependency frontend", () -> checkFrontends(authored));
        rejects("missing Werror", () -> checkFrontends(dependency.replace("-warnings-as-errors", "") + "\n" + authored));
        rejects("missing target", () -> checkFrontends(dependency.replace("-target arm64-apple-macosx15.0", "") + "\n" + authored));
        rejects("missing mode", () -> checkFrontends(dependency.replace("-swift-version 5", "") + "\n" + authored));
    }

    private static void packagePolicyContract() {
        String before = "// swift-tools-version: 5.9\nimport PackageDescription\nlet package = Package(name: \"Proof\", products: [], targets: [])\n";
        String after = SwiftManifestEnforcer.enforce(before);
        if (!after.contains("platforms: [.macOS(\"15.0\")]") || !after.startsWith("// swift-tools-version: 5.9")) throw new AssertionError("Missing deployment or Swift 5 policy");
        if (!SwiftManifestEnforcer.enforce(after).equals(after)) throw new AssertionError("Manifest policy is not idempotent");
        for (String configuration : List.of("Debug", "Release")) {
            String policy = ProducerConsumers.nullableUnsignedManifestPolicy(Path.of("/fixture/output"), configuration, "/jdk/bin/java", "/fixture/classes");
            if (!policy.contains("macosArm64" + configuration + "GenerateSPMPackage") || !policy.contains("/macosArm64/" + configuration + "/Package.swift")
                || !policy.contains("SwiftManifestEnforcer") || !policy.contains("t.doLast") || !policy.contains("process.waitFor() != 0")) throw new AssertionError("Missing generation-time fail-closed manifest policy");
        }
        rejects("unsupported manifest configuration", () -> ProducerConsumers.nullableUnsignedManifestPolicy(Path.of("/fixture/output"), "Other", "/jdk/bin/java", "/fixture/classes"));
    }

    public static void main(String[] arguments) {
        if (arguments.length != 0) throw new IllegalArgumentException("No arguments expected");
        optionContract();
        manifestContract();
        frontendContract();
        packagePolicyContract();
        if (rejected < 90) throw new AssertionError("Regression cases did not execute: " + rejected);
        System.out.println("PASS nullable unsigned consumer option, manifest, frontend and package policy contracts: " + rejected + " invalid cases rejected");
    }
}
