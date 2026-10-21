package com.vacantagent.market.engine;

import java.util.Comparator;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * 2D index answering: "among all entries with x <= xMax and y <= yMax, which has the smallest x + y
 * (oldest first on ties) and passes a filter?"
 *
 * <p>Structure: a sparse segment tree over x. Each leaf holds every entry with that exact x, sorted by
 * (y, seq). Within one leaf x is fixed, so the entry with the smallest y also has the smallest x + y,
 * which means a leaf's first entry is its best. Each node stores two aggregates over its subtree:
 * <ul>
 *   <li>{@code minY} - if this exceeds yMax, no entry in the subtree can fit, so the subtree is skipped;</li>
 *   <li>{@code best} - the subtree's smallest (x + y, seq); a lower bound used to skip subtrees that cannot
 *       beat the best answer found so far.</li>
 * </ul>
 * Subtrees with x beyond xMax are never visited. Inserts and removals are O(log range).
 * Queries visit only subtrees that contain at least one fitting entry and could still win, and the
 * child with the better bound is searched first. With no filter this typically touches a few
 * dozen nodes even with millions of entries.
 *
 * <p>Coordinates must lie strictly between -{@link #MAX_COORD} and {@link #MAX_COORD}, so x + y never overflows.
 * Callers needing "largest" or "x >= limit" semantics negate their coordinates (see {@code OrderBook}).
 *
 * <p>Not thread-safe: owned by a single matcher thread.
 */
public final class QuadrantIndex<T> {

    public static final long MAX_COORD = 1L << 50;

    /** Handle returned by {@link #add}; pass it to {@link #remove}. */
    public static final class Entry<T> {
        private final long x;
        private final long y;
        private final long seq;
        private final T value;
        private boolean live = true;

        private Entry(long x, long y, long seq, T value) {
            this.x = x; this.y = y; this.seq = seq; this.value = value;
        }

        public T value() { return value; }
        public long x() { return x; }
        public long y() { return y; }
        public long seq() { return seq; }
    }

    private static final class Node<T> {
        Node<T> left;
        Node<T> right;
        TreeSet<Entry<T>> entries;        // leaves only
        long minY = Long.MAX_VALUE;
        Entry<T> best;                     // null when the subtree is empty
    }

    private static final Comparator<Entry<?>> LEAF_ORDER =
            Comparator.<Entry<?>>comparingLong(e -> e.y).thenComparingLong(e -> e.seq);

    private static final long LO = -MAX_COORD;
    private static final long HI = MAX_COORD;

    private Node<T> root;
    private int size;

    /** Adds an entry. {@code seq} must be unique; lower seq means older. */
    public Entry<T> add(long x, long y, long seq, T value) {
        checkCoord(x);
        checkCoord(y);
        Entry<T> e = new Entry<>(x, y, seq, value);
        root = insert(root, LO, HI, e);
        size++;
        return e;
    }

    /** Removes a previously added entry. Returns false if it was already removed. */
    public boolean remove(Entry<T> e) {
        if (!e.live) return false;
        e.live = false;
        root = delete(root, LO, HI, e);
        size--;
        return true;
    }

    /** Best entry with x <= xMax, y <= yMax and filter(value) true: smallest x + y, then smallest seq. */
    public Optional<Entry<T>> findBest(long xMax, long yMax, Predicate<? super T> filter) {
        return Optional.ofNullable(search(root, LO, HI, xMax, yMax, filter, null));
    }

    public int size() { return size; }

    public boolean isEmpty() { return size == 0; }

    // ------------------------------------------------------------------ internals

    private Node<T> insert(Node<T> n, long lo, long hi, Entry<T> e) {
        if (n == null) n = new Node<>();
        if (lo == hi) {
            if (n.entries == null) n.entries = new TreeSet<>(LEAF_ORDER);
            n.entries.add(e);
        } else {
            long mid = mid(lo, hi);
            if (e.x <= mid) n.left = insert(n.left, lo, mid, e);
            else n.right = insert(n.right, mid + 1, hi, e);
        }
        pull(n);
        return n;
    }

    private Node<T> delete(Node<T> n, long lo, long hi, Entry<T> e) {
        if (n == null) return null;
        if (lo == hi) {
            n.entries.remove(e);
        } else {
            long mid = mid(lo, hi);
            if (e.x <= mid) n.left = delete(n.left, lo, mid, e);
            else n.right = delete(n.right, mid + 1, hi, e);
        }
        pull(n);
        return n.best == null ? null : n;      // drop empty subtrees to free memory
    }

    private void pull(Node<T> n) {
        if (n.entries != null) {                // leaf
            if (n.entries.isEmpty()) { n.minY = Long.MAX_VALUE; n.best = null; }
            else { Entry<T> first = n.entries.first(); n.minY = first.y; n.best = first; }
            return;
        }
        n.minY = Math.min(minY(n.left), minY(n.right));
        n.best = better(best(n.left), best(n.right));
    }

    private Entry<T> search(Node<T> n, long lo, long hi, long xMax, long yMax,
                            Predicate<? super T> filter, Entry<T> found) {
        if (n == null || lo > xMax || n.minY > yMax) return found;
        if (found != null && compare(n.best, found) >= 0) return found;   // can't beat what we have

        if (lo == hi) {
            // Same x for all entries here: ascending y is ascending x + y.
            for (Entry<T> e : n.entries) {
                if (e.y > yMax) break;
                if (filter.test(e.value)) return better(e, found);
            }
            return found;
        }

        long mid = mid(lo, hi);
        // Search the child with the better bound first: it tightens 'found' and prunes the other.
        if (compareNullable(best(n.right), best(n.left)) < 0) {
            found = search(n.right, mid + 1, hi, xMax, yMax, filter, found);
            found = search(n.left, lo, mid, xMax, yMax, filter, found);
        } else {
            found = search(n.left, lo, mid, xMax, yMax, filter, found);
            found = search(n.right, mid + 1, hi, xMax, yMax, filter, found);
        }
        return found;
    }

    private static long mid(long lo, long hi) { return lo + ((hi - lo) >> 1); }

    private static long minY(Node<?> n) { return n == null ? Long.MAX_VALUE : n.minY; }

    private static <T> Entry<T> best(Node<T> n) { return n == null ? null : n.best; }

    private static <T> Entry<T> better(Entry<T> a, Entry<T> b) {
        if (a == null) return b;
        if (b == null) return a;
        return compare(a, b) <= 0 ? a : b;
    }

    /** Orders by x + y, then seq (older first). */
    private static int compare(Entry<?> a, Entry<?> b) {
        int c = Long.compare(a.x + a.y, b.x + b.y);
        return c != 0 ? c : Long.compare(a.seq, b.seq);
    }

    private static int compareNullable(Entry<?> a, Entry<?> b) {
        if (a == null) return b == null ? 0 : 1;
        if (b == null) return -1;
        return compare(a, b);
    }

    private static void checkCoord(long v) {
        if (v <= -MAX_COORD || v >= MAX_COORD) {
            throw new IllegalArgumentException("Coordinate out of range: " + v);
        }
    }
}