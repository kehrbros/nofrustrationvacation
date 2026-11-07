package com.nfv.bookings.data;

import com.nfv.bookings.entities.BookingTodo;
import com.nfv.bookings.entities.TodoKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BookingTodoRepository extends JpaRepository<BookingTodo, Long> {
    List<BookingTodo> findByTripIdOrderByCreatedAtDescIdDesc(long tripId);

    List<BookingTodo> findByBookingIdIn(Collection<Long> bookingIds);

    boolean existsByBookingIdAndKindAndDoneAtIsNull(long bookingId, TodoKind kind);
}
