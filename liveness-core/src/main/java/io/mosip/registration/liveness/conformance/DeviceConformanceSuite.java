package io.mosip.registration.liveness.conformance;

import io.mosip.registration.liveness.conformance.ConformanceReport.Status;
import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.DeviceInfo;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.device.PixelFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Interoperability test kit for {@link FaceCaptureDevice} adapters. Any vendor adapter (SBI stream,
 * Android camera, vendor SDK) can be run through it to show it behaves the way the liveness service
 * relies on:
 *
 * <ul>
 *   <li>identifies itself and declares its frame formats;</li>
 *   <li>opens, and reports failure as a {@link DeviceException} with a meaningful kind;</li>
 *   <li>delivers the first frame promptly and then a steady stream at a usable rate;</li>
 *   <li>sends only well-formed frames in the declared formats, at a consistent and sufficient resolution;</li>
 *   <li>numbers frames in order and timestamps them monotonically;</li>
 *   <li>stops delivering when told to, restarts cleanly, and closes idempotently;</li>
 *   <li>reports a disconnect through the listener and recovers after reconnection.</li>
 * </ul>
 *
 * The report records frame rate, jitter and first-frame latency, so it doubles as a device performance
 * test. The suite never needs a face: it tests the transport, not liveness.
 */
public final class DeviceConformanceSuite {

    /** Thresholds and optional steps. */
    public static final class Options {
        public long firstFrameTimeoutMs = 5_000;
        public long captureMs = 5_000;
        public double minFps = 10;
        /** Below this resolution the check warns: liveness models and ISO/IEC 19794-5 capture need detail. */
        public int minWidth = 640;
        public int minHeight = 480;
        /** p95 inter-frame gap above this multiple of the median gap is reported as jitter. */
        public double maxJitterRatio = 2.5;
        /** Pulls the plug (or asks a person to); null skips the disconnect checks. */
        public Runnable disconnectAction;
        public long disconnectTimeoutMs = 5_000;
    }

    private static final long STOP_GRACE_MS = 300;

    public ConformanceReport run(FaceCaptureDevice device, Options o) {
        DeviceInfo info = device.info();
        ConformanceReport report = new ConformanceReport(info == null ? "unknown device" : info.describe());
        checkInfo(info, report);

        try {
            device.open();
            report.add("open", Status.PASS, "device ready");
        } catch (DeviceException e) {
            report.add("open", Status.FAIL, e.getKind() + ": " + e.getMessage());
            skipRest(report, "device did not open");
            return report;
        } catch (RuntimeException e) {
            report.add("open", Status.FAIL, "threw " + e + " instead of DeviceException");
            skipRest(report, "device did not open");
            return report;
        }

        Collector stream = new Collector();
        long started = System.nanoTime();
        if (!start(device, stream, report, "stream start")) {
            skipRest(report, "stream did not start");
            device.close();
            return report;
        }
        boolean first = await(stream.firstFrame, o.firstFrameTimeoutMs);
        if (!first) {
            report.add("first frame", Status.FAIL, "no frame within " + o.firstFrameTimeoutMs + " ms");
            device.stopStream();
            skipRest(report, "no frames");
            device.close();
            return report;
        }
        long firstFrameMs = (stream.arrivals().get(0) - started) / 1_000_000;
        report.add("first frame", Status.PASS, firstFrameMs + " ms after startStream");
        report.measure("first frame latency", firstFrameMs + " ms");
        sleep(o.captureMs);
        device.stopStream();
        int atStop = stream.count();
        sleep(STOP_GRACE_MS);
        analyse(stream, info, o, report);
        int late = stream.count() - atStop;
        report.add("stop", late == 0 ? Status.PASS : Status.FAIL,
                late == 0 ? "no frames after stopStream" : late + " frame(s) delivered after stopStream returned");

        Collector restart = new Collector();
        if (start(device, restart, report, "restart")) {
            boolean ok = await(restart.firstFrame, o.firstFrameTimeoutMs);
            report.add("restart", ok ? Status.PASS : Status.FAIL,
                    ok ? "stream restarted after stop" : "no frame after restarting the stream");
            device.stopStream();
        }

        checkDisconnect(device, o, report);

        try {
            device.close();
            device.close();
            report.add("close", Status.PASS, "close is idempotent");
        } catch (RuntimeException e) {
            report.add("close", Status.FAIL, "second close threw " + e);
        }
        return report;
    }

    private static void checkInfo(DeviceInfo info, ConformanceReport report) {
        if (info == null) {
            report.add("device info", Status.FAIL, "info() returned null");
            return;
        }
        List<String> missing = new ArrayList<>();
        if (blank(info.getDeviceId())) {
            missing.add("deviceId");
        }
        if (blank(info.getMake())) {
            missing.add("make");
        }
        if (blank(info.getModel())) {
            missing.add("model");
        }
        if (info.getLevel() == null) {
            missing.add("level");
        }
        if (info.getFormats().isEmpty()) {
            missing.add("formats");
        }
        report.add("device info", missing.isEmpty() ? Status.PASS : Status.FAIL,
                missing.isEmpty() ? info.getLevel() + ", formats " + info.getFormats() : "missing " + missing);
    }

    private static void analyse(Collector s, DeviceInfo info, Options o, ConformanceReport report) {
        List<FaceFrame> frames = s.frames();
        List<Long> arrivals = s.arrivals();
        int malformed = 0;
        Set<PixelFormat> formats = EnumSet.noneOf(PixelFormat.class);
        Set<String> sizes = new LinkedHashSet<>();
        int outOfOrder = 0;
        int timeBackwards = 0;
        for (int i = 0; i < frames.size(); i++) {
            FaceFrame f = frames.get(i);
            if (!f.isWellFormed()) {
                malformed++;
            }
            formats.add(f.getFormat());
            sizes.add(f.getWidth() + "x" + f.getHeight());
            if (i > 0) {
                FaceFrame prev = frames.get(i - 1);
                if (f.getSequence() <= prev.getSequence()) {
                    outOfOrder++;
                }
                if (f.getTimestampMillis() < prev.getTimestampMillis()) {
                    timeBackwards++;
                }
            }
        }
        int n = frames.size();
        report.measure("frames received", Integer.toString(n));
        report.add("well-formed frames", malformed == 0 ? Status.PASS : Status.FAIL,
                malformed == 0 ? "all " + n + " frames well-formed" : malformed + " of " + n + " frames malformed");

        Set<PixelFormat> undeclared = EnumSet.noneOf(PixelFormat.class);
        undeclared.addAll(formats);
        if (info != null) {
            undeclared.removeAll(info.getFormats());
        }
        report.add("declared formats", undeclared.isEmpty() ? Status.PASS : Status.FAIL,
                undeclared.isEmpty() ? "frames in " + formats : "undeclared format(s) " + undeclared);

        FaceFrame last = frames.get(n - 1);
        String resolution = last.getWidth() + "x" + last.getHeight();
        report.measure("resolution", String.join(", ", sizes));
        if (sizes.size() > 1) {
            report.add("resolution", Status.WARN, "resolution changed during the stream: " + sizes);
        } else if (last.getWidth() < o.minWidth || last.getHeight() < o.minHeight) {
            report.add("resolution", Status.WARN, resolution + " is below " + o.minWidth + "x" + o.minHeight);
        } else {
            report.add("resolution", Status.PASS, resolution);
        }

        report.add("frame order", outOfOrder == 0 && timeBackwards == 0 ? Status.PASS : Status.FAIL,
                outOfOrder == 0 && timeBackwards == 0 ? "sequence increasing, timestamps monotonic"
                        : outOfOrder + " out-of-order sequence number(s), " + timeBackwards + " timestamp(s) going back");

        if (n < 3) {
            report.add("frame rate", Status.FAIL, "only " + n + " frame(s) in " + o.captureMs + " ms");
            return;
        }
        double seconds = (arrivals.get(n - 1) - arrivals.get(0)) / 1e9;
        double fps = (n - 1) / seconds;
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            gaps.add((arrivals.get(i) - arrivals.get(i - 1)) / 1e6);
        }
        Collections.sort(gaps);
        double median = gaps.get(gaps.size() / 2);
        double p95 = gaps.get(Math.min(gaps.size() - 1, (int) Math.ceil(0.95 * gaps.size()) - 1));
        report.measure("frame rate", String.format(Locale.ROOT, "%.1f fps", fps));
        report.measure("inter-frame gap", String.format(Locale.ROOT, "median %.1f ms, p95 %.1f ms, max %.1f ms",
                median, p95, gaps.get(gaps.size() - 1)));
        report.add("frame rate", fps >= o.minFps ? Status.PASS : Status.FAIL,
                String.format(Locale.ROOT, "%.1f fps (minimum %.0f)", fps, o.minFps));
        boolean jittery = median > 0 && p95 > o.maxJitterRatio * median;
        report.add("jitter", jittery ? Status.WARN : Status.PASS,
                String.format(Locale.ROOT, "p95 gap %.1f ms vs median %.1f ms", p95, median));
    }

    private static void checkDisconnect(FaceCaptureDevice device, Options o, ConformanceReport report) {
        if (o.disconnectAction == null) {
            report.add("disconnect reported", Status.SKIP, "no disconnect action configured");
            report.add("reconnect", Status.SKIP, "no disconnect action configured");
            return;
        }
        Collector s = new Collector();
        if (!start(device, s, report, "disconnect reported") || !await(s.firstFrame, o.firstFrameTimeoutMs)) {
            report.add("reconnect", Status.SKIP, "stream unavailable");
            return;
        }
        o.disconnectAction.run();
        boolean reported = await(s.ended, o.disconnectTimeoutMs);
        report.add("disconnect reported", reported ? Status.PASS : Status.FAIL, reported
                ? "listener told: " + (s.error != null ? "onStreamError " + s.error.getKind() : "onStreamEnded")
                : "no onStreamEnded/onStreamError within " + o.disconnectTimeoutMs + " ms");
        device.stopStream();
        device.close();
        try {
            device.open();
            Collector again = new Collector();
            device.startStream(again);
            boolean ok = await(again.firstFrame, o.firstFrameTimeoutMs);
            report.add("reconnect", ok ? Status.PASS : Status.FAIL, ok ? "frames again after close/open" : "no frames after reopening");
            device.stopStream();
        } catch (DeviceException e) {
            report.add("reconnect", Status.WARN, "not reconnected yet (" + e.getKind() + "): reconnect the device and re-run");
        }
    }

    private static boolean start(FaceCaptureDevice device, Collector c, ConformanceReport report, String check) {
        try {
            device.startStream(c);
            return true;
        } catch (DeviceException e) {
            report.add(check, Status.FAIL, "startStream failed: " + e.getKind() + ": " + e.getMessage());
            return false;
        }
    }

    private static void skipRest(ConformanceReport report, String why) {
        for (String name : new String[] {"first frame", "well-formed frames", "declared formats", "resolution",
                "frame order", "frame rate", "stop", "restart", "disconnect reported", "reconnect", "close"}) {
            if (report.check(name) == null) {
                report.add(name, Status.SKIP, why);
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean await(CountDownLatch latch, long ms) {
        try {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Records frames and stream events from the device thread. */
    private static final class Collector implements FrameListener {
        final CountDownLatch firstFrame = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        private final List<FaceFrame> frames = new ArrayList<>();
        private final List<Long> arrivals = new ArrayList<>();
        volatile DeviceException error;

        @Override
        public synchronized void onFrame(FaceFrame frame) {
            frames.add(frame);
            arrivals.add(System.nanoTime());
            firstFrame.countDown();
        }

        @Override
        public void onStreamError(DeviceException e) {
            error = e;
            ended.countDown();
        }

        @Override
        public void onStreamEnded() {
            ended.countDown();
        }

        synchronized int count() {
            return frames.size();
        }

        synchronized List<FaceFrame> frames() {
            return new ArrayList<>(frames);
        }

        synchronized List<Long> arrivals() {
            return new ArrayList<>(arrivals);
        }
    }
}
