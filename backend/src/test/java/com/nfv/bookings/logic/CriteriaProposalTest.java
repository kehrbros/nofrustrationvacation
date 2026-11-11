package com.nfv.bookings.logic;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CriteriaProposalTest {

    private static CriteriaProposal booked(String checkIn, String checkOut, String earliest) {
        return new CriteriaProposal(1.0, 2.0, "Place", 5000, LocalDate.parse(checkIn), LocalDate.parse(checkOut),
                LocalDate.parse(earliest), 2, 1, new BigDecimal("300.00"), "EUR", null, null, List.of());
    }

    @Test
    void aStayThatHasNotStartedStaysAsBooked() {
        CriteriaProposal proposal = booked("2026-11-12", "2026-11-14", "2026-11-11");
        assertThat(proposal.fromEarliest()).isSameAs(proposal);
    }

    @Test
    void aRunningStayStartsOnTheEarliestDayWithThePriceOfTheNightsLeft() {
        CriteriaProposal remaining = booked("2026-11-10", "2026-11-13", "2026-11-11").fromEarliest();
        assertThat(remaining.checkIn()).isEqualTo("2026-11-11");
        assertThat(remaining.checkOut()).isEqualTo("2026-11-13");
        assertThat(remaining.maxPrice()).isEqualByComparingTo("200.00");
    }

    @Test
    void aStayWithoutNightsLeftHasNoCheckOut() {
        CriteriaProposal remaining = booked("2026-11-10", "2026-11-12", "2026-11-12").fromEarliest();
        assertThat(remaining.checkIn()).isEqualTo("2026-11-12");
        assertThat(remaining.checkOut()).isNull();
        assertThat(remaining.maxPrice()).isEqualByComparingTo("300.00");
    }
}
