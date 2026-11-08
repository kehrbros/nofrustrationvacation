package com.nfv.network.logic;

import com.nfv.network.config.NetworkProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Configured regions of the road network, see Docs/network.md#schnittstellen. */
@Service
@RequiredArgsConstructor
public class Regions {
    private final NetworkProperties properties;

    /** Whether the point lies within the bounds of a region, regardless of whether its graph is loaded. */
    public boolean covers(double latitude, double longitude) {
        return properties.regions().values().stream().anyMatch(region -> region.bounds().contains(latitude, longitude));
    }
}
