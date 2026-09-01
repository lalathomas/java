# Beginner's Guide to the Wallet Ledger

This guide explains what the project does and why it was built this way. It assumes you are new to Spring Boot, databases, and concurrent requests. The [README](README.md) is the shorter document for running and reviewing the service; this file is the learning guide.

## 1. What problem does the service solve?

Imagine a game gives each player a wallet. The wallet stores whole units such as coins or credits. The game needs to:

- create one wallet for a player;
- add a reward;
- spend currency on a purchase;
- refund a purchase;
- show the balance and transaction history.

That sounds simple until failures occur. A phone can retry the same request, two game servers can spend from one wallet at the same time, or the database can fail after part of an operation. This project is designed so those cases do not create free currency, duplicate charges, or a negative balance.

## 2. A few terms first

| Term | Meaning in this project |
|---|---|
| API | HTTP endpoints another program calls to use the wallet |
| DTO | A Java record/class used only for an API request or response |
| Entity | A Java object mapped to a database table |
| Repository | The interface used to save and query entities |
| Service | The class containing business rules and transaction boundaries |
| Ledger | The permanent list of successful balance changes |
| Transaction | A group of database changes that all succeed or all roll back |
| Idempotency | Retrying the same successful request returns the first result instead of applying it twice |
| Row lock | A database lock that lets only one request change a wallet at a time |
| Migration | A numbered SQL file that creates or changes the database schema |

## 3. The project in one picture

```text
HTTP request
    |
    v
WalletController        checks the HTTP shape and builds a response
    |
    v
WalletService           validates business rules and owns the transaction
    |
    +--> Wallet          changes the current balance
    |
    +--> Repositories    lock, read, and save database rows
    |
    v
PostgreSQL              stores the wallet snapshot and ledger history
```
Each layer has one main job. This makes it easier to find code and prevents database objects from becoming part of the public API.

## 4. Where should I start reading?

Read these files in this order:

1. `wallet/api/WalletController.java` — shows every endpoint.
2. `wallet/application/WalletService.java` — contains the important business decisions.
3. `wallet/domain/Wallet.java` — protects basic balance rules.
4. `wallet/domain/LedgerEntry.java` — represents one successful movement.
5. `wallet/persistence/WalletRepository.java` — contains the locking query.
6. `wallet/persistence/LedgerEntryRepository.java` — contains ledger lookups and history ordering.
7. `common/api/GlobalExceptionHandler.java` — converts Java failures into consistent HTTP errors.
8. `resources/db/migration/*.sql` — shows what PostgreSQL ultimately enforces.
9. The integration tests — readable examples of expected behavior.

## 5. What the main files do

### API layer

`WalletController` maps URLs to Java methods. It does not decide whether a debit is affordable. It reads path values, headers, and JSON, passes a command to the service, and converts the result to a response DTO.

The records in `wallet/api` define the public contract. Keeping them separate from JPA entities means a database change does not automatically become an API change.

### Application layer

`WalletService` is the center of the project. It:

- validates and normalizes input;
- starts database transactions;
- locks a wallet before changing it;
- handles idempotency;
- checks funds and overflow;
- creates ledger entries;
- returns snapshots/results to the controller.

The command and result records are small typed messages between the controller and service. `WalletException` and `WalletErrorCode` represent expected business failures.

### Domain layer

`Wallet` owns its balance. Its `credit` and `debit` methods reject invalid operations rather than exposing a public `setBalance` method.

`LedgerEntry` is an immutable record of `CREDIT`, `DEBIT`, `REFUND`, transfer, and reservation actions. Refunds point to the debit they reverse; transfer pairs share a transfer ID; reservation actions share a reservation ID.

### Persistence layer

Spring Data creates repository implementations at runtime. `WalletRepository` has a `PESSIMISTIC_WRITE` query, which becomes a database row lock. `LedgerEntryRepository` intentionally has no update or delete methods.

## 6. Follow one credit request

Suppose the client sends:

```http
POST /api/v1/wallets/{playerId}/credits
Idempotency-Key: mission-42-credit-v1
Content-Type: application/json

{
  "amount": 100,
  "reason": "Mission reward",
  "referenceId": "mission-42"
}
```

The steps are:

1. Spring converts the JSON into `MoneyMovementRequest`.
2. Bean Validation checks obvious request-shape rules.
3. The controller creates a `MoneyMovementCommand` and calls `WalletService.credit`.
4. The service trims and checks the reason, reference, and key.
5. PostgreSQL locks this player's wallet row.
6. The service searches for the key in this wallet's ledger.
7. If this is a new request, `Wallet.credit(100)` calculates the next balance with overflow protection.
8. A `CREDIT` ledger entry is inserted with the new `balanceAfter`.
9. The transaction commits both changes.
10. The controller returns the transaction and `Idempotent-Replayed: false`.

If step 8 fails, the database transaction rolls step 7 back. The balance does not change by itself.

## 7. Why store both a balance and a ledger?

There are three main tables:

```text
wallets
  player_id
  balance              <- posted balance snapshot
  reserved_balance     <- amount currently held

fund_reservations
  wallet_id
  amount
  status               <- ACTIVE, CAPTURED, or RELEASED
  reason
  reference_id
  completed_at

ledger_entries
  transaction_type
  amount
  balance_after
  reason
  reference_id
  idempotency_key
  reversal_of_entry_id
  transfer_id
  reservation_id
  created_at            <- permanent history
```

The balance gives fast reads. Without it, every balance request would have to add all credits and subtract all debits. The ledger gives an audit trail and lets the service replay completed requests.

Storing both creates a consistency risk, so the service changes them in one transaction. Database checks also reject negative balances, non-positive amounts, duplicate wallet keys, and invalid refund links.

## 8. The concurrency problem

Assume a wallet has 10 units and two requests each try to debit 10.

Without locking:

```text
Request A reads 10          Request B reads 10
A decides debit is valid    B decides debit is valid
A writes 0                  B also writes 0
```

Both requests may report success even though only 10 units existed. This is a lost-update/business-consistency bug.

With the row lock:

```text
Request A locks wallet
Request B waits
A reads 10, debits 10, commits
B gets lock, reads 0, receives INSUFFICIENT_FUNDS
```

The lock applies per wallet. A request for another player's wallet uses another row and does not need to wait.

The trade-off is that one extremely busy wallet becomes a queue. For this service, simple correctness is more valuable than maximum throughput on one wallet.

## 9. Why idempotency is needed

Networks are unreliable. A client can send a credit, receive no response, and retry without knowing whether the first request committed. Applying both requests would award the player twice.

The client therefore sends a unique `Idempotency-Key`. The database makes `(wallet_id, idempotency_key)` unique.

- New key: apply the movement and store the key on its ledger row.
- Same key and same normalized request: return the original transaction with `replayed: true`.
- Same key but different request: return `IDEMPOTENCY_CONFLICT`.

The lookup happens after locking the wallet. If two identical requests arrive together, the second waits for the first commit and then sees its ledger entry.

A failed debit does not store a ledger row, so it does not consume the key. The client can add funds and safely retry that debit.

## 10. How refunds work

A refund is not a negative debit supplied by the client. The URL identifies an existing transaction, and the server checks that it:

1. belongs to this wallet;
2. is a `DEBIT`;
3. has not already been refunded.

The server copies the original amount, credits it back, and writes a `REFUND` row linked by `reversal_of_entry_id`. A unique database constraint on that link is the final protection against double refunds.

Only full, one-time refunds are supported. Partial refunds would need a different model because several refund rows might share one debit and the service would need to track the remaining refundable amount.

## 11. Validation and errors

Validation happens at more than one level:

- DTO annotations catch missing or badly shaped JSON fields.
- `WalletService` checks business limits and normalizes text.
- Domain methods reject impossible balance changes.
- PostgreSQL constraints provide the final defense.

`GlobalExceptionHandler` translates failures into one JSON structure. A client can use the stable `code` (such as `INSUFFICIENT_FUNDS`) instead of trying to parse an English message.

Expected client mistakes return 4xx responses. A temporary lock timeout returns `503 WALLET_BUSY` with `Retry-After: 1`; the client should retry with the same idempotency key. Unexpected failures return a generic 500 response while the detailed exception remains in server logs.

## 12. What Maven, Spring Boot, Flyway, and Docker each do

### Maven

Maven reads `pom.xml`, downloads dependencies, compiles Java, runs tests, and creates `target/wallet-ledger-service.jar`.

Useful commands:

```powershell
mvn compile
mvn test
mvn clean verify
mvn spring-boot:run
```

The Enforcer plugin requires Java 17+ and Maven 3.9+.

### Spring Boot

Spring Boot starts the embedded web server, finds controllers and services, creates repository implementations, configures JSON, opens database transactions, and wires classes through constructor injection.

### Flyway

Flyway reads numbered files such as `V1__create_wallet_ledger.sql` and records which versions have run. Schema changes are reviewable and repeatable instead of being made manually on each computer.

### Docker

The Dockerfile builds a runnable application image. `compose.yaml` starts both PostgreSQL and the application with matching local settings. This gives reviewers a repeatable environment:

```powershell
docker compose up --build
```

## 13. How the tests are divided

| Test | What it proves |
|---|---|
| `WalletServiceIntegrationTest` | Business rules, idempotency, history, refunds, and concurrent operations |
| `WalletApiIntegrationTest` | URLs, JSON, status codes, response headers, and error bodies |
| `WalletTransactionRollbackIntegrationTest` | A ledger failure also rolls back the balance |
| `PostgresWalletConcurrencyIntegrationTest` | Real PostgreSQL locking and retry behavior |

H2 makes the normal suite quick and portable. It is not trusted for every concurrency detail, so Testcontainers starts an actual PostgreSQL instance for the highest-risk scenarios. CI checks that those PostgreSQL tests did not silently skip.

## 14. How to explain the design in a review

A short explanation could be:

> I kept the current balance as a wallet snapshot and wrote every successful change to an append-only ledger. Mutations run in one transaction and take a row-level lock, so same-wallet operations cannot race. The idempotency key is checked after that lock, allowing concurrent duplicate requests to replay one durable result. PostgreSQL constraints back up the Java rules, and the high-risk behavior is tested against a real PostgreSQL container.

Be ready to explain the trade-offs rather than saying the design is perfect:

- Row locking favors clear correctness but serializes a very busy wallet.
- A snapshot makes balance reads fast but must stay consistent with the ledger.
- Offset pagination is simple but new transactions can shift later pages.
- Application-level immutability does not stop a privileged database user.
- H2 is convenient, but PostgreSQL tests are still necessary.

## 15. What is intentionally not included

The service has no authentication, authorization, multiple currencies, cross-service transfers, partial refunds, event publishing, reward campaigns, or rate limiting. It should not be exposed publicly without security controls.

Good production follow-ups would include:

- metrics for lock waits, retries, failures, and insufficient funds;
- request/correlation IDs in logs;
- a per-wallet sequence for stable statements;
- an outbox for reliable downstream events;
- database roles or triggers that forbid ledger updates and deletes.

These are next steps, not hidden claims about the current implementation.

## 16. Final review checklist

Before submitting or demonstrating the project:

```powershell
docker version
mvn -version
mvn clean verify
docker compose up --build
```

Then run the README PowerShell example and check:

- the wallet begins at zero;
- credit makes the balance 100;
- debit makes it 70;
- repeating the same debit key does not debit again;
- refund returns the balance to 100;
- history contains the credit, debit, and linked refund;
- an unaffordable debit returns 422 without changing the balance.

Finally, compare the requirement table in the README with the original assignment PDF. The PDF is not committed, so that manual comparison is the only reliable way to prove every wording-specific requirement has been covered.

## 17. Optional money features added after the core

### Player transfer

A transfer is one database transaction containing a source `TRANSFER_OUT` and destination `TRANSFER_IN`. Both wallets are locked in database-ID order, so A-to-B and B-to-A requests use the same lock order and avoid deadlock. The source idempotency key replays the linked pair instead of moving currency twice. If the destination would overflow or either insert fails, both balances roll back.

### Fund reservation

A wallet now has posted, reserved, and available values: `available = balance - reserved`. Creating a reservation moves no currency; it protects part of the posted balance from ordinary debits and transfers. Capture subtracts the held amount from posted and reserved balances and writes a linked debit. Release only removes the hold. The state machine allows `ACTIVE -> CAPTURED` or `ACTIVE -> RELEASED`, never both.

### Balance reconciliation

`GET /api/v1/wallets/{playerId}/reconciliation` recomputes the posted balance from all signed ledger entries and recomputes reserved funds from all active reservations. It compares those values to the wallet snapshots under `REPEATABLE_READ`, so concurrent commits cannot mix old and new rows. It reports mismatches rather than changing financial data automatically.

### Migration and tests

`V3__add_transfers_reservations_reconciliation.sql` adds reserved balances, reservation state, transfer/reservation ledger links, checks, foreign keys, and uniqueness constraints. `OptionalWalletFeaturesIntegrationTest` and `OptionalWalletFeaturesApiIntegrationTest` cover service and HTTP behavior for these additions. Existing tests continue to protect the mandatory core.
