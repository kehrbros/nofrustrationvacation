package com.nfv.demo.api;

import com.nfv.demo.dto.DemoTripDto;
import com.nfv.demo.logic.DemoScenario;
import com.nfv.demo.logic.DemoTrips;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo trip for testing the main workflow (jury), see Docs/demo-trip.md. Only exists together with the manual test
 * source ({@code nfv.input.sources.manual.enabled=true}), because the demo reports its incidents through it.
 */
@Tag(name = "Demo trip", description = "Ready-made trip with incidents, only on test servers")
@RestController
@RequestMapping("/api/v1/demo-trips")
@ConditionalOnProperty(name = "nfv.input.sources.manual.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DemoTripController {
    private final DemoTrips demoTrips;

    @Operation(summary = "Whether demo trips can be created", description = "204 here, 404 on servers without them")
    @GetMapping
    public ResponseEntity<Void> available() {
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Create a demo trip", description = "New trip over the South Island that started two days "
            + "ago (New Zealand), under way in Kaikōura, with planned routes, bookings and test incidents that affect it. Takes a while: every route "
            + "is planned and every incident goes through the normal processing.")
    @PostMapping
    public DemoTripDto create(@AuthenticationPrincipal CurrentUser user,
                              @RequestParam(defaultValue = "en") String language) {
        DemoScenario.Language lang = "de".equalsIgnoreCase(language) ? DemoScenario.Language.DE
                : DemoScenario.Language.EN;
        return DemoTripDto.of(demoTrips.create(user.appUserId(), lang));
    }
}
