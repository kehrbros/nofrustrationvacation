package com.vacantagent.network.logic;

import com.vacantagent.network.config.NetworkProperties;

import java.nio.file.Path;

/** Where the OSM extract of a region comes from, see Docs/network.md. */
public interface ExtractSource {
    Path fetch(String region, NetworkProperties.Region config);
}