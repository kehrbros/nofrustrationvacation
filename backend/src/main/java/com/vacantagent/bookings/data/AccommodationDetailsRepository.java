package com.vacantagent.bookings.data;

import com.vacantagent.bookings.entities.AccommodationDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccommodationDetailsRepository extends JpaRepository<AccommodationDetails, Long> {
    Optional<AccommodationDetails> findByBookingId(long bookingId);

    List<AccommodationDetails> findByBookingIdIn(Collection<Long> bookingIds);
}
