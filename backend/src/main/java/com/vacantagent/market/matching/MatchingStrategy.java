package com.vacantagent.market.matching;

import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import com.vacantagent.market.matching.entities.OrderBook;

/**
 * Decides what happens to an incoming offer or order: pair it, let it wait, or reject it.
 * Implementations are called only from the matching thread and may modify the book.
 */
public interface MatchingStrategy {

    MatchResult onOrder(BookOrder order, OrderBook book);

    MatchResult onOffer(BookOffer offer, OrderBook book);
}