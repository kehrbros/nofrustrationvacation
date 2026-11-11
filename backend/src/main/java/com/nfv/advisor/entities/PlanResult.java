package com.nfv.advisor.entities;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What a plan leads to, computed by the backend, see Docs/incident-plans.md#building-blocks-and-tools.
 *
 * @param deadline         latest check-in at the accommodation on the day of arrival, else the arrival of the target
 * @param reachesTarget    the planned target is reached, false with {@code NEW_TARGET}
 * @param extraNights      nights on the way
 * @param extraCost        sum of the hotels to book, in {@code costCurrency}
 * @param affectsFollowing the arrival is after the departure planned at the target, later stops move
 * @param newTargetName    {@code NEW_TARGET}: the place the target moves to
 */
@Embeddable
public record PlanResult(
        @Column(name = "plan_deadline") @Nullable OffsetDateTime deadline,
        @Column(name = "reaches_target") boolean reachesTarget,
        @Column(name = "extra_nights") int extraNights,
        @Column(name = "extra_cost", precision = 12, scale = 2) @Nullable BigDecimal extraCost,
        @Column(name = "cost_currency", length = 3) @Nullable String costCurrency,
        @Column(name = "affects_following") boolean affectsFollowing,
        @Column(name = "new_target_name") @Nullable String newTargetName) {
}
