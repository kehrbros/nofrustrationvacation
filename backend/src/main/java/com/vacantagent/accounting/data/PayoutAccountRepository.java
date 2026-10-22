package com.vacantagent.accounting.data;

import com.vacantagent.accounting.entities.PayoutAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, Long> {
    Optional<PayoutAccount> findByAppUserId(long appUserId);

    boolean existsByAppUserId(long appUserId);

    boolean existsByProviderAndExternalAccountIdAndAppUserIdNot(String provider, String externalAccountId, long appUserId);
}
