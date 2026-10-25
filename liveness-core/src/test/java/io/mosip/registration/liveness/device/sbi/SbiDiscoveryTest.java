package io.mosip.registration.liveness.device.sbi;

import io.mosip.registration.liveness.device.DeviceLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Responses below use the formats of the MOSIP mock SBI (mosip-mock-services/MockMDS). */
class SbiDiscoveryTest {

    private static final String DIGITAL_ID = Base64.getUrlEncoder().withoutPadding().encodeToString(
            ("{\"serialNo\":\"2345678901\",\"make\":\"MOSIP\",\"model\":\"FACE01\",\"type\":\"Face\","
                    + "\"deviceSubType\":\"Full face\",\"deviceProvider\":\"MOSIP\"}").getBytes(StandardCharsets.UTF_8));
    private static final String DISCOVERY = "[{\"deviceId\":\"3\",\"deviceStatus\":\"Ready\",\"certification\":\"L0\","
            + "\"serviceVersion\":\"0.9.5\",\"deviceSubId\":[\"0\"],\"callbackId\":\"\",\"digitalId\":\"" + DIGITAL_ID
            + "\",\"deviceCode\":\"b692b595\",\"specVersion\":[\"0.9.5\"],\"purpose\":\"Registration\",\"error\":null}]";

    private ServerSocket server;

    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void discoveryReturnsWhatStreamingNeeds() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        serve(DISCOVERY, request);

        List<SbiDeviceDescriptor> found = SbiDiscovery.discoverOn("127.0.0.1", server.getLocalPort(), "Face", 1000);

        assertEquals(1, found.size());
        SbiDeviceDescriptor d = found.get(0);
        assertEquals("3", d.getDeviceId());
        assertEquals(Arrays.asList("0"), d.getDeviceSubIds());
        assertEquals("0", d.defaultSubId());
        assertTrue(d.isReady());
        assertEquals(DeviceLevel.L0, d.getLevel());
        assertEquals("Registration", d.getPurpose());
        assertEquals("2345678901", d.getSerialNo());
        assertEquals("MOSIP", d.getMake());
        assertEquals("FACE01", d.getModel());
        assertEquals(DeviceLevel.L0, d.toDeviceInfo().getLevel());
        assertTrue(request.get().startsWith("MOSIPDISC /device HTTP/1.1"), request.get());
        assertTrue(request.get().contains("{\"type\":\"Face\"}"), request.get());
    }

    @Test
    void errorEntriesAndBusyDevicesAreRecognised() throws Exception {
        String body = "[{\"error\":{\"errorCode\":\"502\",\"errorInfo\":\"Invalid type\"}},"
                + "{\"deviceId\":\"9\",\"deviceStatus\":\"Busy\",\"certification\":\"L1\",\"deviceSubId\":[1,2],"
                + "\"digitalId\":\"\",\"purpose\":\"Registration\"}]";
        List<SbiDeviceDescriptor> found = SbiDiscovery.parse("127.0.0.1", 4502, body);
        assertEquals(1, found.size(), "the error entry is skipped");
        assertFalse(found.get(0).isReady());
        assertEquals(DeviceLevel.L1, found.get(0).getLevel());
        assertEquals(Arrays.asList("1", "2"), found.get(0).getDeviceSubIds(), "numeric sub-IDs are accepted");
    }

    @Test
    void signedDigitalIdPayloadIsRead() {
        String jws = "eyJhbGciOiJSUzI1NiJ9." + DIGITAL_ID + ".c2lnbmF0dXJl";
        assertEquals("2345678901", SbiDiscovery.digitalId(jws).get("serialNo"));
        assertTrue(SbiDiscovery.digitalId("not base64!").isEmpty());
    }

    @Test
    void nonJsonAnswerIsRejected() {
        assertThrows(IOException.class, () -> SbiDiscovery.parse("127.0.0.1", 4501, "<html>hello</html>"));
        assertThrows(IOException.class, () -> SbiDiscovery.parse("127.0.0.1", 4501, "{\"deviceId\":\"3\"}"));
    }

    @Test
    void scanSkipsPortsWithNothingListening() throws Exception {
        serve(DISCOVERY, new AtomicReference<>());
        int port = server.getLocalPort();
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        assertTrue(SbiDiscovery.discover("127.0.0.1", free, free, "Face", 300).isEmpty());
        assertEquals(1, SbiDiscovery.discover("127.0.0.1", port, port, "Face", 300).size());
    }

    @Test
    void miniJsonHandlesTheSbiSubset() {
        Object v = MiniJson.parse(" {\"a\": [1, 2.5, -3e2, true, false, null], \"s\": \"x\\\"y\\\\z\\u0041\\n\", \"o\": {}} ");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(Arrays.asList(1.0, 2.5, -300.0, true, false, null), m.get("a"));
        assertEquals("x\"y\\zA\n", m.get("s"));
        assertEquals("1", MiniJson.string(Map.of("n", 1.0), "n"));
        assertEquals("b", MiniJson.find(MiniJson.parse("[{\"x\":{\"errorCode\":\"b\"}}]"), "errorCode"));
        for (String bad : new String[] {"{", "[1,]", "{\"a\" 1}", "\"open", "[1] x", "tru"}) {
            assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(bad), bad);
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            deep.append('[');
        }
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(deep.toString()), "depth is bounded");
    }

    /** Answers every connection with an HTTP 200 JSON body; records the last request with its body. */
    private void serve(String json, AtomicReference<String> request) throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    InputStream in = new BufferedInputStream(client.getInputStream());
                    String line = MjpegStreamReader.readLine(in);
                    if (line == null) {
                        continue;
                    }
                    Map<String, String> headers = MjpegStreamReader.readHeaders(in);
                    String body = SbiHttp.readBody(in, headers);
                    request.set(line + "\n" + body);
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                            + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(bytes);
                    out.flush();
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-sbi-discovery");
        t.setDaemon(true);
        t.start();
    }
}
