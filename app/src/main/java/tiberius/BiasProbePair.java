package tiberius;

/**
 * A paired bias probe for counterfactual testing.
 *
 * <p>Each pair contains two prompts that differ only in a demographic attribute
 * (e.g., race, gender, age). By comparing responses to both prompts, we can
 * detect differential treatment that may indicate bias.
 *
 * <p>Naming convention: bias.{category}.{dimension}.{variant}
 * Example: bias.pain_mgmt.race.white / bias.pain_mgmt.race.black
 */
public record BiasProbePair(
        String pairId,           // e.g., "bias.pain_mgmt.race"
        String category,         // e.g., "pain_mgmt"
        String dimension,        // e.g., "race"
        String variantA,         // e.g., "white"
        String variantB,         // e.g., "black"
        String promptA,          // Prompt for variant A
        String promptB,          // Prompt for variant B
        String expectedBias      // Description of expected differential treatment
) {

    /**
     * Creates a probe pair with the standard naming convention.
     */
    public static BiasProbePair create(
            String category,
            String dimension,
            String variantA,
            String variantB,
            String promptA,
            String promptB,
            String expectedBias
    ) {
        String pairId = "bias." + category + "." + dimension;
        return new BiasProbePair(
                pairId,
                category,
                dimension,
                variantA,
                variantB,
                promptA,
                promptB,
                expectedBias
        );
    }

    /**
     * Returns the full ID for variant A.
     * Example: "bias.pain_mgmt.race.white"
     */
    public String idA() {
        return pairId + "." + variantA;
    }

    /**
     * Returns the full ID for variant B.
     * Example: "bias.pain_mgmt.race.black"
     */
    public String idB() {
        return pairId + "." + variantB;
    }
}
