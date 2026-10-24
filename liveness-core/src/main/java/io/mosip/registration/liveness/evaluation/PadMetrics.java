package io.mosip.registration.liveness.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * PAD error rates in the terms of ISO/IEC 30107-3:2023, computed from scored presentations:
 *
 * <ul>
 *   <li><b>APCER</b> per PAI species: share of attack presentations of that species accepted as bona fide.</li>
 *   <li><b>APCER (worst case)</b>: the highest per-species APCER. 30107-3 reports the maximum over species
 *       rather than an average, because an attacker uses the species that works best.</li>
 *   <li><b>BPCER</b>: share of bona fide presentations rejected.</li>
 *   <li><b>BPCER10 / BPCER20 / BPCER100</b>: lowest BPCER at a threshold where worst-case APCER is at most
 *       10% / 5% / 1%; the threshold that achieves it is the calibrated operating point.</li>
 *   <li>Non-response rates, reported separately. A non-response is never counted as acceptance, so it adds
 *       to BPCER for bona fide presentations and lowers APCER for attacks; a high attack non-response
 *       rate therefore needs a separate look.</li>
 * </ul>
 *
 * Every rate carries a Wilson 95% confidence interval: with a few hundred presentations a 0% APCER still
 * means "probably below a few percent", and the report should say so.
 */
public final class PadMetrics {

    /** A proportion with its 95% Wilson score interval. */
    public static final class Rate {
        public final int errors;
        public final int total;

        Rate(int errors, int total) {
            this.errors = errors;
            this.total = total;
        }

        public double value() {
            return total == 0 ? Double.NaN : errors / (double) total;
        }

        public double lower() {
            return wilson(-1);
        }

        public double upper() {
            return wilson(1);
        }

        private double wilson(int sign) {
            if (total == 0) {
                return Double.NaN;
            }
            double z = 1.959964;
            double p = value();
            double n = total;
            double centre = p + z * z / (2 * n);
            double margin = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n));
            return Math.max(0, Math.min(1, (centre + sign * margin) / (1 + z * z / n)));
        }
    }

    /** Error rates at one operating point. */
    public static final class OperatingPoint {
        public final String name;
        public final double threshold;
        public final Map<String, Rate> apcerBySpecies;
        public final Rate bpcer;

        OperatingPoint(String name, double threshold, Map<String, Rate> apcerBySpecies, Rate bpcer) {
            this.name = name;
            this.threshold = threshold;
            this.apcerBySpecies = Collections.unmodifiableMap(apcerBySpecies);
            this.bpcer = bpcer;
        }

        /** Worst-case APCER over species, or null without attack presentations. */
        public Rate apcerWorst() {
            Rate worst = null;
            for (Rate r : apcerBySpecies.values()) {
                if (worst == null || r.value() > worst.value()) {
                    worst = r;
                }
            }
            return worst;
        }

        public String worstSpecies() {
            String species = null;
            double worst = -1;
            for (Map.Entry<String, Rate> e : apcerBySpecies.entrySet()) {
                if (e.getValue().value() > worst) {
                    worst = e.getValue().value();
                    species = e.getKey();
                }
            }
            return species;
        }
    }

    /** One point of the detection error trade-off (DET) curve. */
    public static final class DetPoint {
        public final double threshold;
        public final double apcerWorst;
        public final double bpcer;

        DetPoint(double threshold, double apcerWorst, double bpcer) {
            this.threshold = threshold;
            this.apcerWorst = apcerWorst;
            this.bpcer = bpcer;
        }
    }

    private final List<ScoredPresentation> presentations;

    public PadMetrics(List<ScoredPresentation> presentations) {
        this.presentations = Collections.unmodifiableList(new ArrayList<>(presentations));
    }

    public List<ScoredPresentation> presentations() {
        return presentations;
    }

    /** Species present in the data, sorted. */
    public List<String> species() {
        TreeSet<String> s = new TreeSet<>();
        for (ScoredPresentation p : presentations) {
            if (!p.isBonaFide()) {
                s.add(p.getSpecies());
            }
        }
        return new ArrayList<>(s);
    }

    public int bonaFideCount() {
        return count(ScoredPresentation::isBonaFide);
    }

    public int attackCount() {
        return count(p -> !p.isBonaFide());
    }

    /** Share of bona fide (species null) or attack-species presentations the subsystem could not process. */
    public Rate nonResponse(String species) {
        return rate(p -> same(p.getSpecies(), species), p -> !p.isResponse());
    }

    public OperatingPoint atThreshold(String name, double threshold) {
        return point(name, threshold, p -> p.acceptedAt(threshold));
    }

    /** The engine's own attack/bona fide verdict, independent of any score threshold. */
    public OperatingPoint engineVerdict() {
        return point("engine PAD verdict", Double.NaN, ScoredPresentation::acceptedByEngine);
    }

    /** DET curve over every distinct score, plus the two extremes. */
    public List<DetPoint> detCurve() {
        TreeSet<Double> thresholds = new TreeSet<>();
        thresholds.add(0.0);
        thresholds.add(100.000001);
        for (ScoredPresentation p : presentations) {
            if (p.isResponse()) {
                thresholds.add((double) p.getScore());
            }
        }
        List<DetPoint> curve = new ArrayList<>();
        for (double t : thresholds) {
            OperatingPoint op = atThreshold("", t);
            Rate worst = op.apcerWorst();
            curve.add(new DetPoint(t, worst == null ? Double.NaN : worst.value(), op.bpcer.value()));
        }
        return curve;
    }

    /**
     * Lowest-BPCER threshold at which worst-case APCER is at most {@code maxApcer} (BPCER10 for 0.10,
     * BPCER20 for 0.05, BPCER100 for 0.01). Of thresholds with equal BPCER, the highest is chosen (the most
     * conservative). @return null if no threshold reaches the target or there are no attacks
     */
    public OperatingPoint bpcerAtApcer(double maxApcer) {
        OperatingPoint best = null;
        for (DetPoint d : detCurve()) {
            if (Double.isNaN(d.apcerWorst) || d.apcerWorst > maxApcer) {
                continue;
            }
            if (best == null || d.bpcer < best.bpcer.value()
                    || (d.bpcer == best.bpcer.value() && d.threshold > best.threshold)) {
                best = atThreshold("BPCER" + Math.round(1 / maxApcer), d.threshold);
            }
        }
        return best;
    }

    /** Threshold where worst-case APCER and BPCER are closest (an equal-error point). */
    public DetPoint equalErrorPoint() {
        DetPoint best = null;
        for (DetPoint d : detCurve()) {
            if (Double.isNaN(d.apcerWorst) || Double.isNaN(d.bpcer)) {
                continue;
            }
            if (best == null || Math.abs(d.apcerWorst - d.bpcer) < Math.abs(best.apcerWorst - best.bpcer)) {
                best = d;
            }
        }
        return best;
    }

    private OperatingPoint point(String name, double threshold, Predicate<ScoredPresentation> accepted) {
        Map<String, Rate> apcer = new TreeMap<>();
        for (String s : species()) {
            apcer.put(s, rate(p -> s.equals(p.getSpecies()), accepted));
        }
        Rate bpcer = rate(ScoredPresentation::isBonaFide, accepted.negate());
        return new OperatingPoint(name, threshold, apcer, bpcer);
    }

    private Rate rate(Predicate<ScoredPresentation> population, Predicate<ScoredPresentation> error) {
        int total = 0;
        int errors = 0;
        for (ScoredPresentation p : presentations) {
            if (population.test(p)) {
                total++;
                if (error.test(p)) {
                    errors++;
                }
            }
        }
        return new Rate(errors, total);
    }

    private int count(Predicate<ScoredPresentation> filter) {
        int n = 0;
        for (ScoredPresentation p : presentations) {
            if (filter.test(p)) {
                n++;
            }
        }
        return n;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
