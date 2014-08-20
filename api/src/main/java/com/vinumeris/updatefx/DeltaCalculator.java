package com.vinumeris.updatefx;

import com.nothome.delta.Delta;
import com.nothome.delta.GDiffWriter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static com.vinumeris.updatefx.Utils.println;
import static java.nio.file.Files.*;

/**
 * Given a directory of JARs named like 1.jar, 2.jar, 3.jar etc calculates xdeltas between them and outputs
 * 2.jar.xdelta, 3.jar.xdelta etc where each delta applies to the full jar produced by the previous deltas.
 * Note that for best results the JARs should be uncompressed! The assumption is, compression happens at a
 * higher level like in a native installer and using gzip with http.
 */
public class DeltaCalculator {
    public static void main(String[] args) throws IOException {
        Path inDir = Paths.get(args[0]);
        Path outDir = args.length > 1 ? Paths.get(args[1]) : inDir;
        int num = 2;
        while (true) {
            Path cur = inDir.resolve(num + ".jar");
            Path prev = inDir.resolve((num - 1) + ".jar");
            if (!(isRegularFile(cur) && isRegularFile(prev))) {
                break;
            }
            println("Calculating delta between %s and %s", cur, prev);
            Path deltaFile = outDir.resolve(cur.getFileName().toString() + ".bpatch");
            deleteIfExists(deltaFile);
            try (OutputStream stream = new BufferedOutputStream(newOutputStream(deltaFile, StandardOpenOption.CREATE_NEW))) {
                GDiffWriter writer = new GDiffWriter(stream);
                Delta delta = new Delta();
                delta.compute(prev.toFile(), cur.toFile(), writer);
            }
            println("... done: %s", deltaFile);
            num++;
        }
    }
}
