package com.nfv.bookings.logic;

import com.nfv.bookings.config.AlternativesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Requests to and answers from LiteAPI, against a mocked server. */
class LiteApiTest {
    private static final String SEARCH = "https://api.liteapi.test/v3.0";
    private static final String BOOK = "https://book.liteapi.test/v3.0";

    private MockRestServiceServer server;
    private LiteApi liteApi;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        liteApi = new LiteApi(builder.build(), new AlternativesProperties("sand_key", SEARCH, BOOK, BigDecimal.TEN,
                5000, 20, "DE", "http://localhost:18173", Duration.ofSeconds(5), 30,
                Duration.ofHours(3), false, Duration.ofMinutes(2)));
    }

    @Test
    void ratesAreNetWithHotelDataAndOccupancyPerRoom() {
        server.expect(requestTo(SEARCH + "/hotels/rates"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.margin").value(0))
                .andExpect(jsonPath("$.radius").value(3000))
                .andExpect(jsonPath("$.occupancies[0].adults").value(3))
                .andExpect(jsonPath("$.occupancies[1].adults").value(2))
                .andRespond(withSuccess("""
                        {"data": [{"hotelId": "lp1", "roomTypes": [{"offerId": "OF-1",
                           "offerRetailRate": {"amount": 163.66, "currency": "EUR"},
                           "suggestedSellingPrice": [{"amount": 190.0, "currency": "EUR", "source": "x"}],
                           "rates": [{"name": "Double Room", "boardName": "Breakfast", "cancellationPolicies": {
                             "refundableTag": "RFN", "cancelPolicyInfos": [
                               {"cancelTime": "2027-07-11 12:00:00", "amount": 50, "currency": "EUR"}]}}]}]},
                          {"hotelId": "unknown", "roomTypes": []}],
                         "hotels": [{"id": "lp1", "name": "Hotel Sole", "address": "Via Nazionale 5",
                           "latitude": 41.9, "longitude": 12.49, "stars": 4, "rating": 8.6}]}""",
                        MediaType.APPLICATION_JSON));

        List<LiteApi.HotelOffer> offers = liteApi.rates(new LiteApi.RateQuery(41.9, 12.49, 3000,
                LocalDate.of(2027, 7, 12), LocalDate.of(2027, 7, 15), List.of(3, 2), "EUR"));

        assertThat(offers).singleElement().satisfies(offer -> {
            assertThat(offer.hotelId()).isEqualTo("lp1");
            assertThat(offer.name()).isEqualTo("Hotel Sole");
            assertThat(offer.netPrice()).isEqualByComparingTo("163.66");
            assertThat(offer.suggestedSellingPrice()).isEqualByComparingTo("190.0");
            assertThat(offer.refundable()).isTrue();
            assertThat(offer.freeCancellationUntil()).isEqualTo(LocalDateTime.of(2027, 7, 11, 12, 0));
            assertThat(offer.stars()).isEqualTo(4.0);
        });
        server.verify();
    }

    @Test
    void hotelDetailsAndBooking() {
        server.expect(requestTo(SEARCH + "/data/hotel?hotelId=lp1"))
                .andRespond(withSuccess("""
                        {"data": {"id": "lp1", "name": "Hotel Sole", "address": "Via Nazionale 5", "city": "Rome",
                          "country": "it", "location": {"latitude": 41.9, "longitude": 12.49},
                          "checkinCheckoutTimes": {"checkin_start": "14:00", "checkout": "11:00"}}}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOK + "/rates/book"))
                .andExpect(jsonPath("$.payment.method").value("ACC_CREDIT_CARD"))
                .andExpect(jsonPath("$.clientReference").value("nfv-stay-1"))
                .andExpect(jsonPath("$.guests.length()").value(2))
                .andExpect(jsonPath("$.guests[1].occupancyNumber").value(2))
                .andRespond(withSuccess("""
                        {"data": {"bookingId": "LB-1", "hotelConfirmationCode": "HC-77", "status": "CONFIRMED"}}""",
                        MediaType.APPLICATION_JSON));

        LiteApi.HotelDetails hotel = liteApi.hotel("lp1");
        LiteApi.ProviderBooking booking = liteApi.book("P-1",
                new LiteApi.Holder("Erika", "Muster", "erika@example.org", "+49 170 1"), 2, "nfv-stay-1");

        assertThat(hotel.countryCode()).isEqualTo("IT");
        assertThat(hotel.latitude()).isEqualTo(41.9);
        assertThat(hotel.checkInFrom()).isEqualTo(LocalTime.of(14, 0));
        assertThat(hotel.checkOutUntil()).isEqualTo(LocalTime.of(11, 0));
        assertThat(booking.bookingId()).isEqualTo("LB-1");
        assertThat(booking.hotelConfirmationCode()).isEqualTo("HC-77");
    }

    @Test
    void refusedPrebookMeansOfferUnavailableServerErrorsUnknownOutcome() {
        server.expect(requestTo(BOOK + "/rates/prebook"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error": {"code": 4002, "description": "invalid offerId, rate not found"}}"""));
        server.expect(requestTo(BOOK + "/rates/book"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> liteApi.prebook("OF-1"))
                .isInstanceOf(OfferUnavailableException.class)
                .satisfies(e -> assertThat(((OfferUnavailableException) e).getProviderCode()).isEqualTo("4002"));
        assertThatThrownBy(() -> liteApi.book("P-1", new LiteApi.Holder("E", "M", "e@x.org", "+49 1"), 1, "r"))
                .isInstanceOf(LiteApiUnavailableException.class);
    }

    @Test
    void bookingIsFoundByClientReferenceAndCancelled() {
        server.expect(requestTo(BOOK + "/bookings?clientReference=nfv-stay-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"data": [{"bookingId": "LB-1", "clientReference": "nfv-stay-1",
                                   "hotelConfirmationCode": "HC-77", "status": "CONFIRMED"}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BOOK + "/bookings/LB-1"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("""
                        {"data": {"bookingId": "LB-1", "status": "CANCELLED_WITH_CHARGES", "refund_amount": 80.5}}""",
                        MediaType.APPLICATION_JSON));

        assertThat(liteApi.findBooking("nfv-stay-1")).hasValueSatisfying(booking -> {
            assertThat(booking.bookingId()).isEqualTo("LB-1");
            assertThat(booking.hotelConfirmationCode()).isEqualTo("HC-77");
        });
        LiteApi.Cancellation cancellation = liteApi.cancel("LB-1");
        assertThat(cancellation.isCancelled()).isTrue();
        assertThat(cancellation.isFree()).isFalse();
        assertThat(cancellation.refund()).isEqualByComparingTo("80.5");
    }

    @Test
    void feeIsCappedAtTheSuggestedSellingPrice() {
        assertThat(Pricing.price(new BigDecimal("150.00"), null, BigDecimal.TEN)).isEqualByComparingTo("165.00");
        assertThat(Pricing.price(new BigDecimal("150.00"), new BigDecimal("160.00"), BigDecimal.TEN))
                .isEqualByComparingTo("160.00");
        assertThat(Pricing.price(new BigDecimal("150.00"), new BigDecimal("140.00"), BigDecimal.TEN))
                .isEqualByComparingTo("165.00");
        assertThat(Pricing.adultsPerRoom(5, 2)).containsExactly(3, 2);
    }

    @Test
    void hotelsAreSearchedByNameAroundAPoint() {
        server.expect(requestTo(SEARCH + "/data/hotels?hotelName=Sole&limit=8&latitude=41.9&longitude=12.49&radius=30000"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"data": [{"id": "lp1", "name": "Hotel Sole", "address": "Via Nazionale 5", "city": "Roma",
                           "country": "it", "latitude": 41.9, "longitude": 12.49, "stars": 4},
                          {"id": "lp2"}]}""", MediaType.APPLICATION_JSON));

        assertThat(liteApi.searchHotels("Sole", 41.9, 12.49, 30000, 8)).singleElement()
                .isEqualTo(new LiteApi.HotelMatch("lp1", "Hotel Sole", "Via Nazionale 5", "Roma", "IT", 41.9, 12.49, 4.0));
        server.verify();
    }

    @Test
    void timesAreReadIn24HourAndAmPmFormat() {
        assertThat(LiteApi.time("14:00")).isEqualTo(LocalTime.of(14, 0));
        assertThat(LiteApi.time("9:30")).isEqualTo(LocalTime.of(9, 30));
        assertThat(LiteApi.time("14:00:00")).isEqualTo(LocalTime.of(14, 0));
        assertThat(LiteApi.time("03:00 PM")).isEqualTo(LocalTime.of(15, 0));
        assertThat(LiteApi.time("12:00 PM")).isEqualTo(LocalTime.NOON);
        assertThat(LiteApi.time("12:00 AM")).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(LiteApi.time("11:00 am")).isEqualTo(LocalTime.of(11, 0));
        assertThat(LiteApi.time("flexible")).isNull();
        assertThat(LiteApi.time("25:00")).isNull();
    }
}
