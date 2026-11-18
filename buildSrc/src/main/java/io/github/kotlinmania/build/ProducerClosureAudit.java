package io.github.kotlinmania.build;

import static io.github.kotlinmania.build.ProducerSupport.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Resolves exact compiler-host class/member linkage without rewriting retained bytecode. */
final class ProducerClosureAudit {
    private ProducerClosureAudit() {}
    record Pool(int tag, int first, int second) {}
    record Member(String kind, String name, String descriptor) {}
    record Reference(String kind, String owner, String name, String descriptor) {}
    record ClassInfo(String owner, int flags, String parent, List<String> interfaces,
                     Map<Member, Integer> members, Map<Integer, Reference> references) {}
    record Resolved(String owner, int flags) {}

    private static String utf(Object[] pool, int index) { check(index > 0 && index < pool.length && pool[index] instanceof String, "Invalid UTF constant index"); return (String) pool[index]; }
    private static Pool constant(Object[] pool, int index) { check(index > 0 && index < pool.length && pool[index] instanceof Pool, "Invalid constant index"); return (Pool) pool[index]; }
    private static String owner(Object[] pool, int index) { return index == 0 ? null : utf(pool, constant(pool, index).first()); }

    static ClassInfo parse(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            check(input.readInt() == 0xcafebabe, "Invalid class magic");
            input.skipNBytes(4);
            Object[] pool = new Object[input.readUnsignedShort()];
            for (int index = 1; index < pool.length; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> {
                        int size = input.readUnsignedShort();
                        byte[] data = input.readNBytes(size);
                        check(data.length == size, "Truncated UTF constant");
                        pool[index] = new String(data, StandardCharsets.UTF_8);
                    }
                    case 3, 4 -> input.skipNBytes(4);
                    case 5, 6 -> { input.skipNBytes(8); index++; }
                    case 7, 8, 16, 19, 20 -> pool[index] = new Pool(tag, input.readUnsignedShort(), 0);
                    case 9, 10, 11, 12, 17, 18 -> pool[index] = new Pool(tag, input.readUnsignedShort(), input.readUnsignedShort());
                    case 15 -> pool[index] = new Pool(tag, input.readUnsignedByte(), input.readUnsignedShort());
                    default -> throw new IllegalArgumentException("Unsupported constant-pool tag: " + tag);
                }
            }
            int flags = input.readUnsignedShort();
            String owner = owner(pool, input.readUnsignedShort()), parent = owner(pool, input.readUnsignedShort());
            List<String> interfaces = new ArrayList<>();
            int count = input.readUnsignedShort();
            for (int i = 0; i < count; i++) interfaces.add(owner(pool, input.readUnsignedShort()));
            Map<Member, Integer> members = new LinkedHashMap<>();
            for (String kind : List.of("field", "method")) {
                count = input.readUnsignedShort();
                for (int i = 0; i < count; i++) {
                    int access = input.readUnsignedShort();
                    Member member = new Member(kind, utf(pool, input.readUnsignedShort()), utf(pool, input.readUnsignedShort()));
                    members.put(member, access);
                    int attributes = input.readUnsignedShort();
                    for (int a = 0; a < attributes; a++) { input.readUnsignedShort(); input.skipNBytes(Integer.toUnsignedLong(input.readInt())); }
                }
            }
            Map<Integer, Reference> references = new LinkedHashMap<>();
            for (int index = 1; index < pool.length; index++) {
                if (pool[index] instanceof Pool row && Set.of(9, 10, 11).contains(row.tag())) {
                    Pool signature = constant(pool, row.second());
                    references.put(index, new Reference(row.tag() == 9 ? "field" : "method", owner(pool, row.first()), utf(pool, signature.first()), utf(pool, signature.second())));
                }
            }
            return new ClassInfo(owner, flags, parent, interfaces, members, references);
        }
    }

    private static final class Resolver implements AutoCloseable {
        final List<ZipFile> archives = new ArrayList<>();
        final Map<String, ClassInfo> cache = new LinkedHashMap<>();
        Resolver(List<Path> paths) throws IOException {
            try { for (Path path : paths) archives.add(ZipFile.builder().setPath(path).get()); }
            catch (IOException failure) { close(); throw failure; }
        }
        ClassInfo load(String owner) throws IOException {
            if (cache.containsKey(owner)) return cache.get(owner);
            String entry = owner + ".class";
            for (ZipFile archive : archives) {
                ZipArchiveEntry member = archive.getEntry(entry);
                if (member == null) member = archive.getEntry("classes/" + entry);
                if (member != null) try (InputStream input = archive.getInputStream(member)) {
                    ClassInfo info = parse(input.readAllBytes()); cache.put(owner, info); return info;
                }
            }
            throw new IllegalArgumentException("Unresolved class: " + owner);
        }
        Resolved resolve(String owner, Member member) throws IOException {
            LinkedList<String> queue = new LinkedList<>(List.of(owner));
            Set<String> seen = new LinkedHashSet<>();
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                if (current == null || !seen.add(current)) continue;
                ClassInfo table = load(current);
                if (table.members().containsKey(member)) return new Resolved(current, table.members().get(member));
                if (!member.name().equals("<init>")) { queue.add(table.parent()); queue.addAll(table.interfaces()); }
            }
            throw new IllegalArgumentException("Unresolved member: " + owner + " " + member);
        }
        boolean subclass(String child, String parent) throws IOException {
            Set<String> seen = new LinkedHashSet<>();
            while (child != null) {
                if (child.equals(parent)) return true;
                check(seen.add(child), "Cyclic class hierarchy: " + child);
                child = load(child).parent();
            }
            return false;
        }
        @Override public void close() throws IOException {
            IOException failure = null;
            for (ZipFile archive : archives) try { archive.close(); } catch (IOException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
            if (failure != null) throw failure;
        }
    }

    private static String packageName(String owner) { int slash = owner.lastIndexOf('/'); return slash < 0 ? "" : owner.substring(0, slash); }

    static void audit(ProducerSupport context, Path jar, Path worker, Path entryList, Path jdk, Path output) throws Exception {
        List<Path> paths = new ArrayList<>(List.of(jar));
        for (Map<String, Object> row : rows(Json.read(worker))) {
            Path path = Path.of(s(row.get("path")));
            if (!path.getFileName().toString().startsWith("swift-export-embeddable")) paths.add(path);
        }
        paths.add(jdk.resolve("jmods/java.base.jmod"));
        List<Map<String, Object>> references = new ArrayList<>();
        Pattern instruction = Pattern.compile("^\\s*\\d+:\\s*(invokevirtual|invokestatic|invokespecial|invokeinterface|getfield|putfield|getstatic|putstatic)\\s+#(\\d+)");
        try (Resolver resolver = new Resolver(paths)) {
            for (String entry : strings(Json.read(entryList))) {
                String caller = entry.endsWith(".class") ? entry.substring(0, entry.length() - 6) : entry;
                ClassInfo table = resolver.load(caller);
                String instructions = context.capture(List.of(jdk.resolve("bin/javap").toString(), "-p", "-c", "-classpath", jar.toString(), caller.replace('/', '.')));
                Map<Integer, Set<String>> uses = new LinkedHashMap<>();
                for (String line : instructions.split("\\R")) {
                    Matcher match = instruction.matcher(line);
                    if (match.find()) uses.computeIfAbsent(Integer.parseInt(match.group(2)), ignored -> new TreeSet<>()).add(match.group(1));
                }
                for (Map.Entry<Integer, Reference> item : table.references().entrySet()) {
                    Reference reference = item.getValue();
                    if (reference.owner().startsWith("[")) {
                        check(reference.kind().equals("method") && reference.name().equals("clone") && reference.descriptor().equals("()Ljava/lang/Object;"), "Unsupported array member reference: " + reference);
                        continue;
                    }
                    Resolved declaring = resolver.resolve(reference.owner(), new Member(reference.kind(), reference.name(), reference.descriptor()));
                    int flags = declaring.flags();
                    boolean samePackage = packageName(caller).equals(packageName(declaring.owner()));
                    boolean accessible = (flags & 1) != 0 || (flags & 2) != 0 && caller.equals(declaring.owner())
                            || (flags & 4) != 0 && (samePackage || resolver.subclass(caller, declaring.owner())) || (flags & 7) == 0 && samePackage;
                    check(accessible, "Inaccessible member: " + caller + " " + reference + " " + declaring);
                    for (String opcode : uses.getOrDefault(item.getKey(), Set.of())) {
                        check(((flags & 8) != 0) == Set.of("invokestatic", "getstatic", "putstatic").contains(opcode), "Static linkage mismatch: " + caller + " " + reference + " " + opcode);
                    }
                    references.add(obj("caller", caller, "kind", reference.kind(), "owner", reference.owner(), "name", reference.name(), "descriptor", reference.descriptor(),
                            "declaring_owner", declaring.owner(), "fullflags", flags, "opcodes", new ArrayList<>(uses.getOrDefault(item.getKey(), Set.of()))));
                }
            }
        }
        List<Path> proofPaths = new ArrayList<>(paths);
        proofPaths.addAll(List.of(worker, entryList, context.p("buildSrc/src/main/java/io/github/kotlinmania/build/ProducerClosureAudit.java")));
        writeJson(output, obj("accepted", true, "reference_count", references.size(), "references", references, "input_sha256", pins(proofPaths)));
        System.out.println("PASS exact member descriptors/access/static linkage for " + references.size() + " references");
    }
}
