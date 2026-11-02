package com.nfv.trips;

import com.nfv.trips.data.TripCoverImageRepository;
import com.nfv.trips.data.TripRepository;
import com.nfv.trips.entities.CoverImageSource;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.entities.TripCoverImage;
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

/** Title photo of a trip: stored, replaced and deleted together with the trip. */
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

    @Test
    void generatedImageIsReplacedByUpload() {
        long tripId = trip();
        coverImageRepository.save(new TripCoverImage(tripId, CoverImageSource.GENERATED, "image/png", new byte[]{1, 2, 3}, NOW));

        transactionTemplate.executeWithoutResult(_ -> coverImageRepository.findByTripId(tripId).orElseThrow()
                .replace(CoverImageSource.UPLOADED, "image/jpeg", new byte[]{4, 5}, NOW.plusMinutes(1)));

        transactionTemplate.executeWithoutResult(_ -> {
            TripCoverImage image = coverImageRepository.findByTripId(tripId).orElseThrow();
            assertThat(image.getSource()).isEqualTo(CoverImageSource.UPLOADED);
            assertThat(image.getContentType()).isEqualTo("image/jpeg");
            assertThat(image.getContent()).containsExactly(4, 5);
        });
    }

    @Test
    void imageIsDeletedWithTheTrip() {
        long tripId = trip();
        coverImageRepository.save(new TripCoverImage(tripId, CoverImageSource.UPLOADED, "image/jpeg", new byte[]{1}, NOW));

        // as a database cascade, the trips module has no delete logic yet
        jdbcTemplate.update("DELETE FROM trip WHERE id = ?", tripId);

        assertThat(coverImageRepository.findByTripId(tripId)).isEmpty();
    }

    private long trip() {
        long userId = appUserProvisioning.findOrCreate("cover-" + UUID.randomUUID(), null, null).getId();
        return tripRepository.save(new Trip(userId, "Toskana", NOW, NOW.plusDays(14), NOW)).getId();
    }
}
