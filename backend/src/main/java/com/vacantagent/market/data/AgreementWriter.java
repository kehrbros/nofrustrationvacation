package com.vacantagent.market.data;

import com.vacantagent.market.entities.Agreement;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Persists agreements in batches on its own thread, so the matching thread never waits on the database.
 * {@link #enqueue} is the engine's agreement sink; {@link #stop} flushes everything still queued.
 */
@Slf4j
public final class AgreementWriter {

    private static final int MAX_ATTEMPTS = 3;

    private record Pending(Agreement agreement, CompletableFuture<Agreement> stored) {}

    private final AgreementRepository repository;
    private final int batchSize;
    private final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private Thread thread;

    public AgreementWriter(AgreementRepository repository, int batchSize) {
        this.repository = repository;
        this.batchSize = batchSize;
    }

    /**
     * Non-blocking; safe to call from the matching thread. The future completes on the writer thread once
     * the agreement is stored, or fails if it could not be stored.
     */
    public CompletableFuture<Agreement> enqueue(Agreement agreement) {
        CompletableFuture<Agreement> stored = new CompletableFuture<>();
        queue.add(new Pending(agreement, stored));
        return stored;
    }

    public synchronized void start() {
        if (thread != null) return;
        running = true;
        thread = Thread.ofPlatform().name("agreement-writer").start(this::loop);
    }

    /** Stop after the matching engine, so the last agreements are flushed. */
    public synchronized void stop() throws InterruptedException {
        if (thread == null) return;
        running = false;
        thread.join();
        thread = null;
    }

    private void loop() {
        List<Pending> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                Pending first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                write(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                batch.forEach(p -> p.stored().completeExceptionally(e));
                return;
            } finally {
                batch.clear();
            }
        }
    }

    private void write(List<Pending> batch) throws InterruptedException {
        List<Agreement> agreements = batch.stream().map(Pending::agreement).toList();
        for (int attempt = 1; ; attempt++) {
            try {
                repository.saveAll(agreements);      // one transaction, so Envers writes one revision per batch
                batch.forEach(p -> p.stored().complete(p.agreement()));
                return;
            } catch (RuntimeException e) {
                if (attempt == MAX_ATTEMPTS) {
                    // Agreements are already final in memory. Log every one so they can be replayed.
                    log.error("Failed to persist {} agreements after {} attempts", batch.size(), attempt, e);
                    agreements.forEach(a -> log.error("Unpersisted agreement: order {} offer {}",
                            a.getConsumerOrder().getId(), a.getProviderOffer().getId()));
                    batch.forEach(p -> p.stored().completeExceptionally(e));
                    return;
                }
                log.warn("Persisting {} agreements failed (attempt {}), retrying", batch.size(), attempt, e);
                Thread.sleep(200L * attempt);
            }
        }
    }
}