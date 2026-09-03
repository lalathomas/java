# Wallet Ledger Service

A Java wallet service for managing in-game currency. It supports credits, debits, full refunds, atomic player-to-player transfers, temporary fund reservations, current balances, reconciliation, and paginated transaction history.

The main concern is correctness when requests are retried or arrive at the same time. Every operation is transactional, idempotent where applicable, and recorded in an immutable application-level ledger.

For a plain-language tour of the code and technology, see [PROJECT_GUIDE.md](PROJECT_GUIDE.md).

## Assignment coverage

This checklist follows the four assignment pages supplied with the project.

### Mandatory capabilities

| Assignment requirement | Status | Evidence |
|---|---|---|
| Java 17+, Spring Boot 3.x, relational database | Done | Java 17, Spring Boot 3.5, PostgreSQL 17.6 |
| Credit a player's wallet | Done | `POST /api/v1/wallets/{playerId}/credits` |
| Debit a player's wallet | Done | `POST /api/v1/wallets/{playerId}/debits` |
| Reject a debit when funds are insufficient | Done | `INSUFFICIENT_FUNDS`; balance and ledger remain unchanged |
| Return the current balance | Done | `GET /api/v1/wallets/{playerId}/balance` |
| Return paginated transaction history | Done | `GET /api/v1/wallets/{playerId}/transactions?page=0&size=20` |
| Permanently record what happened and why | Done | append-only ledger records type, amount, resulting balance, reason, reference, and time |
| Do not apply the same request twice | Done | wallet-scoped idempotency key and durable replay result |
| Keep simultaneous requests correct | Done | pessimistic wallet-row lock plus database constraints |
| Leave no partial update after failure | Done | balance and ledger writes share one transaction; rollback is tested |
| Handle invalid input clearly | Done | request validation and structured error responses |
| Test normal and pressure scenarios | Done | API, service, rollback, duplicate-delivery, and concurrent PostgreSQL tests |
| Explain setup, design, and limitations | Done | this README and `PROJECT_GUIDE.md` |

### Optional aspects implemented

| Assignment suggestion | Implementation |
|---|---|
| Transaction Refund | Full, one-time debit refund with an immutable source-transaction link |
| Player Currency Transfer | Atomic two-wallet transfer with deterministic lock ordering and linked ledger entries |
| Reservation of Funds | Hold, capture, and release lifecycle; held funds cannot be spent by ordinary debits |
| Balance Check | Recomputes posted and reserved balances from complete database history under one consistent snapshot |
| Docker Compose | Starts the application and PostgreSQL with one command |
| Flyway | Three versioned database migrations, validated on startup |
| Clean REST controllers, validation, and errors | DTO-only API boundary and centralized structured error handling |
| Basic observability | Logs record wallet mutations, replays, failures, and lock timeouts without request bodies or credentials |

Daily streaks, limited promotions, bulk rewards, claim rewards, domain events, OpenAPI/Swagger, Redis caching, and metrics are not claimed. The implemented extras stay close to the ledger and have complete persistence, idempotency, and test coverage rather than adding shallow endpoints.

## Technology

- Java 17 and Spring Boot 3.5
- Spring MVC and Jakarta Bean Validation for the HTTP API
- Spring Data JPA/Hibernate for persistence and transactions
- PostgreSQL for the running service
- Flyway for versioned database changes
- JUnit, MockMvc, H2, and Testcontainers for tests
- Maven for building; Docker Compose for local startup

## How to run

### Fastest option: Docker

You need Docker Desktop (or another Docker Engine with Compose). From the repository root:
```powershell
docker compose up --build
```

The API starts at `http://localhost:8080`. Flyway creates the PostgreSQL schema automatically.

Stop the containers but keep the database:

```powershell
docker compose down
```

Use `docker compose down -v` only when you also want to delete the local database volume.

### Run Java locally and PostgreSQL in Docker

Requirements: JDK 17+, Maven 3.9+, and Docker.

```powershell
docker compose up -d postgres
mvn spring-boot:run
```

The local defaults are:

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/wallet_ledger` |
| `DB_USERNAME` | `wallet` |
| `DB_PASSWORD` | `wallet` |
| `DB_POOL_MAX_SIZE` | `10` |
| `DB_POOL_MIN_IDLE` | `2` |

These credentials are only for local development. A deployed environment should provide its own secrets.

### Build and test

```powershell
mvn test
mvn clean verify
```

`mvn test` always runs the H2-backed service and API tests. If Docker is available, it also runs the PostgreSQL Testcontainers suite. `mvn clean verify` is the same command used by CI.

To run the packaged JAR against a reachable PostgreSQL database:

```powershell
mvn clean package
java -jar target/wallet-ledger-service.jar
```

## API

Base URL: `http://localhost:8080/api/v1/wallets`

| Method | Path | Result |
|---|---|---|
| `POST` | `/api/v1/wallets` | Creates a zero-balance wallet (`201`) |
| `POST` | `/{playerId}/credits` | Adds units (`200`) |
| `POST` | `/{playerId}/debits` | Spends available units (`200`) |
| `POST` | `/{playerId}/transactions/{transactionId}/refund` | Refunds one complete debit (`200`) |
| `POST` | `/{playerId}/transfers` | Atomically transfers units to another player (`200`) |
| `POST` | `/{playerId}/reservations` | Holds available units (`200`) |
| `POST` | `/{playerId}/reservations/{reservationId}/capture` | Finalizes a hold as a debit (`200`) |
| `POST` | `/{playerId}/reservations/{reservationId}/release` | Releases held units (`200`) |
| `GET` | `/{playerId}/balance` | Returns the posted balance (`200`) |
| `GET` | `/{playerId}/reconciliation` | Compares snapshots with full history (`200`) |
| `GET` | `/{playerId}/transactions?page=0&size=20` | Returns newest-first history (`200`) |

Wallet creation returns a `Location` header pointing to the new balance resource. Every credit, debit, refund, transfer, and reservation action returns `Idempotent-Replayed: false` the first time and `true` when replayed.

### Request rules

- `playerId` and transaction IDs are UUIDs.
- Amounts are positive JSON integers stored as Java `long`/PostgreSQL `BIGINT`. Decimals are rejected.
- `reason` is required, trimmed, and limited to 255 characters.
- `referenceId` is required, trimmed, and limited to 100 characters.
- Every credit, debit, and refund needs an `Idempotency-Key` header.
- The key is trimmed, has 1–100 characters, starts with a letter or digit, and may then contain letters, digits, `.`, `_`, `:`, or `-`.
- History pages start at 0. `size` must be between 1 and 100.

### PowerShell example

```powershell
$playerId = "11111111-1111-1111-1111-111111111111"
$baseUrl = "http://localhost:8080/api/v1/wallets"

# Create a wallet
Invoke-RestMethod -Method Post -Uri $baseUrl `
  -ContentType "application/json" `
  -Body (@{ playerId = $playerId } | ConvertTo-Json)

# Add 100 units
$credit = Invoke-RestMethod -Method Post -Uri "$baseUrl/$playerId/credits" `
  -Headers @{ "Idempotency-Key" = "mission-42-credit-v1" } `
  -ContentType "application/json" `
  -Body (@{
    amount = 100
    reason = "Mission reward"
    referenceId = "mission-42"
  } | ConvertTo-Json)

# Spend 30 units
$debit = Invoke-RestMethod -Method Post -Uri "$baseUrl/$playerId/debits" `
  -Headers @{ "Idempotency-Key" = "purchase-91-debit-v1" } `
  -ContentType "application/json" `
  -Body (@{
    amount = 30
    reason = "Shop purchase"
    referenceId = "purchase-91"
  } | ConvertTo-Json)

# Refund that debit
Invoke-RestMethod -Method Post `
  -Uri "$baseUrl/$playerId/transactions/$($debit.transactionId)/refund" `
  -Headers @{ "Idempotency-Key" = "purchase-91-refund-v1" } `
  -ContentType "application/json" `
  -Body (@{
    reason = "Purchase cancelled"
    referenceId = "support-ticket-203"
  } | ConvertTo-Json)

Invoke-RestMethod -Method Get -Uri "$baseUrl/$playerId/balance"
Invoke-RestMethod -Method Get -Uri "$baseUrl/$playerId/transactions?page=0&size=20"
```

A successful money movement looks like this:

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

### Optional feature requests

Transfer request (`POST /{sourcePlayerId}/transfers`):

```json
{"destinationPlayerId":"22222222-2222-2222-2222-222222222222","amount":25,"reason":"Player gift","referenceId":"gift-1"}
```

A reservation is created with the same `amount`, `reason`, and `referenceId` shape as a debit. Capture and release requests contain `reason` and `referenceId` only. Reservation responses distinguish the posted `balance`, `reservedBalance`, and spendable `availableBalance`.

The reconciliation endpoint returns stored and calculated posted/reserved balances, counts, individual match flags, and an overall `consistent` flag. It reports discrepancies; it never silently repairs financial data.

## Error responses

Errors use the same JSON shape, including a stable `code`, message, request path, field errors, and optional details. Important cases are:

| Status | Code | Meaning |
|---|---|---|
| `400` | `VALIDATION_FAILED` | A body field failed validation |
| `400` | `MALFORMED_REQUEST` | Missing or invalid JSON |
| `400` | `MISSING_REQUEST_VALUE` | A required header or value is absent |
| `400` | `INVALID_PARAMETER` | A path/query value has the wrong type |
| `400` | `INVALID_AMOUNT` / `INVALID_REQUEST` | A business input is invalid |
| `404` | `WALLET_NOT_FOUND` | The player has no wallet |
| `404` | `TRANSACTION_NOT_FOUND` | The refund source is not in this wallet |
| `404` | `RESERVATION_NOT_FOUND` | The reservation is not in this wallet |
| `409` | `WALLET_ALREADY_EXISTS` | The player already has a wallet |
| `409` | `IDEMPOTENCY_CONFLICT` | The same key was used for different request data |
| `409` | `TRANSACTION_ALREADY_REFUNDED` | The debit already has a refund |
| `409` | `RESERVATION_NOT_ACTIVE` | A captured/released reservation cannot transition again |
| `422` | `INSUFFICIENT_FUNDS` | The debit is larger than the balance |
| `422` | `BALANCE_OVERFLOW` | A credit would exceed `Long.MAX_VALUE` |
| `422` | `TRANSACTION_NOT_REFUNDABLE` | The source is not a debit |
| `503` | `WALLET_BUSY` | The row lock timed out; safely retry with the same key |
| `500` | `INTERNAL_ERROR` | An unexpected server or database failure |

A `503 WALLET_BUSY` response also includes `Retry-After: 1`.

## Design decisions and trade-offs

### Balance snapshot and ledger

`wallets.balance` is the posted balance. `wallets.reserved_balance` is the subset temporarily held, so `available = balance - reserved_balance`. `ledger_entries` records balance changes plus reservation lifecycle actions. Wallet and ledger/reservation state are changed in one transaction; if any write fails, all changes roll back.

The application never updates or deletes a ledger entry. The entity is marked immutable and its repository exposes only save/read methods. This is application-level append-only behavior; a privileged database administrator could still modify the table.

### Transfers

A transfer locks both wallets in database-ID order, debits the source and credits the destination, and writes linked `TRANSFER_OUT`/`TRANSFER_IN` entries in one transaction. Fixed lock ordering prevents opposite-direction transfers from deadlocking. Destination overflow or any persistence failure rolls back both wallets.

### Reservations

A reservation increases `reserved_balance` without changing posted balance. Normal debits and transfers can spend only the available balance. Capture decreases both posted and reserved balances and writes a linked debit; release only frees the hold. Wallet locking and database uniqueness permit one terminal transition.

### Reconciliation

The diagnostic endpoint sums the complete signed ledger and all active reservations, then compares those values with both wallet snapshots. `REPEATABLE_READ` gives the calculation one consistent database snapshot during concurrent writes. A mismatch is reported, never automatically repaired.

## Concurrency and idempotency

### Concurrency

Credits, debits, refunds, and reservation actions lock one wallet row. Transfers lock both wallets in deterministic database-ID order. Requests for the same wallet serialize while independent wallets can proceed concurrently. This closes read/check/write races and avoids transfer deadlocks.

### Idempotency

The service checks the idempotency key after taking the wallet lock. An exact retry returns the stored transaction. Reusing the key with another amount, operation, reason, reference, or refund source returns `409` instead. Keys are scoped to a wallet, so two players may use the same value.

A rejected request creates no ledger entry and does not consume its key.

### Refunds

A refund uses the amount from the original debit; clients cannot choose it. The database stores a unique link back to that debit, which prevents two successful refunds even under concurrent requests.

## Testing approach

- `WalletServiceIntegrationTest`: core business rules, pagination, idempotency, concurrency, and refunds.
- `WalletApiIntegrationTest`: core endpoints, JSON, headers, validation, and errors through MockMvc.
- `OptionalWalletFeaturesIntegrationTest`: transfer replay, reservation lifecycle/availability, and reconciliation.
- `OptionalWalletFeaturesApiIntegrationTest`: live HTTP contracts for all new optional routes.
- `WalletTransactionRollbackIntegrationTest`: proves a failed ledger write rolls back the balance.
- `PostgresWalletConcurrencyIntegrationTest`: verifies locking, duplicate delivery, retry, and refund races against PostgreSQL.

The concurrent debit case submits 20 simultaneous debits against funds for only 10. Exactly 10 succeed, 10 receive `INSUFFICIENT_FUNDS`, and the balance ends at zero. The high-risk locking suite runs against PostgreSQL because concurrency behavior is database-specific; CI fails if those tests skip.

## Main source layout

```text
src/main/java/io/github/lalathomas/walletledger
├── common/api          shared error response and exception handler
└── wallet
    ├── api             controller and request/response DTOs
    ├── application     use cases, validation, and transaction boundary
    ├── domain          wallet, ledger entry, and transaction type
    └── persistence     database repositories

src/main/resources
├── application.yml
└── db/migration        Flyway SQL migrations
```

## Assumptions and limitations

This is a single-currency, single-database service. Transfers are local and atomic; cross-service transfers would need a different protocol. Reservations are full-amount holds with one capture or release. Refunds are full and one-time. Authentication, authorization, partial refunds, event publishing, rate limiting, reward campaigns, and scheduled streak processing are outside scope.

Useful next steps are authentication, request correlation IDs, metrics, a per-wallet sequence, an outbox for reliable events, and database roles/triggers that physically prevent ledger updates.
