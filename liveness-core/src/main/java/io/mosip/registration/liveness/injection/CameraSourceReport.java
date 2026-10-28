package io.mosip.registration.liveness.injection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What a {@link CameraSourceCheck} found about where the frames of a session can come from. */
public final class CameraSourceReport {

    private static final CameraSourceReport NOT_APPLICABLE = new CameraSourceReport(false, "not applicable",
            Collections.emptyList());

    private final boolean inspected;
    private final String detail;
    private final List<CameraEndpoint> cameras;

    private CameraSourceReport(boolean inspected, String detail, List<CameraEndpoint> cameras) {
        this.inspected = inspected;
        this.detail = detail;
        this.cameras = Collections.unmodifiableList(new ArrayList<>(cameras));
    }

    /** @param platform where the cameras were listed, e.g. {@code "windows"} */
    public static CameraSourceReport of(String platform, List<CameraEndpoint> cameras) {
        return new CameraSourceReport(true, platform, cameras);
    }

    /** The device is not a local camera (SBI device, mock), so the station's cameras do not matter. */
    public static CameraSourceReport notApplicable() {
        return NOT_APPLICABLE;
    }

    /** Listing the cameras failed; the session continues on the stream checks alone. */
    public static CameraSourceReport failed(String why) {
        return new CameraSourceReport(false, why, Collections.emptyList());
    }

    public boolean isInspected() { return inspected; }
    public String getDetail() { return detail; }
    public List<CameraEndpoint> getCameras() { return cameras; }

    /** Virtual cameras the capture backend can open: frames from any of them would look like a webcam's. */
    public List<CameraEndpoint> reachableVirtualCameras() {
        List<CameraEndpoint> found = new ArrayList<>();
        for (CameraEndpoint c : cameras) {
            if (c.isVirtual() && c.isReachable()) {
                found.add(c);
            }
        }
        return found;
    }

    /** Every virtual camera installed, including ones the capture backend cannot open. */
    public List<CameraEndpoint> virtualCameras() {
        List<CameraEndpoint> found = new ArrayList<>();
        for (CameraEndpoint c : cameras) {
            if (c.isVirtual()) {
                found.add(c);
            }
        }
        return found;
    }

    /** Compact, non-sensitive description for audit records. */
    public String describe() {
        if (!inspected) {
            return detail;
        }
        return detail + ": " + cameras.size() + " camera(s), virtual=" + virtualCameras();
    }
}
