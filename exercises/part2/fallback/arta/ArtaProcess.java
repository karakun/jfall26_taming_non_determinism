package arta;

import java.util.Random;

/**
 * ARTA (AutoRegressive To Anything) process generator.
 *
 * Generates autocorrelated binary sequences (failures) that model bursty behavior.
 * Unlike independent Bernoulli trials, ARTA produces sequences where failures
 * tend to cluster together, simulating real-world scenarios like network outages
 * or cascading service failures.
 *
 * <p>The process works by:
 * <ol>
 *   <li>Generating an AR(1) (autoregressive order 1) process with given autocorrelation</li>
 *   <li>Transforming to uniform distribution via the normal CDF</li>
 *   <li>Mapping to binary outcomes based on the failure probability threshold</li>
 * </ol>
 *
 * <p>Higher autocorrelation values produce longer bursts of consecutive failures.
 */
public class ArtaProcess {

    private final double failureProbability;
    private final double autocorrelation;
    private final Random random;

    // AR(1) state
    private double z;
    private final double stdDev;

    /**
     * Create an ARTA process for generating autocorrelated failures.
     *
     * @param failureProbability probability of failure (0 to 1), default 0.758
     * @param autocorrelation    lag-1 autocorrelation coefficient (0 to 1), controls burstiness
     * @param seed               random seed for reproducibility
     */
    public ArtaProcess(double failureProbability, double autocorrelation, long seed) {
        if (failureProbability < 0 || failureProbability > 1) {
            throw new IllegalArgumentException("Failure probability must be between 0 and 1");
        }
        if (autocorrelation < 0 || autocorrelation > 1) {
            throw new IllegalArgumentException("Autocorrelation must be between 0 and 1");
        }

        this.failureProbability = failureProbability;
        this.autocorrelation = autocorrelation;
        this.random = new Random(seed);

        // Standard deviation for the innovation term in AR(1)
        this.stdDev = Math.sqrt(1 - autocorrelation * autocorrelation);

        // Initialize state from standard normal
        this.z = random.nextGaussian();
    }

    /**
     * Create an ARTA process with default autocorrelation of 0.7 (moderate burstiness).
     */
    public ArtaProcess(double failureProbability, long seed) {
        this(failureProbability, 0.7, seed);
    }

    /**
     * Generate the next failure indicator.
     *
     * @return true if this trial is a failure, false otherwise
     */
    public boolean nextFailure() {
        // AR(1) step: z_t = rho * z_{t-1} + sqrt(1-rho^2) * epsilon_t
        double innovation = random.nextGaussian();
        z = autocorrelation * z + stdDev * innovation;

        // Transform to uniform [0,1] via standard normal CDF
        double u = normalCdf(z);

        // Map to Bernoulli: failure if u < p
        return u < failureProbability;
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
     * Reset the process to a new random state.
     */
    public void reset() {
        z = random.nextGaussian();
    }

    /**
     * Reset with a new seed.
     */
    public void reset(long seed) {
        random.setSeed(seed);
        z = random.nextGaussian();
    }

    /**
     * Approximate CDF of the standard normal distribution using Abramowitz & Stegun formula.
     */
    private static double normalCdf(double x) {
        if (x < -8) return 0;
        if (x > 8) return 1;

        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double d = 0.3989422804014327; // 1/sqrt(2*pi)
        double p = d * Math.exp(-x * x / 2.0) * t *
                   (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));

        return x > 0 ? 1 - p : p;
    }

    /**
     * Get the configured failure probability.
     */
    public double getFailureProbability() {
        return failureProbability;
    }

    /**
     * Get the configured autocorrelation coefficient.
     */
    public double getAutocorrelation() {
        return autocorrelation;
    }
}
