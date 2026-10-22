package com.vacantagent.market.data;

import com.vacantagent.market.entities.ConsumerOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ConsumerOrderRepository extends JpaRepository<ConsumerOrder, Long> {

    /** Orders not yet part of an agreement, in creation order. */
    @Query("""
            SELECT o FROM ConsumerOrder o
            WHERE NOT EXISTS (SELECT a FROM Agreement a WHERE a.consumerOrder = o)
            ORDER BY o.id
            """)
    List<ConsumerOrder> findAllWithoutAgreement();

    boolean existsByIdAndAppUserId(long id, long appUserId);
}