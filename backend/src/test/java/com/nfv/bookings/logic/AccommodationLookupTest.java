package com.nfv.bookings.logic;

import com.nfv.assistant.logic.AssistantUnavailableException;
import com.nfv.assistant.logic.ExtractionInput;
import com.nfv.assistant.logic.StructuredExtraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Suggestions and facts: LiteAPI first, the assistant only for what is missing. */
class AccommodationLookupTest {
    private final LiteApi liteApi = mock(LiteApi.class);
    private final StructuredExtraction assistant = mock(StructuredExtraction.class);
    private final AccommodationLookup lookup = new AccommodationLookup(liteApi, assistant);

    @BeforeEach
    void setUp() {
        when(liteApi.isAvailable()).thenReturn(true);
        when(assistant.isAvailable()).thenReturn(true);
    }

    @Test
    void suggestionsNearTheStopComeFirstNearestFirst() {
        LiteApi.HotelMatch far = new LiteApi.HotelMatch("far", "Hotel Sole Far", "Via B 2", "Roma", "IT", 42.0, 12.6, null);
        LiteApi.HotelMatch near = new LiteApi.HotelMatch("near", "Hotel Sole", "Via A 1", "Roma", "IT", 41.9, 12.49, 4.0);
        LiteApi.HotelMatch elsewhere = new LiteApi.HotelMatch("else", "Sole Milano", "Via C", "Milano", "IT", 45.4, 9.2, 3.0);
        when(liteApi.searchHotels("Sole", 41.9, 12.49, AccommodationLookup.RADIUS_METERS, 8)).thenReturn(List.of(far, near));
        when(liteApi.searchHotels("Sole", null, null, 0, 8)).thenReturn(List.of(near, elsewhere));

        assertThat(lookup.suggest(" Sole ", 41.9, 12.49)).extracting(AccommodationSuggestion::hotelId)
                .containsExactly("near", "far", "else");
        assertThat(lookup.suggest("So", 41.9, 12.49)).isEmpty();
    }

    @Test
    void assistantLooksUpOnlyWhatLiteApiDoesNotKnow() {
        when(liteApi.hotel("lp1")).thenReturn(new LiteApi.HotelDetails("lp1", "Hotel Sole", "Via A 1", "Roma", "IT",
                41.9, 12.49, "+39 06 123", null, null, LocalTime.of(14, 0), null, LocalTime.of(11, 0)));
        when(assistant.extract(anyLong(), anyString(), eq("accommodation_facts"), any(), any(), eq(true)))
                .thenReturn(JsonMapper.builder().build().readTree("""
                        {"phone": "+39 999", "email": "info@sole.example", "website": "http://insecure.example",
                         "checkInFrom": "15:00", "checkInUntil": "11:00 PM", "checkOutUntil": null,
                         "sources": ["https://sole.example/info", "ftp://x"]}"""));

        AccommodationFacts facts = lookup.facts(7, new AccommodationQuery("lp1", "Hotel Sole", null, null, 41.9, 12.49));

        assertThat(facts.phone()).isEqualTo("+39 06 123");
        assertThat(facts.checkInFrom()).isEqualTo(LocalTime.of(14, 0));
        assertThat(facts.checkOutUntil()).isEqualTo(LocalTime.of(11, 0));
        assertThat(facts.addressLine()).isEqualTo("Via A 1, Roma");
        assertThat(facts.email()).isEqualTo("info@sole.example");
        assertThat(facts.checkInUntil()).isEqualTo(LocalTime.of(23, 0));
        assertThat(facts.website()).isNull();
        assertThat(facts.fromLiteApi()).containsExactly("addressLine", "countryCode", "phone", "checkInFrom",
                "checkOutUntil");
        assertThat(facts.fromAssistant()).containsExactly("email", "checkInUntil");
        assertThat(facts.researched()).isTrue();
        assertThat(facts.sources()).containsExactly("https://sole.example/info");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExtractionInput>> inputs = ArgumentCaptor.forClass(List.class);
        verify(assistant).extract(eq(7L), anyString(), eq("accommodation_facts"), any(), inputs.capture(), eq(true));
        String prompt = ((ExtractionInput.Text) inputs.getValue().getFirst()).text();
        assertThat(prompt).contains("Hotel Sole", "Via A 1, Roma", "Missing fields: email, website, checkInUntil")
                .doesNotContain("phone");
    }

    @Test
    void completeLiteApiDataNeedsNoAssistant() {
        when(liteApi.hotel("lp1")).thenReturn(new LiteApi.HotelDetails("lp1", "Hotel Sole", "Via A 1", null, "IT",
                41.9, 12.49, "+39 06 123", "info@sole.example", "https://sole.example", LocalTime.of(14, 0),
                LocalTime.of(22, 0), LocalTime.of(11, 0)));

        AccommodationFacts facts = lookup.facts(7, new AccommodationQuery("lp1", "Hotel Sole", null, null, null, null));

        assertThat(facts.researched()).isFalse();
        assertThat(facts.fromAssistant()).isEmpty();
        verify(assistant, never()).extract(anyLong(), anyString(), anyString(), any(), any(), eq(true));
    }

    @Test
    void withoutLiteApiIdTheAssistantLooksUpEverythingAndFailuresGiveAnEmptyAnswer() {
        when(assistant.extract(anyLong(), anyString(), anyString(), any(), any(), eq(true)))
                .thenThrow(new AssistantUnavailableException("down"));

        AccommodationFacts facts = lookup.facts(7, new AccommodationQuery(null, "Ferienhaus Sonnenschein",
                "Dorfstr. 1, Wyk", "DE", null, null));

        assertThat(facts.phone()).isNull();
        assertThat(facts.fromLiteApi()).isEmpty();
        assertThat(facts.researched()).isFalse();
        verify(liteApi, never()).hotel(anyString());
    }
}
