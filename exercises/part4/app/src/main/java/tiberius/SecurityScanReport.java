package tiberius;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated report of a security scan.
 */
public class SecurityScanReport {

    private final List<SecurityScanResult> results = new ArrayList<>();
    private final Instant startTime;
    private Instant endTime;
    private volatile boolean running = true;
    private volatile boolean cancelled = false;
    private volatile int expectedProbes = 0;

    public SecurityScanReport() {
        this.startTime = Instant.now();
    }

    public void setExpectedProbes(int count) {
        this.expectedProbes = count;
    }

    public int getExpectedProbes() {
        return expectedProbes;
    }

    public synchronized void addResult(SecurityScanResult result) {
        results.add(result);
    }

    public void complete() {
        this.endTime = Instant.now();
        this.running = false;
    }

    public void cancel() {
        this.cancelled = true;
        this.running = false;
        this.endTime = Instant.now();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public synchronized List<SecurityScanResult> getResults() {
        return new ArrayList<>(results);
    }

    public synchronized int getTotalProbes() {
        return results.size();
    }

    public synchronized int getSuccessfulAttacks() {
        return (int) results.stream().filter(SecurityScanResult::attackSucceeded).count();
    }

    public synchronized int getBlockedAttacks() {
        return (int) results.stream().filter(r -> !r.attackSucceeded()).count();
    }

    public synchronized double getAttackSuccessRate() {
        if (results.isEmpty()) return 0.0;
        return (getSuccessfulAttacks() * 100.0) / results.size();
    }

    public synchronized double getResistanceRate() {
        return 100.0 - getAttackSuccessRate();
    }

    /**
     * Returns results grouped by category.
     */
    public synchronized Map<String, List<SecurityScanResult>> getResultsByCategory() {
        Map<String, List<SecurityScanResult>> grouped = new HashMap<>();
        for (SecurityScanResult result : results) {
            grouped.computeIfAbsent(result.category(), k -> new ArrayList<>()).add(result);
        }
        return grouped;
    }

    /**
     * Returns results grouped by buff applied.
     */
    public synchronized Map<String, List<SecurityScanResult>> getResultsByBuff() {
        Map<String, List<SecurityScanResult>> grouped = new HashMap<>();
        for (SecurityScanResult result : results) {
            String buff = result.buffApplied() != null ? result.buffApplied() : "None";
            grouped.computeIfAbsent(buff, k -> new ArrayList<>()).add(result);
        }
        return grouped;
    }

    /**
     * Calculate ASR (Attack Success Rate) per buff.
     */
    public synchronized Map<String, Double> getAsrByBuff() {
        Map<String, Double> asrByBuff = new HashMap<>();
        Map<String, List<SecurityScanResult>> byBuff = getResultsByBuff();

        for (Map.Entry<String, List<SecurityScanResult>> entry : byBuff.entrySet()) {
            List<SecurityScanResult> buffResults = entry.getValue();
            long succeeded = buffResults.stream().filter(SecurityScanResult::attackSucceeded).count();
            double asr = (succeeded * 100.0) / buffResults.size();
            asrByBuff.put(entry.getKey(), asr);
        }
        return asrByBuff;
    }

    /**
     * Calculate ASR per category.
     */
    public synchronized Map<String, Double> getAsrByCategory() {
        Map<String, Double> asrByCat = new HashMap<>();
        Map<String, List<SecurityScanResult>> byCat = getResultsByCategory();

        for (Map.Entry<String, List<SecurityScanResult>> entry : byCat.entrySet()) {
            List<SecurityScanResult> catResults = entry.getValue();
            long succeeded = catResults.stream().filter(SecurityScanResult::attackSucceeded).count();
            double asr = (succeeded * 100.0) / catResults.size();
            asrByCat.put(entry.getKey(), asr);
        }
        return asrByCat;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getDurationMs() {
        Instant end = endTime != null ? endTime : Instant.now();
        return end.toEpochMilli() - startTime.toEpochMilli();
    }

    /**
     * Returns a summary map suitable for JSON serialization.
     */
    public synchronized Map<String, Object> toSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("running", running);
        summary.put("cancelled", cancelled);
        summary.put("expectedProbes", expectedProbes);
        summary.put("completedProbes", getTotalProbes());
        summary.put("totalProbes", expectedProbes > 0 ? expectedProbes : getTotalProbes());
        summary.put("successfulAttacks", getSuccessfulAttacks());
        summary.put("blockedAttacks", getBlockedAttacks());
        summary.put("attackSuccessRate", Math.round(getAttackSuccessRate() * 10.0) / 10.0);
        summary.put("resistanceRate", Math.round(getResistanceRate() * 10.0) / 10.0);
        summary.put("durationMs", getDurationMs());
        summary.put("asrByCategory", getAsrByCategory());
        summary.put("asrByBuff", getAsrByBuff());
        return summary;
    }
}
