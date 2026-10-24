package io.mosip.registration.liveness.conformance;

import io.mosip.registration.liveness.device.DeviceException;
import io.mosip.registration.liveness.device.FaceCaptureDevice;
import io.mosip.registration.liveness.device.FaceFrame;
import io.mosip.registration.liveness.device.FrameListener;
import io.mosip.registration.liveness.engine.FrameAnalysis;
import io.mosip.registration.liveness.engine.LivenessEngine;
import io.mosip.registration.liveness.engine.LivenessEngineException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Performance test for a {@link LivenessEngine} on the hardware it will run on: per-frame analysis
 * latency (median, p95, p99, max), throughput, failures and heap growth. The session keeps only the
 * newest frame, so the engine is real-time capable at a device frame rate when its p95 latency fits
 * in one frame interval; slower engines still work but see fewer frames per challenge.
 */
public final class EngineBenchmark {

    /** Latency statistics in milliseconds. */
    public static final class Result {
        public final String engine;
        public final int frames;
        public final int failures;
        /** Timed frames in which the engine found exactly one face (the full PAD path ran). */
        public final int framesWithFace;
        public final double meanMs;
        public final double p50Ms;
        public final double p95Ms;
        public final double p99Ms;
        public final double maxMs;
        public final long heapGrowthBytes;

        Result(String engine, int frames, int failures, int framesWithFace, List<Double> sortedMs, long heapGrowthBytes) {
            this.engine = engine;
            this.frames = frames;
            this.failures = failures;
            this.framesWithFace = framesWithFace;
            double sum = 0;
            for (double v : sortedMs) {
                sum += v;
            }
            this.meanMs = sortedMs.isEmpty() ? Double.NaN : sum / sortedMs.size();
            this.p50Ms = percentile(sortedMs, 0.50);
            this.p95Ms = percentile(sortedMs, 0.95);
            this.p99Ms = percentile(sortedMs, 0.99);
            this.maxMs = sortedMs.isEmpty() ? Double.NaN : sortedMs.get(sortedMs.size() - 1);
            this.heapGrowthBytes = heapGrowthBytes;
        }

        /** Frames per second one worker thread can analyse. */
        public double throughputFps() {
            return 1000.0 / meanMs;
        }

        /** Whether p95 latency fits within one frame interval at {@code fps}. */
        public boolean realTimeAt(double fps) {
            return p95Ms <= 1000.0 / fps;
        }

        /** Whether most frames exercised the full pipeline; without a face, engines skip PAD and look faster. */
        public boolean representative() {
            return framesWithFace * 2 >= frames;
        }

        public String toMarkdown(double deviceFps) {
            String face = String.format(Locale.ROOT, "| frames with one face | %d of %d%s |%n", framesWithFace, frames,
                    representative() ? "" : " (**not representative**: with no face the engine skips PAD; re-run with a"
                            + " person in front of the camera)");
            return String.format(Locale.ROOT, "## Engine benchmark: %s%n%n| Measurement | Value |%n|---|---|%n"
                            + "| frames analysed | %d (%d failed) |%n" + face.replace("%", "%%") + "| latency | mean %.1f ms, median %.1f ms, p95 %.1f ms, "
                            + "p99 %.1f ms, max %.1f ms |%n| throughput | %.1f fps per worker |%n"
                            + "| real-time at %.0f fps | %s |%n| heap growth | %.1f MB |%n",
                    engine, frames, failures, meanMs, p50Ms, p95Ms, p99Ms, maxMs, throughputFps(), deviceFps,
                    realTimeAt(deviceFps) ? "yes" : "no: the session drops frames (still correct, fewer samples)",
                    heapGrowthBytes / 1e6);
        }

        private static double percentile(List<Double> sorted, double q) {
            if (sorted.isEmpty()) {
                return Double.NaN;
            }
            return sorted.get(Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(q * sorted.size()) - 1)));
        }
    }

    private EngineBenchmark() {
    }

    /**
     * Analyses {@code frames} after {@code warmup} untimed passes (JIT, model initialisation).
     * Frames are reused cyclically when {@code iterations} exceeds their number.
     */
    public static Result run(LivenessEngine engine, List<FaceFrame> frames, int warmup, int iterations) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("No frames to benchmark");
        }
        for (int i = 0; i < warmup; i++) {
            try {
                engine.analyze(frames.get(i % frames.size()));
            } catch (LivenessEngineException | RuntimeException ignored) {
                // counted in the timed run
            }
        }
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        List<Double> ms = new ArrayList<>(iterations);
        int failures = 0;
        int withFace = 0;
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            try {
                FrameAnalysis analysis = engine.analyze(frames.get(i % frames.size()));
                ms.add((System.nanoTime() - t0) / 1e6);
                if (analysis.getFaces().size() == 1) {
                    withFace++;
                }
            } catch (LivenessEngineException | RuntimeException e) {
                failures++;
            }
        }
        System.gc();
        long heapAfter = rt.totalMemory() - rt.freeMemory();
        Collections.sort(ms);
        return new Result(engine.info().describe(), iterations, failures, withFace, ms, Math.max(0, heapAfter - heapBefore));
    }

    /** Captures up to {@code count} frames from a device (opened and closed here). */
    public static List<FaceFrame> capture(FaceCaptureDevice device, int count, long timeoutMs) throws DeviceException {
        List<FaceFrame> frames = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        device.open();
        try {
            device.startStream(new FrameListener() {
                @Override
                public void onFrame(FaceFrame frame) {
                    if (frames.size() < count) {
                        frames.add(frame);
                    }
                    if (frames.size() >= count) {
                        done.countDown();
                    }
                }

                @Override
                public void onStreamError(DeviceException error) {
                    done.countDown();
                }

                @Override
                public void onStreamEnded() {
                    done.countDown();
                }
            });
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            device.stopStream();
            device.close();
        }
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }
}
