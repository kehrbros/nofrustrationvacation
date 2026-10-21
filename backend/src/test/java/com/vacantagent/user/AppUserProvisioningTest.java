package com.vacantagent.user;

import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserProvisioning;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class AppUserProvisioningTest {
    private static final int PARALLEL_REQUESTS = 16;

    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void parallelFirstLoginsCreateExactlyOneAppUser() throws Exception {
        String username = "parallel-first-login";
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS)) {
            List<Future<Long>> ids = IntStream.range(0, PARALLEL_REQUESTS)
                    .mapToObj(_ -> executor.submit(() -> {
                        start.await();
                        return appUserProvisioning.findOrCreate(username, null, "parallel@example.org").getId();
                    }))
                    .toList();
            start.countDown();

            long firstId = ids.getFirst().get();
            for (Future<Long> id : ids) {
                assertEquals(firstId, id.get());
            }
        }
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, username);
        assertEquals(1, rows);
    }

    @Test
    void existingAppUserIsReused() {
        AppUser created = appUserProvisioning.findOrCreate("existing-user", "Existing", null);
        AppUser found = appUserProvisioning.findOrCreate("existing-user", "Other name", null);
        assertEquals(created.getId(), found.getId());
        assertEquals("Existing", found.getName());
    }
}
