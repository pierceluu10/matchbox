# matchbox

Event-driven payment reconciliation: Kafka-connected Spring Boot microservices that take payments,
charge a (simulated) bank, match every charge against the bank's settlement, and track mismatches
through to resolution.

Java 21 · Spring Boot 4.1 · Kafka 4.1 (KRaft) · PostgreSQL 17 · Redis 7 · Docker Compose

## Architecture

```
client ──POST /payments──▶ payment-service ──HTTP charge (paymentId)──▶ bank-simulator
   ◀──202 + paymentId──────┤   owns payments DB,                          │ settles later; sometimes
client ──GET /payments/{id}▶   Redis status cache                         │ wrong amount, missing,
                           │                                              │ or sent twice
        payment-requested ─┘ (consumed by itself)                         │
        payment-processed ─────────▶ reconciliation-service ◀──POST /settlements (HMAC-signed)
                                       owns reconciliation DB; pairs both sides on paymentId
        reconciliation-exception ◀─────┘
                 ▼
        exception-service ── owns exceptions DB, ops dashboard at :8083
        exception-updated ─────────▶ payment-service (shows exception status on the payment)
```

Every topic is keyed by `paymentId` (also the correlation id the bank echoes back), so all events
for one payment land on one partition, in order, handled by one consumer at a time.

| Service | Port | Responsibility |
|---|---|---|
| payment-service | 8080 | Validates and accepts payments (`202 Accepted`), charges the bank, serves status |
| bank-simulator | 8081 | External bank stand-in: idempotent charges, delayed fault-injected settlement webhooks |
| reconciliation-service | 8082 | Bank webhook, pairs charges with settlements, raises exceptions, sweeps for missing ones |
| exception-service | 8083 | Exception cases, resolve API, ops dashboard |

## Reliability decisions

- **Transactional outbox.** A state change and the event it causes are written in one Postgres
  transaction; a relay publishes the outbox to Kafka. No lost or phantom events when a service
  crashes between the database write and the publish ([shared/outbox](shared/src/main/java/dev/matchbox/shared/outbox)).
- **Idempotent consumers.** Every consumer turns a repeated event into a no-op: conditional
  `UPDATE ... WHERE status = 'PENDING'`, "record each side at most once" under a row lock, and a
  unique `(payment_id, type)` on exceptions. The bank deduplicates charges on `paymentId`, so a
  crash after charging can never double-charge. POST also accepts an `Idempotency-Key` header.
- **Retries and dead-letter topics.** Malformed or invalid events skip retries and go straight to
  `<topic>.dlq`. The bank call uses non-blocking retry topics (exponential backoff, 5 attempts) so
  one flaky charge doesn't stall its partition; this alone took end-to-end p95 from 218 ms to ~36 ms.
- **Webhook trust boundary.** Settlements must carry an HMAC-SHA256 signature over the raw body,
  compared in constant time; unsigned or malformed requests get 401/400. A 200 means the
  settlement is durable.
- **Cache correctness.** Status reads are cache-aside in Redis; writes evict after commit
  (transaction-aware cache), with a 5 s TTL as a backstop.

## Run it

Requires Docker. JDK 21 is only needed to run tests outside Docker.

```bash
docker compose up -d --build
```

Dashboard: http://localhost:8083 · API: `POST http://localhost:8080/payments`

```bash
curl -i -X POST localhost:8080/payments -H 'Content-Type: application/json' \
  -d '{"customerId":"c1","amount":"12.34","currency":"USD"}'
```

## Results

Measured on one laptop (Docker Desktop, 10 CPUs, 8 GB), all services, Kafka, Postgres and Redis on
the same machine. Reproduce with the scripts below.

**Load test** (`RATE=200 DURATION=2m scripts/load-test.sh`): 200 payments/s = **12,000/min**, each
created and polled once, after a 20 s warmup.

| Metric | p95 | p99 |
|---|---|---|
| `POST /payments` response | 1.5 ms | 4.7 ms |
| `GET /payments/{id}` response | 0.8 ms | 2.1 ms |
| End to end: accepted → charge recorded | 36 ms | 560 ms* |

0 dropped iterations, 100% of requests succeeded. *p99 is the ~2% of charges the simulator fails
on purpose, which recover through retry topics.

**Replay test** (`scripts/replay-test.sh`): rewinds every consumer group to offset 0 and reprocesses
all events (150,606 events for 73,727 payments). Payments, reconciliation records, exception
cases, bank charge counts and every topic's end offset were **identical before and after**, with
0 bank calls during replay. **PASS.**

## Tests

```bash
./mvnw verify
```

Integration tests run against real Kafka and Postgres (and Redis) via Testcontainers. They cover
the async API contract, idempotency keys, replayed events, retries, DLQ routing, out-of-order
sides, missing/unexpected settlements, webhook signatures and cache eviction.

## Known limits

- The bank simulator keeps charges in memory; restarting it forgets them.
- One outbox relay per service; scaling out needs the outbox partitioned by key to keep order.
- No authentication on the payment or exception APIs.
- Replication factor 1 everywhere: it's a single local broker.
