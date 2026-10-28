package com.vacantagent.llm.api;

import com.vacantagent.accounting.entities.AccountType;
import com.vacantagent.accounting.logic.AccountLedger;
import com.vacantagent.agent.logic.AgentLogic;
import com.vacantagent.common.money.Money;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.llm.data.LlmProtocolRepository;
import com.vacantagent.llm.dto.LlmCompletion;
import com.vacantagent.llm.dto.OrderLimits;
import com.vacantagent.llm.entities.LlmProtocol;
import com.vacantagent.llm.logic.ChatModelFactory;
import com.vacantagent.llm.logic.LlmCallFailedException;
import com.vacantagent.llm.logic.LlmRequestException;
import com.vacantagent.market.data.AgreementRepository;
import com.vacantagent.market.data.ConsumerOrderRepository;
import com.vacantagent.market.data.ProviderOfferRepository;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.logic.IMarketLogic;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserProvisioning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Prompts through agreements, with the agent's chat client mocked; market, ledger and database are real. */
@SpringBootTest
class LlmProxyServiceTest {
    /** Priced above every order other tests leave in the shared engine, so a re-entered offer stays waiting. */
    private static final Money PRICE_INPUT = Money.of("900.00");
    private static final Money PRICE_OUTPUT = Money.of("800.00");

    @Autowired
    private LlmProxyService service;
    @Autowired
    private IMarketLogic market;
    @Autowired
    private AgreementRepository agreements;
    @Autowired
    private ProviderOfferRepository offers;
    @Autowired
    private ConsumerOrderRepository orders;
    @Autowired
    private LlmProtocolRepository protocols;
    @Autowired
    private AccountLedger ledger;
    @Autowired
    private AgentLogic agentLogic;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private ChatModelFactory chatModelFactory;

    private final ChatModel chatModel = mock(ChatModel.class);
    private AppUser consumer;
    private AppUser provider;
    private long agentId;

    @BeforeEach
    void setUp() {
        consumer = newUser();
        provider = newUser();
        agentId = agentLogic.create(provider.getId(), "model", "provider", null, "sk-test-12345678").getId();
        when(chatModelFactory.create(any(), any())).thenReturn(chatModel);
    }

    @Test
    void successfulPromptIsProtocolledBilledAndItsOfferReentersTheMarket() {
        credit(consumer.getId(), "2.00");
        Agreement agreement = agreement(10_000L);
        answer(1000, 500);

        LlmCompletion completion = service.prompt(consumer.getId(), "Hello", null, null, null);

        // 1000 * 900 / 1M + 500 * 800 / 1M
        assertThat(completion.text()).isEqualTo("Hi");
        assertThat(completion.agreementId()).isEqualTo(agreement.getId());
        assertThat(completion.totalTokens()).isEqualTo(1500);
        assertThat(completion.cost().amount()).isEqualByComparingTo("1.30");

        LlmProtocol protocol = onlyProtocol(agreement);
        assertThat(protocol.isSuccess()).isTrue();
        assertThat(protocol.getAgentId()).isEqualTo(agentId);
        assertThat(protocol.getPromptTokens()).isEqualTo(1000);
        assertThat(protocol.getCompletionTokens()).isEqualTo(500);
        assertThat(protocol.getTotalTokens()).isEqualTo(1500);
        assertThat(protocol.getEndedAt()).isAfterOrEqualTo(protocol.getStartedAt());

        ProviderOffer offer = offers.findById(agreement.getProviderOffer().getId()).orElseThrow();
        assertThat(offer.getAvailableTokens()).isEqualTo(8500);
        assertThat(offer.getLastSuccessAt()).isNotNull();
        assertThat(offer.getLastErrorAt()).isNull();
        assertThat(orders.findById(agreement.getConsumerOrder().getId()).orElseThrow().getLastSuccessAt()).isNotNull();

        assertThat(ledger.balance(consumer.getId(), AccountType.CREDIT).amount()).isEqualByComparingTo("0.70");
        assertThat(ledger.balance(provider.getId(), AccountType.EARNINGS).amount()).isEqualByComparingTo("1.30");
        assertThat(market.cancelOffer(offer.getId()).join()).isInstanceOf(MatchResult.Cancelled.class);
    }

    @Test
    void costAboveTheCreditTakesItBelowZeroAndRefusesFurtherPrompts() {
        credit(consumer.getId(), "1.00");
        agreement(10_000L);
        answer(1000, 500);

        service.prompt(consumer.getId(), "Hello", null, null, null);

        assertThat(ledger.balance(consumer.getId(), AccountType.CREDIT).amount()).isEqualByComparingTo("-0.30");
        assertThat(ledger.balance(provider.getId(), AccountType.EARNINGS).amount()).isEqualByComparingTo("1.30");
        assertThatThrownBy(() -> service.prompt(consumer.getId(), "Again", null, null, null))
                .isInstanceOf(LlmRequestException.class)
                .hasMessage("Credit balance must be positive");
    }

    @Test
    void offerWithoutTokensLeftStaysOutOfTheMarket() {
        credit(consumer.getId(), "2.00");
        Agreement agreement = agreement(1000L);
        answer(1000, 500);

        service.prompt(consumer.getId(), "Hello", null, null, null);

        ProviderOffer offer = offers.findById(agreement.getProviderOffer().getId()).orElseThrow();
        assertThat(offer.getAvailableTokens()).isZero();
        assertThat(market.cancelOffer(offer.getId()).join()).isInstanceOf(MatchResult.NotFound.class);
        assertThatThrownBy(() -> service.prompt(consumer.getId(), "Again", null, null, null))
                .hasMessage("No active agreement for the requested model");
    }

    @Test
    void failedPromptIsProtocolledAndChargesNothing() {
        credit(consumer.getId(), "2.00");
        Agreement agreement = agreement(10_000L);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("401 Unauthorized"));

        assertThatThrownBy(() -> service.prompt(consumer.getId(), "Hello", null, null, null))
                .isInstanceOf(LlmCallFailedException.class)
                .hasMessage("Agent " + agentId + " did not complete the prompt");

        LlmProtocol protocol = onlyProtocol(agreement);
        assertThat(protocol.isSuccess()).isFalse();
        assertThat(protocol.getTotalTokens()).isZero();
        ProviderOffer offer = offers.findById(agreement.getProviderOffer().getId()).orElseThrow();
        assertThat(offer.getAvailableTokens()).isEqualTo(10_000);
        assertThat(offer.getLastErrorAt()).isNotNull();
        assertThat(offer.getLastSuccessAt()).isNull();
        assertThat(ledger.balance(consumer.getId(), AccountType.CREDIT).amount()).isEqualByComparingTo("2.00");
        assertThat(ledger.balance(provider.getId(), AccountType.EARNINGS)).isEqualTo(Money.ZERO);
        assertThat(market.cancelOffer(offer.getId()).join()).isInstanceOf(MatchResult.NotFound.class);
    }

    @Test
    void agreementMustFitTheRequestedModel() {
        credit(consumer.getId(), "2.00");
        agreement(10_000L);
        answer(10, 10);

        assertThatThrownBy(() -> service.prompt(consumer.getId(), "Hello", "other", null, null))
                .isInstanceOf(LlmRequestException.class)
                .hasMessage("No active agreement for the requested model");
        assertThatThrownBy(() -> service.prompt(consumer.getId(), "Hello", "provider", "other-model", null))
                .isInstanceOf(LlmRequestException.class);

        assertThat(service.prompt(consumer.getId(), "Hello", " Provider ", "model", null).text()).isEqualTo("Hi");
    }

    @Test
    void consumerWithoutCreditIsRefusedBeforeCallingTheAgent() {
        agreement(10_000L);

        assertThatThrownBy(() -> service.prompt(consumer.getId(), "Hello", null, null, null))
                .isInstanceOf(LlmRequestException.class)
                .hasMessage("Credit balance must be positive");
        verify(chatModelFactory, never()).create(any(), any());
    }

    @Test
    void consumerWithoutAgreementIsRefused() {
        credit(consumer.getId(), "2.00");

        assertThatThrownBy(() -> service.prompt(consumer.getId(), "Hello", null, null, null))
                .hasMessage("No active agreement for the requested model");
        verify(chatModelFactory, never()).create(any(), any());
    }

    @Test
    void withoutAgreementAnOrderIsPlacedAndThePromptUsesItsAgreement() {
        credit(consumer.getId(), "2.00");
        String model = "model-" + UUID.randomUUID();
        long modelAgentId = agentLogic.create(provider.getId(), model, "provider", null, "sk-test-12345678").getId();
        long offerId = market.acceptOffer(new OfferDto(usd("1.00"), usd("2.00"), null, modelAgentId, 10_000L),
                provider.getId()).id();
        answer(1000, 500);

        LlmCompletion completion = service.prompt(consumer.getId(), "Hello", "provider", model,
                new OrderLimits(usd("1.00"), usd("2.00"), Duration.ofSeconds(5)));

        // 1000 * 1 / 1M + 500 * 2 / 1M
        assertThat(completion.text()).isEqualTo("Hi");
        assertThat(completion.cost().amount()).isEqualByComparingTo("0.002");
        Agreement agreement = agreements.findById(completion.agreementId()).orElseThrow();
        assertThat(agreement.getProviderOffer().getId()).isEqualTo(offerId);
        assertThat(agreement.getConsumerOrder().getAppUser().getId()).isEqualTo(consumer.getId());
        assertThat(market.cancelOffer(offerId).join()).isInstanceOf(MatchResult.Cancelled.class);
    }

    @Test
    void orderWithoutFittingOfferRefusesThePrompt() {
        credit(consumer.getId(), "2.00");

        assertThatThrownBy(() -> service.prompt(consumer.getId(), "Hello", "provider", "model-" + UUID.randomUUID(),
                new OrderLimits(usd("1.00"), usd("2.00"), Duration.ZERO)))
                .isInstanceOf(LlmRequestException.class)
                .hasMessageStartingWith("No agreement for the requested model: ");
        verify(chatModelFactory, never()).create(any(), any());
    }

    private static MoneyDto usd(String amount) {
        return new MoneyDto(new BigDecimal(amount), "USD");
    }

    private void answer(int promptTokens, int completionTokens) {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("Hi"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(promptTokens, completionTokens)).build()));
    }

    private Agreement agreement(long availableTokens) {
        ProviderOffer offer = offers.save(new ProviderOffer(PRICE_INPUT, PRICE_OUTPUT, null, agentId, "provider",
                "model", null, null, availableTokens, availableTokens, provider, null));
        ConsumerOrder order = orders.save(new ConsumerOrder(PRICE_INPUT, PRICE_OUTPUT, null, OffsetDateTime.now(),
                "provider", "model", null, consumer, null));
        return agreements.save(new Agreement(order, offer, OffsetDateTime.now(), null, PRICE_INPUT, PRICE_OUTPUT));
    }

    private LlmProtocol onlyProtocol(Agreement agreement) {
        List<LlmProtocol> rows = protocols.findByAgreementIdOrderByIdAsc(agreement.getId());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private AppUser newUser() {
        return appUserProvisioning.findOrCreate("llm-" + UUID.randomUUID(), null, null);
    }

    private void credit(long appUserId, String amount) {
        jdbcTemplate.update("INSERT INTO accounting_account (app_user_id, type, balance, created_at) VALUES (?, 'CREDIT', ?, CURRENT_TIMESTAMP)",
                appUserId, new BigDecimal(amount));
    }
}