package com.nfv.network.logic;

import com.nfv.network.config.NetworkProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Loads or imports every region in the background after the start, routing answers 503 until it is ready. */
@Slf4j
@Component
@RequiredArgsConstructor
class NetworkStartup {
    private final NetworkProperties properties;
    private final RegionImport regionImport;

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!properties.importOnStart()) {
            return;
        }
        properties.regions().keySet().forEach(region -> Thread.ofVirtual().name("network-" + region).start(() -> {
            try {
                regionImport.loadOrImport(region);
            } catch (RuntimeException e) {
                log.error("Region {}: start failed", region, e);
            }
        }));
    }
}