package com.nfv.recommendations.logic;


import java.util.List;

/**
 * Proposes ways for a scenario; one Spring bean per {@link OptionKind}. New generators (preferences, avoid ferries) are
 * a new bean, engine and schema stay, see Docs/recommendations.md#generatoren.
 */
interface CandidateGenerator {
    OptionKind kind();

    /**
     * @return candidates, the fastest first if the generator routes
     * @throws com.nfv.network.logic.NoRouteException            no way between the points
     * @throws com.nfv.network.logic.RoutingUnavailableException no graph yet
     */
    List<Candidate> generate(Scenario scenario);
}