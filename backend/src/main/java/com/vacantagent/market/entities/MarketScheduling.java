package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import lombok.Getter;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

@Getter
@Entity
public class MarketScheduling extends AbstractEntity {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SchedulingType type;
    /** INTERVAL is a reserved word in H2, so the column gets its own name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "scheduling_interval", nullable = false, length = 16)
    private SchedulingInterval interval;
    @Column(nullable = false)
    private LocalTime timeOfDay;
    /** Only filled for {@link SchedulingInterval#WEEKLY}, at least one day. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "market_scheduling_day", joinColumns = @JoinColumn(name = "market_scheduling_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    private Set<DayOfWeek> daysOfWeek = EnumSet.noneOf(DayOfWeek.class);
    /** Only set for {@link SchedulingInterval#MONTHLY}, 1 to 28 so it exists in every month. */
    @Nullable
    private Integer dayOfMonth;
    /** Required for {@link SchedulingType#BEFORE_REFRESH}, how long before the refresh it fires. */
    @Nullable
    private Duration leadTime;
    /** Tokens available per refresh cycle, only for {@link SchedulingType#BEFORE_REFRESH}. */
    @Nullable
    private Long tokensPerCycle;
    /** Only offers if at least this many tokens of the cycle are unused at leadTime before the refresh. Requires tokensPerCycle. */
    @Nullable
    private Long minUnusedTokens;

    protected MarketScheduling() {
    }
}