package com.vacantagent.network.logic;

import com.carrotsearch.hppc.IntSet;
import com.graphhopper.routing.weighting.AbstractAdjustedWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;

/** Makes the given directed edges impassable, the opposite direction stays open. */
class BlockedEdgesWeighting extends AbstractAdjustedWeighting {
    private final IntSet blockedEdgeKeys;

    BlockedEdgesWeighting(Weighting superWeighting, IntSet blockedEdgeKeys) {
        super(superWeighting);
        this.blockedEdgeKeys = blockedEdgeKeys;
    }

    @Override
    public double calcEdgeWeight(EdgeIteratorState edge, boolean reverse) {
        int edgeKey = reverse ? GHUtility.reverseEdgeKey(edge.getEdgeKey()) : edge.getEdgeKey();
        return blockedEdgeKeys.contains(edgeKey) ? Double.POSITIVE_INFINITY
                : superWeighting.calcEdgeWeight(edge, reverse);
    }

    @Override
    public String getName() {
        return "blocked_edges";
    }
}