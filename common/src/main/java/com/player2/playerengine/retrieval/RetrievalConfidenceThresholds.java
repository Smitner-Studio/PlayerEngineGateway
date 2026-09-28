package com.player2.playerengine.retrieval;

/**
 * Config-shaped thresholds for {@link ToolRetriever#retrieveWithConfidence}.
 */
public record RetrievalConfidenceThresholds(
        double weakBelowScore,
        double weakGapRatio,
        double weakTokenCoverage,
        boolean forceDeepCheckOnEmpty
) {
    public static final double DEFAULT_WEAK_BELOW_SCORE = 0.015;
    public static final double DEFAULT_WEAK_GAP_RATIO = 0.15;
    public static final double DEFAULT_WEAK_TOKEN_COVERAGE = 0.35;

    public static RetrievalConfidenceThresholds defaults() {
        return new RetrievalConfidenceThresholds(
                DEFAULT_WEAK_BELOW_SCORE,
                DEFAULT_WEAK_GAP_RATIO,
                DEFAULT_WEAK_TOKEN_COVERAGE,
                true);
    }
}
