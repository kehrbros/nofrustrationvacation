package com.nfv.network.logic;

import jakarta.annotation.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Road numbers of a source and of OpenStreetMap in one comparable form, see Docs/network.md#interfaces-networklogic.
 * New Zealand: {@code SH 1}, {@code SH1}, {@code State Highway 1}, {@code 01N}, {@code 01S} → {@code 1};
 * {@code 006} → {@code 6}; {@code SH 20A} → {@code 20A}. Elsewhere spaces are dropped ({@code A 6} → {@code A6}).
 * One rule set for all regions until a region needs its own; the region is part of the signature for that.
 */
@Component
public class RefNormalizer {
    private static final Pattern PREFIX = Pattern.compile("^(STATE\\s*HIGHWAY|SH)\\s*(?=\\d)");
    private static final Pattern NUMBER = Pattern.compile("^0*(\\d+)([A-Z]?)$");

    /** All road numbers in {@code ref} (OpenStreetMap separates several by {@code ;}), normalised, none if blank. */
    public Set<String> normalize(String region, @Nullable String ref) {
        Set<String> refs = new LinkedHashSet<>();
        if (ref == null) {
            return refs;
        }
        for (String part : ref.split("[;,]")) {
            String value = PREFIX.matcher(part.strip().toUpperCase(Locale.ROOT)).replaceFirst("")
                    .replaceAll("\\s+", "");
            if (value.isEmpty()) {
                continue;
            }
            Matcher number = NUMBER.matcher(value);
            if (number.matches()) {
                String letter = number.group(2);
                // NZTA splits SH 1 into 01N (North Island) and 01S (South Island)
                boolean island = number.group(1).equals("1") && (letter.equals("N") || letter.equals("S"));
                value = island ? "1" : number.group(1) + letter;
            }
            refs.add(value);
        }
        return refs;
    }

    /** Whether the two refs share a road number. */
    public boolean matches(String region, @Nullable String a, @Nullable String b) {
        Set<String> left = normalize(region, a);
        return normalize(region, b).stream().anyMatch(left::contains);
    }
}
