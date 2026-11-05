package com.nfv.incidents.config;

import jakarta.annotation.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Rules of the assessment of consequences, see Docs/consequence-assessment.md#konfiguration.
 *
 * @param noImpactBelow  own delay below this: {@code NO_IMPACT}
 * @param maxWait        a closure needing a longer wait counts as not passable
 * @param minTolerance   the passage is widened by at least this on both sides
 * @param toleranceRatio … or by this share of the time since departure, whichever is larger
 * @param toleranceMax   largest tolerance, also widens the queries on the validity
 */
@ConfigurationProperties("nfv.incidents.assessment")
public record AssessmentProperties(@Nullable Duration noImpactBelow, @Nullable Duration maxWait,
                                   @Nullable Duration minTolerance, @Nullable Double toleranceRatio,
                                   @Nullable Duration toleranceMax) {
    public static final AssessmentProperties DEFAULTS = new AssessmentProperties(null, null, null, null, null);

    public AssessmentProperties {
        noImpactBelow = noImpactBelow == null ? Duration.ofMinutes(5) : noImpactBelow;
        maxWait = maxWait == null ? Duration.ofHours(12) : maxWait;
        minTolerance = minTolerance == null ? Duration.ofMinutes(15) : minTolerance;
        toleranceRatio = toleranceRatio == null ? 0.10 : toleranceRatio;
        toleranceMax = toleranceMax == null ? Duration.ofHours(2) : toleranceMax;
    }

    /** Tolerance of a passage {@code sinceDeparture} after departure: {@code max(min, ratio × time)}, at most max. */
    public Duration tolerance(Duration sinceDeparture) {
        long ratioMillis = Math.round(Math.max(0, sinceDeparture.toMillis()) * toleranceRatio);
        Duration tolerance = Duration.ofMillis(Math.max(minTolerance.toMillis(), ratioMillis));
        return tolerance.compareTo(toleranceMax) > 0 ? toleranceMax : tolerance;
    }
}
