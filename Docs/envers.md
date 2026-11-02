# Historisierung mit Hibernate Envers

- **Aktivieren:** Entity mit `@org.hibernate.envers.Audited` annotieren. Jede Änderung (Insert, Update, Delete) wird dann in `<tabelle>_aud` mitgeschrieben.
- **Einzelne Felder ausnehmen:** `@NotAudited` am Feld. Beziehungen auf nicht auditierte Entities: `@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)`.
- **Revisionen:** Pro Transaktion entsteht eine Zeile in `revinfo` (`rev`, `revtstmp` in Epoch-Millis, `username` des authentifizierten Nutzers oder `NULL`), gemappt durch `com.nfv.common.data.Revision`. Den Username setzt `UsernameRevisionListener`, siehe [authentication.md](authentication.md).
- **Konfiguration:** `backend/src/main/resources/application.properties` (`hibernate.envers.audit_table_suffix=_aud`, Spaltennamen `rev` und `revtype`).
- **Schema:** Hibernate erzeugt keine Tabellen (`ddl-auto=validate`). Für jede neue `@Audited`-Entity eine Flyway-Migration mit der `_aud`-Tabelle anlegen, sonst startet die App nicht. `revinfo` legt `V1__initial_schema.sql` an.
- **Aufbau einer `_aud`-Tabelle:** alle auditierten Spalten der Basistabelle (nullable, ohne Unique/FK), dazu `rev` und `revtype` (`0` = Insert, `1` = Update, `2` = Delete); Primärschlüssel `(id, rev)`:

  ```sql
  CREATE TABLE payout_account_aud (
      id       BIGINT   NOT NULL,
      rev      BIGINT   NOT NULL REFERENCES revinfo (rev),
      revtype  TINYINT,
      -- auditierte Spalten der Basistabelle, z. B.:
      email    VARCHAR(255),
      PRIMARY KEY (id, rev)
  );
  ```

- **Historie lesen:** `AuditReaderFactory.get(entityManager)`, z. B. `getRevisions(PayoutAccount.class, id)`, `find(PayoutAccount.class, id, rev)` oder `createQuery().forRevisionsOfEntity(...)`. Innerhalb einer Transaktion aufrufen.
- **Beispiel/Test:** `backend/src/test/java/com/nfv/common/EnversAuditTest.java`.
