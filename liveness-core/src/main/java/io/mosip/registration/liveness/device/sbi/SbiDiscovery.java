package io.mosip.registration.liveness.device.sbi;

import io.mosip.registration.liveness.device.DeviceLevel;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SBI device discovery: sends {@code MOSIPDISC /device {"type":"Face"}} to each port of the SBI range
 * (4501-4600 by default) and collects the face devices that answer. This is the device capability
 * discovery step of the Registration Client: it yields the {@code deviceId} needed to stream, the sub-IDs,
 * the L0/L1 certification, the purpose and the status, plus make, model and serial number from the
 * (unsigned) digital ID.
 */
public final class SbiDiscovery {

    public static final int FIRST_PORT = 4501;
    public static final int LAST_PORT = 4600;

    private static final Logger LOG = Logger.getLogger(SbiDiscovery.class.getName());

    private SbiDiscovery() {
    }

    /** Face devices on 127.0.0.1 across the standard SBI port range. */
    public static List<SbiDeviceDescriptor> discoverFaceDevices() {
        return discover("127.0.0.1", FIRST_PORT, LAST_PORT, "Face", 300);
    }

    /** Devices of {@code type} on every port in [fromPort, toPort] that answers; ports that refuse are skipped. */
    public static List<SbiDeviceDescriptor> discover(String host, int fromPort, int toPort, String type, int timeoutMs) {
        List<SbiDeviceDescriptor> found = new ArrayList<>();
        for (int port = fromPort; port <= toPort; port++) {
            try {
                found.addAll(discoverOn(host, port, type, timeoutMs));
            } catch (IOException e) {
                LOG.log(Level.FINE, "No SBI answer on port " + port, e);
            }
        }
        return found;
    }

    /** @throws IOException if nothing listens on the port or the answer is not a discovery response */
    public static List<SbiDeviceDescriptor> discoverOn(String host, int port, String type, int timeoutMs)
            throws IOException {
        String body;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(Math.max(timeoutMs, 2000));
            SbiHttp.writeRequest(s.getOutputStream(), "MOSIPDISC", "/device", host, port,
                    "{\"type\":\"" + SbiHttp.json(type) + "\"}");
            InputStream in = new BufferedInputStream(s.getInputStream());
            String status = MjpegStreamReader.readLine(in);
            Map<String, String> headers = MjpegStreamReader.readHeaders(in);
            if (SbiHttp.statusCode(status) != 200) {
                throw new IOException("Discovery on port " + port + " answered " + status);
            }
            body = SbiHttp.readBody(in, headers);
        }
        return parse(host, port, body);
    }

    static List<SbiDeviceDescriptor> parse(String host, int port, String body) throws IOException {
        Object json;
        try {
            json = MiniJson.parse(body.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Port " + port + " did not return JSON", e);
        }
        if (!(json instanceof List)) {
            throw new IOException("Port " + port + " did not return a device list");
        }
        List<SbiDeviceDescriptor> devices = new ArrayList<>();
        for (Object item : (List<?>) json) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> d = (Map<?, ?>) item;
            String deviceId = MiniJson.string(d, "deviceId");
            if (deviceId.isEmpty()) {
                continue; // an error entry such as [{"error":{"errorCode":"500",...}}]
            }
            Map<?, ?> digitalId = digitalId(MiniJson.string(d, "digitalId"));
            devices.add(new SbiDeviceDescriptor(host, port, deviceId, MiniJson.string(d, "deviceStatus"),
                    level(MiniJson.string(d, "certification")), MiniJson.string(d, "purpose"),
                    strings(d.get("deviceSubId")), strings(d.get("specVersion")),
                    MiniJson.string(digitalId, "serialNo"), MiniJson.string(digitalId, "make"),
                    MiniJson.string(digitalId, "model"), MiniJson.string(digitalId, "type")));
        }
        return devices;
    }

    /**
     * Discovery returns the digital ID unsigned, as base64url JSON; some devices send a JWS instead, whose
     * middle part is the same JSON. Signature checks belong to the signed {@code /info} and capture responses.
     */
    static Map<?, ?> digitalId(String encoded) {
        if (encoded.isEmpty()) {
            return Collections.emptyMap();
        }
        String[] parts = encoded.split("\\.");
        String payload = parts.length == 3 ? parts[1] : encoded;
        try {
            Object json = MiniJson.parse(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
            return json instanceof Map ? (Map<?, ?>) json : Collections.emptyMap();
        } catch (IllegalArgumentException e) {
            return Collections.emptyMap();
        }
    }

    private static DeviceLevel level(String certification) {
        return "L1".equals(certification.trim().toUpperCase(Locale.ROOT)) ? DeviceLevel.L1 : DeviceLevel.L0;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object v : (List<?>) value) {
                if (v != null) {
                    out.add(v instanceof Double ? Long.toString(((Double) v).longValue()) : v.toString());
                }
            }
        }
        return out;
    }
}
