package com.vacantagent.market.data;

import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import static com.vacantagent.market.MarketFixtures.emptyOffer;
import static com.vacantagent.market.MarketFixtures.emptyOrder;
import static com.vacantagent.market.MarketFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests the writer thread against a mocked repository. */
class AgreementWriterTest {

    private final AgreementRepository repository = mock(AgreementRepository.class);
    private final AgreementWriter writer = new AgreementWriter(repository, 10);

    @AfterEach
    void tearDown() throws InterruptedException {
        writer.stop();
    }

    @Test
    void futureCompletesWithTheAgreementOnceSaved() throws Exception {
        writer.start();
        Agreement agreement = agreement();

        assertThat(writer.enqueue(agreement).get(1, TimeUnit.SECONDS)).isSameAs(agreement);
    }

    @Test
    void futureFailsWhenEveryAttemptFails() {
        when(repository.saveAll(anyList())).thenThrow(new IllegalStateException("database down"));
        writer.start();

        assertThat(writer.enqueue(agreement())).failsWithin(5, TimeUnit.SECONDS);
    }

    private static Agreement agreement() {
        return new Agreement(withId(emptyOrder(), 1), withId(emptyOffer(), 2),
                OffsetDateTime.now(), null, null, null);
    }
}