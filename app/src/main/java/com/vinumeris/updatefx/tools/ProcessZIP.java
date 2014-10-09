package com.vinumeris.updatefx.tools;

import com.google.common.io.ByteStreams;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites the given zip file so all file timestamps are zeroed out and compression is removed. This format is better
 * for delta calculation.
 */
public class ProcessZIP {
    public static void main(String[] args) throws IOException {
        process(Paths.get(args[0]));
    }

    public static void process(Path zipPath) throws IOException {
        final FileTime zeroTime = FileTime.fromMillis(0);
        Path outPath = Files.createTempFile("processzip", null);
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outPath)))) {
            try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipPath)))) {
                ZipEntry entry;
                boolean printed = false;
                while ((entry = in.getNextEntry()) != null) {
                    if (entry.getLastModifiedTime().toMillis() == 0) {
                        // Already processed, so skip.
                        return;
                    }
                    if (!printed) {
                        System.out.println("Processing " + zipPath);
                        printed = true;
                    }
                    entry.setLastModifiedTime(zeroTime);
                    entry.setCreationTime(zeroTime);
                    out.setLevel(0);
                    out.setMethod(ZipOutputStream.STORED);  // No compression.
                    out.putNextEntry(entry);
                    ByteStreams.copy(in, out);
                    in.closeEntry();
                    out.closeEntry();
                }
            }
        }

        Files.move(outPath, zipPath, StandardCopyOption.REPLACE_EXISTING);
    }
}
