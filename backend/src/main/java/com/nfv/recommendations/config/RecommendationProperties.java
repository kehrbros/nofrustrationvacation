package com.nfv.recommendations.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Thresholds of the route suggestions, see Docs/recommendations.md.
 *
 * @param defaultCount           suggestions per set if the caller gives no count
 * @param maxCount               most suggestions per set
 * @param maxWeightFactor        GraphHopper {@code alternative_route.max_weight_factor}
 * @param maxShareFactor         GraphHopper {@code alternative_route.max_share_factor}
 * @param maxSharedRatio         combined legs: more shared distance with a better ranked suggestion → dropped
 * @param currentSharedRatio     at least this much shared distance with the planned route → label {@code CURRENT}
 * @param viaReproduceRatio      via points must reproduce at least this part of the suggestion
 * @param maxViaPointsPerSection most via points per section that differs from the fastest way
 * @param maxChosenPoints        most points of a chosen suggestion, see {@code RouteChoice}
 * @param departureShiftBuffer   distance of a shifted departure to the start or end of an incident
 * @param departureShiftMax      a later departure further away than this is not suggested
 */
@ConfigurationProperties("nfv.recommendations")
public record RecommendationProperties(
        @DefaultValue("3") int defaultCount,
        @DefaultValue("5") int maxCount,
        @DefaultValue("1.4") double maxWeightFactor,
        @DefaultValue("0.6") double maxShareFactor,
        @DefaultValue("0.6") double maxSharedRatio,
        @DefaultValue("0.95") double currentSharedRatio,
        @DefaultValue("0.9") double viaReproduceRatio,
        @DefaultValue("3") int maxViaPointsPerSection,
        @DefaultValue("25") int maxChosenPoints,
        @DefaultValue("15m") Duration departureShiftBuffer,
        @DefaultValue("24h") Duration departureShiftMax) {
}