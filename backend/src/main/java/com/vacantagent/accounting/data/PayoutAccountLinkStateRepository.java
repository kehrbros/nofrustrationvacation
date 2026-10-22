package com.vacantagent.accounting.data;

import com.vacantagent.accounting.entities.PayoutAccountLinkState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface PayoutAccountLinkStateRepository extends JpaRepository<PayoutAccountLinkState, Long> {
    Optional<PayoutAccountLinkState> findByState(String state);

    @Modifying
    @Query("DELETE FROM PayoutAccountLinkState s WHERE s.appUserId = :appUserId OR s.createdAt < :expiredBefore")
    void deleteByAppUserIdOrExpired(@Param("appUserId") long appUserId, @Param("expiredBefore") OffsetDateTime expiredBefore);
}
