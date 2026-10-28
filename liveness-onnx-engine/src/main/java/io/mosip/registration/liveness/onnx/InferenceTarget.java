package io.mosip.registration.liveness.onnx;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where OpenCV runs a model: CPU or the GPU through OpenCL (full or half precision).
 *
 * <p>{@value #PROPERTY} = {@code cpu} (default), {@code auto}, {@code opencl} or {@code opencl_fp16}.
 * {@code auto} times each model on every target the machine offers, checks that the GPU gives the same output as
 * the CPU, and keeps the fastest; a GPU is used only when it is clearly faster. A forced GPU is used if its
 * output matches, otherwise CPU.
 *
 * <p>CPU is the default because, measured on a laptop with Intel UHD graphics: the first OpenCL use compiled GPU
 * kernels for about 3.5 minutes before the engine could start (about 2 s on later starts), the GPU was slower for
 * the face mesh and iris models (2.2 vs 7.4 ms, 3.4 vs 14 ms), and half precision moved outputs by 0.6-1 %, so
 * it was rejected. Turn {@code auto} on for machines with a discrete GPU, and start the client once ahead of use.
 */
enum InferenceTarget {
    CPU(Dnn.DNN_TARGET_CPU),
    OPENCL(Dnn.DNN_TARGET_OPENCL),
    OPENCL_FP16(Dnn.DNN_TARGET_OPENCL_FP16);

    /** System property choosing the target: auto, cpu, opencl or opencl_fp16. */
    static final String PROPERTY = "mosip.liveness.acceleration";

    /** A GPU must be at least this much faster than the CPU to be chosen, so timing noise never flips it. */
    static final double MIN_SPEEDUP = 1.15;
    private static final int WARMUP_RUNS = 2;
    private static final int TIMED_RUNS = 8;
    private static final Logger LOG = Logger.getLogger(InferenceTarget.class.getName());

    final int dnnTarget;

    InferenceTarget(int dnnTarget) {
        this.dnnTarget = dnnTarget;
    }

    int dnnBackend() {
        return Dnn.DNN_BACKEND_OPENCV;
    }

    String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** One model loaded for one target, run on a fixed input. */
    interface Trial extends AutoCloseable {
        /** @return the model's raw output, compared across targets */
        float[] run();

        @Override
        default void close() {
        }
    }

    /** The target picked for a model, and how it was picked (for logs and diagnostics). */
    static final class Choice {
        final InferenceTarget target;
        final String report;

        Choice(InferenceTarget target, String report) {
            this.target = target;
            this.report = report;
        }

        @Override
        public String toString() {
            return report;
        }
    }

    /** The configured value: cpu (default), auto, opencl or opencl_fp16. */
    static String setting() {
        return System.getProperty(PROPERTY, "cpu").trim().toLowerCase(Locale.ROOT);
    }

    /** @return the forced target, or null for auto */
    static InferenceTarget configured() {
        String value = setting();
        if (value.equals("auto")) {
            return null;
        }
        for (InferenceTarget t : values()) {
            if (t.label().equals(value)) {
                return t;
            }
        }
        LOG.warning("Unknown " + PROPERTY + "=" + value + "; using cpu");
        return CPU;
    }

    /**
     * Picks a target for {@code model}.
     *
     * @param candidates GPU targets worth trying besides CPU
     * @param trials     loads the model for a target; may throw if the target is unusable
     * @param tolerance  largest difference from the CPU output allowed, relative to the CPU output's largest value
     */
    static Choice choose(String model, List<InferenceTarget> candidates, Function<InferenceTarget, Trial> trials,
                         double tolerance) {
        InferenceTarget forced = configured();
        if (forced == CPU) {
            return new Choice(CPU, model + ": cpu (configured)");
        }
        List<InferenceTarget> gpus = new ArrayList<>(candidates);
        gpus.remove(CPU);
        if (forced != null) {
            gpus.retainAll(java.util.Collections.singletonList(forced));
            if (gpus.isEmpty()) {
                return new Choice(CPU, model + ": cpu (" + forced.label() + " not supported for this model)");
            }
        }

        float[] reference;
        double cpuMs;
        try (Trial cpu = trials.apply(CPU)) {
            reference = warmUp(cpu);
            cpuMs = time(cpu);
        } catch (RuntimeException e) {
            // The CPU path is what the engine always had; let the caller's own loading report the problem.
            return new Choice(CPU, model + ": cpu (benchmark failed: " + e.getMessage() + ")");
        }

        StringBuilder report = new StringBuilder(String.format(Locale.ROOT, "%s: cpu %.1f ms", model, cpuMs));
        InferenceTarget best = CPU;
        double bestMs = cpuMs;
        for (InferenceTarget gpu : gpus) {
            try (Trial trial = trials.apply(gpu)) {
                float[] out = warmUp(trial);
                double diff = relativeDifference(reference, out);
                if (!(diff <= tolerance)) {
                    report.append(String.format(Locale.ROOT, ", %s differs (%.3g)", gpu.label(), diff));
                    continue;
                }
                double ms = time(trial);
                report.append(String.format(Locale.ROOT, ", %s %.1f ms", gpu.label(), ms));
                if (forced != null || ms * MIN_SPEEDUP <= bestMs) {
                    best = gpu;
                    bestMs = ms;
                }
            } catch (RuntimeException e) {
                report.append(", ").append(gpu.label()).append(" unavailable");
                LOG.log(Level.FINE, gpu.label() + " unusable for " + model, e);
            }
        }
        report.append(" -> ").append(best.label());
        return new Choice(best, report.toString());
    }

    /**
     * A trial running {@code net} (freshly loaded for this target) on a fixed pseudo-random size x size RGB input
     * in 0..1, returning all its outputs concatenated. The same input on every target makes outputs comparable.
     */
    static Trial netTrial(Net net, InferenceTarget target, int size) {
        net.setPreferableBackend(target.dnnBackend());
        net.setPreferableTarget(target.dnnTarget);
        List<String> outputs = net.getUnconnectedOutLayersNames();
        float[] values = new float[3 * size * size];
        long seed = 42;
        for (int i = 0; i < values.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            values[i] = (seed >>> 40) / (float) (1L << 24);
        }
        Mat input = new Mat(new int[] {1, 3, size, size}, CvType.CV_32F);
        input.put(new int[] {0, 0, 0, 0}, values);
        return new Trial() {
            @Override
            public float[] run() {
                List<Mat> results = new ArrayList<>();
                net.setInput(input);
                net.forward(results, outputs);
                int total = 0;
                for (Mat m : results) {
                    total += (int) m.total();
                }
                float[] all = new float[total];
                int offset = 0;
                for (Mat m : results) {
                    float[] part = new float[(int) m.total()];
                    m.reshape(1, 1).get(0, 0, part);
                    System.arraycopy(part, 0, all, offset, part.length);
                    offset += part.length;
                    m.release();
                }
                return all;
            }

            @Override
            public void close() {
                input.release();
            }
        };
    }

    private static float[] warmUp(Trial trial) {
        float[] out = null;
        for (int i = 0; i < WARMUP_RUNS; i++) {
            out = trial.run();   // the first GPU run also compiles its kernels
        }
        return out;
    }

    private static double time(Trial trial) {
        long start = System.nanoTime();
        for (int i = 0; i < TIMED_RUNS; i++) {
            trial.run();
        }
        return (System.nanoTime() - start) / 1e6 / TIMED_RUNS;
    }

    /** Largest absolute difference divided by the reference's largest magnitude; infinite if the shapes differ. */
    static double relativeDifference(float[] reference, float[] other) {
        if (reference == null || other == null || reference.length != other.length) {
            return Double.POSITIVE_INFINITY;
        }
        double maxDiff = 0;
        double scale = 1e-6;
        for (int i = 0; i < reference.length; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(reference[i] - other[i]));
            scale = Math.max(scale, Math.abs(reference[i]));
        }
        return maxDiff / scale;
    }
}
