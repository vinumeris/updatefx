package com.vinumeris.updatefx.tools

import com.google.common.io.ByteStreams
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.util.zip.*

/**
 * Rewrites the given zip file so all file timestamps are zeroed out and compression is removed. This format is better
 * for delta calculation.
 */
public class ProcessZIP {
    companion object {
        public fun process(zipPath: Path, destPath: Path) {
            val zeroTime = FileTime.fromMillis(0)
            val outPath = Files.createTempFile("processzip", null)

            var skipped = false

            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(outPath))).use { output ->
                ZipInputStream(BufferedInputStream(Files.newInputStream(zipPath))).use { input ->
                    var printed = false
                    while (true) {
                        val entry = input.getNextEntry() ?: break
                        // Skip if already processed.
                        if (entry.getLastModifiedTime().toMillis() == 0L) {
                            skipped = true
                            break
                        }
                        if (!printed) {
                            System.out.println("Processing " + zipPath)
                            printed = true
                        }
                        entry.setLastModifiedTime(zeroTime)
                        entry.setCreationTime(zeroTime)
                        output.setLevel(0)
                        output.setMethod(ZipOutputStream.STORED)  // No compression.
                        output.putNextEntry(entry)
                        ByteStreams.copy(input, output)
                        input.closeEntry()
                        output.closeEntry()
                    }
                }
            }
            if (!skipped)
                Files.move(outPath, destPath, StandardCopyOption.REPLACE_EXISTING)
            else
                Files.copy(zipPath, destPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
