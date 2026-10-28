package io.mosip.registration.liveness.injection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the operating systems' camera listings into {@link CameraEndpoint}s. Kept apart from the code that runs
 * the commands so the parsing can be tested on any platform.
 */
public final class CameraListings {

    private CameraListings() {
    }

    /**
     * Windows Plug and Play cameras (classes Camera and Image), one {@code instanceId|name} per line, as printed by
     * {@code Get-CimInstance Win32_PnPEntity}. Media Foundation, which OpenCV uses, opens exactly these.
     */
    public static List<CameraEndpoint> windowsPnp(String output) {
        List<CameraEndpoint> cameras = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            int bar = line.indexOf('|');
            if (bar > 0) {
                cameras.add(new CameraEndpoint(line.substring(bar + 1), line.substring(0, bar), true));
            }
        }
        return cameras;
    }

    /**
     * Windows DirectShow video sources, from {@code reg query HKCR\CLSID\{860BB310-…}\Instance /s /v FriendlyName}.
     * Software cameras such as OBS register here; Media Foundation does not see DirectShow-only sources.
     *
     * @param reachable whether the capture backend in use enumerates DirectShow sources
     */
    public static List<CameraEndpoint> windowsDirectShow(String output, boolean reachable) {
        List<CameraEndpoint> cameras = new ArrayList<>();
        String key = "";
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("HKEY_")) {
                int instance = trimmed.lastIndexOf('\\');
                key = instance >= 0 ? trimmed.substring(instance + 1) : trimmed;
            } else if (trimmed.startsWith("FriendlyName")) {
                int type = trimmed.indexOf("REG_SZ");
                if (type > 0) {
                    cameras.add(new CameraEndpoint(trimmed.substring(type + "REG_SZ".length()).trim(),
                            "DSHOW\\" + key, reachable));
                }
            }
        }
        return cameras;
    }

    /**
     * macOS cameras from {@code system_profiler SPCameraDataType}: each camera is a heading indented by four
     * spaces, followed by more deeply indented details such as {@code Unique ID:}. AVFoundation opens all of them,
     * including virtual cameras installed as system extensions or DAL plug-ins.
     */
    public static List<CameraEndpoint> macProfiler(String output) {
        List<CameraEndpoint> cameras = new ArrayList<>();
        String name = null;
        String id = "";
        for (String line : output.split("\\r?\\n")) {
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String trimmed = line.trim();
            if (indent == 4 && trimmed.endsWith(":")) {
                if (name != null) {
                    cameras.add(new CameraEndpoint(name, id, true));
                }
                name = trimmed.substring(0, trimmed.length() - 1);
                id = "";
            } else if (name != null && trimmed.startsWith("Unique ID:")) {
                id = trimmed.substring("Unique ID:".length()).trim();
            }
        }
        if (name != null) {
            cameras.add(new CameraEndpoint(name, id, true));
        }
        return cameras;
    }
}
