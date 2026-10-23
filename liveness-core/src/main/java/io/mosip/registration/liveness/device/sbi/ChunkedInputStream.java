package io.mosip.registration.liveness.device.sbi;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Decodes an HTTP/1.1 {@code Transfer-Encoding: chunked} body. */
final class ChunkedInputStream extends InputStream {

    private final InputStream in;
    private int remaining;
    private boolean eof;

    ChunkedInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (eof) {
            return -1;
        }
        if (remaining == 0) {
            nextChunk();
            if (eof) {
                return -1;
            }
        }
        int n = in.read(b, off, Math.min(len, remaining));
        if (n < 0) {
            throw new EOFException("Stream ended inside a chunk");
        }
        remaining -= n;
        if (remaining == 0) {
            MjpegStreamReader.readLine(in); // CRLF after chunk data
        }
        return n;
    }

    private void nextChunk() throws IOException {
        String line = MjpegStreamReader.readLine(in);
        if (line == null) {
            eof = true;
            return;
        }
        int semi = line.indexOf(';');
        String hex = (semi >= 0 ? line.substring(0, semi) : line).trim();
        try {
            remaining = Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid chunk size: " + line);
        }
        if (remaining < 0) {
            throw new IOException("Invalid chunk size: " + line);
        }
        if (remaining == 0) {
            eof = true;
            while ((line = MjpegStreamReader.readLine(in)) != null && !line.isEmpty()) {
                // discard trailers
            }
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
