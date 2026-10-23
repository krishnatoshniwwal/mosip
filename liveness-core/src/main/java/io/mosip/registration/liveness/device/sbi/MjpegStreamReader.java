package io.mosip.registration.liveness.device.sbi;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Splits a {@code multipart/x-mixed-replace} MJPEG body into JPEG images. Parts with a
 * Content-Length are read exactly; parts without one (or streams without a boundary) are
 * delimited by scanning for the JPEG SOI/EOI markers.
 */
public final class MjpegStreamReader {

    static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LINE = 8192;

    private final InputStream in;
    private final String boundary;

    /** @param boundary multipart boundary from the Content-Type header, or null to scan for JPEG markers */
    public MjpegStreamReader(InputStream in, String boundary) {
        this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in);
        this.boundary = boundary == null || boundary.isEmpty() ? null : boundary;
    }

    /** @return the next JPEG image, or null when the stream ends cleanly */
    public byte[] nextFrame() throws IOException {
        if (boundary == null) {
            return scanJpeg();
        }
        String rest;
        while (true) {
            String line = readLine(in);
            if (line == null) {
                return null;
            }
            rest = afterDelimiter(line);
            if (rest != null) {
                break;
            }
        }
        if (rest.startsWith("--")) {
            return null;
        }
        Map<String, String> headers = readHeaders(in);
        String length = headers.get("content-length");
        if (length == null) {
            return scanJpeg();
        }
        int n;
        try {
            n = Integer.parseInt(length.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid MJPEG part length: " + length);
        }
        if (n <= 0 || n > MAX_FRAME_BYTES) {
            throw new IOException("Invalid MJPEG part length: " + n);
        }
        byte[] frame = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(frame, off, n - off);
            if (r < 0) {
                throw new EOFException("Stream ended inside an MJPEG part");
            }
            off += r;
        }
        return frame;
    }

    /** Accepts both RFC 2046 delimiters ("--" + boundary) and servers that send the boundary verbatim. */
    private String afterDelimiter(String line) {
        String rfc = "--" + boundary;
        if (line.startsWith(rfc)) {
            return line.substring(rfc.length());
        }
        if (line.startsWith(boundary)) {
            return line.substring(boundary.length());
        }
        return null;
    }

    private byte[] scanJpeg() throws IOException {
        int prev = -1;
        int b;
        while (true) {
            b = in.read();
            if (b < 0) {
                return null;
            }
            if (prev == 0xFF && b == 0xD8) {
                break;
            }
            prev = b;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        out.write(0xFF);
        out.write(0xD8);
        prev = -1;
        while ((b = in.read()) >= 0) {
            out.write(b);
            if (prev == 0xFF && b == 0xD9) {
                return out.toByteArray();
            }
            if (out.size() > MAX_FRAME_BYTES) {
                throw new IOException("JPEG frame exceeds " + MAX_FRAME_BYTES + " bytes");
            }
            prev = b;
        }
        throw new EOFException("Stream ended inside a JPEG frame");
    }

    /** Reads a CRLF (or LF) terminated ISO-8859-1 line; null at end of stream. */
    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            if (sb.length() >= MAX_LINE) {
                throw new IOException("Header line too long");
            }
            sb.append((char) b);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Reads header lines up to the blank line. Names are lower-cased. */
    static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
