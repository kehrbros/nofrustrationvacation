package com.vacantagent.common;

import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class EnversAuditTest {
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void auditedEntityIsHistorizedInAudTable() {
        long id = transactionTemplate.execute(status -> {
            AuditedTestEntity entity = new AuditedTestEntity("first");
            entityManager.persist(entity);
            return entity.getId();
        });
        transactionTemplate.executeWithoutResult(status ->
                entityManager.find(AuditedTestEntity.class, id).setName("second"));

        List<Number> revisions = transactionTemplate.execute(status ->
                AuditReaderFactory.get(entityManager).getRevisions(AuditedTestEntity.class, id));
        assertEquals(2, revisions.size());

        String firstName = transactionTemplate.execute(status -> AuditReaderFactory.get(entityManager)
                .find(AuditedTestEntity.class, id, revisions.getFirst()).getName());
        assertEquals("first", firstName);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audited_test_entity_aud a JOIN revinfo r ON a.rev = r.rev WHERE a.id = ?",
                Integer.class, id);
        assertEquals(2, rows);
    }
}
