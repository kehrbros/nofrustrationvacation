package com.nfv.demo.logic;

import com.nfv.incidents.entities.IncidentType;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The demo trip over the South Island of New Zealand with its stops, bookings and test incidents, relative to the first
 * day of the trip, see Docs/demo-trip.md. Pure data, nothing is stored here.
 */
public final class DemoScenario {
    public static final ZoneId ZONE = ZoneId.of("Pacific/Auckland");
    public static final int DAYS = 11;
    /** The trip started this many days before today (New Zealand): the user is under way, in Kaikōura. */
    public static final int STARTED_DAYS_AGO = 2;
    public static final int TRAVELERS = 2;
    public static final String CURRENCY = "NZD";

    /** Language of the names and texts the user reads. */
    public enum Language {
        DE, EN
    }

    /** A stop with its accommodation; arrival and departure in New Zealand time, {@code null} at the ends. */
    public record DemoStop(String name, double latitude, double longitude, @Nullable OffsetDateTime arrivalAt,
                           @Nullable OffsetDateTime departureAt, @Nullable DemoStay stay) {
    }

    /** A manually entered accommodation booking, times local at the accommodation. */
    public record DemoStay(String name, String addressLine, double latitude, double longitude, String reference,
                           LocalDateTime checkIn, LocalDateTime checkOut, LocalTime checkInFrom,
                           LocalTime checkInUntil, LocalTime checkOutUntil, BigDecimal price, String notes) {
    }

    /**
     * A test incident on the route from the stop at {@code fromStop} to the next one, placed onto the planned route
     * next to {@code latitude}/{@code longitude}.
     *
     * @param key      stable part of the event id, events of the same key and first day are one event
     * @param resolved reported and then ended at once, so that the trip shows a resolved alert
     */
    public record DemoIncident(String key, int fromStop, IncidentType type, double latitude, double longitude,
                               OffsetDateTime validFrom, @Nullable OffsetDateTime validTo,
                               @Nullable Long delayMinutes, String text, boolean resolved) {
    }

    private final LocalDate firstDay;
    private final Language language;
    private final OffsetDateTime now;

    /**
     * @param firstDay first day of the trip (day 1), in New Zealand, {@link #STARTED_DAYS_AGO} before today
     * @param now      validity start of the incidents that are already active
     */
    public DemoScenario(LocalDate firstDay, Language language, OffsetDateTime now) {
        this.firstDay = firstDay;
        this.language = language;
        this.now = now;
    }

    public LocalDate firstDay() {
        return firstDay;
    }

    public String tripName() {
        return text("Südinsel Neuseeland (Demo)", "New Zealand South Island (demo)");
    }

    public OffsetDateTime startAt() {
        return at(1, 0, 0);
    }

    public OffsetDateTime endAt() {
        return firstDay.plusDays(DAYS - 1L).atTime(23, 59, 59).atZone(ZONE).toOffsetDateTime();
    }

    public List<DemoStop> stops() {
        return List.of(
                new DemoStop("Christchurch", -43.5309, 172.6366, null, at(1, 9, 0), null),
                new DemoStop("Kaikōura", -42.4004, 173.6810, at(1, 13, 0), at(4, 9, 0),
                        stay("Kaikōura Seaview Motel (Demo)", "Esplanade, Kaikōura 7300", -42.4010, 173.6835,
                                "DEMO-KAI-1", 1, 4, LocalTime.of(14, 0), LocalTime.of(21, 0), 510,
                                text("Whale Watch und Robbenkolonie, Weiterfahrt am Morgen von Tag 4.",
                                        "Whale watching and the seal colony, driving on the morning of day 4."))),
                new DemoStop("Lake Tekapo", -44.0046, 170.4770, at(4, 16, 0), at(6, 10, 0),
                        stay("Tekapo Starlight Lodge (Demo)", "Lakeside Drive, Lake Tekapo 7999", -44.0060, 170.4805,
                                "DEMO-TEK-4", 4, 6, LocalTime.of(15, 0), LocalTime.of(19, 0), 390,
                                text("Sternwarte Mount John am Abend von Tag 4.",
                                        "Mount John observatory on the evening of day 4."))),
                new DemoStop("Wānaka", -44.6990, 169.1360, at(6, 14, 0), at(8, 10, 0),
                        stay("Wānaka Lakefront Apartments (Demo)", "Ardmore Street, Wānaka 9305", -44.6975, 169.1380,
                                "DEMO-WAN-6", 6, 8, LocalTime.of(14, 0), LocalTime.of(20, 0), 420,
                                text("Roys Peak am Morgen von Tag 7.", "Roys Peak on the morning of day 7."))),
                new DemoStop("Queenstown", -45.0312, 168.6626, at(8, 12, 15), at(10, 7, 30),
                        stay("Queenstown Lakeview Hotel (Demo)", "Lake Esplanade, Queenstown 9300", -45.0335, 168.6585,
                                "DEMO-QUE-8", 8, 10, LocalTime.of(15, 0), LocalTime.of(22, 0), 460,
                                text("Gondel und Abendessen am Tag 8.", "Gondola and dinner on day 8."))),
                new DemoStop("Milford Sound", -44.6717, 167.9256, at(10, 12, 30), at(11, 10, 0),
                        stay("Milford Sound Rainforest Lodge (Demo)", "State Highway 94, Milford Sound 9679",
                                -44.6760, 167.9290, "DEMO-MIL-10", 10, 11, LocalTime.of(14, 0), LocalTime.of(20, 0),
                                280, text("Bootsfahrt durch den Fjord am Nachmittag von Tag 10.",
                                        "Fjord cruise on the afternoon of day 10."))));
    }

    /** Waypoint of the route Wānaka → Queenstown: over the Crown Range instead of through Cromwell. */
    public static final int CROWN_RANGE_ROUTE_FROM = 3;
    public static final double CARDRONA_LATITUDE = -44.8730;
    public static final double CARDRONA_LONGITUDE = 169.0080;

    /** Only on the routes still ahead: the route Christchurch → Kaikōura was driven on day 1. */
    public List<DemoIncident> incidents() {
        return List.of(
                // snow over the pass from the morning of day 4 to the next morning: no way into the basin that day
                new DemoIncident("burkes-pass", 1, IncidentType.CLOSURE, -44.0800, 170.6500, at(4, 6, 0),
                        at(5, 11, 0), null,
                        text("Schneefall am Burkes Pass, Straße bis zum nächsten Vormittag gesperrt.",
                                "Snow on Burkes Pass, road closed until the next morning."), false),
                // a few minutes only
                new DemoIncident("cheviot", 1, IncidentType.CONGESTION, -42.8130, 173.2730, now, at(4, 18, 0), 3L,
                        text("Viehtrieb auf dem Highway, kurze Wartezeit.",
                                "Stock crossing on the highway, short wait."), false),
                // late, but still in time: solutions only on request
                new DemoIncident("lindis-pass", 2, IncidentType.ACCIDENT, -44.5860, 169.6530, now, at(6, 16, 0), 25L,
                        text("Unfall am Lindis Pass, eine Spur gesperrt.",
                                "Crash on Lindis Pass, one lane closed."), false),
                // already over again
                new DemoIncident("omarama", 2, IncidentType.ROADWORKS, -44.4870, 169.9690, now, at(6, 18, 0), 10L,
                        text("Baustelle mit Ampel bei Omarama, inzwischen beendet.",
                                "Roadworks with traffic lights near Omarama, finished in the meantime."), true),
                // closed until further notice, the way through Cromwell is on time: free detour
                new DemoIncident("crown-range", 3, IncidentType.CLOSURE, -44.9390, 168.9690, now, null, null,
                        text("Glatteis auf der Crown Range, Straße bis auf Weiteres gesperrt.",
                                "Black ice on the Crown Range, road closed until further notice."), false),
                // the only road, closed until further notice: another destination
                new DemoIncident("milford-road", 4, IncidentType.CLOSURE, -45.0480, 168.0000, now, null, null,
                        text("Lawinengefahr, Milford Road bis auf Weiteres gesperrt.",
                                "Avalanche risk, Milford Road closed until further notice."), false));
    }

    private DemoStay stay(String name, String address, double latitude, double longitude, String reference,
                          int checkInDay, int checkOutDay, LocalTime checkInFrom, LocalTime checkInUntil, int price,
                          String notes) {
        return new DemoStay(name, address, latitude, longitude, reference,
                day(checkInDay).atTime(checkInFrom), day(checkOutDay).atTime(10, 0), checkInFrom, checkInUntil,
                LocalTime.of(10, 0), BigDecimal.valueOf(price).setScale(2), notes);
    }

    private LocalDate day(int day) {
        return firstDay.plusDays(day - 1L);
    }

    private OffsetDateTime at(int day, int hour, int minute) {
        return day(day).atTime(hour, minute).atZone(ZONE).toOffsetDateTime();
    }

    private String text(String german, String english) {
        return language == Language.DE ? german : english;
    }
}
