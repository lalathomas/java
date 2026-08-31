# Wallet Ledger Service

A production-minded Java 17 / Spring Boot backend for player wallets and an immutable money ledger. It supports exact integer credits and debits, idempotent full refunds of prior debits, current-balance reads, deterministic history, and diagnostic balance reconciliation. The design prioritizes the failure modes that matter for money movement: duplicate delivery, concurrent requests, overdraft, arithmetic overflow, partial persistence, double refund, and snapshot/ledger divergence.

This repository contains production-hardening foundations, not a claim that the service can be exposed publicly without deployment-specific controls. Authentication and authorization are intentionally deferred until a caller model and identity provider are specified; inventing a fake security scheme would be less safe than documenting the boundary clearly.

## Contents

- [Implemented capabilities](#implemented-capabilities)
- [Technology](#technology)
- [How to run](#how-to-run)
- [API](#api)
- [Refund contract](#refund-contract)
- [Balance reconciliation](#balance-reconciliation)
- [Observability and API documentation](#observability-and-api-documentation)
- [Design decisions](#design-decisions)
- [Concurrency and idempotency](#concurrency-and-idempotency)
- [Testing approach](#testing-approach)
- [Project structure](#project-structure)
- [Production boundary and limitations](#production-boundary-and-limitations)
- [Possible next steps](#possible-next-steps)
- [AI tooling note](#ai-tooling-note)

## Implemented capabilities

### Money and ledger behavior

- Explicit wallet creation for a UUID player ID
- Exact positive integer credits and debits using Java `long` / SQL `BIGINT`
- No-overdraft debit validation while holding a database row lock
- `Math.addExact` overflow protection for normal credits and refunds
- Current-balance lookup
- Immutable, paginated, newest-first ledger history
- Required reason and external reference on every successful balance change
- Atomic wallet snapshot update and ledger insert in one database transaction

### Duplicate and concurrency safety

- Required, wallet-scoped idempotency keys for credit, debit, and refund operations
- Exact replay of a previously successful request
- `409 IDEMPOTENCY_CONFLICT` when a key is reused for different request data
- Pessimistic wallet-row locking before idempotency or refund checks
- Serialized mutations for one wallet while different wallets remain independent
- Database uniqueness as a final defense for idempotency and one-refund rules
- Retryable `503 WALLET_BUSY` response when the configured lock timeout is reached

### Additional production-hardening features

- Full refund of a prior `DEBIT`, represented as one linked `CREDIT`
- Refund amount derived from the original debit; clients cannot alter it
- Exactly one refund per debit, including under concurrent requests
- Diagnostic reconciliation of `wallets.balance` against `SUM(CREDIT) - SUM(DEBIT)`
- Exact `NUMERIC(38,0)` aggregation so large lifetime turnover does not overflow `BIGINT`
- Correlation IDs in response headers, MDC logs, and structured error bodies
- Spring Boot Actuator health/liveness/readiness/info endpoints
- Prometheus-format JVM, process, HTTP, datasource, and application-framework metrics
- OpenAPI JSON and Swagger UI documentation
- Additive Flyway V2 migration; the published V1 migration remains unchanged
- Hardened non-root/read-only Compose application container configuration
- CI checks for Maven verification, PostgreSQL tests, Compose syntax, image build, and container readiness

## Technology

| Component | Choice |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.5.15 |
| API | Spring MVC + Jakarta Bean Validation |
| Persistence | Spring Data JPA / Hibernate |
| Production database | PostgreSQL 17.6 in Compose |
| Schema management | Flyway V1 + additive V2 |
| Portable tests | H2 2.x in PostgreSQL compatibility mode |
| Production-dialect tests | Testcontainers + PostgreSQL |
| Operations | Spring Boot Actuator + Micrometer Prometheus registry |
| API documentation | Springdoc OpenAPI `2.8.17` |
| Build | Maven 3.9+ |

Springdoc is pinned instead of using an open version range. Its official compatibility matrix maps Spring Boot 3.5.x to Springdoc 2.8.x; `2.8.17` is the newest published 2.8.x artifact at implementation time. Spring Boot manages the Actuator, Micrometer, database, and test dependency versions.

The Maven Enforcer plugin stops the build with a clear message if Maven runs under Java older than 17.

## How to run

### Prerequisites

Choose one setup:

1. **Docker:** Docker Desktop or another Docker Engine with Docker Compose.
2. **Local:** JDK 17+, Maven 3.9+, and PostgreSQL.

Verify the toolchain:

```powershell
java -version
mvn -version
```

Both commands must report Java 17 or newer. On Windows, if `java -version` is correct but Maven reports Java 8, update the current PowerShell session:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -version
```

### Option 1: application and PostgreSQL with Compose

```powershell
docker compose up --build
```

The API is available at `http://localhost:8080`. PostgreSQL is exposed at `localhost:5432`, and Flyway applies V1 followed by V2 during startup. The application container runs as a non-root user with a read-only root filesystem, a writable temporary filesystem, and `no-new-privileges`.

Stop containers while retaining data:

```powershell
docker compose down
```

Delete containers and the local database volume:

```powershell
docker compose down -v
```

The second command permanently deletes local Compose database data.

### Option 2: local application with Compose PostgreSQL

```powershell
docker compose up -d postgres
mvn spring-boot:run
```

Runtime datasource variables:

| Variable | Local default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/wallet_ledger` |
| `DB_USERNAME` | `wallet` |
| `DB_PASSWORD` | `wallet` |
| `DB_POOL_MAX_SIZE` | `10` |
| `DB_POOL_MIN_IDLE` | `2` |

These credentials are local-development defaults. Deployments must inject secrets through their secret-management mechanism.

### Build and run the JAR

```powershell
mvn clean package
java -jar target/wallet-ledger-service.jar
```

A reachable PostgreSQL database is still required.

### Run tests

```powershell
mvn test
mvn clean verify
```

The H2 and MockMvc suites run without Docker. The four PostgreSQL Testcontainers methods across two classes run when Docker is available and are skipped when the Docker daemon is unavailable.

Run only the production-dialect classes after starting Docker:

```powershell
mvn -Dtest=PostgresWalletConcurrencyIntegrationTest test
mvn -Dtest=PostgresV1ToV2MigrationIntegrationTest test
```

The checked-in CI workflow requires both PostgreSQL classes to run without skips.

## API

Base path: `/api/v1/wallets`

Amounts are positive JSON integers representing whole in-game currency units. Floating-point and decimal JSON values are rejected rather than truncated. Request DTOs are closed contracts: unknown JSON properties return `400 MALFORMED_REQUEST` instead of being ignored. In particular, refund requests must not contain `amount`; the server always derives the full amount from the original debit.

| Method | Path | Purpose | Success |
|---|---|---|---|
| `POST` | `/api/v1/wallets` | Create a zero-balance wallet | `201 Created` |
| `POST` | `/api/v1/wallets/{playerId}/credits` | Credit a wallet | `200 OK` |
| `POST` | `/api/v1/wallets/{playerId}/debits` | Debit a wallet | `200 OK` |
| `POST` | `/api/v1/wallets/{playerId}/transactions/{debitTransactionId}/refunds` | Refund one debit in full | `200 OK` |
| `GET` | `/api/v1/wallets/{playerId}/balance` | Read current materialized balance | `200 OK` |
| `GET` | `/api/v1/wallets/{playerId}/reconciliation` | Compare snapshot and signed ledger | `200 OK` |
| `GET` | `/api/v1/wallets/{playerId}/transactions?page=0&size=20` | Read immutable history | `200 OK` |

History pages are zero-based. `size` defaults to 20 and must be from 1 through 100.

### Shared request and response headers

Credit, debit, and refund endpoints require:

```text
Idempotency-Key: mission-42-credit-v1
```

The key must be 1–100 characters, start with an alphanumeric character, and otherwise contain only letters, digits, `.`, `_`, `:`, or `-`.

Every endpoint accepts an optional correlation header with the same character policy:

```text
X-Correlation-ID: client.trace-123
```

A valid value is echoed. Missing, oversized, whitespace-containing, or otherwise invalid values are replaced with a generated UUID. Every response contains `X-Correlation-ID`; structured errors also contain `correlationId` in their JSON body.

Successful credit, debit, and refund responses contain:

```text
Idempotent-Replayed: false
```

It is `true` when the durable result of the exact same request is replayed.

### PowerShell walkthrough

```powershell
$playerId = "11111111-1111-1111-1111-111111111111"
$baseUrl = "http://localhost:8080/api/v1/wallets"

Invoke-RestMethod `
  -Method Post `
  -Uri $baseUrl `
  -ContentType "application/json" `
  -Body (@{ playerId = $playerId } | ConvertTo-Json)

$credit = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/$playerId/credits" `
  -Headers @{
    "Idempotency-Key" = "mission-42-credit-v1"
    "X-Correlation-ID" = "manual-demo-1"
  } `
  -ContentType "application/json" `
  -Body (@{
    amount = 100
    reason = "Mission reward"
    referenceId = "mission-42"
  } | ConvertTo-Json)

$debit = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/$playerId/debits" `
  -Headers @{ "Idempotency-Key" = "purchase-91-debit-v1" } `
  -ContentType "application/json" `
  -Body (@{
    amount = 30
    reason = "Shop purchase"
    referenceId = "purchase-91"
  } | ConvertTo-Json)

$refund = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/$playerId/transactions/$($debit.transactionId)/refunds" `
  -Headers @{ "Idempotency-Key" = "purchase-91-refund-v1" } `
  -ContentType "application/json" `
  -Body (@{
    reason = "Purchase reversed"
    referenceId = "refund-case-17"
  } | ConvertTo-Json)

Invoke-RestMethod -Method Get -Uri "$baseUrl/$playerId/balance"
Invoke-RestMethod -Method Get -Uri "$baseUrl/$playerId/reconciliation"
Invoke-RestMethod -Method Get -Uri "$baseUrl/$playerId/transactions?page=0&size=20"
```

### Credit/debit request and response

Request:

```json
{
  "amount": 100,
  "reason": "Mission reward",
  "referenceId": "mission-42"
}
```

Response:

```json
{
  "transactionId": "395c3e27-5411-4f7a-b067-31911f3fcb33",
  "playerId": "11111111-1111-1111-1111-111111111111",
  "type": "CREDIT",
  "amount": 100,
  "balanceAfter": 100,
  "reason": "Mission reward",
  "referenceId": "mission-42",
  "idempotencyKey": "mission-42-credit-v1",
  "refundedDebitId": null,
  "createdAt": "2026-08-31T02:00:00Z",
  "replayed": false
}
```

`refundedDebitId` is null for ordinary credits and debits. It identifies the reversed debit on a refund credit.

### History response

```json
{
  "playerId": "11111111-1111-1111-1111-111111111111",
  "transactions": [
    {
      "transactionId": "8e440b6e-ac82-421e-849e-72501ac26d31",
      "type": "CREDIT",
      "amount": 30,
      "balanceAfter": 100,
      "reason": "Purchase reversed",
      "referenceId": "refund-case-17",
      "refundedDebitId": "ea40971a-574a-48c3-8574-20902ba86123",
      "createdAt": "2026-08-31T02:03:00Z"
    }
  ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 3,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

## Refund contract

A refund is deliberately narrow and unambiguous:

- It targets one existing transaction ID in the same wallet.
- The target must be a `DEBIT`.
- The server derives the full amount from that debit; there is no client-supplied amount.
- It creates an immutable `CREDIT` linked through `refunded_debit_id`.
- It does not change the original debit and does not introduce a third transaction type.
- One debit may have at most one linked refund.

Request body:

```json
{
  "reason": "Purchase reversed",
  "referenceId": "refund-case-17"
}
```

The first success returns a normal money-movement response with `type: "CREDIT"`, the original amount, and `refundedDebitId` set to the debit transaction ID.

Refund idempotency policy:

1. Same key, same debit, same normalized reason/reference: replay the original refund.
2. Same key with another target or payload: `409 IDEMPOTENCY_CONFLICT`.
3. Another key after that debit was refunded: `409 DEBIT_ALREADY_REFUNDED`.
4. Unknown transaction and transaction owned by another wallet both return `404 TRANSACTION_NOT_FOUND`; ownership is not disclosed.
5. A credit target returns `422 TRANSACTION_NOT_REFUNDABLE`.
6. If restoring the amount would overflow `long`, return `422 BALANCE_OVERFLOW` and roll back all state.

## Balance reconciliation

`GET /api/v1/wallets/{playerId}/reconciliation` performs a diagnostic read. It does not repair data.

Example:

```json
{
  "playerId": "11111111-1111-1111-1111-111111111111",
  "storedBalance": 100,
  "calculatedBalance": 100,
  "consistent": true,
  "transactionCount": 3,
  "checkedAt": "2026-08-31T02:04:00Z"
}
```

The database query reads the wallet snapshot and computes:

```text
SUM(CASE CREDIT => +amount, DEBIT => -amount)
```

It intentionally does not sum `balance_after`. The aggregate uses `NUMERIC(38,0)` and maps to Java `BigInteger`, because total lifetime credits or debits can exceed `BIGINT` even while the net wallet balance stays valid. Snapshot and aggregate are read in one SQL statement, giving one statement-level database snapshot at `READ_COMMITTED` and avoiding a two-query race.

A mismatch is returned as `200 OK` with `consistent: false`. Operators can alert on it, but the endpoint never silently modifies financial state.

## Observability and API documentation

### Actuator

Only these endpoint families are exposed:

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Aggregate health |
| `/actuator/health/liveness` | Process liveness probe |
| `/actuator/health/readiness` | Readiness probe, including datasource readiness |
| `/actuator/info` | Non-sensitive service metadata |
| `/actuator/prometheus` | Prometheus-format metrics scrape |

Sensitive Actuator endpoints such as `/actuator/env`, `/actuator/beans`, and `/actuator/configprops` are not exposed. The Prometheus endpoint has explicit read-only access. Metrics use framework-provided low-cardinality dimensions; no player ID, transaction ID, idempotency key, or correlation ID is used as a metric tag.

### Correlation IDs

`CorrelationIdFilter` runs once per request. It validates or generates the ID, sets the response header, places it in SLF4J MDC for logs, and clears MDC in `finally` so pooled servlet threads cannot leak one request's ID into another request.

Correlation IDs aid tracing but are not authentication credentials and are not trusted for authorization.

### OpenAPI

- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

The generated specification includes refund and reconciliation schemas, operation summaries, idempotency headers, and correlation request/response headers. No security scheme is fabricated because the required identity provider and caller roles have not been defined.

## Design decisions

### Snapshot balance plus immutable ledger

The database stores both:

- `wallets.balance`: authoritative current snapshot for constant-time reads and debit validation.
- `ledger_entries`: immutable record for each successful balance mutation.

Deriving every balance by summing all history would make hot-path cost grow with transaction count. The snapshot keeps reads and locked validation predictable. The cost is duplicated representation, so wallet and ledger changes share one transaction and reconciliation can independently detect divergence.

`LedgerEntry` is Hibernate `@Immutable`; the repository exposes no update/delete operation. A foreign key prevents wallet deletion while history exists. Database administrators still require appropriately restricted production roles because application-level immutability does not stop privileged SQL.

### Refunds are linked credits

A refund restores value, so it is represented by the existing positive ledger operation: `CREDIT`. The nullable `refunded_debit_id` link carries reversal semantics. This preserves the simple signed-ledger formula and avoids changing historical V1's `CREDIT`/`DEBIT` type invariant.

Flyway V2 adds:

- nullable self-foreign key to `ledger_entries(id)` with restricted deletion;
- unique constraint on `refunded_debit_id`;
- check that a non-null refund link can only occur on a `CREDIT`.

The service additionally proves the target belongs to the same wallet and is a debit. A portable row check/foreign key cannot inspect another row's wallet and type in both PostgreSQL and H2.

### Exact integer amounts and exact aggregate arithmetic

Individual amounts and wallet balances use `long` / `BIGINT`. Credits use `Math.addExact`. Reconciliation uses wider decimal/integer arithmetic because lifetime turnover is not bounded by the current balance.

If decimal currency is introduced, define a fixed scale and use integer minor units or constrained `NUMERIC`; never use `float` or `double` for money.

### Additive migrations

`V1__create_wallet_ledger.sql` is published history and must not be edited. Refund linkage is in `V2__add_ledger_refunds.sql`. This preserves Flyway checksums and lets an existing V1 database move forward without rewriting history; it is not a zero-downtime or mixed-version guarantee.

**Refund rollout gate:** do not allow refund rows to be written until every pre-refund application instance has been drained from **all** wallet traffic. An old node does not map `refunded_debit_id`, so its idempotency logic can interpret a linked refund `CREDIT` as an ordinary credit. Applying the nullable column while old binaries are still running is schema-compatible, but serving wallet requests from those binaries after refund traffic begins is unsupported. Routing only the refund endpoint to new nodes is not sufficient.

**V2 operational risk:** PostgreSQL must build the unique index behind `uk_ledger_refunded_debit` and validate the foreign-key and check constraints against existing `ledger_entries`. On a populated ledger these operations scan data, acquire locks, and can block writes. The configured five-second `lock_timeout` limits how long migration SQL waits to acquire a lock; it does not limit how long index creation or constraint validation can run after obtaining one. Rehearse V2 against a production-size copy with representative write traffic, and use a maintenance window or a separately designed staged PostgreSQL migration when the measured impact is not acceptable. If V2 has already been applied in an environment, preserve its checksum and introduce any staged strategy in a later migration rather than editing V2.

### Layering

The HTTP boundary only exposes DTOs. `WalletService` owns use-case transaction boundaries and ordering rules. Entities guard local invariants, and restricted repository interfaces expose only required persistence operations. This feature-focused structure avoids unnecessary abstraction while keeping database entities out of the wire contract.

## Concurrency and idempotency

### Credit/debit sequence

Every credit or debit runs in one `READ_COMMITTED` transaction:

1. Normalize and validate request fields.
2. lock the wallet row with `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`);
3. look up `(wallet_id, idempotency_key)` after acquiring the lock;
4. replay an exact match or reject conflicting key reuse;
5. validate funds or overflow against the locked snapshot;
6. mutate the managed wallet and insert an immutable ledger row;
7. commit both or roll back both.

The post-lock key lookup matters. A concurrent duplicate waits, then sees the first request's committed row and replays it.

### Refund sequence

Refunds retain the same lock order:

1. validate player, debit ID, reason, reference, and key;
2. lock the wallet;
3. perform exact target-aware idempotency lookup;
4. load the target using both wallet ID and transaction ID;
5. require `DEBIT`;
6. check for an existing linked refund;
7. run exact overflow-safe credit arithmetic;
8. insert the linked credit and commit with the wallet update.

Same-key and distinct-key refund races therefore serialize on the wallet. The V2 unique constraint is the final defense against a second linked credit even if a future code path fails to follow the lock protocol.

### Failed requests and lock timeout

Failed requests do not insert a ledger row and do not consume their idempotency key. A caller may correct the state and safely retry with the same key.

PostgreSQL connections set a five-second `lock_timeout`. A lock failure maps to `503 WALLET_BUSY` with `Retry-After: 1`; callers should retry with the same idempotency key.

## Errors

Structured error example:

```json
{
  "timestamp": "2026-08-31T02:00:01Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "INSUFFICIENT_FUNDS",
  "message": "The wallet does not have enough funds for this debit",
  "path": "/api/v1/wallets/11111111-1111-1111-1111-111111111111/debits",
  "correlationId": "client.trace-123",
  "fieldErrors": {},
  "details": {
    "playerId": "11111111-1111-1111-1111-111111111111",
    "requestedAmount": 30,
    "availableBalance": 20
  }
}
```

| Status | Code | Meaning |
|---|---|---|
| `400` | `VALIDATION_FAILED` | Request DTO constraint failed |
| `400` | `MALFORMED_REQUEST` | Missing, invalid, or unknown JSON input |
| `400` | `MISSING_REQUEST_VALUE` | Required header/query/path value missing |
| `400` | `INVALID_PARAMETER` | Path/query value has an invalid type |
| `400` | `INVALID_AMOUNT` / `INVALID_REQUEST` | Service input validation failed |
| `404` | `HTTP_NOT_FOUND` | No endpoint exists for the requested path |
| `404` | `WALLET_NOT_FOUND` | Wallet does not exist |
| `404` | `TRANSACTION_NOT_FOUND` | Transaction is absent or not in that wallet |
| `405` | `HTTP_METHOD_NOT_ALLOWED` | HTTP method is unsupported for the endpoint |
| `415` | `HTTP_UNSUPPORTED_MEDIA_TYPE` | Request media type is unsupported |
| `409` | `WALLET_ALREADY_EXISTS` | Player already has a wallet |
| `409` | `IDEMPOTENCY_CONFLICT` | Key identifies another request |
| `409` | `DEBIT_ALREADY_REFUNDED` | Another key targets a refunded debit |
| `422` | `INSUFFICIENT_FUNDS` | Debit exceeds locked balance |
| `422` | `BALANCE_OVERFLOW` | Credit/refund exceeds `Long.MAX_VALUE` |
| `422` | `TRANSACTION_NOT_REFUNDABLE` | Refund target is not a debit |
| `503` | `WALLET_BUSY` | Lock acquisition timed out; retry same key |

Unexpected details and stack traces are not returned to clients.

## Testing approach

There are 30 test methods across five integration classes.

### Portable H2 service suite: 16 methods

`WalletServiceIntegrationTest` covers baseline money behavior plus:

- successful full refund and balance restoration;
- exact same-key refund replay;
- same key targeting another debit or changed payload;
- another key after refund;
- missing, wrong-wallet, and credit targets;
- refund overflow rollback;
- concurrent same-key and distinct-key refund races;
- empty, normal, refunded, high-turnover, and deliberately corrupted reconciliation states;
- proof that diagnostic reconciliation does not repair data.

The high-turnover test records two `Long.MAX_VALUE` credit/debit cycles. Positive lifetime turnover exceeds `BIGINT`, while exact reconciliation still returns net zero.

### MockMvc API and operational suite: 8 methods

`WalletApiIntegrationTest` covers:

- complete create/credit/replay/debit/balance/history flow;
- refund response/replay/linkage/error mapping;
- reconciliation JSON;
- validation, malformed/unknown JSON rejection, strict decimal rejection, and missing values;
- caller-supplied, generated, and replaced correlation IDs plus correlated framework 404/405/415 errors;
- OpenAPI 3.1 generation, correct create/credit/debit success contracts, idempotency/replay headers, refund error responses, nullable refund links, correlation headers, reconciliation schema, and Swagger UI redirect;
- health, liveness, datasource-backed readiness, info, and Prometheus endpoints;
- non-exposure of sensitive Actuator endpoints.

### Forced rollback suite: 2 methods

`WalletTransactionRollbackIntegrationTest` forces ledger writes to fail after wallet mutation for both ordinary credit and refund paths, then verifies the wallet update rolls back and no refund/key row is created.

### PostgreSQL Testcontainers suite: 3 methods

`PostgresWalletConcurrencyIntegrationTest` verifies:

- no overdraft and duplicate replay under PostgreSQL row locks;
- one refund under same-key and distinct-key races;
- V2's one-linked-refund uniqueness constraint on PostgreSQL;
- reconciliation after concurrent refund behavior.

### PostgreSQL V1-to-V2 migration suite: 1 method

`PostgresV1ToV2MigrationIntegrationTest` starts a fresh PostgreSQL 17.6 container, migrates only to V1, inserts a wallet with ordinary credit/debit history, and then migrates to V2. It verifies that legacy data remains unchanged, legacy refund links are null, valid linked credits are accepted, and duplicate-link, foreign-key, and linked-debit violations are rejected by PostgreSQL. This proves populated-schema compatibility and constraint behavior, not zero-downtime performance on a production-size ledger.

H2 is valuable for portable coverage but is not treated as proof of PostgreSQL lock, isolation, or migration behavior.

## Project structure

```text
.
├── pom.xml
├── compose.yaml
├── Dockerfile
├── .github/workflows/ci.yml
├── src
│   ├── main
│   │   ├── java/io/github/lalathomas/walletledger
│   │   │   ├── WalletLedgerApplication.java
│   │   │   ├── common/api
│   │   │   │   ├── ApiErrorResponse.java
│   │   │   │   ├── CorrelationIdFilter.java
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   └── OpenApiConfiguration.java
│   │   │   └── wallet
│   │   │       ├── api             # controller and wire DTOs
│   │   │       ├── application     # use cases and transaction boundaries
│   │   │       ├── domain          # wallet and immutable ledger entities
│   │   │       └── persistence     # restricted repositories/projections
│   │   └── resources
│   │       ├── application.yml
│   │       └── db/migration
│   │           ├── V1__create_wallet_ledger.sql
│   │           └── V2__add_ledger_refunds.sql
│   └── test
│       ├── java/.../WalletServiceIntegrationTest.java
│       ├── java/.../WalletApiIntegrationTest.java
│       ├── java/.../WalletTransactionRollbackIntegrationTest.java
│       ├── java/.../PostgresWalletConcurrencyIntegrationTest.java
│       ├── java/.../PostgresV1ToV2MigrationIntegrationTest.java
│       └── resources/application-test.yml
├── README.md
└── PROJECT_GUIDE.md
```

## Production boundary and limitations

- No caller identity contract, JWT issuer, OAuth2 provider, API-key policy, or role model was provided. Authentication/authorization is therefore not guessed. Deploy behind a trusted gateway/network until those requirements exist.
- Reconciliation is an on-demand diagnostic. There is no scheduled fleet-wide scan or alert rule yet.
- The ledger is append-only through application code, not cryptographically immutable. Restrict database roles and consider update/delete-denying triggers or archival controls.
- This is a single-database service. Reliable downstream events require an outbox before external side effects are added.
- Offset pagination is deterministic for a fixed dataset but can shift when new entries arrive between page requests. A per-wallet sequence and cursor pagination would give stable live traversal.
- A highly contended wallet is intentionally serialized. Capacity and lock-timeout values need load testing against expected traffic.
- No rate limiting, tenant model, currency code, transfer operation, reservation/hold, partial refund, or chargeback workflow is defined.
- Actuator endpoints are narrowly exposed but still need network policy in a real deployment; Prometheus should scrape from an internal network.
- Deploying refunds requires an explicit compatibility gate: apply/rehearse V2, keep refund traffic disabled, drain every pre-refund node from all wallet traffic, and only then enable refunds.
- Additive V2 is not assumed to be an online migration for a large ledger; constraint/index work needs production-size rehearsal and a maintenance or staged rollout decision.
- Compose credentials are local-only examples.
- Local PostgreSQL/container validation requires a running Docker daemon. CI is configured to enforce it on a Docker-enabled runner.

## Possible next steps

1. Define the caller model and integrate the actual OAuth2/JWT or gateway identity provider with operation-level authorization.
2. Schedule reconciliation scans and alert on mismatches without automatic repair.
3. Add fixed, low-cardinality business metrics for outcomes, replays, insufficient funds, lock waits, refund conflicts, and reconciliation mismatches.
4. Add an outbox for reliable downstream notifications.
5. Use restricted database roles and optionally denial triggers for ledger update/delete operations.
6. Add a per-wallet monotonic sequence and cursor pagination.
7. Establish backup/restore, retention, partitioning, and disaster-recovery procedures.
8. Load-test hot-wallet contention and tune pool, request, statement, and lock timeouts from measured SLOs.
9. Add currency/minor-unit rules if multiple currencies become a requirement.
10. Design ordered two-wallet locking before implementing transfers.

## AI tooling note

Kiro was used to analyze the assignment, challenge the concurrency/idempotency/refund design, generate and revise implementation and tests, and review documentation. The solution was developed from the assignment requirements rather than copied from an external implementation. Automated tests exposed real JPA-proxy, H2 UUID-projection, and Actuator-access issues; the implementation was corrected rather than weakening those tests. The architecture, trade-offs, operational boundary, and remaining limitations are documented so the author can understand, rewrite, and explain the solution directly.
