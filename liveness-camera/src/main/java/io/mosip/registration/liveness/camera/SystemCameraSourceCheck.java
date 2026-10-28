package io.mosip.registration.liveness.camera;

import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.injection.CameraEndpoint;
import io.mosip.registration.liveness.injection.CameraListings;
import io.mosip.registration.liveness.injection.CameraSourceCheck;
import io.mosip.registration.liveness.injection.CameraSourceReport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lists the station's cameras for {@link OpenCvCameraDevice} sessions and flags software cameras:
 *
 * <ul>
 *   <li><b>Windows:</b> Plug and Play cameras (what Media Foundation, OpenCV's backend, can open) plus DirectShow
 *       video sources from the registry, where OBS, ManyCam and similar register. DirectShow-only sources count as
 *       reachable only when OpenCV is told to skip Media Foundation ({@code OPENCV_VIDEOIO_PRIORITY_MSMF=0}).</li>
 *   <li><b>Linux:</b> {@code /sys/class/video4linux}; v4l2loopback devices live under {@code /sys/devices/virtual}.</li>
 *   <li><b>macOS:</b> {@code system_profiler SPCameraDataType}.</li>
 * </ul>
 *
 * Listing takes up to a couple of seconds on Windows, so results are cached for a minute; call {@link #prefetch()}
 * when the application starts so the first session does not wait.
 */
public final class SystemCameraSourceCheck implements CameraSourceCheck {

    private static final Logger LOG = Logger.getLogger(SystemCameraSourceCheck.class.getName());
    private static final long CACHE_MS = 60_000;
    private static final long COMMAND_TIMEOUT_MS = 15_000;
    private static final String PNP_SCRIPT = "Get-CimInstance Win32_PnPEntity -Filter \"PNPClass='Camera' OR "
            + "PNPClass='Image'\" | ForEach-Object { $_.PNPDeviceID + '|' + $_.Name }";
    private static final String DSHOW_KEY = "HKCR\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance";

    private CameraSourceReport cached;
    private long cachedAt;

    @Override
    public CameraSourceReport inspect(DeviceInfo device) {
        if (!device.getDeviceId().startsWith(OpenCvCameraDevice.DEVICE_ID_PREFIX)) {
            return CameraSourceReport.notApplicable();
        }
        return current();
    }

    /** Lists the cameras on a background thread, so that the first session finds the result cached. */
    public void prefetch() {
        Thread t = new Thread(this::current, "camera-source-check");
        t.setDaemon(true);
        t.start();
    }

    /** The cached listing, refreshed when older than a minute. */
    public synchronized CameraSourceReport current() {
        long now = System.currentTimeMillis();
        if (cached == null || now - cachedAt > CACHE_MS) {
            cached = listCameras();
            cachedAt = now;
            LOG.info("Camera sources: " + cached.describe());
        }
        return cached;
    }

    private static CameraSourceReport listCameras() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                List<CameraEndpoint> cameras = new ArrayList<>(CameraListings.windowsPnp(run(
                        "powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encode(PNP_SCRIPT))));
                boolean directShowReachable = "0".equals(System.getenv("OPENCV_VIDEOIO_PRIORITY_MSMF"));
                cameras.addAll(CameraListings.windowsDirectShow(
                        run("reg", "query", DSHOW_KEY, "/s", "/v", "FriendlyName"), directShowReachable));
                return CameraSourceReport.of("windows", cameras);
            }
            if (os.contains("mac")) {
                return CameraSourceReport.of("macos", CameraListings.macProfiler(run("system_profiler", "SPCameraDataType")));
            }
            return CameraSourceReport.of("linux", linuxCameras(Paths.get("/sys/class/video4linux")));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not list cameras", e);
            return CameraSourceReport.failed("listing failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CameraSourceReport.failed("listing interrupted");
        }
    }

    static List<CameraEndpoint> linuxCameras(Path root) throws IOException {
        List<CameraEndpoint> cameras = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return cameras;
        }
        try (DirectoryStream<Path> nodes = Files.newDirectoryStream(root, "video*")) {
            for (Path node : nodes) {
                Path name = node.resolve("name");
                String label = Files.isRegularFile(name)
                        ? new String(Files.readAllBytes(name), StandardCharsets.UTF_8).trim() : node.getFileName().toString();
                cameras.add(new CameraEndpoint(label, node.toRealPath().toString(), true));
            }
        }
        return cameras;
    }

    /** PowerShell's -EncodedCommand avoids Windows command-line quoting of the script. */
    private static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    /** Runs a command and returns its output; a non-zero exit (e.g. reg query finding nothing) is not an error. */
    private static String run(String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // The process was killed after a timeout.
            }
        });
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IOException(command[0] + " did not finish within " + COMMAND_TIMEOUT_MS + " ms");
        }
        reader.join(1000);
        return new String(out.toByteArray(), Charset.defaultCharset());
    }
}
