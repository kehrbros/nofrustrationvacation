package com.nfv.demo;

import com.jayway.jsonpath.JsonPath;
import com.nfv.user.entities.AppUser;
import com.nfv.user.logic.AppUserProvisioning;
import com.nfv.user.security.AppUserAuthentication;
import com.nfv.user.security.CurrentUser;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Demo trip through the API. The test network is Wellington only, so the South Island routes are not planned and the
 * incidents are reported as given (no segments there); planning and placing are covered by the trips and incidents tests.
 */
// as ManualSourceApiTest (shared context); a region with a network, so the events are processed instead of staying
// pending for other tests (the South Island points lie outside it: NO_SEGMENTS)
@SpringBootTest(properties = {"nfv.input.sources.manual.enabled=true",
        "nfv.input.sources.manual.network-region=wellington",
        "nfv.input.processing.after-poll=true"})
@AutoConfigureMockMvc
class DemoTripApiTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private RegionImport regionImport;

    @BeforeEach
    void setUp() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.loadOrImport("wellington");
        }
    }

    @Test
    void createsTheTripWithStopsRoutesBookingsAndIncidents() throws Exception {
        RequestPostProcessor user = login("demo-" + UUID.randomUUID());
        mockMvc.perform(get("/api/v1/demo-trips").with(user)).andExpect(status().isNoContent());

        String body = mockMvc.perform(post("/api/v1/demo-trips").param("language", "de").with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidents", hasSize(6)))
                .andExpect(jsonPath("$.incidents[0].key").value("burkes-pass"))
                .andExpect(jsonPath("$.incidents[*].outcome", everyItem(not("PENDING"))))
                .andReturn().getResponse().getContentAsString();
        long tripId = ((Number) JsonPath.read(body, "$.tripId")).longValue();

        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Südinsel Neuseeland (Demo)"))
                .andExpect(jsonPath("$.travelers").value(2))
                .andExpect(jsonPath("$.stops", hasSize(6)))
                .andExpect(jsonPath("$.stops[0].name").value("Christchurch"))
                .andExpect(jsonPath("$.stops[5].name").value("Milford Sound"))
                .andExpect(jsonPath("$.routes", hasSize(5)));
        mockMvc.perform(get("/api/v1/trips/{id}/bookings", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    void eachCallCreatesANewTrip() throws Exception {
        RequestPostProcessor user = login("demo-" + UUID.randomUUID());
        String first = mockMvc.perform(post("/api/v1/demo-trips").with(user).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/demo-trips").with(user).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long firstId = ((Number) JsonPath.read(first, "$.tripId")).longValue();
        long secondId = ((Number) JsonPath.read(second, "$.tripId")).longValue();
        org.assertj.core.api.Assertions.assertThat(secondId).isNotEqualTo(firstId);
        mockMvc.perform(get("/api/v1/trips/{id}", firstId).with(user))
                .andExpect(jsonPath("$.name").value("New Zealand South Island (demo)"));
    }

    private RequestPostProcessor login(String username) {
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(username).build();
        return authentication(new AppUserAuthentication(new CurrentUser(appUser.getId(), username), jwt, List.of()));
    }
}
