package com.nfv.input.logic;

import com.nfv.assistant.config.AssistantProperties;
import com.nfv.assistant.logic.AssistantRateLimitedException;
import com.nfv.assistant.logic.AssistantUnavailableException;
import com.nfv.assistant.logic.ExtractionInput;
import com.nfv.assistant.logic.StructuredExtraction;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.entities.VehicleScope;
import com.nfv.incidents.logic.TravelDirection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The model call without the model: what is sent and how answers are read. */
class AssistantEnrichmentModelTest {
    private static final ZoneId AUCKLAND = ZoneId.of("Pacific/Auckland");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final AssistantProperties PROPERTIES = new AssistantProperties("key", "url", "a-model", "image",
            "low", null, 30, 60);
    private static final TrafficReport REPORT = new TrafficReport(IncidentType.CLOSURE, false,
            OffsetDateTime.parse("2026-11-01T08:00:26Z"), OffsetDateTime.parse("2026-12-17T15:30:26Z"), "01N", null,
            null, null, null, "title", "text", "the message", null, false, List.of(), null);
    private static final String ANSWER = """
            {"timeWindows": [{"start": "2026-11-04T21:00", "end": "2026-11-05T04:30"}], "closedDuringWindows": true,
             "expectedReopening": "2026-11-06T15:00", "delayMinutes": 30, "direction": "NORTHBOUND",
             "vehicleScope": "HEAVY_ONLY", "evidence": "the message", "confidence": "MEDIUM"}""";

    private final StructuredExtraction extraction = mock(StructuredExtraction.class);

    private AssistantEnrichmentModel model(boolean enabled) {
        return new AssistantEnrichmentModel(extraction, PROPERTIES, enabled);
    }

    @Test
    void readsTheAnswer() {
        when(extraction.isAvailable()).thenReturn(true);
        when(extraction.extractForSystem(anyString(), anyString(), any(), anyList()))
                .thenReturn(MAPPER.readTree(ANSWER));

        EnrichmentModel.Answer answer = model(true).extract("the message", REPORT, AUCKLAND).orElseThrow();

        assertThat(answer.timeWindows()).containsExactly(new EnrichmentModel.LocalWindow(
                LocalDateTime.parse("2026-11-04T21:00"), LocalDateTime.parse("2026-11-05T04:30")));
        assertThat(answer.closedDuringWindows()).isTrue();
        assertThat(answer.expectedReopening()).isEqualTo(LocalDateTime.parse("2026-11-06T15:00"));
        assertThat(answer.delayMinutes()).isEqualTo(30);
        assertThat(answer.direction()).isEqualTo(TravelDirection.NORTHBOUND);
        assertThat(answer.vehicleScope()).isEqualTo(VehicleScope.HEAVY_ONLY);
        assertThat(answer.evidence()).isEqualTo("the message");
        assertThat(answer.confidence()).isEqualTo(EnrichmentModel.Confidence.MEDIUM);
    }

    @Test
    void sendsTheTextAndTheLocalValidity() {
        when(extraction.isAvailable()).thenReturn(true);
        when(extraction.extractForSystem(anyString(), anyString(), any(), anyList()))
                .thenReturn(MAPPER.readTree(ANSWER));

        model(true).extract("the message", REPORT, AUCKLAND);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExtractionInput>> inputs = ArgumentCaptor.forClass(List.class);
        verify(extraction).extractForSystem(anyString(), anyString(), any(), inputs.capture());
        assertThat(inputs.getValue()).singleElement().isInstanceOfSatisfying(ExtractionInput.Text.class,
                text -> assertThat(text.text()).contains("2026-11-01T21:00:26").contains("2026-12-18T04:30:26")
                        .contains("the message"));
    }

    @Test
    void unknownDirectionAndNoFiguresAreEmpty() {
        EnrichmentModel.Answer answer = AssistantEnrichmentModel.answer(MAPPER.readTree("""
                {"timeWindows": [], "closedDuringWindows": false, "expectedReopening": null, "delayMinutes": null,
                 "direction": "UNKNOWN", "vehicleScope": "ALL", "evidence": "x", "confidence": "HIGH"}"""));

        assertThat(answer.direction()).isNull();
        assertThat(answer.expectedReopening()).isNull();
        assertThat(answer.delayMinutes()).isNull();
        assertThat(answer.timeWindows()).isEmpty();
    }

    @Test
    void withoutKeyOrSwitchedOffNothingIsAsked() {
        when(extraction.isAvailable()).thenReturn(false);
        assertThat(model(true).extract("the message", REPORT, AUCKLAND)).isEmpty();

        when(extraction.isAvailable()).thenReturn(true);
        assertThat(model(false).extract("the message", REPORT, AUCKLAND)).isEmpty();

        verify(extraction, never()).extractForSystem(anyString(), anyString(), any(), anyList());
    }

    @Test
    void noAnswerNowIsDeferred() {
        when(extraction.isAvailable()).thenReturn(true);
        doThrow(new AssistantRateLimitedException()).when(extraction)
                .extractForSystem(anyString(), anyString(), any(), anyList());
        assertThatThrownBy(() -> model(true).extract("the message", REPORT, AUCKLAND))
                .isInstanceOf(EnrichmentDeferredException.class);

        doThrow(new AssistantUnavailableException("failed")).when(extraction)
                .extractForSystem(anyString(), anyString(), any(), anyList());
        assertThatThrownBy(() -> model(true).extract("the message", REPORT, AUCKLAND))
                .isInstanceOf(EnrichmentDeferredException.class);

        doReturn(MAPPER.readTree(ANSWER.replace("2026-11-04T21:00", "tonight"))).when(extraction)
                .extractForSystem(anyString(), anyString(), any(), anyList());
        assertThatThrownBy(() -> model(true).extract("the message", REPORT, AUCKLAND))
                .isInstanceOf(EnrichmentDeferredException.class);
    }
}
