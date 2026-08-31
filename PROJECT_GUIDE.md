# Project Guide and Home Transfer Instructions

This guide explains the Wallet Ledger project's build, generated files, runtime behavior, production-hardening additions, validation strategy, and safe transfer through Git. Read [`README.md`](README.md) first for the public API and design trade-offs; use this document as a file-by-file implementation map.

## 1. What Git transfers

The repository contains everything required to rebuild the service:

- Java 17 source code
- Maven build and pinned external dependency configuration
- additive Flyway migrations
- runtime and test configuration
- integration, API, rollback, PostgreSQL concurrency, and populated-migration tests
- Dockerfile and hardened Compose configuration
- GitHub Actions workflow
- README and this guide

These local/generated items are intentionally excluded:

| Item | Reason |
|---|---|
| `target/` | Maven output; regenerate with `mvn clean verify` |
| `*.pdf` | Keeps the confidential assignment document out of Git |
| `.env` and `.env.*` | May contain credentials; only `.env.example` is allowed |
| `.idea/`, `.vscode/`, IDE files | Machine-specific settings |
| logs and local database files | Runtime/test artifacts |

Rules are in [`.gitignore`](.gitignore). The assignment PDF remains local and is not part of commits or Docker build context.

## 2. Maven configuration (`pom.xml`)

### 2.1 Identity and output

```xml
<groupId>io.github.lalathomas</groupId>
<artifactId>wallet-ledger-service</artifactId>
<version>0.0.1-SNAPSHOT</version>
```

The executable artifact is:

```text
target/wallet-ledger-service.jar
```

`SNAPSHOT` indicates a development build, not a published immutable release.

### 2.2 Spring Boot parent and Java

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.15</version>
</parent>

<java.version>17</java.version>
```

The Boot parent manages compatible Spring, Hibernate, Jackson, Micrometer, database-driver, JUnit, and Testcontainers versions. Java 17 is the compiler target and minimum supported runtime.

### 2.3 Runtime dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | Spring MVC, JSON, embedded server, servlet filters, HTTP errors |
| `spring-boot-starter-validation` | Jakarta request constraints and `@Valid` |
| `spring-boot-starter-data-jpa` | JPA entities, Hibernate, transactions, repositories, row locks |
| `spring-boot-starter-actuator` | Health, liveness, readiness, info, and metrics endpoints |
| `micrometer-registry-prometheus` | Prometheus scrape registry/endpoint |
| `springdoc-openapi-starter-webmvc-ui` | OpenAPI JSON and Swagger UI |
| `flyway-core` | Versioned startup migrations |
| `flyway-database-postgresql` | PostgreSQL-specific Flyway integration |
| `postgresql` | Runtime JDBC driver |

Springdoc is explicitly pinned through:

```xml
<springdoc-openapi.version>2.8.17</springdoc-openapi.version>
```

The [official Springdoc v2 compatibility matrix](https://springdoc.org/v2/index.html#_what_is_the_compatibility_matrix_of_springdoc_openapi_with_spring_boot) maps Spring Boot 3.5.x to Springdoc 2.8.x. Version 2.8.17 was the newest published artifact in that compatible line when selected. Open ranges and `latest` are avoided for reproducible builds.

Actuator and Micrometer versions are managed by the Spring Boot parent and are not independently overridden.

### 2.4 Test dependencies

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-test` | JUnit 5, AssertJ, Mockito, Spring integration tests, MockMvc |
| `h2` | Portable in-memory integration database in PostgreSQL mode |
| `spring-boot-testcontainers` | `@ServiceConnection` integration |
| `testcontainers-junit-jupiter` | JUnit container lifecycle |
| `testcontainers-postgresql` | Real production-dialect database tests |

They use `test` scope and are not packaged into the production JAR.

### 2.5 Build plugins

- **Spring Boot Maven plugin:** repackages the JAR for `java -jar` execution.
- **Maven Enforcer 3.5.0:** requires Java 17+ and Maven 3.9+ before compilation.

Common commands:

```powershell
mvn compile
mvn test
mvn clean verify
mvn -Dtest=WalletServiceIntegrationTest test
mvn -Dtest=PostgresWalletConcurrencyIntegrationTest test
mvn -Dtest=PostgresV1ToV2MigrationIntegrationTest test
mvn spring-boot:run
```

On this Windows machine, set Java explicitly if Maven inherits Java 8:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -version
```

## 3. Root files

| File | Purpose |
|---|---|
| `README.md` | API, behavior, concurrency, observability, testing, and limitations |
| `PROJECT_GUIDE.md` | Build/file walkthrough and Git transfer instructions |
| `pom.xml` | Maven identity, dependencies, Java rules, and plugins |
| `Dockerfile` | Multi-stage Maven build and non-root Java 17 runtime |
| `compose.yaml` | PostgreSQL plus read-only/non-root application service |
| `.env.example` | Supported datasource/pool variables without secrets |
| `.gitignore` | Excludes generated, confidential, secret, and IDE files |
| `.dockerignore` | Keeps private/unneeded files outside Docker build context |
| `.gitattributes` | Normalizes line endings across Windows/Linux |
| `.github/workflows/ci.yml` | Build, PostgreSQL-test enforcement, Compose/image/readiness checks |

The Compose application uses `init: true`, a read-only root filesystem, `/tmp` as tmpfs, `no-new-privileges`, and the non-root image user. PostgreSQL must pass `pg_isready` before the application starts.

## 4. Production Java source map

Source root:

```text
src/main/java/io/github/lalathomas/walletledger/
```

### 4.1 Entry point

| File | Purpose |
|---|---|
| `WalletLedgerApplication.java` | Starts Spring Boot and component scanning |

### 4.2 Shared API/operations (`common/api`)

| File | Purpose |
|---|---|
| `ApiErrorResponse.java` | Stable structured error body, including correlation ID |
| `CorrelationIdFilter.java` | Validates/generates `X-Correlation-ID`, sets MDC/header, clears MDC |
| `GlobalExceptionHandler.java` | Maps validation, domain, lock, database, and unexpected failures |
| `OpenApiConfiguration.java` | API metadata and global correlation request/response documentation |

Correlation IDs are bounded to 100 characters and a safe character set. They are for tracing only, never authorization, and are not metric labels.

### 4.3 HTTP layer (`wallet/api`)

| File | Purpose |
|---|---|
| `WalletController.java` | Create, credit, debit, refund, balance, reconciliation, history |
| `CreateWalletRequest.java` | Wallet-create input validation |
| `MoneyMovementRequest.java` | Credit/debit amount/reason/reference validation |
| `RefundRequest.java` | Refund reason/reference validation; intentionally no amount |
| `WalletResponse.java` | Wallet-create response |
| `BalanceResponse.java` | Current balance response |
| `MoneyMovementResponse.java` | Mutation result, replay state, nullable debit-refund link |
| `ReconciliationResponse.java` | Stored/calculated balances, consistency, count, timestamp |
| `TransactionResponse.java` | Public immutable ledger item with nullable refund link |
| `TransactionHistoryResponse.java` | History and pagination metadata |

JPA entities never cross the HTTP boundary. This allows the database mapping to evolve without accidentally exposing lazy proxies or internal identifiers.

### 4.4 Application layer (`wallet/application`)

| File | Purpose |
|---|---|
| `WalletService.java` | Transactions, lock ordering, idempotency, refunds, reconciliation |
| `MoneyMovementCommand.java` | Internal credit/debit command |
| `RefundCommand.java` | Internal refund command without amount |
| `MoneyMovementResult.java` | Successful/replayed mutation result |
| `ReconciliationResult.java` | Exact reconciliation result using `BigInteger` |
| `WalletSnapshot.java` | Current wallet state projection |
| `LedgerEntryView.java` | Internal history projection |
| `TransactionHistory.java` | Internal paginated result |
| `WalletException.java` | Safe domain/application exception factories |
| `WalletErrorCode.java` | Stable machine-readable business codes |

`WalletService` is the primary file to understand for an interview. Its ordering choices are financial correctness rules, not incidental implementation detail.

### 4.5 Domain layer (`wallet/domain`)

| File | Purpose |
|---|---|
| `Wallet.java` | Wallet entity, positive amounts, non-negative balance, exact arithmetic |
| `LedgerEntry.java` | Immutable ledger row, idempotency fingerprint, optional debit link |
| `TransactionType.java` | Only `CREDIT` and `DEBIT` |

A refund remains a linked `CREDIT`; there is no `REFUND` enum. That keeps the signed-ledger equation simple and preserves the V1 transaction-type invariant.

### 4.6 Persistence layer (`wallet/persistence`)

| File | Purpose |
|---|---|
| `WalletRepository.java` | Wallet creation/read/lock plus native reconciliation statement |
| `LedgerEntryRepository.java` | Insert, key lookup, wallet-scoped target/refund lookup, history |
| `WalletReconciliationProjection.java` | Numeric aggregate projection without dialect-sensitive UUID mapping |

Repository interfaces deliberately expose no ledger update/delete operation.

## 5. Configuration and migrations

### 5.1 `application.yml`

The runtime configuration includes:

- PostgreSQL URL/credential environment overrides
- Hikari pool limits and connection timeout
- PostgreSQL five-second `lock_timeout`
- strict Jackson request contracts: decimals are not truncated and unknown JSON properties are rejected
- Flyway startup migration
- Hibernate `ddl-auto: validate`
- Open Session in View disabled
- UTC JDBC timestamps
- graceful shutdown
- correlation-aware console logging
- only `health`, `info`, and `prometheus` Actuator exposure
- liveness plus datasource-backed readiness probes, with no production health components/details
- explicit read-only Prometheus endpoint access
- OpenAPI 3.1 JSON and Swagger paths

### 5.2 V1 migration

`V1__create_wallet_ledger.sql` creates wallets, ledger rows, player/idempotency uniqueness, foreign keys, amount/balance/type/audit checks, and the history index.

V1 is published migration history. Never edit it after deployment because Flyway records its checksum.

### 5.3 Additive V2 migration

`V2__add_ledger_refunds.sql` adds:

```text
ledger_entries.refunded_debit_id UUID NULL
```

and these defenses:

- self-foreign key to `ledger_entries(id)` with `ON DELETE RESTRICT`;
- unique `refunded_debit_id`, allowing many null ordinary rows but one linked refund per debit;
- check requiring linked rows to be `CREDIT`.

The service must still verify same-wallet ownership and `DEBIT` type. Portable SQL row constraints cannot inspect arbitrary properties of the referenced row.

### 5.4 Deployment sequencing and migration risk

An additive nullable column provides schema coexistence; it does **not** make refund data semantically safe for pre-refund binaries. Use this rollout order:

1. Keep the refund route unavailable at the gateway/deployment layer. There is no built-in application feature flag.
2. Rehearse V2 on a production-size database copy with representative write traffic. Choose a measured maintenance-window or staged-migration plan.
3. Apply and verify V2, then deploy refund-capable instances while refund traffic remains disabled.
4. Drain every pre-refund instance from **all** wallet traffic and verify that none remain. Sending only the refund route to new instances is insufficient.
5. Enable refund traffic only after the drain gate has passed.

The drain is mandatory because an old entity does not map `refunded_debit_id`; old idempotency logic can mistake a linked refund `CREDIT` for an ordinary credit. Schema coexistence before refund rows are written is supported. Mixed semantic processing after refunds begin is not.

On a populated PostgreSQL ledger, the V2 unique constraint builds an index and the foreign-key/check constraints validate existing rows. These operations scan data, acquire locks, and can block writers. The configured five-second `lock_timeout` bounds lock acquisition waiting only; after a lock is acquired, index creation or validation can run longer. A small Testcontainers migration proves compatibility and constraint behavior, not online safety. If production measurements require concurrent-index or `NOT VALID`/`VALIDATE CONSTRAINT` staging, preserve any already-published V2 checksum and introduce that strategy through a later migration and deployment runbook.

## 6. End-to-end behavior

### 6.1 Credit/debit flow

```text
HTTP request
  -> DTO validation
  -> WalletService @Transactional(READ_COMMITTED)
  -> validate/normalize command
  -> SELECT wallet FOR UPDATE
  -> wallet-scoped idempotency lookup
  -> exact replay or conflict
  -> funds/overflow validation
  -> wallet dirty-check mutation
  -> immutable ledger insert
  -> commit both or roll back both
  -> response DTO
```

The idempotency lookup occurs after the wallet lock. A duplicate waiting behind the first request therefore sees its committed ledger row and replays it.

### 6.2 Refund flow

```text
HTTP refund request (reason/reference only)
  -> validate player + debit ID + payload + key
  -> SELECT wallet FOR UPDATE
  -> target-aware key lookup
  -> wallet-scoped debit lookup
  -> require DEBIT
  -> existing linked-refund lookup
  -> overflow-safe credit of original amount
  -> linked CREDIT insert
  -> atomic commit or rollback
```

Policies:

- exact same key/target/payload replays;
- same key with another target/payload conflicts;
- another key after refund returns `DEBIT_ALREADY_REFUNDED`;
- wrong-wallet target is indistinguishable from missing target;
- refund amount is never accepted from the caller.

### 6.3 Reconciliation flow

One native SQL statement joins the wallet to its ledger and returns:

```text
stored wallet balance
SUM(CREDIT) - SUM(DEBIT) as NUMERIC(38,0)
transaction count
```

The one-statement snapshot avoids reading wallet and ledger at different committed points. Java converts the exact aggregate to `BigInteger`. A mismatch is reported and never repaired automatically.

### 6.4 Correlation/error flow

```text
request
  -> CorrelationIdFilter validates or generates ID
  -> response header + request attribute + MDC
  -> controller/service/handler
  -> error body reads MDC correlation ID when needed
  -> finally removes MDC from pooled servlet thread
```

## 7. Automated tests

There are 30 methods across five classes.

### `WalletServiceIntegrationTest` (16)

Uses real Spring transactions, JPA, Flyway V1+V2, and H2. It covers baseline behavior, full refund semantics, overflow rollback, same/distinct-key races, and exact reconciliation including turnover greater than `BIGINT` and JDBC-tampered mismatch state.

### `WalletApiIntegrationTest` (8)

Uses MockMvc with real service/persistence. It verifies the public API, strict unknown-field rejection without mutation, structured domain and framework errors, correlation headers/body, explicit create/credit/debit success contracts, complete refund OpenAPI responses and nullable schemas, Swagger UI, Prometheus, liveness, datasource-backed readiness membership, and non-exposure of sensitive Actuator endpoints.

### `WalletTransactionRollbackIntegrationTest` (2)

Replaces only the ledger repository to force failure after wallet mutation. Both ordinary credit and refund tests prove transaction rollback restores the wallet and does not create a partial ledger/idempotency/refund row.

### `PostgresWalletConcurrencyIntegrationTest` (3)

Uses `postgres:17.6-alpine` through Testcontainers and verifies:

- row-lock no-overdraft plus duplicate serialization;
- same-key and distinct-key refund races;
- V2 unique refund linkage on the production dialect.

### `PostgresV1ToV2MigrationIntegrationTest` (1)

Uses a separate fresh `postgres:17.6-alpine` container without Spring auto-migration. It targets Flyway V1, inserts consistent legacy wallet/credit/debit data, migrates to V2, proves data preservation, accepts one valid linked refund, and verifies PostgreSQL unique, foreign-key, and check violations. It proves upgrade compatibility, not online migration duration or lock impact.

Both PostgreSQL classes are skipped locally when Docker is unavailable. CI parses each Surefire report, requires at least three concurrency methods and one migration method, and fails on any skip, failure, or error.

### Test configuration

`src/test/resources/application-test.yml` uses one in-memory H2 database in PostgreSQL mode, a 20-connection pool, H2 lock timeout, Flyway, and Hibernate schema validation. H2 is portable coverage, not a substitute for PostgreSQL locking tests.

## 8. CI and container validation

`.github/workflows/ci.yml` performs:

1. checkout and Java 17 setup;
2. `mvn clean verify`;
3. Surefire report checks requiring all three PostgreSQL concurrency methods and the populated V1-to-V2 migration method to run with zero skips/failures/errors;
4. `docker compose config --quiet`;
5. Compose application image build;
6. PostgreSQL/application startup;
7. polling `/actuator/health/readiness` until ready;
8. unconditional `docker compose down --volumes --remove-orphans`.

Local commands matching those checks:

```powershell
mvn clean verify
docker compose config --quiet
docker compose build app
docker compose up -d --no-build
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
docker compose down -v --remove-orphans
```

Do not start a second Compose stack if ports 5432 or 8080 are already in use.

## 9. Git branches and home transfer

Remote:

```text
https://github.com/lalathomas/java.git
```

The previously published baseline is:

```text
wallet-ledger-takehome
```

The production-hardening work is being developed separately on:

```text
production-hardening
```

Creating a local branch does **not** transfer it to GitHub. This hardening branch must be reviewed, committed, and pushed before it is available at home. No commit or push should be performed automatically unless explicitly requested.

### 9.1 Review before committing

```powershell
git status --short --branch
git diff --check
git diff --stat
git diff
mvn clean verify
docker compose config --quiet
```

With Docker running, also run the PostgreSQL class and image/readiness checks.

### 9.2 Commit and publish when ready

Stage reviewed files specifically rather than `git add .`:

```powershell
git add pom.xml README.md PROJECT_GUIDE.md
git add src .github compose.yaml
git status --short
git commit -m "Harden wallet refunds and operations"
git push -u origin production-hardening
```

Before running those commands, inspect `git status` to ensure no PDF, secret, local environment file, or unrelated work is staged.

### 9.3 Clone the hardening branch at home after it is pushed

```powershell
git clone --branch production-hardening --single-branch https://github.com/lalathomas/java.git
Set-Location java
```

If the private repository requests authentication, use Git Credential Manager, SSH, or a personal access token through the credential prompt. Never place a token in a command, source file, or remote URL.

### 9.4 Verify and run at home

```powershell
java -version
mvn -version
mvn clean verify
docker compose up --build
```

Then open:

```text
API:         http://localhost:8080/api/v1/wallets
OpenAPI:     http://localhost:8080/v3/api-docs
Swagger UI:  http://localhost:8080/swagger-ui.html
Health:      http://localhost:8080/actuator/health
Prometheus:  http://localhost:8080/actuator/prometheus
```

### 9.5 Continue work safely on either computer

```powershell
git switch production-hardening
git pull --ff-only
```

After a reviewed change:

```powershell
git status
git add path/to/changed-file
git commit -m "Describe the change"
git push
```

## 10. Recommended reading order

To understand and explain the implementation rather than only run it:

1. `README.md`
2. `PROJECT_GUIDE.md`
3. `pom.xml`
4. `V1__create_wallet_ledger.sql`
5. `V2__add_ledger_refunds.sql`
6. `Wallet.java`
7. `LedgerEntry.java`
8. `WalletRepository.java`
9. `LedgerEntryRepository.java`
10. `WalletService.java`
11. `WalletController.java`
12. `CorrelationIdFilter.java`
13. `GlobalExceptionHandler.java`
14. `WalletServiceIntegrationTest.java`
15. `WalletTransactionRollbackIntegrationTest.java`
16. `PostgresWalletConcurrencyIntegrationTest.java`
17. `PostgresV1ToV2MigrationIntegrationTest.java`
18. `.github/workflows/ci.yml`

After this sequence, you should be able to explain:

- why the wallet lock is acquired before idempotency/refund queries;
- why a refund is a linked credit instead of a new transaction type;
- why the refund amount is server-derived;
- how V2 and application checks jointly prevent double refund;
- why pre-refund nodes must be drained before refund traffic and why additive V2 still requires production-size migration rehearsal;
- why reconciliation uses one SQL snapshot and `NUMERIC`/`BigInteger`;
- how rollback tests prove there is no partial balance mutation;
- why correlation IDs are bounded, echoed, and removed from MDC;
- which Actuator endpoints are safe to expose and which remain hidden;
- why real authentication was deferred instead of invented.
