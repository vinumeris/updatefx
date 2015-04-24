package com.vinumeris.updatefx;

import com.google.common.hash.Hashing;
import com.google.common.hash.HashingOutputStream;
import com.nothome.delta.Delta;
import com.nothome.delta.GDiffWriter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static com.vinumeris.updatefx.Utils.println;
import static com.vinumeris.updatefx.Utils.sha256;
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
        process(inDir, outDir, -1);
    }

    public static class Result {
        public byte[] preHash, patchHash, postHash;
        public Path path;
        public long patchSize;
    }

    public static List<Result> process(Path inDir, Path outDir, int gzipFrom) throws IOException {
        List<Result> result = new ArrayList<>();
        int num = 2;
        while (true) {
            Path cur = inDir.resolve(num + ".jar");
            Path prev = inDir.resolve((num - 1) + ".jar");
            if (!(isRegularFile(cur) && isRegularFile(prev)))
                break;
            println("Calculating delta between %s and %s", cur, prev);
            Result deltaHashes = processFile(prev, cur, outDir, num, gzipFrom);
            result.add(deltaHashes);
            num++;
        }
        return result;
    }

    public static Result processFile(Path prev, Path cur, Path outDir, int num, int gzipFrom) throws IOException {
        Result deltaHashes = new Result();
        Path deltaFile = outDir.resolve(cur.getFileName().toString() + ".bpatch");
        deleteIfExists(deltaFile);
        deltaHashes.path = deltaFile;

        boolean isGzipping = num >= gzipFrom;
        try (
            HashingOutputStream hashingStream = new HashingOutputStream(Hashing.sha256(),
                    new BufferedOutputStream(
                            newOutputStream(deltaFile, StandardOpenOption.CREATE_NEW)
                    )
            )
        ) {
            GZIPOutputStream zipStream = null;
            GDiffWriter writer;
            if (isGzipping) {
                // Just constructing this object writes to the stream.
                zipStream = new GZIPOutputStream(hashingStream);
                writer = new GDiffWriter(zipStream);
            } else {
                writer = new GDiffWriter(hashingStream);
            }
            Delta delta = new Delta();
            deltaHashes.preHash = sha256(readAllBytes(prev));
            delta.compute(prev.toFile(), cur.toFile(), writer);
            if (isGzipping)
                zipStream.close();
            deltaHashes.patchHash = hashingStream.hash().asBytes();
            deltaHashes.postHash = sha256(readAllBytes(cur));
        }
        long size = Files.size(deltaFile);
        deltaHashes.patchSize = size;
        println("... done: %s   (%.2fkb) %s", deltaFile, size / 1024.0, isGzipping ? "zipped" : "");
        return deltaHashes;
    }
}
