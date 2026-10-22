package com.vacantagent.accounting.data;

import com.vacantagent.accounting.entities.Payout;
import com.vacantagent.accounting.entities.PayoutStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payout p WHERE p.id = :id")
    Optional<Payout> findByIdForUpdate(@Param("id") long id);

    List<Payout> findByAppUserIdOrderByRequestedAtDescIdDesc(long appUserId, Pageable page);

    List<Payout> findByStatus(PayoutStatus status);

    boolean existsByAppUserIdAndStatusIn(long appUserId, Collection<PayoutStatus> statuses);
}
