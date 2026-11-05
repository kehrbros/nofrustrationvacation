package com.nfv.input.logic;

import java.time.Duration;

/**
 * Credit for a source as required by its terms of use, shown with its incidents.
 *
 * @param sourceName name of the source as shown to users
 * @param notice     note on the data, e.g. which events the source reports
 * @param maxAge     data older than this (last successful poll) is shown as "not current"
 */
public record Attribution(String sourceName, String notice, Duration maxAge) {
}
