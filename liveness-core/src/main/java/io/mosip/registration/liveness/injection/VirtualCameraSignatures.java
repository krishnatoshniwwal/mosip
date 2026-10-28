package io.mosip.registration.liveness.injection;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognises software cameras: by product name, and by device identifiers that show no hardware behind the
 * camera. The name list catches the common tools used to feed deepfakes, recordings or screen captures into an
 * application; the identifier rules catch software cameras whatever they call themselves.
 */
public final class VirtualCameraSignatures {

    /** Product name patterns, matched case-insensitively against the camera's name. */
    private static final String[][] PRODUCTS = {
            {"\\bobs\\b", "OBS Virtual Camera"},
            {"streamlabs", "Streamlabs"},
            {"manycam", "ManyCam"},
            {"xsplit", "XSplit VCam"},
            {"snap ?camera", "Snap Camera"},
            {"splitcam", "SplitCam"},
            {"e2esoft|\\bvcam\\b", "e2eSoft VCam"},
            {"droidcam", "DroidCam"},
            {"iriun", "Iriun Webcam"},
            {"epoccam", "EpocCam"},
            {"\\bivcam\\b", "iVCam"},
            {"\\bcamo\\b", "Camo"},
            {"mmhmm", "mmhmm"},
            {"nvidia broadcast", "NVIDIA Broadcast"},
            {"chromacam", "ChromaCam"},
            {"youcam", "CyberLink YouCam"},
            {"altercam", "AlterCam"},
            {"webcamoid|akvcam", "Webcamoid / AkVirtualCamera"},
            {"v4l2 ?loopback|dummy video device", "v4l2loopback"},
            {"unity video capture", "Unity Video Capture"},
            {"deepfacelive|avatarify|facerig", "face-swap / avatar software"},
            {"logi capture", "Logi Capture"},
            {"vmix", "vMix"},
            {"\\bndi\\b", "NDI Virtual Input"},
            {"prism lens", "Prism Lens"},
            {"virtual|fake|emulat", "generic software camera"},
    };

    private static final Pattern[] PATTERNS = new Pattern[PRODUCTS.length];

    static {
        for (int i = 0; i < PRODUCTS.length; i++) {
            PATTERNS[i] = Pattern.compile(PRODUCTS[i][0], Pattern.CASE_INSENSITIVE);
        }
    }

    private VirtualCameraSignatures() {
    }

    /**
     * @param name       the camera's display name
     * @param hardwareId PnP instance ID ({@code USB\VID_…}, {@code SWD\VCAMDEVAPI\…}), sysfs device path, or empty
     * @return why the camera is virtual, or null if nothing marks it as virtual
     */
    public static String identify(String name, String hardwareId) {
        String id = hardwareId == null ? "" : hardwareId.toUpperCase(Locale.ROOT).replace('/', '\\');
        // Windows 11 software cameras (MFCreateVirtualCamera) and root-enumerated drivers have no bus behind them.
        if (id.startsWith("SWD\\VCAMDEVAPI")) {
            return "Windows software camera (" + hardwareId + ")";
        }
        if (id.startsWith("ROOT\\")) {
            return "root-enumerated driver, no hardware bus (" + hardwareId + ")";
        }
        // Linux: v4l2loopback and similar register under /sys/devices/virtual.
        if (id.contains("\\DEVICES\\VIRTUAL\\")) {
            return "Linux virtual video device (" + hardwareId + ")";
        }
        if (name != null) {
            for (int i = 0; i < PATTERNS.length; i++) {
                if (PATTERNS[i].matcher(name).find()) {
                    return "name matches " + PRODUCTS[i][1];
                }
            }
        }
        return null;
    }
}
