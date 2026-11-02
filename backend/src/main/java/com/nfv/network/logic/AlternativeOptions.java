package com.nfv.network.logic;

/**
 * Parameters of GraphHopper {@code alternative_route} for {@link Routing#alternatives}.
 *
 * @param maxPaths        most paths returned, including the fastest
 * @param maxWeightFactor an alternative may be at most this factor slower than the fastest
 * @param maxShareFactor  an alternative may share at most this part of its way with the fastest
 */
public record AlternativeOptions(int maxPaths, double maxWeightFactor, double maxShareFactor) {
    public AlternativeOptions {
        if (maxPaths < 1) {
            throw new IllegalArgumentException("maxPaths must be at least 1");
        }
        if (maxWeightFactor < 1) {
            throw new IllegalArgumentException("maxWeightFactor must be at least 1");
        }
        if (maxShareFactor <= 0 || maxShareFactor > 1) {
            throw new IllegalArgumentException("maxShareFactor must be in (0, 1]");
        }
    }
}