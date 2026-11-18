package io.github.kotlinmania.build;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurperClassic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** JSON support from the running Gradle distribution, without another dependency. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Object parsed = new JsonSlurperClassic().parseText("[" + text + "]");
        if (!(parsed instanceof java.util.List<?> values) || values.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one JSON value");
        }
        return values.getFirst();
    }

    public static Object read(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static String stringify(Object value) {
        return JsonOutput.toJson(value);
    }

    public static void write(Path path, Object value) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, JsonOutput.prettyPrint(stringify(value)) + "\n", StandardCharsets.UTF_8);
    }
}
