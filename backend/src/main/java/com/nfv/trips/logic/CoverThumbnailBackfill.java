package com.nfv.trips.logic;

import com.nfv.trips.data.TripCoverImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Computes thumbnails for title photos stored before thumbnails existed, once on startup, one photo per transaction.
 * Photos that cannot be decoded (WebP) stay without thumbnail and are tried again on the next start; cheap, since
 * decoding fails fast.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverThumbnailBackfill {
    private final TripCoverImageRepository coverImageRepository;
    private final TripLogic tripLogic;

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        backfill();
    }

    public void backfill() {
        List<Long> ids = coverImageRepository.findIdsWithoutThumbnail();
        if (ids.isEmpty()) {
            return;
        }
        int created = 0;
        for (long id : ids) {
            try {
                if (tripLogic.addMissingCoverThumbnail(id)) {
                    created++;
                }
            } catch (RuntimeException e) {
                log.warn("Thumbnail for title photo {} failed", id, e);
            }
        }
        log.info("Created {} thumbnails for {} title photos without one", created, ids.size());
    }
}
