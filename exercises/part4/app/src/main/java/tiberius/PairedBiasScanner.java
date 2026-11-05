package tiberius;

import io.tiberius.core.generator.Generator;
import io.tiberius.core.generator.GeneratorResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Scanner for paired bias probes with multi-trial support.
 *
 * <p>This scanner implements the Weg 2 approach:
 * <ol>
 *   <li>Run each probe variant multiple times (multi-trial robustness)</li>
 *   <li>Collect all responses</li>
 *   <li>Use LLM judge to compare paired responses for differential treatment</li>
 * </ol>
 *
 * <p>The multi-trial approach provides statistical robustness against LLM non-determinism.
 */
public class PairedBiasScanner {

    private final Generator targetGenerator;
    private final BiasComparator comparator;
    private final int samples;
    private volatile boolean cancelled = false;

    private PairedBiasScanner(Builder builder) {
        this.targetGenerator = builder.targetGenerator;
        this.comparator = builder.comparator;
        this.samples = builder.samples;
    }

    /**
     * Result of scanning all bias probe pairs.
     */
    public record ScanReport(
            int totalPairs,
            int biasedPairs,
            double biasRate,
            List<BiasComparator.ComparisonResult> results
    ) {}

    /**
     * Progress callback for scan updates.
     */
    public interface ProgressCallback {
        void onProgress(String pairId, int currentPair, int totalPairs, String status);
    }

    /**
     * Scans all registered bias probe pairs.
     *
     * @param progressCallback Optional callback for progress updates
     * @return Scan report with results
     */
    public ScanReport scan(ProgressCallback progressCallback) {
        return scan(BiasProbeRegistry.getAllPairs(), progressCallback);
    }

    /**
     * Scans specific bias probe pairs.
     *
     * @param pairs            Pairs to scan
     * @param progressCallback Optional callback for progress updates
     * @return Scan report with results
     */
    public ScanReport scan(List<BiasProbePair> pairs, ProgressCallback progressCallback) {
        List<BiasComparator.ComparisonResult> results = new ArrayList<>();
        int totalPairs = pairs.size();
        int biasedCount = 0;

        for (int i = 0; i < pairs.size(); i++) {
            if (cancelled) {
                break;
            }

            BiasProbePair pair = pairs.get(i);
            int currentPair = i + 1;

            if (progressCallback != null) {
                progressCallback.onProgress(pair.pairId(), currentPair, totalPairs,
                        "Collecting responses for " + pair.pairId());
            }

            // Collect multiple responses for each variant (multi-trial)
            List<String> responsesA = collectResponses(pair.promptA(), samples);
            List<String> responsesB = collectResponses(pair.promptB(), samples);

            if (cancelled) {
                break;
            }

            if (progressCallback != null) {
                progressCallback.onProgress(pair.pairId(), currentPair, totalPairs,
                        "Analyzing bias for " + pair.pairId());
            }

            // Compare responses using LLM judge
            BiasComparator.ComparisonResult result = comparator.compare(pair, responsesA, responsesB);
            results.add(result);

            if (result.biasDetected()) {
                biasedCount++;
            }

            if (progressCallback != null) {
                progressCallback.onProgress(pair.pairId(), currentPair, totalPairs,
                        result.biasDetected() ? "BIASED: " + result.explanation() : "FAIR");
            }
        }

        double biasRate = totalPairs > 0 ? (biasedCount * 100.0) / totalPairs : 0.0;
        return new ScanReport(totalPairs, biasedCount, biasRate, results);
    }

    /**
     * Collects multiple responses for a prompt (multi-trial).
     */
    private List<String> collectResponses(String prompt, int count) {
        List<String> responses = new ArrayList<>();
        for (int i = 0; i < count && !cancelled; i++) {
            GeneratorResponse response = targetGenerator.generate(prompt);
            if (response.success()) {
                responses.add(response.content());
            } else {
                responses.add("[ERROR: " + response.error() + "]");
            }
        }
        return responses;
    }

    /**
     * Cancels the current scan.
     */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * Resets the cancelled flag for a new scan.
     */
    public void reset() {
        this.cancelled = false;
    }

    /**
     * Returns true if the scan was cancelled.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Generator targetGenerator;
        private BiasComparator comparator;
        private int samples = 3; // Default: 3 samples per probe for robustness

        public Builder withTargetGenerator(Generator generator) {
            this.targetGenerator = generator;
            return this;
        }

        public Builder withComparator(BiasComparator comparator) {
            this.comparator = comparator;
            return this;
        }

        public Builder samples(int samples) {
            this.samples = samples;
            return this;
        }

        public PairedBiasScanner build() {
            if (targetGenerator == null) {
                throw new IllegalStateException("Target generator is required");
            }
            if (comparator == null) {
                throw new IllegalStateException("Comparator is required");
            }
            return new PairedBiasScanner(this);
        }
    }
}
