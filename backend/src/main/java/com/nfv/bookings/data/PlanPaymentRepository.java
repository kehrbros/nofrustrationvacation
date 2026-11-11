package com.nfv.bookings.data;

import com.nfv.bookings.entities.PlanPayment;
import com.nfv.bookings.entities.PlanPaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PlanPaymentRepository extends JpaRepository<PlanPayment, Long> {
    List<PlanPayment> findByStatusIn(Collection<PlanPaymentStatus> statuses);
}
