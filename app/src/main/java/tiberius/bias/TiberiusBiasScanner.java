package tiberius.bias;

import io.tiberius.core.TiberiusScanner;
import io.tiberius.core.detector.Detector;
import io.tiberius.core.detector.PatternDetector;
import io.tiberius.core.generator.Generator;
import io.tiberius.dataset.DatasetScanReport;
import io.tiberius.dataset.DatasetScanResult;
import io.tiberius.dataset.RedTeamDataset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps Tiberius scanner for bias evaluation with multi-sample support.
 *
 * <p>This scanner:
 * <ol>
 *   <li>Takes a BiasDatasetBuilder with paired probes</li>
 *   <li>Runs Tiberius scanDataset() N times for multi-trial robustness</li>
 *   <li>Collects all responses grouped by probe ID</li>
 *   <li>Returns a MultiSampleScanReport for post-processing</li>
 * </ol>
 *
 * <p>The scanner does NOT perform bias detection itself - it only collects
 * responses. Bias detection is done by BiasReportComparator in post-processing.
 */
public class TiberiusBiasScanner {

    private final Generator generator;
    private final int samples;
    private volatile boolean cancelled = false;

    private TiberiusBiasScanner(Builder builder) {
        this.generator = builder.generator;
        this.samples = builder.samples;
    }

    /**
     * Progress callback for scan updates.
     */
    public interface ProgressCallback {
        void onProgress(int currentSample, int totalSamples, String probeId, String status);
    }

    /**
     * Scans the dataset with multiple samples per probe.
     *
     * @param datasetBuilder The bias dataset builder
     * @param callback       Optional progress callback
     * @return Multi-sample scan report
     */
    public MultiSampleScanReport scan(BiasDatasetBuilder datasetBuilder, ProgressCallback callback) {
        RedTeamDataset dataset = datasetBuilder.build();

        // Use a simple detector that doesn't interfere - we'll do bias detection in post-processing
        // PatternDetector with empty patterns will just pass through
        Detector passThruDetector = new PatternDetector(
                "passthru",
                "PassThrough",
                List.of(),  // No success patterns
                List.of()   // No refusal patterns
        );

        TiberiusScanner scanner = TiberiusScanner.builder()
                .withGenerator(generator)
                .withDetector(passThruDetector)
                .build();

        // Collect responses for each probe across all samples
        Map<String, List<String>> responsesByProbeId = new HashMap<>();
        Map<String, String> promptsByProbeId = new HashMap<>();

        // Initialize maps
        for (var entry : dataset.getEntries()) {
            responsesByProbeId.put(entry.id(), new ArrayList<>());
            promptsByProbeId.put(entry.id(), entry.prompt());
        }

        // Run N samples
        for (int sample = 1; sample <= samples && !cancelled; sample++) {
            if (callback != null) {
                callback.onProgress(sample, samples, "*", "Starting sample " + sample + "/" + samples);
            }

            DatasetScanReport report = scanner.scanDataset(dataset);

            // Collect responses
            for (DatasetScanResult result : report.results().values()) {
                String probeId = result.entry().id();
                responsesByProbeId.get(probeId).add(result.response());

                if (callback != null) {
                    callback.onProgress(sample, samples, probeId, "Collected response");
                }
            }
        }

        scanner.shutdown();

        return new MultiSampleScanReport(
                datasetBuilder.getPairs(),
                responsesByProbeId,
                promptsByProbeId,
                samples,
                cancelled
        );
    }

    /**
     * Cancels the current scan.
     */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * Resets the cancelled flag.
     */
    public void reset() {
        this.cancelled = false;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Generator generator;
        private int samples = 3;

        public Builder withGenerator(Generator generator) {
            this.generator = generator;
            return this;
        }

        public Builder samples(int samples) {
            this.samples = Math.max(1, samples);
            return this;
        }

        public TiberiusBiasScanner build() {
            if (generator == null) {
                throw new IllegalStateException("Generator is required");
            }
            return new TiberiusBiasScanner(this);
        }
    }

    /**
     * Report containing multi-sample responses for all probes.
     */
    public record MultiSampleScanReport(
            List<BiasDatasetBuilder.BiasProbePairDefinition> pairs,
            Map<String, List<String>> responsesByProbeId,
            Map<String, String> promptsByProbeId,
            int samplesPerProbe,
            boolean cancelled
    ) {
        /**
         * Gets all responses for a specific probe ID.
         */
        public List<String> getResponses(String probeId) {
            return responsesByProbeId.getOrDefault(probeId, List.of());
        }

        /**
         * Gets the prompt for a specific probe ID.
         */
        public String getPrompt(String probeId) {
            return promptsByProbeId.get(probeId);
        }

        /**
         * Gets responses for variant A of a pair.
         */
        public List<String> getResponsesA(BiasDatasetBuilder.BiasProbePairDefinition pair) {
            return getResponses(pair.idA());
        }

        /**
         * Gets responses for variant B of a pair.
         */
        public List<String> getResponsesB(BiasDatasetBuilder.BiasProbePairDefinition pair) {
            return getResponses(pair.idB());
        }

        /**
         * Total number of responses collected.
         */
        public int totalResponses() {
            return responsesByProbeId.values().stream()
                    .mapToInt(List::size)
                    .sum();
        }
    }
}
