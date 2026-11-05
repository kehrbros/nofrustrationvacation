package com.nfv.input;

import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.data.IncidentRepository;
import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Consequence;
import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.Incident;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentReporting;
import com.nfv.incidents.logic.TimeWindow;
import com.nfv.input.data.EventRepository;
import com.nfv.input.data.EventSourceRepository;
import com.nfv.input.entities.Event;
import com.nfv.input.entities.EventSourceType;
import com.nfv.input.logic.Attribution;
import com.nfv.incidents.logic.TravelDirection;
import com.nfv.input.logic.EnrichmentDeferredException;
import com.nfv.input.logic.EnrichmentModel;
import com.nfv.input.logic.FetchContext;
import com.nfv.input.logic.PollPolicy;
import com.nfv.input.logic.SourceSnapshot;
import com.nfv.input.logic.TrafficEventProcessor;
import com.nfv.input.logic.TrafficPolling;
import com.nfv.input.logic.TrafficReport;
import com.nfv.input.logic.TrafficSource;
import com.nfv.input.sources.nz.NztaSource;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.StopRepository;
import com.nfv.trips.data.TripRepository;
import com.nfv.trips.entities.PlanOutcome;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.logic.RoutePlanning;
import com.nfv.user.logic.AppUserProvisioning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End to end: NZTA sample → events → incidents on the Wellington extract → consequence on a planned route through
 * the Terrace Tunnel, then the end reported by NZTA. Fixed clock 2026-11-03 22:15 (NZ), shortly after the sample, see
 * Docs/traffic-feed.md#testumgebung.
 */
@SpringBootTest(properties = "nfv.input.sources.test-unloaded.enabled=true")
class TrafficProcessingTest {
    private static final String NZTA = NztaSource.NAME;
    private static final String UNLOADED = "test-unloaded";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-03T22:15:00+13:00");
    /** Tuesday daytime, inside the validity of 566003 (1.11.–18.12.). */
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-11-10T10:00:00+13:00");

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW.toInstant(), NOW.getOffset());
        }

        /** The Terrace Tunnel event on region "nz", whose network is never loaded in tests. */
        @Bean
        TrafficSource unloadedRegionSource(NztaSource nzta) {
            return new TrafficSource() {
                @Override
                public String name() {
                    return UNLOADED;
                }

                @Override
                public String country() {
                    return "NZ";
                }

                @Override
                public String networkRegion() {
                    return "nz";
                }

                @Override
                public ZoneId zone() {
                    return nzta.zone();
                }

                @Override
                public EventSourceType type() {
                    return EventSourceType.TRAFFIC_FEED;
                }

                @Override
                public PollPolicy pollPolicy() {
                    return nzta.pollPolicy();
                }

                @Override
                public Attribution attribution() {
                    return nzta.attribution();
                }

                @Override
                public SourceSnapshot fetch(FetchContext context) {
                    return SourceSnapshot.complete(nzta.fetch(context).items().stream()
                            .filter(item -> item.subjectId().equals("566003")).toList(), null, null);
                }

                @Override
                public TrafficReport read(String payload) {
                    return nzta.read(payload);
                }
            };
        }
    }

    /** Unstubbed it answers nothing, like production until stage 4. */
    @MockitoBean
    private EnrichmentModel enrichmentModel;
    @Autowired
    private TrafficPolling polling;
    @Autowired
    private TrafficEventProcessor processor;
    @Autowired
    private IncidentReporting incidentReporting;
    @Autowired
    private List<TrafficSource> trafficSources;
    @Autowired
    private RegionImport regionImport;
    @Autowired
    private RoutePlanning routePlanning;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private EventSourceRepository eventSourceRepository;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private ConsequenceRepository consequenceRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private StopRepository stopRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.importRegion("wellington");
        }
        for (String source : List.of(NZTA, UNLOADED)) {
            jdbc.update("DELETE FROM message_enrichment WHERE source_id IN (SELECT id FROM event_source WHERE name = ?)",
                    source);
            jdbc.update("DELETE FROM input_event WHERE source_id IN (SELECT id FROM event_source WHERE name = ?)",
                    source);
            jdbc.update("DELETE FROM source_subject WHERE source_id IN (SELECT id FROM event_source WHERE name = ?)",
                    source);
            jdbc.update("UPDATE event_source SET enabled = TRUE, etag = NULL, last_modified = NULL WHERE name = ?",
                    source);
        }
        // events of other test classes (other sources) must not get in the way
        jdbc.update("UPDATE input_event SET processed_at = now() WHERE processed_at IS NULL");
    }

    @Test
    void sampleLeadsToIncidentAndConsequenceUntilResolved() {
        long routeId = plannedRoute();

        polling.pollNow(NZTA);
        TrafficEventProcessor.Result first = processor.processPending();

        assertThat(first.pending()).isZero();
        assertThat(first.processed()).isEqualTo(197);
        List<Event> events = nztaEvents();
        assertThat(events).allSatisfy(event -> assertThat(event.getProcessedAt()).isNotNull());
        assertThat(events).filteredOn(event -> event.getIncidentId() != null).extracting(Event::getSubjectId)
                .containsExactlyInAnyOrder("566003", "566353");
        assertThat(events).filteredOn(event -> event.getIncidentId() == null)
                .extracting(Event::getProcessingNote).containsOnly("NO_SEGMENTS", "ENDED");

        Event tunnelEvent = event("566003");
        // times of day in the text: only a note until time windows exist (stage 4), no closure
        assertThat(tunnelEvent.getProcessingNote()).startsWith("TIMES_IN_TEXT");
        Incident tunnel = incidentRepository.findById(tunnelEvent.getIncidentId()).orElseThrow();
        assertThat(tunnel.getType()).isEqualTo(IncidentType.OTHER);
        assertThat(tunnel.getValidFrom()).isAtSameInstantAs(OffsetDateTime.parse("2026-11-01T21:00:26+13:00"));
        assertThat(tunnel.getDescription()).startsWith("Road Work: SH 1 Terrace Tunnel, northbound");
        assertThat(jdbc.queryForList("SELECT DISTINCT ref FROM road_segment s JOIN incident_segment i"
                + " ON i.segment_id = s.id WHERE i.incident_id = ?", String.class, tunnel.getId())).containsOnly("SH 1");
        Consequence consequence = consequenceRepository.findByRouteId(routeId).stream()
                .filter(c -> c.getIncident().getId() == tunnel.getId()).findFirst().orElseThrow();
        assertThat(consequence.getImpact()).isEqualTo(Impact.DELAYED);
        // a closure at unconfirmed times: "check times", not NO_IMPACT, adds no delay
        assertThat(tunnel.isTimesUnconfirmed()).isTrue();
        assertThat(consequence.getCategory()).isEqualTo(Category.DELAYED);
        assertThat(consequence.getAssessedDelay()).isZero();

        Incident motorway = incidentRepository.findById(event("566353").getIncidentId()).orElseThrow();
        assertThat(motorway.getAssumedDelay()).isEqualTo(Duration.ZERO);

        // NZTA reports the end: same incident, ends now, the passage on the 10th is no longer affected
        polling.pollNow(NZTA, "classpath:sources/nz-nzta/events-changed-status.json");
        TrafficEventProcessor.Result resolved = processor.processPending();

        assertThat(resolved.processed()).isEqualTo(1);
        Event resolvedEvent = event("566003", "2026-11-04T09:30:00.000+13:00");
        assertThat(resolvedEvent.getIncidentId()).isEqualTo(tunnel.getId());
        assertThat(incidentRepository.findById(tunnel.getId()).orElseThrow().getValidTo()).isAtSameInstantAs(NOW);
        assertThat(consequenceRepository.findByRouteId(routeId))
                .noneMatch(c -> c.getIncident().getId() == tunnel.getId());
    }

    @Test
    void newVersionChangesTheSameIncidentAndItsType() {
        polling.pollNow(NZTA);
        processor.processPending();
        long incidentId = event("566003").getIncidentId();

        // new text without times of day: the closure applies again (type OTHER → CLOSURE, same incident)
        String payload = event("566003").getPayload().replace(
                "on Sunday to Thursday nights between 9pm and 4:30am", "until further notice")
                .replace("2026-11-03T21:00:06.200+13:00", "2026-11-04T10:00:00.000+13:00");
        transactionTemplate.executeWithoutResult(_ -> eventRepository.save(new com.nfv.input.entities.TrafficEvent(
                eventSourceRepository.findByName(NZTA).orElseThrow(), "566003@2026-11-04T10:00:00.000+13:00",
                "566003", null, payload, NOW)));
        processor.processPending();

        Event changed = event("566003", "2026-11-04T10:00:00.000+13:00");
        assertThat(changed.getIncidentId()).isEqualTo(incidentId);
        assertThat(changed.getProcessingNote()).isNull();
        Incident incident = incidentRepository.findById(incidentId).orElseThrow();
        assertThat(incident.getType()).isEqualTo(IncidentType.CLOSURE);
        assertThat(incident.isTimesUnconfirmed()).isFalse();
    }

    @Test
    void closureStillReportedAfterItsEndHasAnOpenEnd() {
        polling.pollNow(NZTA);
        processor.processPending();
        long incidentId = event("566003").getIncidentId();

        String payload = event("566003").getPayload().replace(
                "on Sunday to Thursday nights between 9pm and 4:30am", "until further notice")
                .replace("2026-12-18T04:30:26+13:00", "2026-11-02T04:30:26+13:00");
        assertThat(payload).contains("2026-11-02T04:30:26+13:00");
        transactionTemplate.executeWithoutResult(_ -> eventRepository.save(new com.nfv.input.entities.TrafficEvent(
                eventSourceRepository.findByName(NZTA).orElseThrow(), "566003@2026-11-04T11:00:00.000+13:00",
                "566003", null, payload, NOW)));
        processor.processPending();

        Incident incident = incidentRepository.findById(incidentId).orElseThrow();
        assertThat(incident.getType()).isEqualTo(IncidentType.CLOSURE);
        assertThat(incident.getValidTo()).isNull();
    }

    @Test
    void nightWindowsBlockOnlyPassagesAtNight() {
        long wednesdayNight = plannedRoute(OffsetDateTime.parse("2026-11-04T22:00:00+13:00"));
        long wednesdayNoon = plannedRoute(OffsetDateTime.parse("2026-11-04T12:00:00+13:00"));
        long fridayNight = plannedRoute(OffsetDateTime.parse("2026-11-06T22:00:00+13:00"));
        // "closed to northbound traffic on Sunday to Thursday nights between 9pm and 4:30am from Sunday 1st November
        // to Friday 18th December", as the model would read it: one window per night, local time
        List<EnrichmentModel.LocalWindow> nights = new ArrayList<>();
        for (LocalDate night = LocalDate.of(2026, 11, 1); night.isBefore(LocalDate.of(2026, 12, 18));
             night = night.plusDays(1)) {
            if (night.getDayOfWeek() != DayOfWeek.FRIDAY && night.getDayOfWeek() != DayOfWeek.SATURDAY) {
                nights.add(new EnrichmentModel.LocalWindow(night.atTime(21, 0), night.plusDays(1).atTime(4, 30)));
            }
        }
        when(enrichmentModel.extract(argThat(text -> text.contains("between 9pm and 4:30am")), any(), any()))
                .thenReturn(Optional.of(new EnrichmentModel.Answer(nights)));

        polling.pollNow(NZTA);
        processor.processPending();

        Event tunnelEvent = event("566003");
        assertThat(tunnelEvent.getProcessingNote()).isNull();
        Incident tunnel = incidentRepository.findById(tunnelEvent.getIncidentId()).orElseThrow();
        assertThat(tunnel.getType()).isEqualTo(IncidentType.CLOSURE);
        // first night starts 26 s before validFrom (21:00:26): cut to the validity
        List<TimeWindow> windows = inTransaction(() ->
                incidentRepository.findById(tunnel.getId()).orElseThrow().timeWindows());
        assertThat(windows).hasSize(nights.size());
        assertThat(windows.getFirst().startsAt()).isAtSameInstantAs(tunnel.getValidFrom());

        Consequence blocked = tunnelConsequence(wednesdayNight, tunnel).orElseThrow();
        assertThat(blocked.getImpact()).isEqualTo(Impact.BLOCKED);
        assertThat(blocked.getWindowStart()).isAtSameInstantAs(OffsetDateTime.parse("2026-11-04T21:00:00+13:00"));
        assertThat(blocked.getWindowEnd()).isAtSameInstantAs(OffsetDateTime.parse("2026-11-05T04:30:00+13:00"));
        assertThat(tunnelConsequence(wednesdayNoon, tunnel)).isEmpty();
        assertThat(tunnelConsequence(fridayNight, tunnel)).isEmpty();

        // planned after the incident: the route side of the matching checks the windows as well
        long thursdayNight = plannedRoute(OffsetDateTime.parse("2026-11-05T23:00:00+13:00"));
        long saturdayNight = plannedRoute(OffsetDateTime.parse("2026-11-07T23:00:00+13:00"));
        assertThat(tunnelConsequence(thursdayNight, tunnel)).hasValueSatisfying(c -> assertThat(c.getWindowStart())
                .isAtSameInstantAs(OffsetDateTime.parse("2026-11-05T21:00:00+13:00")));
        assertThat(tunnelConsequence(saturdayNight, tunnel)).isEmpty();
    }

    @Test
    void sameTextAsksTheModelOnce() {
        when(enrichmentModel.extract(any(), any(), any()))
                .thenReturn(Optional.of(new EnrichmentModel.Answer(List.of())));

        polling.pollNow(NZTA);
        processor.processPending();
        // 197 events, 30 of them ended: those never ask
        verify(enrichmentModel, times(167)).extract(any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_enrichment", Long.class)).isEqualTo(167);

        // only the status changed: no new question; new text: one question
        clearInvocations(enrichmentModel);
        polling.pollNow(NZTA, "classpath:sources/nz-nzta/events-changed-text.json");
        processor.processPending();
        verify(enrichmentModel, times(1)).extract(any(), any(), any());

        // a new version with text seen before asks nothing
        clearInvocations(enrichmentModel);
        polling.pollNow(NZTA);
        processor.processPending();
        verify(enrichmentModel, never()).extract(any(), any(), any());
    }

    @Test
    void newValidityAsksAgainAndReplacesTheWindows() {
        OffsetDateTime night = OffsetDateTime.parse("2027-01-20T22:00:00+13:00");
        long routeId = plannedRoute(night);
        when(enrichmentModel.extract(mentioning("between 9pm and 4:30am"), any(), any())).thenAnswer(call -> {
            // dated windows as far as the validity the model is given
            TrafficReport report = call.getArgument(1);
            LocalDate last = report.validTo().toLocalDate();
            List<EnrichmentModel.LocalWindow> nights = new ArrayList<>();
            for (LocalDate day = LocalDate.of(2026, 12, 1); day.isBefore(last); day = day.plusDays(1)) {
                nights.add(new EnrichmentModel.LocalWindow(day.atTime(21, 0), day.plusDays(1).atTime(4, 30)));
            }
            return Optional.of(new EnrichmentModel.Answer(nights.subList(Math.max(0, nights.size() - 200), nights.size())));
        });
        polling.pollNow(NZTA);
        processor.processPending();
        Incident tunnel = incidentRepository.findById(event("566003").getIncidentId()).orElseThrow();
        assertThat(tunnelConsequence(routeId, tunnel)).as("after the old end").isEmpty();

        // NZTA extends the closure, the text stays the same
        String extended = event("566003").getPayload()
                .replace("2026-12-18T04:30:26+13:00", "2027-01-31T04:30:26+13:00");
        org.mockito.Mockito.clearInvocations(enrichmentModel);
        transactionTemplate.executeWithoutResult(_ -> eventRepository.save(new com.nfv.input.entities.TrafficEvent(
                eventSourceRepository.findByName(NZTA).orElseThrow(), "566003@extended", "566003", null, extended,
                NOW)));
        processor.processPending();

        verify(enrichmentModel, times(1)).extract(any(), any(), any());
        assertThat(event("566003", "extended").getProcessingNote()).as("not FAILED").isNull();
        assertThat(tunnelConsequence(routeId, tunnel)).as("the extended closure is found").isPresent();

        // another version with the same text and validity asks nothing
        org.mockito.Mockito.clearInvocations(enrichmentModel);
        transactionTemplate.executeWithoutResult(_ -> eventRepository.save(new com.nfv.input.entities.TrafficEvent(
                eventSourceRepository.findByName(NZTA).orElseThrow(), "566003@same", "566003", null, extended, NOW)));
        processor.processPending();
        verify(enrichmentModel, never()).extract(any(), any(), any());
        assertThat(event("566003", "same").getProcessingNote()).as("not FAILED").isNull();
        assertThat(event("566003", "same").getIncidentId()).isEqualTo(tunnel.getId());
    }

    @Test
    void closureWithWindowsCanEnd() {
        when(enrichmentModel.extract(mentioning("between 9pm and 4:30am"), any(), any()))
                .thenReturn(Optional.of(new EnrichmentModel.Answer(List.of(
                        new EnrichmentModel.LocalWindow(LocalDate.of(2026, 11, 4).atTime(21, 0),
                                LocalDate.of(2026, 11, 5).atTime(4, 30)),
                        new EnrichmentModel.LocalWindow(LocalDate.of(2026, 11, 5).atTime(21, 0),
                                LocalDate.of(2026, 11, 6).atTime(4, 30))))));
        long routeId = plannedRoute(OffsetDateTime.parse("2026-11-04T22:00:00+13:00"));
        polling.pollNow(NZTA);
        processor.processPending();
        Incident tunnel = incidentRepository.findById(event("566003").getIncidentId()).orElseThrow();
        assertThat(tunnelConsequence(routeId, tunnel)).isPresent();

        polling.pollNow(NZTA, "classpath:sources/nz-nzta/events-changed-status.json");
        processor.processPending();

        Event ended = event("566003", "2026-11-04T09:30:00.000+13:00");
        assertThat(ended.getProcessingNote()).as("not FAILED").isNull();
        assertThat(ended.getIncidentId()).isEqualTo(tunnel.getId());
        assertThat(incidentRepository.findById(tunnel.getId()).orElseThrow().getValidTo()).isAtSameInstantAs(NOW);
        assertThat(tunnelConsequence(routeId, tunnel)).isEmpty();
    }

    @Test
    void answerFromTheTextChangesTheIncident() {
        when(enrichmentModel.extract(mentioning("between 9pm and 4:30am"), any(), any()))
                .thenReturn(Optional.of(new EnrichmentModel.Answer(List.of(), null, false, null, 25,
                        TravelDirection.NORTHBOUND, "closed to northbound traffic", EnrichmentModel.Confidence.HIGH)));
        // the warning that NZTA rates Caution, closed according to its text
        when(enrichmentModel.extract(mentioning("between Terrace Tunnel and Ngauranga"), any(), any()))
                .thenReturn(Optional.of(new EnrichmentModel.Answer(List.of(), null, true, null, null, null, null,
                        EnrichmentModel.Confidence.HIGH)));

        polling.pollNow(NZTA);
        processor.processPending();

        Incident tunnel = incidentRepository.findById(event("566003").getIncidentId()).orElseThrow();
        assertThat(tunnel.getType()).isEqualTo(IncidentType.CLOSURE);
        assertThat(tunnel.isTimesUnconfirmed()).isFalse();
        // a figure from the text counts as an estimate
        assertThat(tunnel.getAssumedDelay()).isEqualTo(Duration.ofMinutes(25));
        // the road number is in both directions of the tunnel, the text limits it to one
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_segment WHERE incident_id = ?", Long.class,
                tunnel.getId())).isEqualTo(1);
        assertThat(incidentRepository.findById(event("566353").getIncidentId()).orElseThrow().getType())
                .isEqualTo(IncidentType.CLOSURE);
    }

    @Test
    void lowConfidenceAndFalseEvidenceAreIgnored() {
        when(enrichmentModel.extract(mentioning("between 9pm and 4:30am"), any(), any()))
                .thenReturn(Optional.of(new EnrichmentModel.Answer(List.of(), null, false, null, null, null,
                        "this quote is not in the message", EnrichmentModel.Confidence.HIGH)));
        when(enrichmentModel.extract(mentioning("between Terrace Tunnel and Ngauranga"), any(), any()))
                .thenReturn(Optional.of(new EnrichmentModel.Answer(List.of(), null, true, null, null, null, null,
                        EnrichmentModel.Confidence.LOW)));

        polling.pollNow(NZTA);
        processor.processPending();

        Incident tunnel = incidentRepository.findById(event("566003").getIncidentId()).orElseThrow();
        assertThat(tunnel.isTimesUnconfirmed()).isTrue();
        assertThat(incidentRepository.findById(event("566353").getIncidentId()).orElseThrow().getType())
                .isEqualTo(IncidentType.OTHER);
    }

    @Test
    void withoutAnAnswerTheEventWaitsAndIsCaughtUp() {
        when(enrichmentModel.extract(any(), any(), any())).thenThrow(new EnrichmentDeferredException("quota"));

        polling.pollNow(NZTA);
        processor.processPending();

        // tried once, then left alone for this run; processed like without a model
        verify(enrichmentModel, times(1)).extract(any(), any(), any());
        Event waiting = event("566003");
        assertThat(waiting.isEnrichmentPending()).isTrue();
        assertThat(waiting.getProcessingNote()).startsWith("TIMES_IN_TEXT");
        long incidentId = waiting.getIncidentId();
        assertThat(incidentRepository.findById(incidentId).orElseThrow().isTimesUnconfirmed()).isTrue();

        // the model answers again: same incident, now a real closure
        org.mockito.Mockito.reset(enrichmentModel);
        when(enrichmentModel.extract(any(), any(), any()))
                .thenReturn(Optional.of(new EnrichmentModel.Answer(List.of())));
        processor.processPending();

        Event caughtUp = event("566003");
        assertThat(caughtUp.isEnrichmentPending()).isFalse();
        assertThat(caughtUp.getIncidentId()).isEqualTo(incidentId);
        assertThat(caughtUp.getProcessingNote()).isNull();
        Incident closure = incidentRepository.findById(incidentId).orElseThrow();
        assertThat(closure.getType()).isEqualTo(IncidentType.CLOSURE);
        assertThat(closure.isTimesUnconfirmed()).isFalse();
    }

    @Test
    void anAnswerThatNeverComesIsGivenUpAfterSomeTries() {
        when(enrichmentModel.extract(any(), any(), any())).thenThrow(new EnrichmentDeferredException("refused"));
        polling.pollNow(NZTA, "classpath:sources/nz-nzta/events-empty.json");
        transactionTemplate.executeWithoutResult(_ -> eventRepository.save(new com.nfv.input.entities.TrafficEvent(
                eventSourceRepository.findByName(NZTA).orElseThrow(), "tries@1", "tries", null,
                polledPayload(), NOW)));

        for (int i = 0; i < 25; i++) {
            processor.processPending();
        }

        Event event = event("tries");
        assertThat(event.isEnrichmentPending()).isFalse();
        assertThat((int) event.getEnrichmentAttempts()).isEqualTo(20);
    }

    @Test
    void aUsedUpQuotaIsNoFailedTry() {
        when(enrichmentModel.extract(any(), any(), any()))
                .thenThrow(new EnrichmentDeferredException("quota", true));
        polling.pollNow(NZTA);

        for (int i = 0; i < 25; i++) {
            processor.processPending();
        }

        assertThat(event("566003").isEnrichmentPending()).isTrue();
        assertThat(jdbc.queryForObject("SELECT MAX(enrichment_attempts) FROM input_event", Integer.class)).isZero();
    }

    /** A later stubbing calls the mock with {@code null}, the matcher must cope. */
    private static String mentioning(String text) {
        return argThat(message -> message != null && message.contains(text));
    }

    /** The Terrace Tunnel event, read from the sample. */
    private String polledPayload() {
        polling.pollNow(NZTA);
        String payload = event("566003").getPayload();
        jdbc.update("DELETE FROM input_event WHERE subject_id <> 'tries' AND source_id IN "
                + "(SELECT id FROM event_source WHERE name = ?)", NZTA);
        return payload;
    }

    @Test
    void unloadedNetworkKeepsTheEventPending() {
        assertThat(polling.pollNow(UNLOADED).status()).isEqualTo(TrafficPolling.Status.POLLED);

        TrafficEventProcessor.Result result = processor.processPending();
        TrafficEventProcessor.Result again = processor.processPending();

        assertThat(result.pending()).isEqualTo(1);
        assertThat(again.pending()).isEqualTo(1);
        long sourceId = eventSourceRepository.findByName(UNLOADED).orElseThrow().getId();
        assertThat(eventRepository.findAll()).filteredOn(event -> event.getSource().getId() == sourceId)
                .singleElement().satisfies(event -> {
                    assertThat(event.getProcessedAt()).isNull();
                    assertThat(event.getIncidentId()).isNull();
                });
    }

    @Test
    void pollingPausesWhileNobodyTravelsInTheRegion() {
        TrafficSource unloaded = trafficSources.stream().filter(s -> s.name().equals(UNLOADED)).findFirst()
                .orElseThrow();
        assertThat(polling.isNeeded(unloaded)).as("no route and no incident in region nz").isFalse();
        assertThat(incidentReporting.hasPassagesInRegion("wellington", NOW.plusYears(5), NOW.plusYears(5).plusDays(14)))
                .isFalse();

        plannedRoute();

        assertThat(incidentReporting.hasPassagesInRegion("wellington", NOW, NOW.plusDays(14))).isTrue();
        assertThat(incidentReporting.hasPassagesInRegion("nz", NOW, NOW.plusDays(14))).isFalse();
    }

    /** Te Aro → Thorndon through the Terrace Tunnel (northbound), planned for {@link #DEPARTURE}. */
    private long plannedRoute() {
        return plannedRoute(DEPARTURE);
    }

    private long plannedRoute(OffsetDateTime departure) {
        long routeId = inTransaction(() -> {
            long userId = appUserProvisioning.findOrCreate("traffic-" + UUID.randomUUID(), null, null).getId();
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", departure, departure.plusDays(1), NOW));
            Stop start = stopRepository.save(new Stop(trip, 0, "Te Aro", -41.2947, 174.7735, null, departure));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", -41.2755, 174.7790, null, null));
            return routeRepository.save(new com.nfv.trips.entities.Route(trip, start, end)).getId();
        });
        assertThat(routePlanning.plan(routeId).outcome()).isEqualTo(PlanOutcome.PLANNED);
        return routeId;
    }

    private Optional<Consequence> tunnelConsequence(long routeId, Incident tunnel) {
        return consequenceRepository.findByRouteId(routeId).stream()
                .filter(c -> c.getIncident().getId() == tunnel.getId()).findFirst();
    }

    private List<Event> nztaEvents() {
        long sourceId = eventSourceRepository.findByName(NZTA).orElseThrow().getId();
        return eventRepository.findAll().stream().filter(event -> event.getSource().getId() == sourceId).toList();
    }

    private Event event(String subjectId) {
        return nztaEvents().stream().filter(event -> subjectId.equals(event.getSubjectId()))
                .min((a, b) -> Long.compare(a.getId(), b.getId())).orElseThrow();
    }

    private Event event(String subjectId, String version) {
        long sourceId = eventSourceRepository.findByName(NZTA).orElseThrow().getId();
        return eventRepository.findBySourceIdAndExternalId(sourceId, subjectId + "@" + version).orElseThrow();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}
