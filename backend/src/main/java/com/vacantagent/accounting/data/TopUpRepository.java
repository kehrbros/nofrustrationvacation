package com.vacantagent.accounting.data;

import com.vacantagent.accounting.entities.TopUp;
import com.vacantagent.accounting.entities.TopUpStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TopUpRepository extends JpaRepository<TopUp, Long> {
    Optional<TopUp> findByPaypalOrderIdAndAppUserId(String paypalOrderId, long appUserId);

    /** Serializes concurrent captures of the same top-up, so it is credited only once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TopUp t WHERE t.id = :id")
    Optional<TopUp> findByIdForUpdate(@Param("id") long id);

    List<TopUp> findByAppUserIdOrderByCreatedAtDescIdDesc(long appUserId, Pageable page);

    List<TopUp> findByStatus(TopUpStatus status);
}
