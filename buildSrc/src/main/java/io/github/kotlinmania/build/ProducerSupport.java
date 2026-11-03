package io.github.kotlinmania.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Shared I/O and byte-preserving primitives for the Java producer commands. */
final class ProducerSupport {
    final Path repo;
    ProducerSupport(Path repo) { this.repo = repo.toAbsolutePath().normalize(); }

    static void check(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    static Map<String, Object> m(Object value) {
        check(value instanceof Map<?, ?>, "Expected JSON object");
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    static List<Object> l(Object value) {
        check(value instanceof List<?>, "Expected JSON array");
        return new ArrayList<>((List<?>) value);
    }
    static String s(Object value) { check(value instanceof String, "Expected string: " + value); return (String) value; }
    static List<String> strings(Object value) { return l(value).stream().map(ProducerSupport::s).toList(); }
    static List<Map<String, Object>> rows(Object value) { return l(value).stream().map(ProducerSupport::m).toList(); }
    static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    Map<String, Object> lock() throws IOException { return m(Json.read(repo.resolve("inputs.lock.json"))); }
    Map<String, Object> frozen() throws IOException { return m(Json.read(repo.resolve("verification/release-artifacts.json"))); }
    Path p(String relative) { return repo.resolve(relative); }
    static String sha(Path path) throws IOException { return SwiftToolingPreparer.sha(path); }
    static String digest(byte[] data) { return digest("SHA-256", data); }
    static String digest(String algorithm, byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void write(Path path, byte[] data) throws IOException { Files.createDirectories(path.toAbsolutePath().getParent()); Files.write(path, data); }
    static void write(Path path, String text) throws IOException { write(path, text.getBytes(StandardCharsets.UTF_8)); }

    /** Stable two-space, ASCII JSON retains the frozen release metadata representation. */
    static String json(Object value) { return json(value, 0, true); }
    private static String json(Object value, int depth, boolean sort) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder result = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '"' -> result.append("\\\""); case '\\' -> result.append("\\\\");
                    case '\b' -> result.append("\\b"); case '\f' -> result.append("\\f"); case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
                    default -> { if (c < 32 || c > 126) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c)); else result.append(c); }
                }
            }
            return result.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        String indent = "  ".repeat(depth), child = indent + "  ";
        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = sort ? new TreeMap<>(m(value)) : m(value);
            if (map.isEmpty()) return "{}";
            List<String> pairs = new ArrayList<>();
            map.forEach((key, item) -> pairs.add(child + json(key, 0, sort) + ": " + json(item, depth + 1, sort)));
            return "{\n" + String.join(",\n", pairs) + "\n" + indent + "}";
        }
        List<Object> values = value instanceof Set<?> set ? new ArrayList<>(set) : l(value);
        if (values.isEmpty()) return "[]";
        return "[\n" + String.join(",\n", values.stream().map(item -> child + json(item, depth + 1, sort)).toList()) + "\n" + indent + "]";
    }
    static void writeJson(Path path, Object value) throws IOException { write(path, json(value) + "\n"); }

    static Path safe(Path root, String relative) {
        check(!relative.startsWith("/") && !relative.contains("\\") && Arrays.stream(relative.split("/")).noneMatch(".."::equals), "Unsafe archive path: " + relative);
        Path path = root.resolve(relative).normalize();
        check(path.startsWith(root.normalize()), "Unsafe archive path: " + relative);
        return path;
    }
    static byte[] entry(Path archive, String name) throws IOException {
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            ZipArchiveEntry entry = zip.getEntry(name);
            check(entry != null, "Missing archive entry: " + name);
            try (InputStream input = zip.getInputStream(entry)) { return input.readAllBytes(); }
        }
    }
    static Map<String, byte[]> entries(Path archive) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                if (entry.isDirectory()) continue;
                check(!result.containsKey(entry.getName()), "Duplicate archive entry: " + entry.getName());
                try (InputStream input = zip.getInputStream(entry)) { result.put(entry.getName(), input.readAllBytes()); }
            }
        }
        return result;
    }
    static void archive(Path path, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        // The published ZIP format has fixed headers, Unix 0644 and no extra fields.
        // Keep those bytes independent of archive-library defaults and host timezone.
        check(entries.size() < 65535, "ZIP64 is outside the frozen release format");
        record Central(byte[] name, int flags, long crc, int size, long offset) {}
        List<Central> central = new ArrayList<>();
        try (OutputStream output = Files.newOutputStream(path)) {
            long offset = 0;
            for (Map.Entry<String, byte[]> value : new TreeMap<>(entries).entrySet()) {
                byte[] name = value.getKey().getBytes(StandardCharsets.UTF_8), data = value.getValue();
                int flags = value.getKey().chars().anyMatch(c -> c > 127) ? 2048 : 0;
                check(name.length < 65536 && offset < 0xffffffffL, "ZIP entry exceeds the frozen format");
                CRC32 crc = new CRC32(); crc.update(data);
                central.add(new Central(name, flags, crc.getValue(), data.length, offset));
                little(output, 0x04034b50, 4); little(output, 20, 2); little(output, flags, 2); little(output, 0, 2);
                little(output, 0, 2); little(output, 33, 2); little(output, crc.getValue(), 4);
                little(output, data.length, 4); little(output, data.length, 4); little(output, name.length, 2); little(output, 0, 2);
                output.write(name); output.write(data); offset += 30L + name.length + data.length;
            }
            long centralOffset = offset;
            for (Central entry : central) {
                little(output, 0x02014b50, 4); little(output, 0x0314, 2); little(output, 20, 2);
                little(output, entry.flags(), 2); little(output, 0, 2); little(output, 0, 2); little(output, 33, 2);
                little(output, entry.crc(), 4); little(output, entry.size(), 4); little(output, entry.size(), 4);
                little(output, entry.name().length, 2); little(output, 0, 2); little(output, 0, 2); little(output, 0, 2); little(output, 0, 2);
                little(output, 0100644L << 16, 4); little(output, entry.offset(), 4); output.write(entry.name()); offset += 46L + entry.name().length;
            }
            check(offset < 0xffffffffL, "ZIP64 is outside the frozen release format");
            little(output, 0x06054b50, 4); little(output, 0, 2); little(output, 0, 2); little(output, central.size(), 2); little(output, central.size(), 2);
            little(output, offset - centralOffset, 4); little(output, centralOffset, 4); little(output, 0, 2);
        }
    }
    private static void little(OutputStream output, long value, int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) output.write((int) (value >>> (8 * i)) & 255);
    }
    static List<Path> files(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var paths = Files.walk(directory)) { return paths.filter(Files::isRegularFile).sorted().toList(); }
    }
    static List<String> classInventory(Path directory) throws IOException {
        return files(directory).stream().filter(path -> path.toString().endsWith(".class")).map(path -> directory.relativize(path).toString().replace('\\', '/')).sorted().toList();
    }
    static Set<String> difference(Set<String> a, Set<String> b) { Set<String> result = new TreeSet<>(a); result.removeAll(b); return result; }
    static Set<String> union(Set<String> a, Set<String> b) { Set<String> result = new TreeSet<>(a); result.addAll(b); return result; }
    static List<String> changed(Map<String, byte[]> before, Map<String, byte[]> after) {
        return before.keySet().stream().filter(name -> !Arrays.equals(before.get(name), after.get(name))).sorted().toList();
    }
    static String classpath(List<Path> files) { return String.join(java.io.File.pathSeparator, files.stream().map(Path::toString).toList()); }
    static String normalizeSource(String text) {
        return text.replaceAll("\\{ \\[([a-zA-Z_, ]+)\\] ->", "{ ($1) ->")
                .replaceAll("val \\[([a-zA-Z_, ]+)\\] =", "val ($1) =")
                .replaceAll("for \\(\\[([a-zA-Z_, ]+)\\] in", "for (($1) in");
    }
    Path fetch(Map<String, Object> row, Path cache, Map<String, Object> seeds) throws IOException {
        Files.createDirectories(cache);
        String url = s(row.get("url"));
        String name = s(row.getOrDefault("name", Path.of(URI.create(url).getPath()).getFileName().toString()));
        String expected = s(row.getOrDefault("sha256", row.get("baseline_sha256")));
        Path target = safe(cache, name);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // A verified existing binary cache is an immutable input, not another
            // SDK/toolchain tree to copy. Compiler receipts pin this actual path.
            if (seeds.containsKey(expected)) target = Path.of(s(seeds.get(expected))).toAbsolutePath().normalize();
            else download(url, target, 120_000);
        }
        check(!Files.isSymbolicLink(target) && Files.isRegularFile(target) && sha(target).equals(expected), "Input checksum mismatch: " + name);
        return target;
    }
    static void download(String url, Path path, int timeout) throws IOException {
        URLConnection connection = URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (InputStream input = connection.getInputStream()) { Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING); }
    }
    static void patch(Path target, Path patch, Path log) throws IOException {
        byte[] repaired = SwiftToolingPreparer.applyPatch(Files.readAllBytes(target), Files.readAllBytes(patch));
        Files.write(target, repaired);
        write(log, "Applied exact unified diff in Java; no fuzz, source offsets or interpreter\n");
    }
    record Result(int exit, String output) {}
    Result capture(List<String> command, Path cwd, Map<String, String> environment) throws IOException, InterruptedException {
        Path tmp = p("build/tmp/producer-processes"); Files.createDirectories(tmp);
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().put("TMPDIR", tmp.toString()); builder.environment().putAll(environment);
        Process process = builder.start();
        String output;
        try (InputStream input = process.getInputStream()) { output = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        return new Result(process.waitFor(), output);
    }
    String capture(List<String> command) throws IOException, InterruptedException {
        Result result = capture(command, repo, Map.of());
        check(result.exit() == 0, "Command failed (" + result.exit() + "): " + command.getFirst());
        return result.output();
    }
    void run(List<String> command, Path log) throws IOException, InterruptedException { run(command, log, repo, Map.of()); }
    void run(List<String> command, Path log, Path cwd, Map<String, String> environment) throws IOException, InterruptedException {
        int exit = process(command, log, cwd, environment, 0);
        check(exit == 0, "Command failed (" + exit + "); see " + log);
    }
    int process(List<String> command, Path log, Path cwd, Map<String, String> environment, int seconds) throws IOException, InterruptedException {
        Files.createDirectories(log.toAbsolutePath().getParent());
        Path tmp = log.toAbsolutePath().getParent().resolve("tmp"); Files.createDirectories(tmp);
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("TMPDIR", tmp.toString()); builder.environment().putAll(environment);
        Process process = builder.start();
        if (seconds == 0) return process.waitFor();
        if (process.waitFor(seconds, TimeUnit.SECONDS)) return process.exitValue();
        process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); process.waitFor();
        throw new IllegalArgumentException("Command timed out; see " + log);
    }
    static Map<String, String> pins(List<Path> paths) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (Path path : paths) result.put(path.toString(), sha(path));
        return result;
    }
    static void verifyPins(Map<String, String> pins, String message) throws IOException {
        for (Map.Entry<String, String> entry : pins.entrySet()) check(sha(Path.of(entry.getKey())).equals(entry.getValue()), message);
    }
    static Map<String, String> options(String... args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            check(args[i].startsWith("--") && i + 1 < args.length, "Expected --option value");
            String key = args[i].substring(2);
            check(!result.containsKey(key), "Duplicate option: " + key);
            result.put(key, args[++i]);
        }
        return result;
    }
    static String required(Map<String, String> args, String name) { String value = args.get(name); check(value != null, "--" + name + " is required"); return value; }
    static Path path(Map<String, String> args, String name) { return Path.of(required(args, name)).toAbsolutePath().normalize(); }
}
