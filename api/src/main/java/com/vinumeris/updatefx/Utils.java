package com.vinumeris.updatefx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

public class Utils {
    public static List<Path> listDir(Path dir) throws IOException {
        List<Path> contents = new LinkedList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.forEach(contents::add);
        }
        return contents;
    }

    public static void println(String s, Object... args) {
        System.out.println(String.format(s, args));
    }
}
