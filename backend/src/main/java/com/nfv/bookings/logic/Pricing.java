package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Our price for an offer and the split of guests on rooms. */
final class Pricing {
    private static final BigDecimal HUNDRED = new BigDecimal(100);

    private Pricing() {
    }

    /**
     * Net rate plus fee, at most the suggested selling price (LiteAPI expects it not to be exceeded), never below net.
     */
    static BigDecimal price(BigDecimal net, @Nullable BigDecimal suggestedSellingPrice, BigDecimal feePercent) {
        BigDecimal price = net.add(net.multiply(feePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP);
        if (suggestedSellingPrice != null && price.compareTo(suggestedSellingPrice) > 0
                && suggestedSellingPrice.compareTo(net) >= 0) {
            price = suggestedSellingPrice.setScale(2, RoundingMode.HALF_UP);
        }
        return price;
    }

    /** Guests spread evenly, the first rooms get one more: 5 guests in 2 rooms → 3 + 2. */
    static List<Integer> adultsPerRoom(int guests, int rooms) {
        List<Integer> split = new ArrayList<>();
        for (int room = 0; room < rooms; room++) {
            split.add(guests / rooms + (room < guests % rooms ? 1 : 0));
        }
        return split;
    }

    /** Great-circle distance in meters. */
    static int distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return (int) Math.round(6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }
}
