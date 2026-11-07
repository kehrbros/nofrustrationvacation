package com.nfv.advisor.logic;

import com.nfv.advisor.entities.ProposalKind;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the tools of one run share: fixed by the backend, plus the options found so far under ids that only hold in this
 * run. The model can only propose what is registered here. One conversation at a time, so not thread safe.
 */
public final class RunContext {
    /** Always available, the planned route with the incidents on it. */
    static final String KEEP_ROUTE = "keep_route";

    private final long appUserId;
    private final long consequenceId;
    private final boolean underway;
    private final @Nullable OffsetDateTime deadline;
    private final boolean overnightRelevant;
    private final Map<String, ProposalDraft> options = new LinkedHashMap<>();
    private final Map<String, Area> areas = new LinkedHashMap<>();
    private int next = 1;
    private int nextArea = 1;

    /** A place found for a stay, input for later tools. Not an option, {@code submit_proposals} cannot name it. */
    record Area(String name, double latitude, double longitude, @Nullable String routeOptionId) {
    }

    RunContext(long appUserId, long consequenceId, boolean underway, @Nullable OffsetDateTime deadline,
               boolean overnightRelevant, ProposalDraft keepRoute) {
        this.appUserId = appUserId;
        this.consequenceId = consequenceId;
        this.underway = underway;
        this.deadline = deadline;
        this.overnightRelevant = overnightRelevant;
        options.put(KEEP_ROUTE, keepRoute);
    }

    public long appUserId() {
        return appUserId;
    }

    public long consequenceId() {
        return consequenceId;
    }

    /** The route has started: its departure can no longer be shifted. */
    public boolean underway() {
        return underway;
    }

    public @Nullable OffsetDateTime deadline() {
        return deadline;
    }

    /** The route is too late or cannot be passed, also without a deadline: a stay along the way can be the answer. */
    public boolean overnightRelevant() {
        return overnightRelevant;
    }

    /** @return the id for the model */
    String registerArea(Area area) {
        String id = "area_" + nextArea++;
        areas.put(id, area);
        return id;
    }

    Optional<Area> area(String id) {
        return Optional.ofNullable(areas.get(id));
    }

    /** @return the id for the model */
    String register(ProposalDraft draft) {
        String id = (draft.kind() == ProposalKind.REROUTE ? "route_" : "departure_") + next++;
        options.put(id, draft);
        return id;
    }

    Optional<ProposalDraft> option(String id) {
        return Optional.ofNullable(options.get(id));
    }
}
