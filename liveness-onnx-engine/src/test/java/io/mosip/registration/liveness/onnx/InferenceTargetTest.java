package io.mosip.registration.liveness.onnx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Target choice with fake trials of known speed and output; the real models are exercised by the live engine. */
class InferenceTargetTest {

    private static final List<InferenceTarget> GPUS = Arrays.asList(InferenceTarget.OPENCL, InferenceTarget.OPENCL_FP16);

    @BeforeEach
    void auto() {
        System.setProperty(InferenceTarget.PROPERTY, "auto");
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty(InferenceTarget.PROPERTY);
    }

    @Test
    void cpuIsTheDefaultAndUnknownValuesFallBackToIt() {
        System.clearProperty(InferenceTarget.PROPERTY);
        assertEquals(InferenceTarget.CPU, InferenceTarget.configured());
        System.setProperty(InferenceTarget.PROPERTY, "cuda");
        assertEquals(InferenceTarget.CPU, InferenceTarget.configured());
    }

    @Test
    void aClearlyFasterGpuWithTheSameOutputIsChosen() {
        InferenceTarget.Choice c = InferenceTarget.choose("m", GPUS,
                t -> trial(t == InferenceTarget.CPU ? 6 : 1, 1f), 0.005);
        assertEquals(InferenceTarget.OPENCL, c.target, c.report);
        assertTrue(c.report.endsWith("-> opencl"), c.report);
    }

    @Test
    void aSlowerGpuKeepsTheCpu() {
        InferenceTarget.Choice c = InferenceTarget.choose("m", GPUS,
                t -> trial(t == InferenceTarget.CPU ? 1 : 6, 1f), 0.005);
        assertEquals(InferenceTarget.CPU, c.target, c.report);
    }

    @Test
    void aFasterGpuWithDifferentOutputIsRejected() {
        InferenceTarget.Choice c = InferenceTarget.choose("m", GPUS,
                t -> trial(t == InferenceTarget.CPU ? 6 : 1, t == InferenceTarget.CPU ? 1f : 1.1f), 0.005);
        assertEquals(InferenceTarget.CPU, c.target);
        assertTrue(c.report.contains("opencl differs"), c.report);
    }

    @Test
    void anUnusableGpuIsSkipped() {
        InferenceTarget.Choice c = InferenceTarget.choose("m", GPUS, t -> {
            if (t != InferenceTarget.CPU) {
                throw new IllegalStateException("no OpenCL device");
            }
            return trial(1, 1f);
        }, 0.005);
        assertEquals(InferenceTarget.CPU, c.target);
        assertTrue(c.report.contains("opencl unavailable"), c.report);
    }

    @Test
    void forcingCpuRunsNoBenchmark() {
        System.setProperty(InferenceTarget.PROPERTY, "cpu");
        AtomicInteger loads = new AtomicInteger();
        InferenceTarget.Choice c = InferenceTarget.choose("m", GPUS, t -> {
            loads.incrementAndGet();
            return trial(1, 1f);
        }, 0.005);
        assertEquals(InferenceTarget.CPU, c.target);
        assertEquals(0, loads.get());
    }

    @Test
    void aForcedGpuIsUsedEvenIfSlowerButNeverWithWrongOutput() {
        System.setProperty(InferenceTarget.PROPERTY, "opencl_fp16");
        assertEquals(InferenceTarget.OPENCL_FP16, InferenceTarget.choose("m", GPUS,
                t -> trial(t == InferenceTarget.CPU ? 1 : 3, 1f), 0.005).target);
        assertEquals(InferenceTarget.CPU, InferenceTarget.choose("m", GPUS,
                t -> trial(1, t == InferenceTarget.CPU ? 1f : 2f), 0.005).target);
    }

    @Test
    void differenceIsRelativeToTheLargestOutput() {
        assertEquals(0.01, InferenceTarget.relativeDifference(new float[] {100, 0}, new float[] {101, 0}), 1e-6);
        assertTrue(Double.isInfinite(InferenceTarget.relativeDifference(new float[2], new float[3])));
    }

    /**
     * A trial taking {@code ms} per run and returning {value, 0.5}. Busy-waits: Thread.sleep rounds to the
     * Windows timer tick (about 15 ms), which would make 1 ms and 6 ms trials equally slow.
     */
    private static InferenceTarget.Trial trial(long ms, float value) {
        return () -> {
            long end = System.nanoTime() + ms * 1_000_000;
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            return new float[] {value, 0.5f};
        };
    }
}
