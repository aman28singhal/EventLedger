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
- `GET /metrics/eventledger.gateway.requests.total`: Custom Micrometer total requests counter.
- **Swagger UI:** `http://localhost:8080/swagger-ui/index.html` (Interactive API documentation)

### 2. Account Service (Internal, Port 8081)

- `POST /accounts/{accountId}/transactions`: Apply credit/debit.
- `GET /accounts/{accountId}/balance`: Get net balance.
- `GET /accounts/{accountId}`: Get account transaction list and net balance.
- `GET /health`: Health status.
- `GET /metrics/eventledger.account.transactions.applied`: Custom Micrometer transaction counter.

---

## Testing with Swagger UI

The Event Gateway includes a **Swagger UI** for interactive testing.

1. **Start the services** (either via Docker Compose or manually).
2. **Open the browser** and navigate to:
   [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
3. **Submit a Event:**
   - Under the `/events` endpoint, click **POST**, then click **Try it out**.
   - Paste the sample JSON payload in the request body and click **Execute**.
   - You will see the response (`201 Created` or `200 OK` on duplicates) and the `X-Trace-Id` response headers.
4. **List Events:**
   - Click on the `GET /events` endpoint, click **Try it out**, fill in the `account` parameter (e.g. `acct-999`), and click **Execute**.

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

### 3. Check Account Balance (Note: only works internally or when running locally on port 8081)
```bash
curl -i http://localhost:8081/accounts/acct-999/balance
```

### 4. Fetch Event List (Chronologically Sorted)
```bash
curl -i http://localhost:8080/events?account=acct-999
```

---

## Architectural, Resiliency & Observability Decisions

* **Configurable Retry with Exponential Backoff + Jitter:** Instead of a simple retry loops or immediate failures, the `event-gateway` employs a **Resilience4j Retry** mechanism when calling `account-service`. Retries are configured with an exponential backoff (`multiplier: 2`) and random jitter (`randomizationFactor: 0.5`) to prevent thundering herd conditions when recovering from downstream outages.
* **Transactional Outbox Pattern (Eventual Consistency):** If the `account-service` is down, incoming events are still accepted by the Gateway, saved with a status of `RECEIVED`, and returned to the client as a `503 Service Unavailable`. A background `@Scheduled` scheduler (`OutboxRetryScheduler`) periodically polls for pending `RECEIVED` events and attempts to forward them sequentially in chronological order. The job breaks on the first error to guarantee order preservation.
* **Out-of-Order Handling:** Balances are calculated dynamically using database aggregation (`SUM` query) rather than being incrementally stored in the database. This ensures that even if transactions are inserted out-of-order, the net balance is always correct.
* **Idempotency:** Both services enforce idempotency at multiple levels — the Gateway checks by `eventId` before saving and forwarding, and the Account Service has a unique constraint on `eventId` with a `DataIntegrityViolationException` fallback for concurrent requests.
* **OpenAPI Integration:** Embedded `springdoc-openapi` to provide interactive, easy-to-use Swagger documentation for reviewers.
* **Observability:** Custom Micrometer metrics track total request volume on the Gateway (`eventledger.gateway.requests.total`) and transaction count on the Account Service (`eventledger.account.transactions.applied`). All service boundaries propagate a generated `X-Trace-Id` header for end-to-end distributed tracing.
