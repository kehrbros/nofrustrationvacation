package com.nfv.common;

import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
class EnversAuditTest {
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void auditedEntityIsHistorizedInAudTable() {
        long id = transactionTemplate.execute(_ -> {
            AuditedTestEntity entity = new AuditedTestEntity("first");
            entityManager.persist(entity);
            return entity.getId();
        });
        transactionTemplate.executeWithoutResult(_ ->
                entityManager.find(AuditedTestEntity.class, id).setName("second"));

        List<Number> revisions = transactionTemplate.execute(status ->
                AuditReaderFactory.get(entityManager).getRevisions(AuditedTestEntity.class, id));
        assertEquals(2, revisions.size());

        String firstName = transactionTemplate.execute(_ -> AuditReaderFactory.get(entityManager)
                .find(AuditedTestEntity.class, id, revisions.getFirst()).getName());
        assertEquals("first", firstName);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audited_test_entity_aud a JOIN revinfo r ON a.rev = r.rev WHERE a.id = ?",
                Integer.class, id);
        assertEquals(2, rows);
    }

    @Test
    void revisionContainsUsernameOfAuthenticatedUser() {
        long id = transactionTemplate.execute(_ -> {
            AuditedTestEntity entity = new AuditedTestEntity("without user");
            entityManager.persist(entity);
            return entity.getId();
        });
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", null, "ROLE_user"));
        transactionTemplate.executeWithoutResult(_ ->
                entityManager.find(AuditedTestEntity.class, id).setName("with user"));

        List<String> usernames = jdbcTemplate.queryForList(
                "SELECT r.username FROM audited_test_entity_aud a JOIN revinfo r ON a.rev = r.rev WHERE a.id = ? ORDER BY r.rev",
                String.class, id);
        assertNull(usernames.get(0));
        assertEquals("alice", usernames.get(1));
    }
}
