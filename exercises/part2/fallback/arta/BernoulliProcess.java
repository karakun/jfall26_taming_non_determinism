package arta;

import java.util.Random;

/**
 * Simple Bernoulli process for generating independent failures.
 *
 * Unlike ARTA, each trial is independent—there's no correlation between
 * consecutive failures. This serves as a baseline for comparing with
 * autocorrelated failure patterns.
 */
public class BernoulliProcess {

    private final double failureProbability;
    private final Random random;

    /**
     * Create a Bernoulli process for generating independent failures.
     *
     * @param failureProbability probability of failure per trial (0 to 1)
     * @param seed               random seed for reproducibility
     */
    public BernoulliProcess(double failureProbability, long seed) {
        if (failureProbability < 0 || failureProbability > 1) {
            throw new IllegalArgumentException("Failure probability must be between 0 and 1");
        }
        this.failureProbability = failureProbability;
        this.random = new Random(seed);
    }

    /**
     * Generate the next failure indicator.
     *
     * @return true if this trial is a failure, false otherwise
     */
    public boolean nextFailure() {
        return random.nextDouble() < failureProbability;
    }

    /**
     * Generate a sequence of failure indicators.
     *
     * @param count number of trials to generate
     * @return array of failure indicators
     */
    public boolean[] generateSequence(int count) {
        boolean[] sequence = new boolean[count];
        for (int i = 0; i < count; i++) {
            sequence[i] = nextFailure();
        }
        return sequence;
    }

    /**
     * Reset with a new seed.
     */
    public void reset(long seed) {
        random.setSeed(seed);
    }

    /**
     * Get the configured failure probability.
     */
    public double getFailureProbability() {
        return failureProbability;
    }
}
