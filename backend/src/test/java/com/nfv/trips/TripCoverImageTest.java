package com.nfv.trips;

import com.nfv.trips.data.TripCoverImageRepository;
import com.nfv.trips.data.TripRepository;
import com.nfv.trips.entities.CoverImageSource;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.entities.TripCoverImage;
import com.nfv.trips.logic.CoverThumbnailBackfill;
import com.nfv.user.logic.AppUserProvisioning;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Title photo of a trip: stored, replaced, thumbnails added later, deleted together with the trip. */
@SpringBootTest
class TripCoverImageTest {
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);

    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private TripCoverImageRepository coverImageRepository;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CoverThumbnailBackfill coverThumbnailBackfill;

    @Test
    void generatedImageIsReplacedByUpload() {
        long tripId = trip();
        coverImageRepository.save(new TripCoverImage(tripId, CoverImageSource.GENERATED, "image/png", new byte[]{1, 2, 3}, NOW, null));

        transactionTemplate.executeWithoutResult(_ -> coverImageRepository.findByTripId(tripId).orElseThrow()
                .replace(CoverImageSource.UPLOADED, "image/jpeg", new byte[]{4, 5}, NOW.plusMinutes(1), null));

        transactionTemplate.executeWithoutResult(_ -> {
            TripCoverImage image = coverImageRepository.findByTripId(tripId).orElseThrow();
            assertThat(image.getSource()).isEqualTo(CoverImageSource.UPLOADED);
            assertThat(image.getContentType()).isEqualTo("image/jpeg");
            assertThat(image.getContent()).containsExactly(4, 5);
        });
    }

    @Test
    void backfillAddsMissingThumbnails() throws Exception {
        long tripId = trip();
        long undecodableTripId = trip();
        coverImageRepository.save(new TripCoverImage(tripId, CoverImageSource.GENERATED, "image/jpeg",
                CoverImageGenerationTest.jpeg(1536, 1024), NOW, null));
        coverImageRepository.save(new TripCoverImage(undecodableTripId, CoverImageSource.UPLOADED, "image/webp",
                new byte[]{1}, NOW, null));

        coverThumbnailBackfill.backfill();

        transactionTemplate.executeWithoutResult(_ -> {
            TripCoverImage.Thumbnail thumbnail = coverImageRepository.findByTripId(tripId).orElseThrow()
                    .thumbnail().orElseThrow();
            assertThat(thumbnail.contentType()).isEqualTo("image/jpeg");
            assertThat(coverImageRepository.findByTripId(undecodableTripId).orElseThrow().thumbnail()).isEmpty();
        });
    }

    @Test
    void imageIsDeletedWithTheTrip() {
        long tripId = trip();
        coverImageRepository.save(new TripCoverImage(tripId, CoverImageSource.UPLOADED, "image/jpeg", new byte[]{1}, NOW, null));

        // as a database cascade, the trips module has no delete logic yet
        jdbcTemplate.update("DELETE FROM trip WHERE id = ?", tripId);

        assertThat(coverImageRepository.findByTripId(tripId)).isEmpty();
    }

    private long trip() {
        long userId = appUserProvisioning.findOrCreate("cover-" + UUID.randomUUID(), null, null).getId();
        return tripRepository.save(new Trip(userId, "Toskana", NOW, NOW.plusDays(14), NOW)).getId();
    }
}
