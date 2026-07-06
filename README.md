# EventLedger System

An Event Ledger system consisting of two independent, decoupled Spring Boot microservices communicating over synchronous REST. The architecture ensures idempotency, chronological ordering, and resiliency under service degradation.

```
                          ┌──────────────────────┐
Browser / Client ──────→  │  Event Gateway API    │ (Port 8080, gatewaydb)
                          │  (public-facing)      │
                          └──────┬───────────────┘
                                 │ REST + X-Trace-Id
                                 ▼
                          ┌──────────────────────┐
                          │  Account Service      │ (Port 8081, accountdb)
                          │  (internal)           │
                          └──────────────────────┘
```

---

## Key Features

1. **Service Isolation**: Each service runs as a separate process on distinct ports with its own H2 in-memory database.
2. **Strict Idempotency**: Duplicate events (identical `eventId`) do not result in duplicate transactions or double-crediting.
3. **Out-of-Order Tolerance**: Balance calculations are derived from query aggregations, ensuring correctness regardless of transaction insertion order. Event queries on the Gateway are returned in chronological order by `eventTimestamp`.
4. **Resiliency**: Gateway downstream integrations are guarded by a **Resilience4j Circuit Breaker** and timeout configs.
5. **Graceful Degradation**: If `account-service` is unreachable, `GET /events` endpoints continue to serve cached read operations successfully from the Gateway, while `POST /events` degrades gracefully with `503 Service Unavailable` errors.
6. **Distributed Tracing**: Incoming Gateway requests generate a `traceId` propagated to the Account Service via `X-Trace-Id` headers. Logs across both systems include the identical trace ID in JSON format.
7. **Custom Metrics**: Actuator metrics (e.g. `eventledger.gateway.events.processed`) track success and failure counts.

---

## Prerequisites

- **Java**: JDK 17
- **Docker**: Docker & Docker Compose (Optional for container deployment)
- **Maven**: Not required (standard Maven Wrapper `mvnw` / `mvnw.cmd` is included in each service directory)

---

## How to Build and Run

### Option A: Using Docker Compose (Recommended)

1. Start both services in detached mode:
   ```bash
   docker compose up --build -d
   ```
2. Check logs:
   ```bash
   docker compose logs -f
   ```
3. Stop services:
   ```bash
   docker compose down
   ```

### Option B: Running Locally (Manual)

Run the services using the Maven Wrapper:

1. **Start Account Service**:
   ```bash
   cd account-service
   # Windows:
   mvnw.cmd spring-boot:run
   # Unix/macOS:
   ./mvnw spring-boot:run
   ```
2. **Start Event Gateway** (in a new terminal):
   ```bash
   cd event-gateway
   # Windows:
   mvnw.cmd spring-boot:run
   # Unix/macOS:
   ./mvnw spring-boot:run
   ```

---

## Running Automated Tests

Run the JUnit test suite on both projects:

1. **Account Service Tests**:
   ```bash
   cd account-service
   # Windows:
   mvnw.cmd test
   # Unix/macOS:
   ./mvnw test
   ```
2. **Event Gateway Tests**:
   ```bash
   cd event-gateway
   # Windows:
   mvnw.cmd test
   # Unix/macOS:
   ./mvnw test
   ```

---

## API Endpoints

### 1. Event Gateway (Public-Facing, Port 8080)

- `POST /events`: Submit a transaction event.
- `GET /events/{id}`: Retrieve a stored event by ID.
- `GET /events?account={accountId}`: List events for an account, ordered chronologically by `eventTimestamp`.
- `GET /health`: Health status.
- `GET /metrics/eventledger.gateway.events.processed`: Custom Micrometer success/failure counter.

### 2. Account Service (Internal, Port 8081)

- `POST /accounts/{accountId}/transactions`: Apply credit/debit.
- `GET /accounts/{accountId}/balance`: Get net balance.
- `GET /accounts/{accountId}`: Get account transaction list and net balance.
- `GET /health`: Health status.
- `GET /metrics/eventledger.account.transactions.applied`: Custom Micrometer transaction counter.

---

## Verification Commands (Examples)

### 1. Submit a Transaction Event
```bash
curl -i -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-1001",
    "accountId": "acct-999",
    "type": "CREDIT",
    "amount": 250.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z"
  }'
```

### 2. Duplicate Event (Idempotency Test)
Posting the same request again will return `200 OK` (original event) instead of `201 Created`, without double-crediting.
```bash
curl -i -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-1001",
    "accountId": "acct-999",
    "type": "CREDIT",
    "amount": 250.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z"
  }'
```

### 3. Check Account Balance
```bash
curl -i http://localhost:8081/accounts/acct-999/balance
```

### 4. Fetch Event List (Chronologically Sorted)
```bash
curl -i http://localhost:8080/events?account=acct-999
```

---

## Architectural & Resiliency Decisions

- **Circuit Breaker Choice**: We chose the Resilience4j Circuit Breaker over Simple Retries or Bulkheads alone. When the Account Service is struggling, retries compound load, leading to eventual failure. The circuit breaker detects high failure rates or slow responses and trips, preventing cascading resources exhaustion.
- **Out-of-Order Handling**: Balances are calculated dynamically using database aggregation (`SUM` query) rather than being incrementally stored in the database. This ensures that even if transactions are inserted out-of-order, the net balance is always correct.
- **Event Persistence on Failure**: When the Account Service is down, events are still persisted locally in the Gateway with `RECEIVED` status. When re-submitted (or if a retry mechanism is added), the Gateway will re-attempt forwarding without creating a duplicate.
- **Idempotency**: Both services enforce idempotency at multiple levels — the Gateway checks by `eventId` before saving and forwarding, and the Account Service has a unique constraint on `eventId` with a `DataIntegrityViolationException` fallback for concurrent requests.
