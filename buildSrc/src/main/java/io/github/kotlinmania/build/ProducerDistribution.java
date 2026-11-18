package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.ProducerSupport.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Deterministic release assembly, immutable validation, SDK preparation and publication gates. */
final class ProducerDistribution {
    static final String SIGNER = "1B07CFB6CD172EF8B1E078A7BC3EFBDB5650D775";
    static final String SDK_URL = "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-native-prebuilt/2.4.20/kotlin-native-prebuilt-2.4.20-macos-aarch64.tar.gz";
    static final String SDK_SHA = "b70d4fd2855fdb054fc9604beaa7de83c18d786454ae948de72a226e81f07dc8";
    private final ProducerSupport c;
    ProducerDistribution(ProducerSupport context) { c = context; }

    static String prefix(Map<String, Object> lock) { return lock.get("artifact") + "-" + lock.get("version"); }
    static String centralBase(Map<String, Object> lock) { return "https://repo.maven.apache.org/maven2/io/github/kotlinmania/swift-export-embeddable/" + lock.get("version") + "/"; }
    private static final List<String> REPAIRS = List.of("array_repair", "bytearray_repair", "collection_repair", "km5_repair");
    private Map<String, byte[]> common(Map<String, Object> lock) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        for (String name : m(lock.get("licenses")).keySet()) result.put(name, Files.readAllBytes(safe(c.repo, name)));
        result.put("inputs.lock.json", Files.readAllBytes(c.p("inputs.lock.json")));
        for (String repair : REPAIRS) if (lock.containsKey(repair)) result.put(s(lock.get(repair)), Files.readAllBytes(safe(c.repo, s(lock.get(repair)))));
        return result;
    }
    Set<String> helperNames(Map<String, Object> lock) {
        Set<String> names = new TreeSet<>(m(lock.get("licenses")).keySet());
        names.addAll(List.of("inputs.lock.json", "README.md", "patches/KotlinBase.h.patch"));
        if (!lock.containsKey("km5_repair")) names.add("scripts/prepare_native_sdk.py");
        for (String repair : REPAIRS) if (lock.containsKey(repair)) names.add(s(lock.get(repair)));
        return names;
    }

    /** The old helper is an immutable release input. Its source bytes are never extracted or executed. */
    Path frozenHelper(Map<String, Object> lock, Path supplied) throws IOException {
        String name = prefix(lock) + "-native-sdk-helper.zip", expected = s(m(c.frozen().get("files")).get(name));
        Path helper = supplied;
        if (helper == null) {
            for (Path candidate : List.of(c.p("build/immutable-inputs/" + name), c.p("build/distribution-a/" + name), c.p("build/central-retrieval/" + name))) {
                if (Files.isRegularFile(candidate)) { helper = candidate; break; }
            }
            if (helper == null) { helper = c.p("build/immutable-inputs/" + name); download(centralBase(lock) + name, helper, 120_000); }
        }
        check(!Files.isSymbolicLink(helper) && sha(helper).equals(expected), "Frozen published helper checksum mismatch");
        Map<String, byte[]> entries = entries(helper);
        check(entries.keySet().equals(helperNames(lock)), "Frozen helper closure changed");
        check(Arrays.equals(entries.get("inputs.lock.json"), Files.readAllBytes(c.p("inputs.lock.json"))), "Frozen helper input lock differs");
        return helper;
    }

    Map<String, Object> prepare(Path build, Path output, Path helperInput) throws Exception {
        Map<String, Object> lock = c.lock(), result = m(Json.read(build.resolve("build-result.json")));
        Path main = safe(build, s(result.get("artifact")));
        check(sha(main).equals(result.get("sha256")) && !Files.exists(output), "Unverified build or existing distribution directory");
        boolean isKm5 = lock.containsKey("km5_repair");
        Path helper = isKm5 ? null : frozenHelper(lock, helperInput);
        Files.createDirectories(output); String prefix = prefix(lock);
        Files.copy(main, output.resolve(prefix + ".jar"));
        Map<String, byte[]> common = common(lock), sources = new TreeMap<>(common);
        for (Map<String, Object> row : rows(lock.get("sources"))) sources.put(s(row.get("path")), Files.readAllBytes(safe(build.resolve("candidate/sources"), s(row.get("name")))));
        for (Path path : files(c.p("patches"))) if (path.toString().endsWith(".patch")) sources.put(c.repo.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        List<String> repairDirs = List.of("producer/arrays", "producer/byte-arrays", "producer/collections", "producer/km5");
        for (int i = 0; i < REPAIRS.size(); i++) if (lock.containsKey(REPAIRS.get(i))) {
            Path directory = c.p(repairDirs.get(i));
            try (var paths = Files.list(directory)) { for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) sources.put(c.repo.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path)); }
        }
        if (isKm5) {
            for (String extraDir : List.of("producer/protocol-spi-defaults", "producer/nullable-unsigned", "producer/deprecation-bridge", "producer/reverse-collection-cast")) {
                Path directory = c.p(extraDir);
                if (Files.isDirectory(directory)) try (var paths = Files.list(directory)) { for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) sources.put(c.repo.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path)); }
            }
        }
        archive(output.resolve(prefix + "-sources.jar"), sources);
        byte[] releasedReadme = isKm5 ? Files.readAllBytes(c.p("README.md")) : entry(helper, "README.md");
        String document = "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>KotlinMania Swift export tooling</title><body><pre>" + html(new String(releasedReadme, StandardCharsets.UTF_8)) + "</pre></body></html>\n";
        Map<String, byte[]> docs = new TreeMap<>(common); docs.put("index.html", document.getBytes(StandardCharsets.UTF_8));
        archive(output.resolve(prefix + "-javadoc.jar"), docs);
        Map<String, byte[]> expectedHelper = new TreeMap<>(common);
        expectedHelper.put("README.md", releasedReadme);
        expectedHelper.put("patches/KotlinBase.h.patch", Files.readAllBytes(c.p("patches/KotlinBase.h.patch")));
        if (!isKm5) {
            expectedHelper.put("scripts/prepare_native_sdk.py", entry(helper, "scripts/prepare_native_sdk.py"));
            Map<String, byte[]> publishedHelper = entries(helper);
            check(publishedHelper.keySet().equals(expectedHelper.keySet()) && expectedHelper.entrySet().stream().allMatch(row -> Arrays.equals(row.getValue(), publishedHelper.get(row.getKey()))), "Published helper data differs from current locked sources");
        }
        Path helperArchive = output.resolve(prefix + "-native-sdk-helper.zip");
        archive(helperArchive, expectedHelper);
        if (!isKm5) check(sha(helperArchive).equals(sha(helper)), "Helper format differs from immutable published bytes");
        else if (helperInput != null) check(sha(helperArchive).equals(sha(helperInput)), "Helper format differs from supplied archive");
        write(output.resolve(prefix + ".pom"), pom(lock));
        writeJson(output.resolve(prefix + "-sbom.json"), sbom(lock));
        Map<String, String> hashes = new TreeMap<>();
        for (Path path : files(output)) hashes.put(path.getFileName().toString(), sha(path));
        Map<String, Object> receipt = obj("files", hashes, "main_component_sha256", result.get("sha256"), "native_header_sha256", m(lock.get("native_header")).get("candidate_sha256"), "rust_sources_included", false, "native_sdk_binaries_included", false);
        writeJson(output.resolve("distribution.json"), receipt);
        List<Path> javaSources = files(c.p("buildSrc/src/main/java"));
        writeJson(output.resolveSibling(output.getFileName() + ".java-provenance.json"), obj("entry_point", "io.github.kotlinmania.build.ProducerCli distribution", "java_source_sha256", pins(javaSources), "current_readme_sha256", sha(c.p("README.md")), "immutable_release_readme_sha256", digest(releasedReadme), "immutable_helper_archive_sha256", sha(isKm5 ? helperArchive : helper), "archived_interpreter_source_executed", false, "release_files", hashes));
        return receipt;
    }
    private static String html(String text) { return xml(text).replace("\"", "&quot;").replace("'", "&#x27;"); }
    private static String xml(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static void element(StringBuilder text, int depth, String tag, Object value) { text.append("  ".repeat(depth)).append('<').append(tag).append('>').append(xml(String.valueOf(value))).append("</").append(tag).append(">\n"); }
    static String pom(Map<String, Object> lock) {
        StringBuilder text = new StringBuilder("<?xml version='1.0' encoding='utf-8'?>\n<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        for (Map.Entry<String, Object> row : obj("modelVersion", "4.0.0", "groupId", lock.get("group"), "artifactId", lock.get("artifact"), "version", lock.get("version"), "packaging", "jar", "name", "KotlinMania Swift Export Embeddable", "description", "Version-pinned Native-to-Swift exporter repairs and coherent isolated SDK header preparation", "url", "https://github.com/KotlinMania/kotlin-swift-export-tooling").entrySet()) element(text, 1, row.getKey(), row.getValue());
        text.append("  <licenses>\n    <license>\n");
        for (Map.Entry<String, Object> row : obj("name", "Apache License, Version 2.0", "url", "https://www.apache.org/licenses/LICENSE-2.0.txt", "distribution", "repo").entrySet()) element(text, 3, row.getKey(), row.getValue());
        text.append("    </license>\n  </licenses>\n  <developers>\n    <developer>\n");
        for (Map.Entry<String, Object> row : obj("id", "kotlinmania", "name", "KotlinMania contributors", "organization", "KotlinMania", "organizationUrl", "https://github.com/KotlinMania").entrySet()) element(text, 3, row.getKey(), row.getValue());
        text.append("    </developer>\n  </developers>\n  <scm>\n");
        for (Map.Entry<String, Object> row : obj("url", "https://github.com/KotlinMania/kotlin-swift-export-tooling", "connection", "scm:git:https://github.com/KotlinMania/kotlin-swift-export-tooling.git", "tag", "v" + lock.get("version")).entrySet()) element(text, 2, row.getKey(), row.getValue());
        text.append("  </scm>\n  <dependencies>\n");
        Set<String> direct = Set.of("checker-qual", "error_prone_annotations", "opentelemetry-api", "caffeine", "kotlin-stdlib", "kotlin-compiler-embeddable", "kotlinx-serialization-core-jvm");
        for (Map<String, Object> row : rows(lock.get("tools"))) if ("worker".equals(row.get("scope")) && (direct.contains(row.get("artifact")) || "1.10.2-intellij-1".equals(row.get("version")))) {
            text.append("    <dependency>\n");
            for (Map.Entry<String, Object> item : obj("groupId", row.get("group"), "artifactId", row.get("artifact"), "version", row.get("version"), "scope", "runtime").entrySet()) element(text, 3, item.getKey(), item.getValue());
            text.append("    </dependency>\n");
        }
        return text.append("  </dependencies>\n</project>\n").toString();
    }
    static Map<String, Object> sbom(Map<String, Object> lock) {
        return obj("bomFormat", "CycloneDX", "specVersion", "1.6", "version", 1,
                "metadata", obj("component", obj("type", "library", "group", lock.get("group"), "name", lock.get("artifact"), "version", lock.get("version"), "licenses", List.of(obj("license", obj("id", "Apache-2.0"))))),
                "components", rows(lock.get("tools")).stream().map(row -> obj("type", "library", "group", row.get("group"), "name", row.get("artifact"), "version", row.get("version"), "hashes", List.of(obj("alg", "SHA-256", "content", row.get("sha256"))), "properties", List.of(obj("name", "kotlinmania:input-scope", "value", row.get("scope"))))).toList());
    }

    void validate(Path directory, Path comparison) throws Exception {
        Map<String, Object> lock = c.lock(), receipt = m(Json.read(directory.resolve("distribution.json")));
        check(receipt.equals(c.frozen()), "Distribution differs from the frozen real-consumer candidate");
        for (Map.Entry<String, Object> row : m(receipt.get("files")).entrySet()) {
            Path path = safe(directory, row.getKey()); check(sha(path).equals(row.getValue()), "Release checksum mismatch: " + row.getKey());
            if (comparison != null) check(Files.mismatch(path, safe(comparison, row.getKey())) == -1, "Nonreproducible: " + row.getKey());
            if (!(row.getKey().endsWith(".jar") || row.getKey().endsWith(".zip"))) continue;
            try (ZipFile zip = ZipFile.builder().setPath(path).get()) {
                List<ZipArchiveEntry> entries = Collections.list(zip.getEntries()); Set<String> names = new TreeSet<>();
                for (ZipArchiveEntry entry : entries) {
                    check(names.add(entry.getName()), "Duplicate archive entries"); safe(directory, entry.getName());
                    check(!List.of(".rs", ".rlib", ".dylib", ".so", ".dll", ".a").stream().anyMatch(entry.getName()::endsWith) && !Path.of(entry.getName()).getFileName().toString().startsWith("Cargo."), "Rust or Native binary bundled");
                    check(!entry.isUnixSymlink(), "Archive symlink");
                }
                String prefix = row.getKey().equals(prefix(lock) + ".jar") ? "META-INF/" : "";
                check(names.contains(prefix + "LICENSE") && names.contains(prefix + "NOTICE"), "Missing release licenses");
                check(Arrays.equals(entry(path, prefix + "LICENSE"), Files.readAllBytes(c.p("LICENSE"))), "Release license differs");
                if (row.getKey().endsWith("-native-sdk-helper.zip")) check(names.equals(helperNames(lock)) && names.stream().noneMatch(name -> name.endsWith(".class")), "Helper closure changed");
            }
        }
        Document pom = document(directory.resolve(prefix(lock) + ".pom"));
        check(text(pom.getDocumentElement(), "groupId").equals("io.github.kotlinmania"), "POM group changed");
        check(text(pom.getDocumentElement(), "version").equals(lock.get("version")), "POM version changed");
        Element licenses = child(pom.getDocumentElement(), "licenses"), license = child(licenses, "license");
        check(text(license, "name").equals("Apache License, Version 2.0"), "POM license changed");
        System.out.println("Release contents, licensing and reproduction verified");
    }
    static Document document(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException problem) throws org.xml.sax.SAXException { throw problem; }
            @Override public void fatalError(org.xml.sax.SAXParseException problem) throws org.xml.sax.SAXException { throw problem; }
        });
        return builder.parse(path.toFile());
    }
    static Element child(Element parent, String name) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element element && (name.equals(element.getLocalName()) || name.equals(element.getNodeName()))) return element;
        throw new IllegalArgumentException("Missing XML element " + name);
    }
    static String text(Element parent, String name) { return child(parent, name).getTextContent(); }
    void stage(Path distribution, Path repository) throws Exception {
        validate(distribution, null); Map<String, Object> lock = c.lock();
        Path target = repository.resolve("io/github/kotlinmania/swift-export-embeddable/" + lock.get("version"));
        check(!Files.exists(target), "Resolution repository must be new"); Files.createDirectories(target);
        for (String suffix : List.of(".pom", ".jar")) Files.copy(distribution.resolve(prefix(lock) + suffix), target.resolve(prefix(lock) + suffix));
    }
    void downloadSdk(Path output) throws IOException {
        check(!Files.exists(output), "New SDK download directory required"); Files.createDirectories(output);
        Path archive = output.resolve("official-sdk.tar.gz"); download(SDK_URL, archive, 120_000); check(sha(archive).equals(SDK_SHA), "Official archive checksum mismatch");
        Path extracted = output.resolve("extracted"); SwiftToolingPreparer.extractSdkArchive(archive, extracted);
        try (var paths = Files.list(extracted)) { for (Path path : paths.toList()) Files.move(path, output.resolve(path.getFileName())); } Files.delete(extracted);
        writeJson(output.resolve("download.json"), obj("url", SDK_URL, "archive_sha256", sha(archive), "identity_validation", "Java SDK preparer pins compiler and original header before use"));
    }
    void prepareSdk(Path source, Path destination, Path receipt, Path helper) throws Exception {
        Map<String, Object> lock = c.lock(); Path patch = c.p("patches/KotlinBase.h.patch");
        Map<String, Object> provenance = obj("entry_point", "io.github.kotlinmania.build.ProducerCli prepare-native-sdk", "java_source_sha256", pins(files(c.p("buildSrc/src/main/java"))), "archived_interpreter_source_executed", false);
        if (helper != null) {
            helper = frozenHelper(lock, helper);
            check(Arrays.equals(entry(helper, "patches/KotlinBase.h.patch"), Files.readAllBytes(patch)), "Published header patch differs");
            provenance.put("immutable_helper_archive_sha256", sha(helper));
            if (!lock.containsKey("km5_repair")) provenance.put("immutable_archived_helper_source_sha256", digest(entry(helper, "scripts/prepare_native_sdk.py")));
        }
        writeJson(receipt, SwiftToolingPreparer.prepareSdk(source, destination, lock, patch));
        writeJson(receipt.resolveSibling(receipt.getFileName() + ".java-provenance.json"), provenance);
    }

    Map<String, Object> signatures(Path directory, Set<String> names, Path output) throws Exception {
        check(!Files.exists(output), "New signature verification directory required"); Files.createDirectories(output);
        Path keyring = output.resolve("public-key.gpg");
        c.run(List.of("gpg", "--no-options", "--batch", "--dearmor", "--output", keyring.toString(), c.p("verification/signing-public-key.asc").toString()), output.resolve("public-key.log"));
        Map<String, Object> receipts = new TreeMap<>();
        for (String name : names) {
            Result status = c.capture(List.of("gpgv", "--keyring", keyring.toAbsolutePath().toString(), "--status-fd", "1", safe(directory, name + ".asc").toString(), safe(directory, name).toString()), c.repo, Map.of());
            check(status.exit() == 0, "Signature verification failed: " + name); verifySigner(status.output());
            write(output.resolve(name + ".verification.txt"), status.output());
            receipts.put(name, obj("fingerprint", SIGNER, "signature_sha256", sha(directory.resolve(name + ".asc"))));
        }
        return receipts;
    }
    static void verifySigner(String status) {
        List<String> valid = status.lines().filter(line -> line.startsWith("[GNUPG:] VALIDSIG ")).map(line -> line.split("\\s+")[2]).toList();
        check(valid.equals(List.of(SIGNER)), "Unexpected signing identity");
    }
    void verifyPublished(Path output) throws Exception {
        check(!Files.exists(output), "New retrieval directory required"); Files.createDirectories(output);
        Map<String, Object> frozen = c.frozen(), files = m(frozen.get("files")); String base = centralBase(c.lock()); Map<String, String> signatures = new TreeMap<>();
        for (Map.Entry<String, Object> row : files.entrySet()) {
            String name = row.getKey();
            for (String suffix : List.of("", ".asc", ".sha256")) download(base + name + suffix, safe(output, name + suffix), 60_000);
            check(sha(output.resolve(name)).equals(row.getValue()), "Published artifact checksum mismatch");
            check(Files.readString(output.resolve(name + ".sha256")).strip().equals(row.getValue()), "Published checksum sidecar mismatch");
            check(Files.readString(output.resolve(name + ".asc")).contains("BEGIN PGP SIGNATURE"), "Invalid detached signature format");
            signatures.put(name, sha(output.resolve(name + ".asc")));
        }
        writeJson(output.resolve("distribution.json"), frozen); validate(output, null);
        Map<String, Object> verified = signatures(output, files.keySet(), output.resolve("signature-verification"));
        writeJson(output.resolve("retrieval.json"), obj("base_url", base, "files", files, "signatures_sha256", signatures, "signature_crypto_verification", verified));
        System.out.println("Published files independently retrieved, byte-verified and cryptographically signature-verified");
    }
    static void validateCi(List<Map<String, Object>> runs, String head, String sourceTree, Map<String, String> trees) {
        check(runs.size() == 2, "Both required CI runs must be supplied");
        for (Map<String, Object> run : runs) {
            check("success".equals(run.get("conclusion")) && ".github/workflows/ci.yml".equals(run.get("path")), "Required CI did not pass");
            check(sourceTree.equals(trees.get(s(run.get("head_sha")))), "CI source differs");
        }
        check(head.equals(runs.get(1).get("head_sha")) && "main".equals(runs.get(1).get("head_branch")), "Exact-main CI required");
    }
    void publish(Map<String, String> args) throws Exception {
        Path distribution = path(args, "distribution"), output = path(args, "output");
        check(c.capture(List.of("git", "status", "--porcelain")).isEmpty(), "Clean source required");
        String head = c.capture(List.of("git", "rev-parse", "HEAD")).strip(), sourceTree = c.capture(List.of("git", "rev-parse", head + "^{tree}")).strip();
        List<Map<String, Object>> runs = new ArrayList<>(); Map<String, String> trees = new HashMap<>();
        for (String key : List.of("pr-ci-run", "main-ci-run")) {
            Map<String, Object> run = m(Json.parse(c.capture(List.of("gh", "api", "repos/KotlinMania/kotlin-swift-export-tooling/actions/runs/" + required(args, key))))); runs.add(run);
            String revision = s(run.get("head_sha")); check(revision.matches("[0-9a-fA-F]{40}"), "Invalid CI revision");
            trees.put(revision, c.capture(List.of("git", "rev-parse", revision + "^{tree}")).strip());
        }
        validateCi(runs, head, sourceTree, trees); validate(distribution, null);
        Map<String, Object> actual = m(Json.read(distribution.resolve("distribution.json"))), lock = c.lock(); check(actual.equals(c.frozen()), "Release files differ from independently tested candidate");
        String prefix = prefix(lock); HttpURLConnection probe = (HttpURLConnection) URI.create(centralBase(lock) + prefix + ".pom").toURL().openConnection();
        probe.setConnectTimeout(30_000); probe.setReadTimeout(30_000);
        try { int status = probe.getResponseCode(); check(status == 404, status == 200 ? "Immutable version is already published; do not overwrite" : "Publication existence check failed: HTTP " + status); } finally { probe.disconnect(); }
        Map<String, String> environment = new HashMap<>(System.getenv());
        if (args.containsKey("existing-secrets-dir")) {
            Path secrets = path(args, "existing-secrets-dir"); Element credentials = document(secrets.resolve("creds.xml")).getDocumentElement();
            environment.put("MAVEN_CENTRAL_USERNAME", text(credentials, "username").strip()); environment.put("MAVEN_CENTRAL_PASSWORD", text(credentials, "password").strip());
            environment.put("SIGNING_KEY", Files.readString(secrets.resolve("private.maven.asc"))); environment.put("SIGNING_KEY_PASSWORD", Files.readString(secrets.resolve("passphrase.txt")).replaceAll("[\\r\\n]+$", ""));
        }
        for (String key : List.of("MAVEN_CENTRAL_USERNAME", "MAVEN_CENTRAL_PASSWORD", "SIGNING_KEY", "SIGNING_KEY_PASSWORD")) check(environment.containsKey(key) && !environment.get(key).isEmpty(), "Existing credential missing: " + key);
        check(!Files.exists(output), "New publication output directory required"); Files.createDirectories(output);
        String gradleCmd = Files.isExecutable(c.p("gradlew")) ? c.p("gradlew").toString() : "gradle";
        c.run(List.of(gradleCmd, "signRelease", "--no-daemon", "--max-workers=2", "--no-configuration-cache", "--console=plain", "-PdistributionDirectory=" + distribution), output.resolve("signing.log"), c.repo, environment);
        Map<String, Object> verified = signatures(distribution, m(actual.get("files")).keySet(), output.resolve("signature-verification"));
        Path staging = output.resolve("staging"), layout = staging.resolve("io/github/kotlinmania/swift-export-embeddable/" + lock.get("version")); Files.createDirectories(layout);
        for (String name : m(actual.get("files")).keySet()) for (String suffix : List.of("", ".asc")) {
            Path source = safe(distribution, name + suffix); check(Files.isRegularFile(source) && Files.size(source) > 0, "Missing artifact or signature"); Files.copy(source, layout.resolve(source.getFileName()));
        }
        for (Path file : files(layout)) for (String algorithm : List.of("md5", "sha1", "sha256", "sha512")) write(file.resolveSibling(file.getFileName() + "." + algorithm), digest(algorithm, Files.readAllBytes(file)));
        Path bundle = output.resolve(prefix + "-bundle.zip");
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(bundle))) {
            for (Path file : files(staging)) { archive.putNextEntry(new ZipEntry(staging.relativize(file).toString().replace('\\', '/'))); Files.copy(file, archive); archive.closeEntry(); }
        }
        String token = Base64.getEncoder().encodeToString((environment.get("MAVEN_CENTRAL_USERNAME") + ":" + environment.get("MAVEN_CENTRAL_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        String boundary = "KotlinMania" + UUID.randomUUID().toString().replace("-", "");
        byte[] start = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"" + bundle.getFileName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8), end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection request = (HttpURLConnection) URI.create("https://central.sonatype.com/api/v1/publisher/upload?name=" + URLEncoder.encode(prefix, StandardCharsets.UTF_8) + "&publishingType=AUTOMATIC").toURL().openConnection();
        request.setConnectTimeout(120_000); request.setReadTimeout(120_000); request.setDoOutput(true); request.setRequestMethod("POST");
        request.setRequestProperty("Authorization", "Bearer " + token); request.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary); request.setFixedLengthStreamingMode(start.length + Files.size(bundle) + end.length);
        String deployment;
        try {
            try (var body = request.getOutputStream()) { body.write(start); Files.copy(bundle, body); body.write(end); }
            check(request.getResponseCode() >= 200 && request.getResponseCode() < 300, "Central upload failed: HTTP " + request.getResponseCode());
            try (InputStream response = request.getInputStream()) { deployment = new String(response.readAllBytes(), StandardCharsets.UTF_8).strip(); }
        } finally { request.disconnect(); }
        writeJson(output.resolve("upload.json"), obj("deployment_id", deployment, "source_head", head, "required_ci", runs.stream().map(row -> row.get("html_url")).toList(), "files", actual.get("files"), "signatures", verified, "bundle_sha256", sha(bundle), "status", "uploaded_not_yet_independently_verified"));
        System.out.println("Central upload accepted; deployment " + deployment + ". Verify status and independently retrieve artifacts before adoption.");
    }
}
