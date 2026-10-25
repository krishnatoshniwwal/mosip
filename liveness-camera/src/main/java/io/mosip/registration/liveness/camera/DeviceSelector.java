package io.mosip.registration.liveness.camera;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceException.Kind;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.sbi.SbiDeviceDescriptor;
import io.mosip.registration.liveness.device.sbi.SbiDiscovery;

import java.io.IOException;
import java.util.List;

/**
 * Creates the face capture device named by a device spec, shared by the live capture app and the tools:
 *
 * <pre>
 * webcam:&lt;index&gt;                 OpenCV camera, e.g. webcam:0
 * sbi:auto                        discover SBI face devices on 127.0.0.1:4501-4600, use the first ready one
 * sbi:&lt;host&gt;:&lt;port&gt;[:&lt;subId&gt;]    discover on that port, e.g. sbi:127.0.0.1:4501
 * </pre>
 *
 * SBI devices are found through discovery ({@code MOSIPDISC}), which supplies the device ID the stream needs.
 */
public final class DeviceSelector {

    /** System property holding the device spec. */
    public static final String PROPERTY = "mosip.liveness.device";
    public static final String DEFAULT_SPEC = "webcam:0";
    public static final String HELP = "  webcam:<index>                e.g. webcam:0\n"
            + "  sbi:auto                      first ready SBI face device on 127.0.0.1:4501-4600\n"
            + "  sbi:<host>:<port>[:<subId>]   e.g. sbi:127.0.0.1:4501";

    private DeviceSelector() {
    }

    /** The spec from {@value #PROPERTY}, or {@code fallback} when the property is unset. */
    public static String specFromProperty(String fallback) {
        String spec = System.getProperty(PROPERTY);
        return spec == null || spec.trim().isEmpty() ? fallback : spec.trim();
    }

    /**
     * @return a device that is created but not opened
     * @throws DeviceException      if no matching SBI device is found or ready
     * @throws IllegalArgumentException if the spec is malformed
     */
    public static FaceCaptureDevice create(String spec, int webcamFps) throws DeviceException {
        if (spec.startsWith("webcam:")) {
            return new OpenCvCameraDevice(parseInt(spec.substring("webcam:".length()), spec), webcamFps);
        }
        if (spec.equals("sbi:auto")) {
            return pick(SbiDiscovery.discoverFaceDevices(), null, "on 127.0.0.1:" + SbiDiscovery.FIRST_PORT + "-"
                    + SbiDiscovery.LAST_PORT);
        }
        if (spec.startsWith("sbi:")) {
            String[] p = spec.split(":");
            if (p.length < 3 || p.length > 4) {
                throw new IllegalArgumentException("Bad device spec '" + spec + "'. Use one of:\n" + HELP);
            }
            int port = parseInt(p[2], spec);
            List<SbiDeviceDescriptor> found;
            try {
                found = SbiDiscovery.discoverOn(p[1], port, "Face", 1000);
            } catch (IOException e) {
                throw new DeviceException(Kind.UNAVAILABLE, "No SBI service answered discovery at " + p[1] + ":" + port
                        + " (" + e.getMessage() + ")", e);
            }
            return pick(found, p.length == 4 ? p[3] : null, "at " + p[1] + ":" + port);
        }
        throw new IllegalArgumentException("Unknown device spec '" + spec + "'. Use one of:\n" + HELP);
    }

    private static FaceCaptureDevice pick(List<SbiDeviceDescriptor> found, String subId, String where)
            throws DeviceException {
        for (SbiDeviceDescriptor d : found) {
            if (d.isReady()) {
                return d.newStreamDevice(subId != null ? subId : d.defaultSubId());
            }
        }
        if (found.isEmpty()) {
            throw new DeviceException(Kind.UNAVAILABLE, "No SBI face device found " + where
                    + ". Is the device service (MDS) running?", null);
        }
        throw new DeviceException(Kind.CAMERA_UNAVAILABLE, "SBI face device(s) found " + where
                + " but none is ready: " + found, null);
    }

    private static int parseInt(String value, String spec) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad number in device spec '" + spec + "'. Use one of:\n" + HELP);
        }
    }
}
