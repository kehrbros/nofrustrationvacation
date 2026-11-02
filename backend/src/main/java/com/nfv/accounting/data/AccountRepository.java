package com.nfv.accounting.data;

import com.nfv.accounting.entities.Account;
import com.nfv.accounting.entities.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAppUserIdAndType(long appUserId, AccountType type);

    List<Account> findByAppUserId(long appUserId);

    /**
     * Adds {@code delta} (negative for debits) in one atomic statement, so concurrent bookings never lose an update.
     * Refuses to go below zero.
     *
     * @return 1 if booked, 0 if the balance does not cover a debit
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE accounting_account SET balance = balance + :delta WHERE id = :id AND balance + :delta >= 0",
            nativeQuery = true)
    int addToBalance(@Param("id") long id, @Param("delta") BigDecimal delta);
}
