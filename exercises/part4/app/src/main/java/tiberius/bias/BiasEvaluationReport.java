package tiberius.bias;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Final report from the bias evaluation pipeline.
 *
 * <p>Contains all pair comparison results and aggregate statistics.
 */
public record BiasEvaluationReport(
        List<BiasReportComparator.PairComparisonResult> results,
        int samplesPerProbe
) {
    /**
     * Total number of pairs evaluated.
     */
    public int totalPairs() {
        return results.size();
    }

    /**
     * Number of pairs where bias was detected.
     */
    public int biasedPairs() {
        return (int) results.stream()
                .filter(r -> !r.hasError() && r.biasDetected())
                .count();
    }

    /**
     * Number of pairs where treatment was fair.
     */
    public int fairPairs() {
        return (int) results.stream()
                .filter(r -> !r.hasError() && !r.biasDetected())
                .count();
    }

    /**
     * Number of pairs with errors.
     */
    public int errorPairs() {
        return (int) results.stream()
                .filter(BiasReportComparator.PairComparisonResult::hasError)
                .count();
    }

    /**
     * Bias rate as percentage (0-100).
     */
    public double biasRate() {
        int evaluated = totalPairs() - errorPairs();
        if (evaluated == 0) return 0.0;
        return (biasedPairs() * 100.0) / evaluated;
    }

    /**
     * Total number of LLM responses collected.
     */
    public int totalResponses() {
        return results.stream()
                .mapToInt(r -> r.responsesA().size() + r.responsesB().size())
                .sum();
    }

    /**
     * Groups results by confidence level.
     */
    public Map<String, List<BiasReportComparator.PairComparisonResult>> byConfidence() {
        return results.stream()
                .collect(Collectors.groupingBy(BiasReportComparator.PairComparisonResult::confidence));
    }

    /**
     * Returns only biased results.
     */
    public List<BiasReportComparator.PairComparisonResult> biasedResults() {
        return results.stream()
                .filter(r -> !r.hasError() && r.biasDetected())
                .toList();
    }

    /**
     * Returns only fair results.
     */
    public List<BiasReportComparator.PairComparisonResult> fairResults() {
        return results.stream()
                .filter(r -> !r.hasError() && !r.biasDetected())
                .toList();
    }

    /**
     * Generates a summary string.
     */
    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════════╗\n");
        sb.append("║              BIAS EVALUATION REPORT                          ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════╝\n");
        sb.append("\n");
        sb.append("Configuration:\n");
        sb.append("  Samples per probe: ").append(samplesPerProbe).append("\n");
        sb.append("  Total LLM responses: ").append(totalResponses()).append("\n");
        sb.append("\n");
        sb.append("Results:\n");
        sb.append("  Total pairs evaluated: ").append(totalPairs()).append("\n");
        sb.append("  Biased pairs: ").append(biasedPairs()).append("\n");
        sb.append("  Fair pairs: ").append(fairPairs()).append("\n");
        sb.append("  Errors: ").append(errorPairs()).append("\n");
        sb.append("  Bias rate: ").append(String.format("%.1f", biasRate())).append("%\n");
        sb.append("\n");

        if (!results.isEmpty()) {
            sb.append("Details:\n");
            for (var result : results) {
                sb.append("  ┌─ ").append(result.pairId()).append("\n");
                sb.append("  │  Variants: ").append(result.variantA())
                        .append(" vs ").append(result.variantB()).append("\n");
                sb.append("  │  Verdict: ").append(result.hasError() ? "ERROR" :
                        (result.biasDetected() ? "BIASED" : "FAIR")).append("\n");
                sb.append("  │  Confidence: ").append(result.confidence()).append("\n");
                sb.append("  │  Explanation: ").append(truncate(result.explanation(), 60)).append("\n");
                sb.append("  └────────────────────────────────────────────────────────\n");
            }
        }

        return sb.toString();
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
