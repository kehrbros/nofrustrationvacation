package com.vacantagent.market.matching.index;

import com.vacantagent.market.engine.QuadrantIndex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuadrantIndexTest {

    private long seq;

    @Test
    void findsSmallestTotalWithinBothLimits() {
        QuadrantIndex<String> index = new QuadrantIndex<>();
        add(index, 10, 12, "a");   // total 22
        add(index, 6, 14, "b");    // total 20, but y = 14
        add(index, 9, 13, "c");    // total 22

        assertThat(best(index, 11, 13)).contains("a");   // b fails on y, a older than c
        assertThat(best(index, 10, 15)).contains("b");
        assertThat(best(index, 5, 20)).isEmpty();
    }

    @Test
    void equalTotalsGoToTheOldest() {
        QuadrantIndex<String> index = new QuadrantIndex<>();
        add(index, 9, 13, "first");
        add(index, 10, 12, "second");
        add(index, 11, 11, "third");

        assertThat(best(index, 20, 20)).contains("first");
    }

    @Test
    void limitsAreInclusive() {
        QuadrantIndex<String> index = new QuadrantIndex<>();
        add(index, 10, 12, "a");

        assertThat(best(index, 10, 12)).contains("a");
        assertThat(best(index, 9, 12)).isEmpty();
        assertThat(best(index, 10, 11)).isEmpty();
    }

    @Test
    void removedEntriesAreNotReturned() {
        QuadrantIndex<String> index = new QuadrantIndex<>();
        QuadrantIndex.Entry<String> a = add(index, 1, 1, "a");
        add(index, 2, 2, "b");

        assertThat(index.remove(a)).isTrue();
        assertThat(index.remove(a)).isFalse();
        assertThat(best(index, 10, 10)).contains("b");
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void filterSkipsToTheNextBest() {
        QuadrantIndex<String> index = new QuadrantIndex<>();
        add(index, 1, 1, "blocked");
        add(index, 1, 2, "same-x");
        add(index, 2, 2, "other-x");

        assertThat(index.findBest(10, 10, v -> !v.equals("blocked")).map(QuadrantIndex.Entry::value))
                .contains("same-x");
    }

    @Test
    void negativeCoordinatesWork() {
        QuadrantIndex<String> index = new QuadrantIndex<>();
        add(index, -20, -15, "high");   // what OrderBook stores for maxA=20, maxB=15
        add(index, -10, -30, "higher");

        assertThat(best(index, -10, -15)).contains("higher");   // -40 < -35
    }

    @Test
    void rejectsCoordinatesOutOfRange() {
        QuadrantIndex<String> index = new QuadrantIndex<>();
        assertThatThrownBy(() -> index.add(QuadrantIndex.MAX_COORD, 0, 0, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Random inserts, removals and filtered queries, each checked against a plain linear scan. */
    @Test
    void agreesWithBruteForceOnRandomOperations() {
        Random rnd = new Random(42);
        QuadrantIndex<Long> index = new QuadrantIndex<>();
        List<QuadrantIndex.Entry<Long>> live = new ArrayList<>();

        for (int op = 0; op < 50_000; op++) {
            int kind = rnd.nextInt(10);
            if (kind < 5 || live.isEmpty()) {
                long s = seq++;
                live.add(index.add(rnd.nextInt(200), rnd.nextInt(200), s, s));
            } else if (kind < 7) {
                index.remove(live.remove(rnd.nextInt(live.size())));
            } else {
                long xMax = rnd.nextInt(220);
                long yMax = rnd.nextInt(220);
                long mod = 1 + rnd.nextInt(4);
                Predicate<Long> filter = v -> v % mod != 0 || mod == 1;

                Optional<Long> expected = live.stream()
                        .filter(e -> e.x() <= xMax && e.y() <= yMax && filter.test(e.value()))
                        .min(Comparator.<QuadrantIndex.Entry<Long>>comparingLong(e -> e.x() + e.y())
                                .thenComparingLong(QuadrantIndex.Entry::seq))
                        .map(QuadrantIndex.Entry::value);

                assertThat(index.findBest(xMax, yMax, filter).map(QuadrantIndex.Entry::value))
                        .as("op %d, xMax=%d yMax=%d", op, xMax, yMax)
                        .isEqualTo(expected);
            }
        }
        assertThat(index.size()).isEqualTo(live.size());
    }

    private QuadrantIndex.Entry<String> add(QuadrantIndex<String> index, long x, long y, String v) {
        return index.add(x, y, seq++, v);
    }

    private static Optional<String> best(QuadrantIndex<String> index, long xMax, long yMax) {
        return index.findBest(xMax, yMax, v -> true).map(QuadrantIndex.Entry::value);
    }
}
