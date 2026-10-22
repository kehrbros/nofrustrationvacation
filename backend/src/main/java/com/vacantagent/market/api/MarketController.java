package com.vacantagent.market.api;

import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.dto.SubmissionDto;
import com.vacantagent.market.entities.Submission;
import com.vacantagent.market.logic.IMarketLogic;
import com.vacantagent.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Currency;
import java.util.concurrent.CompletableFuture;

/**
 * The market's API for consumers and providers. Orders and offers belong to the authenticated user, only the owner
 * may cancel them. Responses wait for the matching result without blocking a request thread.
 */
@Tag(name = "Market", description = "Place and cancel consumer orders and provider offers")
@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketController {
    private final IMarketLogic market;
    private final MarketRequestPreparer preparer;
    private final SubmissionDtoMapper submissionMapper;

    @Operation(summary = "Place an order",
            description = "Matched against the cheapest fitting offer for the requested model. If none fits, "
                    + "the order waits in the market for its marketWaitTime. A user may have only one waiting "
                    + "order per modelProvider and modelName. Both are trimmed, empty means any.")
    @ApiResponse(responseCode = "201", description = "Matched (agreement included) or waiting in the market")
    @ApiResponse(responseCode = "422", description = "Rejected by the market rules, or the user already has an "
            + "order waiting for this modelProvider and modelName, see reason",
            content = @Content(schema = @Schema(implementation = SubmissionDto.class)))
    @ApiResponse(responseCode = "400", description = "modelName without modelProvider, or unsupported currency",
            content = @Content)
    @PostMapping("/orders")
    public CompletableFuture<ResponseEntity<SubmissionDto>> placeOrder(@AuthenticationPrincipal CurrentUser user,
                                                                       @RequestBody OrderDto order) {
        OrderDto prepared = preparer.prepare(order);
        Currency currency = submissionMapper.responseCurrency(user);
        Submission submission = market.acceptOrder(prepared, user.appUserId());
        return submission.result().thenApply(result -> submissionMapper.toResponse(submission.id(), result, currency));
    }

    @Operation(summary = "Cancel a waiting order")
    @ApiResponse(responseCode = "204", description = "Removed from the market")
    @ApiResponse(responseCode = "404", description = "Not waiting: already matched, cancelled, expired, unknown "
            + "or placed by another user", content = @Content)
    @DeleteMapping("/orders/{id}")
    public CompletableFuture<ResponseEntity<Void>> cancelOrder(@AuthenticationPrincipal CurrentUser user,
                                                               @Parameter(description = "Order id from placing it")
                                                               @PathVariable long id) {
        if (!market.isOrderOwnedBy(id, user.appUserId())) {
            return CompletableFuture.completedFuture(ResponseEntity.notFound().build());
        }
        return market.cancelOrder(id).thenApply(SubmissionDtoMapper::toCancelResponse);
    }

    @Operation(summary = "Place an offer",
            description = "Matched against the highest-paying fitting order for its model. If none fits, "
                    + "the offer waits in the market until offeredUntil.")
    @ApiResponse(responseCode = "201", description = "Matched (agreement included) or waiting in the market")
    @ApiResponse(responseCode = "422", description = "Rejected by the market rules, see reason",
            content = @Content(schema = @Schema(implementation = SubmissionDto.class)))
    @ApiResponse(responseCode = "400", description = "modelProvider or modelName missing, or unsupported currency",
            content = @Content)
    @PostMapping("/offers")
    public CompletableFuture<ResponseEntity<SubmissionDto>> placeOffer(@AuthenticationPrincipal CurrentUser user,
                                                                       @RequestBody OfferDto offer) {
        OfferDto prepared = preparer.prepare(offer);
        Currency currency = submissionMapper.responseCurrency(user);
        Submission submission = market.acceptOffer(prepared, user.appUserId());
        return submission.result().thenApply(result -> submissionMapper.toResponse(submission.id(), result, currency));
    }

    @Operation(summary = "Cancel a waiting offer")
    @ApiResponse(responseCode = "204", description = "Removed from the market")
    @ApiResponse(responseCode = "404", description = "Not waiting: already matched, cancelled, expired, unknown "
            + "or placed by another user", content = @Content)
    @DeleteMapping("/offers/{id}")
    public CompletableFuture<ResponseEntity<Void>> cancelOffer(@AuthenticationPrincipal CurrentUser user,
                                                               @Parameter(description = "Offer id from placing it")
                                                               @PathVariable long id) {
        if (!market.isOfferOwnedBy(id, user.appUserId())) {
            return CompletableFuture.completedFuture(ResponseEntity.notFound().build());
        }
        return market.cancelOffer(id).thenApply(SubmissionDtoMapper::toCancelResponse);
    }
}