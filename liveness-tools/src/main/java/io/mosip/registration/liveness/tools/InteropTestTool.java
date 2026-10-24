package io.mosip.registration.liveness.tools;

import io.mosip.registration.liveness.camera.OpenCvCameraDevice;
import io.mosip.registration.liveness.camera.OpenCvLoader;
import io.mosip.registration.liveness.conformance.ConformanceReport;
import io.mosip.registration.liveness.conformance.DeviceConformanceSuite;
import io.mosip.registration.liveness.conformance.EngineBenchmark;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.DeviceLevel;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.PixelFormat;
import io.mosip.registration.liveness.device.sbi.SbiFaceStreamDevice;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.mock.MockFaceDevice;
import io.mosip.registration.liveness.mock.SimulatedLivenessEngine;
import io.mosip.registration.liveness.mock.SimulatedSubject;
import io.mosip.registration.liveness.onnx.OnnxLivenessEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * {@code interop} subcommand: runs a face capture device through {@link DeviceConformanceSuite} and
 * benchmarks the liveness engine on frames from that device, writing one Markdown report. Run it on
 * every device model and client machine type a deployment supports.
 *
 * <pre>
 * --device webcam:0                 OpenCV camera (index)
 * --device sbi:127.0.0.1:4501[:1]   MOSIP SBI face stream (host, port, device sub-id)
 * --device mock                     mock L1 device (development)
 * --disconnect manual               asks you to unplug the device mid-test, then plug it back in
 * --disconnect auto                 (mock only) simulates the disconnect
 * --yes                             skip the camera permission prompt (automated runs)
 * </pre>
 */
final class InteropTestTool {

    private InteropTestTool() {
    }

    static int run(String[] a) throws Exception {
        String spec = LivenessTools.option(a, "--device", "webcam:0");
        DeviceConformanceSuite.Options o = new DeviceConformanceSuite.Options();
        o.captureMs = (long) (1000 * Double.parseDouble(LivenessTools.option(a, "--seconds", "5")));
        o.minFps = Double.parseDouble(LivenessTools.option(a, "--min-fps", "10"));
        int benchmarkFrames = Integer.parseInt(LivenessTools.option(a, "--benchmark", "200"));
        Path out = Paths.get(LivenessTools.option(a, "--out", "interop-report.md"));

        FaceCaptureDevice device;
        LivenessEngine engine;
        double nominalFps;
        if (spec.equals("mock")) {
            MockFaceDevice mock = new MockFaceDevice(new SimulatedSubject(System::currentTimeMillis, new Random()), 15,
                    DeviceLevel.L1);
            device = mock;
            engine = new SimulatedLivenessEngine();
            nominalFps = 15;
            if ("auto".equals(LivenessTools.option(a, "--disconnect", null))) {
                o.disconnectAction = mock::simulateDisconnect;
            }
        } else {
            OpenCvLoader.ensureLoaded();
            engine = new OnnxLivenessEngine(OnnxLivenessEngine.defaultModelDir());
            nominalFps = 15;
            if (spec.startsWith("webcam:")) {
                device = new OpenCvCameraDevice(Integer.parseInt(spec.substring("webcam:".length())), 15);
            } else if (spec.startsWith("sbi:")) {
                String[] p = spec.split(":");
                DeviceInfo info = new DeviceInfo("SBI-FACE", "SBI", "Face stream " + p[1] + ":" + p[2], "unknown",
                        DeviceLevel.L1, "unknown", EnumSet.of(PixelFormat.JPEG));
                device = new SbiFaceStreamDevice(p[1], Integer.parseInt(p[2]), p.length > 3 ? p[3] : "1", info);
            } else {
                System.err.println("Unknown --device '" + spec + "'. Use one of:\n"
                        + "  webcam:<index>              e.g. webcam:0\n"
                        + "  sbi:<host>:<port>[:<subId>] e.g. sbi:127.0.0.1:4501\n"
                        + "  mock");
                return 2;
            }
        }
        if (!spec.equals("mock") && !Arrays.asList(a).contains("--yes") && !askPermission(spec)) {
            System.out.println("Camera permission not given; the camera was not turned on.");
            return 3;
        }
        if ("manual".equals(LivenessTools.option(a, "--disconnect", null))) {
            o.disconnectTimeoutMs = 30_000;
            o.disconnectAction = () -> System.out.println(
                    ">>> Unplug the face capture device now (30 s). Plug it back in once the disconnect is reported.");
        }

        System.out.println("Conformance test: " + device.info().describe());
        ConformanceReport conformance = new DeviceConformanceSuite().run(device, o);
        for (ConformanceReport.Check c : conformance.getChecks()) {
            System.out.printf("  %-20s %-4s %s%n", c.name, c.status, c.detail);
        }

        StringBuilder report = new StringBuilder("# Interoperability and performance report\n\n")
                .append("Generated ").append(Instant.now()).append(" on ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.arch")).append(", Java ").append(System.getProperty("java.version"))
                .append(", ").append(Runtime.getRuntime().availableProcessors()).append(" CPUs\n\n")
                .append(conformance.toMarkdown()).append('\n');

        if (benchmarkFrames > 0) {
            List<FaceFrame> frames;
            try {
                frames = EngineBenchmark.capture(device, 30, 10_000);
            } catch (Exception e) {
                frames = Collections.emptyList();
                System.out.println("  benchmark skipped: " + e.getMessage());
            }
            if (!frames.isEmpty()) {
                System.out.println("Benchmarking " + engine.info().describe() + " on " + frames.size() + " captured frames");
                EngineBenchmark.Result r = EngineBenchmark.run(engine, frames, 10, benchmarkFrames);
                System.out.printf("  median %.1f ms, p95 %.1f ms, %d failures, face in %d of %d frames%n",
                        r.p50Ms, r.p95Ms, r.failures, r.framesWithFace, r.frames);
                if (!r.representative()) {
                    System.out.println("  WARNING: few frames had a face; sit in front of the camera and re-run for real latency");
                }
                report.append(r.toMarkdown(nominalFps));
            }
        }
        engine.close();
        Files.write(out, report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println((conformance.passed() ? "PASS" : "FAIL") + " - report: " + out.toAbsolutePath());
        return conformance.passed() ? 0 : 1;
    }

    /** Asks on the terminal before turning on a real camera. No answer (e.g. no terminal) means no. */
    private static boolean askPermission(String spec) {
        System.out.print("This test turns on the camera (" + spec + ") for a short test. "
                + "Frames stay on this machine and are not saved. Allow camera use? [y/N] ");
        System.out.flush();
        try {
            String answer = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            return answer != null && (answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"));
        } catch (IOException e) {
            return false;
        }
    }
}
