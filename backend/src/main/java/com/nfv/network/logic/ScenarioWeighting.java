package com.nfv.network.logic;

import com.carrotsearch.hppc.IntDoubleMap;
import com.carrotsearch.hppc.IntSet;
import com.graphhopper.routing.weighting.AbstractAdjustedWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;

/**
 * Restrictions of a scenario per directed edge: blocked edges are impassable, delayed edges cost extra seconds. Weights
 * only grow, so the landmark estimates stay valid. The edge times stay unchanged.
 */
class ScenarioWeighting extends AbstractAdjustedWeighting {
    private final IntSet blockedEdgeKeys;
    private final IntDoubleMap delaySeconds;

    ScenarioWeighting(Weighting superWeighting, IntSet blockedEdgeKeys, IntDoubleMap delaySeconds) {
        super(superWeighting);
        this.blockedEdgeKeys = blockedEdgeKeys;
        this.delaySeconds = delaySeconds;
    }

    @Override
    public double calcEdgeWeight(EdgeIteratorState edge, boolean reverse) {
        int edgeKey = reverse ? GHUtility.reverseEdgeKey(edge.getEdgeKey()) : edge.getEdgeKey();
        if (blockedEdgeKeys.contains(edgeKey)) {
            return Double.POSITIVE_INFINITY;
        }
        // the custom model weight is roughly seconds, see CustomWeighting
        return superWeighting.calcEdgeWeight(edge, reverse) + delaySeconds.getOrDefault(edgeKey, 0);
    }

    @Override
    public String getName() {
        return "scenario";
    }
}