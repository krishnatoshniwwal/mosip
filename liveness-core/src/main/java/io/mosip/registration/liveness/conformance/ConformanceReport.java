package io.mosip.registration.liveness.conformance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outcome of running a device through {@link DeviceConformanceSuite}. */
public final class ConformanceReport {

    public enum Status { PASS, WARN, FAIL, SKIP }

    /** One check and what was observed. */
    public static final class Check {
        public final String name;
        public final Status status;
        public final String detail;

        Check(String name, Status status, String detail) {
            this.name = name;
            this.status = status;
            this.detail = detail;
        }
    }

    private final String device;
    private final List<Check> checks = new ArrayList<>();
    private final Map<String, String> measurements = new LinkedHashMap<>();

    ConformanceReport(String device) {
        this.device = device;
    }

    void add(String name, Status status, String detail) {
        checks.add(new Check(name, status, detail));
    }

    void measure(String name, String value) {
        measurements.put(name, value);
    }

    public String getDevice() { return device; }
    public List<Check> getChecks() { return Collections.unmodifiableList(checks); }
    public Map<String, String> getMeasurements() { return Collections.unmodifiableMap(measurements); }

    /** True when no check failed (warnings and skipped checks allowed). */
    public boolean passed() {
        for (Check c : checks) {
            if (c.status == Status.FAIL) {
                return false;
            }
        }
        return true;
    }

    public Check check(String name) {
        for (Check c : checks) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Device conformance: ").append(device).append("\n\n");
        sb.append("Result: **").append(passed() ? "PASS" : "FAIL").append("**\n\n");
        sb.append("| Check | Status | Detail |\n|---|---|---|\n");
        for (Check c : checks) {
            sb.append("| ").append(c.name).append(" | ").append(c.status).append(" | ")
                    .append(c.detail.replace("|", "\\|")).append(" |\n");
        }
        if (!measurements.isEmpty()) {
            sb.append("\n| Measurement | Value |\n|---|---|\n");
            for (Map.Entry<String, String> m : measurements.entrySet()) {
                sb.append("| ").append(m.getKey()).append(" | ").append(m.getValue()).append(" |\n");
            }
        }
        return sb.toString();
    }
}
