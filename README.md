# Wallet Ledger Service

A production-minded backend for creating player wallets, crediting and debiting exact currency units, issuing full transaction refunds, reading current balances, and browsing an append-only transaction history. The design focuses on the failure modes that matter for money movement: duplicate delivery, concurrent requests, insufficient funds, overflow, double refunds, and atomic persistence.

## Contents

- [Implemented capabilities](#implemented-capabilities)
- [Technology](#technology)
- [How to run](#how-to-run)
- [API](#api)
- [Design decisions](#design-decisions)
- [Concurrency and idempotency](#concurrency-and-idempotency)
- [Testing approach](#testing-approach)
- [Project structure](#project-structure)
- [Assumptions and limitations](#assumptions-and-limitations)
- [Possible next steps](#possible-next-steps)
- [AI tooling note](#ai-tooling-note)

## Implemented capabilities

- Explicit wallet creation for a UUID player ID
- Exact integer credits and debits
- Full refund of an original debit, with immutable source-transaction linkage
- One-refund-only enforcement under concurrent requests
- Insufficient-funds rejection without partial state changes
- Current-balance lookup
- Paginated, newest-first transaction history
- Required reason and external reference on every balance change
- Required, wallet-scoped idempotency keys
- Detection of an idempotency key reused with different request data
- Serialized concurrent mutations for the same wallet
- Independent concurrency for different wallets
- Atomic wallet and ledger persistence in one database transaction
- Database constraints for non-negative balances, positive amounts, transaction types, references, and idempotency uniqueness
- Structured validation and domain error responses
- Flyway-managed schema
- Docker Compose environment for the application and PostgreSQL
- H2-backed integration/API tests that run without Docker
- A PostgreSQL Testcontainers concurrency test that runs when Docker is available

The optional refund feature is intentionally narrow: it supports a full, one-time reversal of an existing debit. Transfers, reservations, streaks, promotions, and bulk rewards remain outside this service's scope so the money core stays reviewable and complete.

## Technology

| Component | Choice |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.5.15 |
| API | Spring MVC + Jakarta Bean Validation |
| Persistence | Spring Data JPA / Hibernate |
| Production database | PostgreSQL 17.6 in Compose |
| Schema management | Flyway |
| Portable tests | H2 2.x in PostgreSQL compatibility mode |
| Production-dialect tests | Testcontainers + PostgreSQL |
| Build | Maven 3.9+ |

The Maven Enforcer plugin stops the build with a clear message if Maven is running under Java older than 17.

## How to run

### Prerequisites

Choose one of these setups:

1. **Docker setup:** Docker Desktop (or another Docker Engine) with Docker Compose.
2. **Local setup:** JDK 17+, Maven 3.9+, and PostgreSQL.

Verify Java and Maven before running the project:

```powershell
java -version
mvn -version
```

Both commands must report Java 17 or newer. On Windows, if `java -version` is correct but Maven reports Java 8, update the current PowerShell session before running Maven:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -version
```

### Option 1: application and PostgreSQL with Docker Compose

From the repository root:

```powershell
docker compose up --build
```

The API is then available at `http://localhost:8080`. PostgreSQL is exposed at `localhost:5432`, and Flyway applies the schema automatically during startup.

Stop the containers while retaining database data:

```powershell
docker compose down
```

To also remove the local database volume, use `docker compose down -v`. That command permanently deletes the Compose database data.

### Option 2: run the application locally against Compose PostgreSQL

Start only PostgreSQL:

```powershell
docker compose up -d postgres
```

Then run the Spring Boot application:

```powershell
mvn spring-boot:run
```

The checked-in defaults match the Compose database:

| Environment variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/wallet_ledger` |
| `DB_USERNAME` | `wallet` |
| `DB_PASSWORD` | `wallet` |
| `DB_POOL_MAX_SIZE` | `10` |
| `DB_POOL_MIN_IDLE` | `2` |

The defaults are for local development only. Real deployments must inject secrets rather than store credentials in source control.

### Build and run the JAR

```powershell
mvn clean package
java -jar target/wallet-ledger-service.jar
```

The application still requires a reachable PostgreSQL database when started this way.

### Run the tests

```powershell
mvn test
```

The H2 service and MockMvc suites always run. The Testcontainers PostgreSQL test runs automatically when Docker is available and is reported as skipped when no Docker daemon is available.

Run only the production-dialect concurrency test after starting Docker:

```powershell
mvn -Dtest=PostgresWalletConcurrencyIntegrationTest test
```

Run the complete build lifecycle:

```powershell
mvn clean verify
```

## API

Base path: `/api/v1/wallets`

Amounts are positive JSON integers representing whole in-game currency units. Floating-point and decimal JSON values are not accepted.

| Method | Path | Purpose | Success |
|---|---|---|---|
| `POST` | `/api/v1/wallets` | Create a zero-balance wallet | `201 Created` |
| `POST` | `/api/v1/wallets/{playerId}/credits` | Credit a wallet | `200 OK` |
| `POST` | `/api/v1/wallets/{playerId}/debits` | Debit a wallet | `200 OK` |
| `POST` | `/api/v1/wallets/{playerId}/transactions/{transactionId}/refund` | Fully refund one debit | `200 OK` |
| `GET` | `/api/v1/wallets/{playerId}/balance` | Read current balance | `200 OK` |
| `GET` | `/api/v1/wallets/{playerId}/transactions?page=0&size=20` | Read ledger history | `200 OK` |

History pages are zero-based. `size` must be between 1 and 100 and defaults to 20.

### Example with PowerShell

Set a player ID and base URL:

```powershell
$playerId = "11111111-1111-1111-1111-111111111111"
$baseUrl = "http://localhost:8080/api/v1/wallets"
```

Create a wallet:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri $baseUrl `
  -ContentType "application/json" `
  -Body (@{ playerId = $playerId } | ConvertTo-Json)
```

Credit 100 units:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/$playerId/credits" `
  -Headers @{ "Idempotency-Key" = "mission-42-credit-v1" } `
  -ContentType "application/json" `
  -Body (@{
    amount = 100
    reason = "Mission reward"
    referenceId = "mission-42"
  } | ConvertTo-Json)
```

Debit 30 units:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/$playerId/debits" `
  -Headers @{ "Idempotency-Key" = "purchase-91-debit-v1" } `
  -ContentType "application/json" `
  -Body (@{
    amount = 30
    reason = "Shop purchase"
    referenceId = "purchase-91"
  } | ConvertTo-Json)
```

Refund the debit using its returned `transactionId` (the server derives the refund amount from that debit):

```powershell
$debitTransactionId = "replace-with-debit-transaction-id"
Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/$playerId/transactions/$debitTransactionId/refund" `
  -Headers @{ "Idempotency-Key" = "purchase-91-refund-v1" } `
  -ContentType "application/json" `
  -Body (@{
    reason = "Purchase cancelled"
    referenceId = "support-ticket-203"
  } | ConvertTo-Json)
```

Read the balance and first history page:

```powershell
Invoke-RestMethod -Method Get -Uri "$baseUrl/$playerId/balance"
Invoke-RestMethod -Method Get -Uri "$baseUrl/$playerId/transactions?page=0&size=20"
```

### Credit/debit requests

Credit and debit requests require an `Idempotency-Key` header and this body:

```json
{
  "amount": 100,
  "reason": "Mission reward",
  "referenceId": "mission-42"
}
```

The key must be 1–100 characters, begin with an alphanumeric character, and otherwise contain only letters, digits, `.`, `_`, `:`, or `-`.

### Refund request

A refund requires the original debit transaction ID in the path, an `Idempotency-Key` header, and audit metadata:

```json
{
  "reason": "Purchase cancelled",
  "referenceId": "support-ticket-203"
}
```

The client cannot provide an amount. The service refunds the exact amount of the linked debit, only permits one refund for that debit, and records a `REFUND` entry whose `reversalOfTransactionId` points to the original transaction. Repeating the same refund request with the same key replays the durable result; another key receives `409 TRANSACTION_ALREADY_REFUNDED`.

A successful mutation returns the durable transaction identity and the balance immediately after that transaction:

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
  "reversalOfTransactionId": null,
  "createdAt": "2026-08-31T02:00:00Z",
  "replayed": false
}
```

The response header `Idempotent-Replayed` is `false` for the first successful application and `true` when returning a previously recorded result.

### Transaction history response

```json
{
  "playerId": "11111111-1111-1111-1111-111111111111",
  "transactions": [
    {
      "transactionId": "395c3e27-5411-4f7a-b067-31911f3fcb33",
      "type": "CREDIT",
      "amount": 100,
      "balanceAfter": 100,
      "reason": "Mission reward",
      "referenceId": "mission-42",
      "reversalOfTransactionId": null,
      "createdAt": "2026-08-31T02:00:00Z"
    }
  ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

### Errors

Errors use a consistent JSON structure. Framework errors are returned as `application/json` even when the requested success representation is unavailable. An insufficient debit returns `422 Unprocessable Entity` and does not alter either table:

```json
{
  "timestamp": "2026-08-31T02:00:01Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "INSUFFICIENT_FUNDS",
  "message": "The wallet does not have enough funds for this debit",
  "path": "/api/v1/wallets/11111111-1111-1111-1111-111111111111/debits",
  "fieldErrors": {},
  "details": {
    "playerId": "11111111-1111-1111-1111-111111111111",
    "requestedAmount": 30,
    "availableBalance": 20
  }
}
```

Important statuses and codes:

| Status | Code | Meaning |
|---|---|---|
| `400` | `VALIDATION_FAILED` | Invalid request body field |
| `400` | `MALFORMED_REQUEST` | Missing or invalid JSON body |
| `400` | `MISSING_REQUEST_VALUE` | Required header or request value missing |
| `400` | `INVALID_PARAMETER` | Invalid path/query type, such as a malformed UUID |
| `400` | `INVALID_AMOUNT` / `INVALID_REQUEST` | Service-level business input validation failed |
| `404` | `RESOURCE_NOT_FOUND` | No route or static resource matches the request |
| `405` | `METHOD_NOT_ALLOWED` | The route does not support the requested HTTP method |
| `406` | `NOT_ACCEPTABLE` | The requested response media type is unsupported |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | The request body media type is unsupported |
| `404` | `WALLET_NOT_FOUND` | No wallet exists for the player |
| `404` | `TRANSACTION_NOT_FOUND` | The refund source does not exist in this wallet |
| `409` | `WALLET_ALREADY_EXISTS` | The player already has a wallet |
| `409` | `IDEMPOTENCY_CONFLICT` | The key belongs to a different request payload |
| `409` | `TRANSACTION_ALREADY_REFUNDED` | Another refund already reversed this debit |
| `422` | `TRANSACTION_NOT_REFUNDABLE` | The source is not a debit transaction |
| `422` | `INSUFFICIENT_FUNDS` | Debit exceeds the locked current balance |
| `422` | `BALANCE_OVERFLOW` | Credit would exceed `Long.MAX_VALUE` |
| `503` | `WALLET_BUSY` | Database lock acquisition failed; retry safely with the same key |

## Design decisions

### Snapshot balance plus append-only ledger

The database stores both:

- `wallets.balance`: the authoritative current snapshot used for constant-time balance reads and debit validation.
- `ledger_entries`: one immutable application-level record for each successful balance mutation.

Deriving every balance by summing the complete ledger would reduce duplicated state, but read and lock costs would grow with transaction count. Keeping a snapshot makes the hot path predictable. The cost is that two representations must remain consistent, so both are changed in one local database transaction and protected by integration tests and database constraints.

No application operation updates or deletes a ledger entry. `LedgerEntry` is marked Hibernate `@Immutable`, and its repository intentionally exposes only save and read methods. The foreign key also prevents deleting a wallet that has ledger history.

### Exact integer amounts

Amounts and balances use Java `long` and database `BIGINT`. This avoids binary floating-point errors and fits an in-game currency expressed as whole units. Credits use `Math.addExact`; an overflow is rejected before persistence.

If the product later needs decimal currency, the API should define a fixed scale and either continue using integer minor units or migrate to a constrained `NUMERIC` column. It should not use `float` or `double`.

### Database-enforced invariants

Flyway creates constraints for:

- unique player wallets;
- non-negative wallet and resulting balances;
- positive ledger amounts;
- `CREDIT`, `DEBIT`, or `REFUND` transaction types;
- nonblank reason, reference, and idempotency values;
- unique `(wallet_id, idempotency_key)` pairs;
- a unique refund-source foreign key, preventing a debit from being refunded twice;
- restricted wallet deletion when ledger rows exist.

Application validation gives clients useful messages. Database constraints remain the final defense if a future code path bypasses that validation.

### Layering

The HTTP layer only accepts and returns DTOs. JPA entities never cross the API boundary. `WalletService` owns transaction boundaries and use-case rules, while the entities protect local invariants. Repository interfaces expose only operations needed by those use cases.

This is deliberately feature-focused rather than a large ports-and-adapters implementation. For the current service size, additional abstraction layers would add ceremony without improving isolation.

## Concurrency and idempotency

### Mutation sequence

Every credit, debit, or refund runs in one `@Transactional` method at `READ_COMMITTED` isolation:

1. Normalize and validate the request.
2. Select the wallet row using a JPA `PESSIMISTIC_WRITE` lock (`SELECT ... FOR UPDATE`).
3. After acquiring the lock, query for `(wallet_id, idempotency_key)`.
4. For credits/debits, the key identity is normalized type, amount, reason, and reference. For refunds it is source transaction ID, reason, and reference. An exact match returns the original ledger result without changing balance.
5. If the key exists with different request data, return `409 IDEMPOTENCY_CONFLICT`.
6. Validate available funds or arithmetic overflow against the locked balance.
7. Mutate the wallet snapshot and insert the ledger entry.
8. Commit both changes together, or roll both back together.

The lookup intentionally occurs **after** the row lock. If two identical requests arrive together, one transaction commits first. The waiting transaction then acquires the lock and, under `READ_COMMITTED`, sees the committed ledger entry and replays it.

The database unique constraint is a final idempotency defense. Keys are scoped per wallet so different players may safely use the same client-generated key.

### Why pessimistic locking

Pessimistic locking makes the debit rule simple to reason about: only one transaction at a time can inspect and change a particular wallet. There is no read/check/write gap where two debits can both approve the same funds.

Benefits:

- deterministic no-overdraft behavior;
- simple interaction with idempotency;
- no client-visible optimistic retry loop;
- wallet A and wallet B can still mutate concurrently.

Trade-off: a single extremely busy wallet becomes a serialized hotspot. For this take-home, correctness and clarity outweigh peak same-wallet throughput. A larger system could evaluate optimistic versioning with bounded retries, account partitioning, or an ordered command stream.

### Refund rules

A refund locks the wallet before checking idempotency or refund state. The source must belong to that wallet and be a `DEBIT`; its exact amount is credited without accepting an amount from the client. A unique `reversal_of_entry_id` foreign key is the final defense against double refunds. Same-key retries replay the first refund, while a different key receives `TRANSACTION_ALREADY_REFUNDED`.

### Failed requests

A failed request does not create a ledger entry and therefore does not consume its idempotency key. A client may correct the state—for example, add funds—and retry the failed debit with that key. Successful operations are durable and replayable.

## Testing approach

The main service, API, and concurrency suites exercise behavior against real persistence and do not mock the repositories that enforce locking and uniqueness. The dedicated rollback test replaces the ledger repository only to force a failure after balance mutation and prove transaction rollback.

### Portable H2 integration suite

`WalletServiceIntegrationTest` covers:

- wallet creation and duplicate rejection;
- successful credit and debit;
- balance-after values and audit metadata;
- insufficient-funds rollback;
- sequential duplicate replay;
- idempotency payload/type conflicts;
- invalid amount, reason, key, and missing wallet;
- `long` overflow rollback;
- pagination metadata and no duplicate entries across pages;
- 20 simultaneous independent credits with an exact final sum;
- 20 simultaneous debits against funds for only 10, proving exactly 10 succeed and the balance ends at zero;
- 20 simultaneous submissions of one idempotency key, proving one ledger row and one balance change;
- full refund, replay, invalid source, cross-wallet source hiding, and already-refunded rules;
- 20 simultaneous refund attempts, proving exactly one refund and one linked ledger row.

`WalletApiIntegrationTest` uses MockMvc to cover:

- the complete create/credit/replay/debit/balance/history HTTP flow;
- status codes and replay headers;
- body validation, malformed JSON, missing headers, and malformed UUIDs;
- structured domain errors;
- no partial balance change after an API-level insufficient debit;
- conflicting reuse of an idempotency key;
- complete refund/replay flow, immutable source linkage, validation, and refund business errors.

### PostgreSQL concurrency verification

`PostgresWalletConcurrencyIntegrationTest` runs the high-risk concurrent debit, duplicate-delivery, lock-timeout/retry, distinct-key refund, and same-key duplicate-refund scenarios against a Testcontainers PostgreSQL instance. This matters because lock and isolation behavior are database-specific. The test is automatically skipped when Docker is unavailable so contributors can still run the portable suite, but it should be run before submission and in CI with Docker enabled.

H2 is test infrastructure only; the deployed application is configured for PostgreSQL.

## Project structure

```text
.
├── pom.xml
├── compose.yaml
├── Dockerfile
├── src
│   ├── main
│   │   ├── java/io/github/lalathomas/walletledger
│   │   │   ├── WalletLedgerApplication.java
│   │   │   ├── common/api
│   │   │   │   ├── ApiErrorResponse.java
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   └── wallet
│   │   │       ├── api             # controller and wire DTOs
│   │   │       ├── application     # use cases, transaction boundary, results
│   │   │       ├── domain          # Wallet, LedgerEntry, TransactionType
│   │   │       └── persistence     # restricted Spring Data repositories
│   │   └── resources
│   │       ├── application.yml
│   │       └── db/migration
│   │           ├── V1__create_wallet_ledger.sql
│   │           └── V2__add_transaction_refunds.sql
│   └── test
│       ├── java/.../WalletServiceIntegrationTest.java
│       ├── java/.../WalletApiIntegrationTest.java
│       ├── java/.../PostgresWalletConcurrencyIntegrationTest.java
│       ├── java/.../WalletTransactionRollbackIntegrationTest.java
│       └── resources
│           ├── application-test.yml
│           └── application-postgres-test.yml
└── README.md
```

## Assumptions and limitations

- A player ID is a UUID and has at most one wallet.
- A wallet must be explicitly created before money can move; a missing wallet is not silently created by a credit.
- Currency is a single, whole-unit in-game currency. There is no currency code or decimal scale. Jackson is configured to reject decimal numeric tokens rather than truncate them into integers.
- Only successful money movements are recorded. Rejected attempts are visible in application logs but are not audit-ledger rows.
- Idempotency is scoped to one wallet. Credit/debit identity uses normalized operation type, amount, reason, and reference; refund identity uses source transaction ID, reason, and reference.
- Refunds are full, one-time reversals of debit transactions. Partial refunds and refunding credits or prior refunds are intentionally unsupported.
- Transaction history is ordered by `created_at DESC, id DESC`. The UUID tie-breaker makes pages deterministic for a fixed data set, but does not represent a business sequence when timestamps are exactly equal. Because this API uses offset pages, transactions inserted between page requests can shift later pages; cursor pagination backed by a per-wallet sequence would provide stable traversal under concurrent writes.
- The ledger is append-only through this application, but a privileged database administrator can still modify rows. Production hardening could use separate database roles, update/delete-denying triggers, or immutable archival storage.
- This is a single-database service. It does not publish events or coordinate external side effects. An outbox pattern would be required before reliably notifying other systems.
- No authentication, authorization, rate limiting, or tenant isolation is included. These endpoints should not be exposed publicly as-is. A production deployment must restrict mutation permissions and distinguish players from administrators.
- PostgreSQL connections set `lock_timeout` to five seconds so stalled same-wallet operations eventually fail and can be mapped to a retryable response. That value is a safe local default, not a measured production SLO, and should be tuned alongside pool and request timeouts.
- High contention on one wallet serializes operations by design.
- Compose credentials are intentionally simple local-development values.
- The PostgreSQL Testcontainers test requires a running Docker daemon and is skipped during local builds without one. The checked-in GitHub Actions workflow verifies the report and fails CI if that production-dialect test is skipped.

## Possible next steps

In a production roadmap, the most valuable additions would be:

1. Authentication and operation-level authorization.
2. A reconciliation job that recomputes ledger totals and alerts on snapshot differences.
3. A per-wallet monotonic sequence number for statements and downstream ordering.
4. Metrics for mutation latency, lock waits, insufficient funds, replay rate, and failures.
5. Structured request/correlation IDs with sensitive-data-safe logs.
6. An outbox table and domain events for reliable downstream notifications.
7. Database roles or triggers that technically prevent ledger update/delete operations.
8. Retention, partitioning, and archival policies for large ledgers.
9. Currency codes and fixed minor-unit rules if multiple currencies are introduced.
10. Carefully ordered two-wallet locks for transfers to prevent deadlocks.

## AI tooling note

Kiro was used to help analyze the assignment, challenge the concurrency/idempotency design, generate an initial implementation and tests, and review documentation. The solution was developed from the assignment requirements rather than copied from an external implementation. The generated work was compiled and exercised through automated tests, and the design choices, assumptions, and limitations are documented above so they can be reviewed and explained directly.
