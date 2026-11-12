# History tracking with Hibernate Envers

- **Enable:** annotate the entity with `@org.hibernate.envers.Audited`. Every change (insert, update, delete) is then recorded in `<table>_aud`.
- **Exclude individual fields:** `@NotAudited` on the field. Relationships to non-audited entities: `@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)`.
- **Revisions:** each transaction creates one row in `revinfo` (`rev`, `revtstmp` in epoch millis, `username` of the authenticated user or `NULL`), mapped by `com.nfv.common.data.Revision`. The username is set by `UsernameRevisionListener`, see [authentication.md](authentication.md).
- **Configuration:** `backend/src/main/resources/application.properties` (`hibernate.envers.audit_table_suffix=_aud`, column names `rev` and `revtype`).
- **Schema:** Hibernate does not create tables (`ddl-auto=validate`). For every new `@Audited` entity create a Flyway migration with the `_aud` table, otherwise the app does not start. `revinfo` is created by `V1__initial_schema.sql`.
- **Structure of an `_aud` table:** all audited columns of the base table (nullable, without unique/FK), plus `rev` and `revtype` (`0` = insert, `1` = update, `2` = delete); primary key `(id, rev)`:

  ```sql
  CREATE TABLE trip_aud (
      id       BIGINT   NOT NULL,
      rev      BIGINT   NOT NULL REFERENCES revinfo (rev),
      revtype  SMALLINT,
      -- audited columns of the base table, e.g.:
      name     VARCHAR(255),
      PRIMARY KEY (id, rev)
  );
  ```

- **Reading history:** `AuditReaderFactory.get(entityManager)`, e.g. `getRevisions(Trip.class, id)`, `find(Trip.class, id, rev)` or `createQuery().forRevisionsOfEntity(...)`. Call within a transaction.
- **Example/test:** `backend/src/test/java/com/nfv/common/EnversAuditTest.java`.
