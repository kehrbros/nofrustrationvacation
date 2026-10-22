package com.vacantagent.market.data;

import com.vacantagent.market.entities.ConsumerOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ConsumerOrderRepository extends JpaRepository<ConsumerOrder, Long> {

    /** Orders not yet part of an agreement, in creation order. */
    @Query("""
            SELECT o FROM ConsumerOrder o
            WHERE NOT EXISTS (SELECT a FROM Agreement a WHERE a.consumerOrder = o)
            ORDER BY o.id
            """)
    List<ConsumerOrder> findAllWithoutAgreement();

    boolean existsByIdAndAppUserId(long id, long appUserId);

    /**
     * Locks the agreement's order until the transaction ends. Recording a call and releasing an idle agreement both
     * lock the order, so one waits for the other and sees its result. Call it first in the transaction: an order
     * already loaded (e.g. with its agreement) is returned as is and may be stale.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Agreement a JOIN a.consumerOrder o WHERE a.id = :agreementId")
    Optional<ConsumerOrder> findForUpdateByAgreementId(long agreementId);
}