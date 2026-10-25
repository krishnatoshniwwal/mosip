package io.mosip.registration.liveness.device.sbi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP framing for SBI's custom methods ({@code MOSIPDISC}, {@code STREAM}, ...), which
 * {@link java.net.HttpURLConnection} refuses to send. The SBI service listens on localhost only.
 */
final class SbiHttp {

    static final int MAX_JSON_BYTES = 256 * 1024;
    private static final String CRLF = "\r\n";

    private SbiHttp() {
    }

    static void writeRequest(OutputStream out, String method, String path, String host, int port, String jsonBody)
            throws IOException {
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        String head = method + " " + path + " HTTP/1.1" + CRLF
                + "Host: " + host + ":" + port + CRLF
                + "Content-Type: application/json" + CRLF
                + "Content-Length: " + body.length + CRLF
                + "Connection: close" + CRLF + CRLF;
        out.write(MjpegStreamReader.ascii(head));
        out.write(body);
        out.flush();
    }

    /** @return the status code of an {@code HTTP/1.x NNN ...} line, or -1 */
    static int statusCode(String statusLine) {
        if (statusLine == null || !statusLine.matches("HTTP/1\\.[01] \\d{3}( .*)?")) {
            return -1;
        }
        return Integer.parseInt(statusLine.substring(9, 12));
    }

    /** Reads a JSON body: Content-Length bytes if given, otherwise until the server closes, at most {@link #MAX_JSON_BYTES}. */
    static String readBody(InputStream in, Map<String, String> headers) throws IOException {
        String length = headers.get("content-length");
        int limit = MAX_JSON_BYTES;
        if (length != null) {
            try {
                limit = Math.min(limit, Integer.parseInt(length.trim()));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length: " + length);
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (body.size() < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - body.size()))) > 0) {
            body.write(buf, 0, n);
        }
        return new String(body.toByteArray(), StandardCharsets.UTF_8);
    }

    static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
