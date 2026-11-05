package com.nfv.trips;

import com.nfv.trips.api.TripTools;
import com.nfv.trips.dto.RouteDetailDto;
import com.nfv.trips.dto.TripDetailDto;
import com.nfv.trips.dto.WaypointInputDto;
import com.nfv.trips.entities.PlanOutcome;
import com.nfv.trips.logic.TripNotFoundException;
import com.nfv.user.entities.AppUser;
import com.nfv.user.logic.AppUserProvisioning;
import com.nfv.user.security.AppUserAuthentication;
import com.nfv.user.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP tools act for the user of the request. Tools run on the request thread (Spring AI sets immediate execution for
 * servlet servers), so the user comes from the SecurityContext. Scope checks: McpAuthorizationFlowTest.
 */
@SpringBootTest
class TripToolsTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2027-02-01T00:00:00+13:00");

    @Autowired
    private TripTools tools;
    @Autowired
    private AppUserProvisioning appUserProvisioning;

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void toolsWorkOnTheTripsOfTheUser() {
        login("mcp-" + UUID.randomUUID());
        TripDetailDto trip = tools.createTrip("Neuseeland", START, START.plusDays(14), 2);
        assertThat(trip.travelers()).isEqualTo(2);
        tools.addStop(trip.id(), "Wellington", -41.2865, 174.7762, null, START.plusDays(1), null);
        TripDetailDto withStops = tools.addStop(trip.id(), "Napier", -39.4928, 176.9120, null, null, null);

        assertThat(withStops.stops()).extracting("name").containsExactly("Wellington", "Napier");
        assertThat(withStops.routes()).singleElement().satisfies(route -> assertThat(route.planOutcome())
                .isIn(PlanOutcome.PLANNED, PlanOutcome.NO_ROUTE, PlanOutcome.ROUTING_UNAVAILABLE));
        long routeId = withStops.routes().getFirst().id();

        RouteDetailDto route = tools.setWaypoints(trip.id(), routeId,
                List.of(new WaypointInputDto(null, -40.3523, 175.6082, "Palmerston North", "SH 1")));
        assertThat(route.waypoints()).extracting("name").containsExactly("Palmerston North");

        TripDetailDto moved = tools.moveStop(trip.id(), withStops.stops().get(1).id(), 0);
        assertThat(moved.stops()).extracting("name").containsExactly("Napier", "Wellington");
        assertThat(tools.listTrips()).extracting("name").containsExactly("Neuseeland");

        login("mcp-other-" + UUID.randomUUID());
        assertThat(tools.listTrips()).isEmpty();
        assertThatThrownBy(() -> tools.getTrip(trip.id())).isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void withoutUserNothingIsRead() {
        assertThatThrownBy(() -> tools.listTrips()).isInstanceOf(IllegalStateException.class);
    }

    private void login(String username) {
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(username)
                .claim("scope", "mcp:read mcp:write").build();
        SecurityContextHolder.getContext().setAuthentication(
                new AppUserAuthentication(new CurrentUser(appUser.getId(), username), jwt, List.of()));
    }
}