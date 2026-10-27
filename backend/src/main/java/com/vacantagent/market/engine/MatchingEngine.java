package com.vacantagent.market.engine;

import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.market.matching.MatchingStrategy;
import com.vacantagent.market.matching.entities.OrderBook;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;

/**
 * Solely responsible for matching: owns the {@link OrderBook} and runs the {@link MatchingStrategy} on one dedicated
 * thread. Commands are queued and processed strictly one at a time in arrival order, so the book and strategy need
 * no locking.
 *
 * <p>Everything around matching is coordinated by {@code MarketLogic}, the only caller: persisting orders and
 * offers, persisting agreements, restoring the book at startup and starting/stopping the engine. The engine does no
 * I/O. A {@code Matched} result carries an agreement that is <b>not yet persisted</b>; calling the engine directly
 * instead of through {@code MarketLogic} would lose it.
 *
 * <p>Plain Java on purpose: Spring creates it as a bean, while tests can construct it directly or call
 * {@link #handle} synchronously without a thread.
 */
public final class MatchingEngine {

    /** Commands accepted by the engine. */
    public sealed interface Command {}
    public record SubmitOffer(BookOffer offer) implements Command {}
    public record SubmitOrder(BookOrder order) implements Command {}
    public record CancelOffer(long offerId) implements Command {}
    public record CancelOrder(long orderId) implements Command {}
    /** Removes waiting entries that expired at or before {@code nowMillis}; the engine itself has no clock. */
    public record ExpireEntries(long nowMillis) implements Command {}

    private record Envelope(Command command, CompletableFuture<MatchResult> reply) {}

    private static final Envelope POISON = new Envelope(null, null);

    private final OrderBook book;
    private final MatchingStrategy strategy;
    private final BlockingQueue<Envelope> inbox;
    private volatile Thread thread;

    public MatchingEngine(OrderBook book, MatchingStrategy strategy, int inboxCapacity) {
        this.book = Objects.requireNonNull(book);
        this.strategy = Objects.requireNonNull(strategy);
        this.inbox = new ArrayBlockingQueue<>(inboxCapacity);
    }

    // ------------------------------------------------------------------ startup restore

    /** Puts a waiting offer loaded from the database into the book. Call before {@link #start}, in creation order. */
    public void restoreOffer(BookOffer offer) {
        requireNotRunning();
        book.addOffer(offer);
    }

    /**
     * Puts a waiting order loaded from the database into the book. Call before {@link #start}, in creation order.
     * Returns false and skips it if the same user already has an order waiting for the same provider and model.
     */
    public boolean restoreOrder(BookOrder order) {
        requireNotRunning();
        if (book.waitingOrderLike(order).isPresent()) return false;
        book.addOrder(order);
        return true;
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
     * Queues a command and returns its result asynchronously; the future completes on the engine thread as soon as
     * the command is processed. A {@code Matched} result's agreement is not persisted yet. Blocks the caller if the
     * inbox is full, which applies backpressure to producers during bursts.
     */
    public CompletableFuture<MatchResult> submit(Command command) {
        return submit(command, new CompletableFuture<>());
    }

    /**
     * Like {@link #submit(Command)}, with a reply the caller created. Stages attached to it before the call run on
     * the engine thread before the next command, e.g. to hand off an agreement in order.
     */
    public CompletableFuture<MatchResult> submit(Command command, CompletableFuture<MatchResult> reply) {
        Objects.requireNonNull(command);
        if (thread == null) throw new IllegalStateException("Matching engine is not running");
        try {
            inbox.put(new Envelope(command, reply));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply.completeExceptionally(e);
        }
        return reply;
    }

    /**
     * Executes one command synchronously on the calling thread. Tests may call it directly when the engine is not
     * started, which makes matching fully synchronous and deterministic.
     */
    public MatchResult handle(Command command) {
        return match(command);
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
            case ExpireEntries c -> new MatchResult.Expired(book.removeExpiredOffers(c.nowMillis()),
                    book.removeExpiredOrders(c.nowMillis()));
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
                env.reply().complete(match(env.command()));
            } catch (RuntimeException e) {
                env.reply().completeExceptionally(e);     // one bad command must not kill the engine
            }
        }
    }

    private void requireNotRunning() {
        if (thread != null) throw new IllegalStateException("Restore only before start()");
    }
}