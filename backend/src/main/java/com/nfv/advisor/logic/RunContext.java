package com.nfv.advisor.logic;

import com.nfv.advisor.entities.ProposalKind;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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
    private final @Nullable Target target;
    private final Map<String, ProposalDraft> options = new LinkedHashMap<>();
    private final Map<String, Area> areas = new LinkedHashMap<>();
    /** Stays only usable in a plan (at another target), by option id. */
    private final Map<String, ProposalDraft> planOnly = new LinkedHashMap<>();
    /** Where a stay option lies: an area_, a place_ or {@link #TARGET}. */
    private final Map<String, String> stayOrigins = new LinkedHashMap<>();
    private final Map<String, Place> places = new LinkedHashMap<>();
    private final Map<String, PlanDraft> plans = new LinkedHashMap<>();
    private int next = 1;
    private int nextArea = 1;
    private int nextPlace = 1;
    private int nextPlan = 1;
    private @Nullable Optional<StayOptions.BookedStay> bookedStay;
    private @Nullable OffsetDateTime plannedArrival;
    private @Nullable NextStop nextStop;

    /** Origin of a stay at the target stop. */
    static final String TARGET = "target";

    /**
     * Another target the route can reach, input for {@code search_accommodations} and {@code compose_plan}.
     *
     * @param drive the way there from the start of the route
     */
    record Place(String name, double latitude, double longitude, ProposalDraft drive) {
    }

    /** The stop after the target, for other targets close to the rest of the trip. */
    record NextStop(String name, double latitude, double longitude) {
    }

    /**
     * A place found for a stay, input for later tools. Not an option, {@code submit_proposals} cannot name it.
     *
     * @param arrivalAt approximate, see {@code find_overnight_areas}
     */
    record Area(String name, double latitude, double longitude, OffsetDateTime arrivalAt,
                @Nullable String routeOptionId) {
    }

    /**
     * The stop the route leads to, for a stay there.
     *
     * @param travelers of the trip, null if not given
     */
    record Target(long tripId, long stopId, String name, @Nullable Double latitude, @Nullable Double longitude,
                  @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt,
                  @Nullable Integer travelers) {
    }

    RunContext(long appUserId, long consequenceId, boolean underway, @Nullable OffsetDateTime deadline,
               boolean overnightRelevant, ProposalDraft keepRoute, @Nullable Target target) {
        this.appUserId = appUserId;
        this.consequenceId = consequenceId;
        this.underway = underway;
        this.deadline = deadline;
        this.overnightRelevant = overnightRelevant;
        this.target = target;
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

    /** Null in tests that do not need it. */
    @Nullable Target target() {
        return target;
    }

    /** The planned route, always registered. */
    ProposalDraft keepRoute() {
        return options.get(KEEP_ROUTE);
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
    String registerPlace(Place place) {
        String id = "place_" + nextPlace++;
        places.put(id, place);
        return id;
    }

    Optional<Place> place(String id) {
        return Optional.ofNullable(places.get(id));
    }

    /** @return the id for the model */
    String registerPlan(PlanDraft plan) {
        String id = "plan_" + nextPlan++;
        plans.put(id, plan);
        return id;
    }

    Optional<PlanDraft> plan(String id) {
        return Optional.ofNullable(plans.get(id));
    }

    /**
     * A stay: proposable on its own unless {@code planOnly} (a hotel at another target only makes sense with moving the
     * target, in a plan).
     *
     * @param origin an area_, a place_ or {@link #TARGET}
     * @return the id for the model
     */
    String registerStay(ProposalDraft draft, String origin, boolean planOnly) {
        String id;
        if (planOnly) {
            id = "stay_" + next++;
            this.planOnly.put(id, draft);
        } else {
            id = register(draft);
        }
        stayOrigins.put(id, origin);
        return id;
    }

    /** A stay option, also one only usable in a plan, with where it lies. */
    Optional<StayChoice> stay(String id) {
        ProposalDraft draft = options.containsKey(id) ? options.get(id) : planOnly.get(id);
        String origin = stayOrigins.get(id);
        return draft == null || draft.stay() == null || origin == null ? Optional.empty()
                : Optional.of(new StayChoice(id, draft, origin));
    }

    record StayChoice(String id, ProposalDraft draft, String origin) {
    }

    /** The accommodation booked at the target stop, asked once per run. */
    Optional<StayOptions.BookedStay> bookedStay(Supplier<Optional<StayOptions.BookedStay>> lookup) {
        if (bookedStay == null) {
            bookedStay = lookup.get();
        }
        return bookedStay;
    }

    /** Planned arrival at the target without incidents, and the stop after it. */
    RunContext plannedArrival(@Nullable OffsetDateTime plannedArrival, @Nullable NextStop nextStop) {
        this.plannedArrival = plannedArrival;
        this.nextStop = nextStop;
        return this;
    }

    @Nullable OffsetDateTime plannedArrival() {
        return plannedArrival;
    }

    @Nullable NextStop nextStop() {
        return nextStop;
    }

    /** Local offset at the target: of its arrival, else of the deadline, else of the planned route. */
    ZoneOffset targetOffset() {
        if (target != null && target.arrivalAt() != null) {
            return target.arrivalAt().getOffset();
        }
        if (deadline != null) {
            return deadline.getOffset();
        }
        OffsetDateTime departure = keepRoute().departureAt();
        return departure != null ? departure.getOffset() : ZoneOffset.UTC;
    }

    /** @return the id for the model */
    String register(ProposalDraft draft) {
        String prefix = switch (draft.kind()) {
            case REROUTE -> "route_";
            case SHIFT_DEPARTURE -> "departure_";
            case STOPOVER, TARGET_STAY -> "stay_";
            case KEEP_ROUTE -> throw new IllegalArgumentException("keep_route is registered at the start");
            case PLAN -> throw new IllegalArgumentException("plans are registered with registerPlan");
        };
        String id = prefix + next++;
        options.put(id, draft);
        return id;
    }

    Optional<ProposalDraft> option(String id) {
        return Optional.ofNullable(options.get(id));
    }
}
