package com.vacantagent.market.engine;

import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.market.matching.MatchingStrategy;
import com.vacantagent.market.matching.entities.OrderBook;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Runs matching on one dedicated thread. Any number of threads (web requests, listeners) call
 * {@link #submit}; commands are queued and processed strictly one at a time in arrival order,
 * so the book and strategy need no locking.
 *
 * <p>Plain Java on purpose: Spring creates it as a bean and calls {@link #start}/{@link #stop},
 * while tests can construct it directly or call {@link #handle} synchronously without a thread.
 */
public final class MatchingEngine {

    /** Commands accepted by the engine. */
    public sealed interface Command {}
    public record SubmitOffer(BookOffer offer) implements Command {}
    public record SubmitOrder(BookOrder order) implements Command {}
    public record CancelOffer(long offerId) implements Command {}
    public record CancelOrder(long orderId) implements Command {}

    private record Envelope(Command command, CompletableFuture<MatchResult> reply) {}

    private static final Envelope POISON = new Envelope(null, null);

    private final OrderBook book;
    private final MatchingStrategy strategy;
    private final Function<Agreement, CompletableFuture<Agreement>> agreementSink;
    private final BlockingQueue<Envelope> inbox;
    private volatile Thread thread;

    /**
     * @param agreementSink receives every agreement on the engine thread and returns a future that completes
     *                      once it is stored; must be fast and non-blocking (e.g. hand off to
     *                      {@code AgreementWriter}), or it slows down all matching
     */
    public MatchingEngine(OrderBook book, MatchingStrategy strategy,
                          Function<Agreement, CompletableFuture<Agreement>> agreementSink, int inboxCapacity) {
        this.book = Objects.requireNonNull(book);
        this.strategy = Objects.requireNonNull(strategy);
        this.agreementSink = Objects.requireNonNull(agreementSink);
        this.inbox = new ArrayBlockingQueue<>(inboxCapacity);
    }

    // ------------------------------------------------------------------ startup restore

    /** Loads a waiting offer from the database. Call before {@link #start}, in creation order. */
    public void restoreOffer(BookOffer offer) {
        requireNotRunning();
        book.addOffer(offer);
    }

    /** Loads a waiting order from the database. Call before {@link #start}, in creation order. */
    public void restoreOrder(BookOrder order) {
        requireNotRunning();
        book.addOrder(order);
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() {
        if (thread != null) return;
        thread = Thread.ofPlatform().name("matching-engine").start(this::loop);
    }

    /** Processes everything already queued, then stops. */
    public synchronized void stop() throws InterruptedException {
        if (thread == null) return;
        inbox.put(POISON);
        thread.join();
        thread = null;
    }

    public boolean isRunning() {
        return thread != null;
    }

    // ------------------------------------------------------------------ API

    /**
     * Queues a command and returns its result asynchronously. A {@code Matched} result completes only once
     * the agreement is stored, so callers see its id; if storing fails, the future fails although the pair
     * has already left the book. Blocks the caller if the inbox is full, which applies backpressure to
     * producers during bursts.
     */
    public CompletableFuture<MatchResult> submit(Command command) {
        Objects.requireNonNull(command);
        if (thread == null) throw new IllegalStateException("Matching engine is not running");
        CompletableFuture<MatchResult> reply = new CompletableFuture<>();
        try {
            inbox.put(new Envelope(command, reply));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply.completeExceptionally(e);
        }
        return reply;
    }

    /**
     * Executes one command without waiting for the agreement to be stored. Tests may call it directly
     * when the engine is not started, which makes matching fully synchronous and deterministic.
     */
    public MatchResult handle(Command command) {
        MatchResult result = match(command);
        if (result instanceof MatchResult.Matched m) agreementSink.apply(m.agreement());
        return result;
    }

    public int waitingOffers() { return book.offerCount(); }

    public int waitingOrders() { return book.orderCount(); }

    // ------------------------------------------------------------------ internals

    private MatchResult match(Command command) {
        return switch (command) {
            case SubmitOffer c -> strategy.onOffer(c.offer(), book);
            case SubmitOrder c -> strategy.onOrder(c.order(), book);
            case CancelOffer c -> book.removeOffer(c.offerId()) ? new MatchResult.Cancelled() : new MatchResult.NotFound();
            case CancelOrder c -> book.removeOrder(c.orderId()) ? new MatchResult.Cancelled() : new MatchResult.NotFound();
        };
    }

    private void loop() {
        while (true) {
            Envelope env;
            try {
                env = inbox.take();
            } catch (InterruptedException e) {
                return;
            }
            if (env == POISON) return;
            try {
                MatchResult result = match(env.command());
                if (result instanceof MatchResult.Matched m) {
                    // Reply once the agreement is stored; the engine thread moves on without waiting.
                    agreementSink.apply(m.agreement()).whenComplete((stored, e) -> {
                        if (e == null) env.reply().complete(result);
                        else env.reply().completeExceptionally(e);
                    });
                } else {
                    env.reply().complete(result);
                }
            } catch (RuntimeException e) {
                env.reply().completeExceptionally(e);     // one bad command must not kill the engine
            }
        }
    }

    private void requireNotRunning() {
        if (thread != null) throw new IllegalStateException("Restore only before start()");
    }
}