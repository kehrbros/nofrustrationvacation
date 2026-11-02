package com.nfv.accounting.data;

import com.nfv.accounting.entities.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    @EntityGraph(attributePaths = "account")
    List<LedgerEntry> findByAccountAppUserIdOrderByBookedAtDescIdDesc(long appUserId, Pageable page);

    List<LedgerEntry> findByAccountId(long accountId);
}
