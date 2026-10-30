# Database schema

The default runtime profile uses Spring Data JPA with a file-backed H2 database at `./data/relaydb` and Hibernate schema validation, preserving ledger and demo-user rows across normal application restarts. The automated unit tests do not use or modify the database. Docker Compose stores the H2 file in a named volume.

- `accounts`: VPA primary key, holder name, decimal balance, optimistic-lock version.
- `transactions`: generated ID, unique packet hash, sender and receiver VPAs, amount, signed/settled timestamps, bridge ID, hop count, and `SETTLED`/`REJECTED` status.
- `demo_users`: generated ID, unique email/phone contact, display name. Demo OTP challenges remain in process memory and are not persisted.

Accounts are seeded at startup only when the account table is empty. Flyway runs versioned SQL migrations, and Hibernate validates the migrated schema at startup. The JVM-local idempotency cache and OTP challenges do not become distributed or durable when the database is persistent. The browser queue is stored separately in IndexedDB with a localStorage fallback; queue entries are not ledger transactions until submitted and processed by the server.
