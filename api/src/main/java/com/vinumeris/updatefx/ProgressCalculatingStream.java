package com.vinumeris.updatefx;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A ProgressCalculatingStream can be used to automatically track progress of reading from a wrapped stream, assuming the
 * total length of that stream is known. Override updateProgress to get updates that occur any time read() is called
 * successfully. expectedBytes can be set to -1 in which case no progress reports will occur.
 */
public class ProgressCalculatingStream extends FilterInputStream {
    private final long expectedBytes;
    private long readSoFar = 0;

    public ProgressCalculatingStream(InputStream in, long expectedBytes) {
        super(in);
        this.expectedBytes = expectedBytes;
    }

    private void update() {
        if (expectedBytes == -1) return;  // HTTP server doesn't tell us how big the file is :(
        updateProgress(readSoFar, expectedBytes, readSoFar / (double) expectedBytes);
    }

    protected void updateProgress(long readSoFar, long expectedBytes, double progress) {
    }

    @Override
    public int read() throws IOException {
        int result = super.read();
        if (result != -1) {
            readSoFar += result;
            update();
        }
        return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int result = super.read(b, off, len);
        if (result != -1) {
            readSoFar += result;
            update();
        }
        return result;
    }
}