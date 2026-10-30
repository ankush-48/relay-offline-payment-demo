# API documentation

Base URL: `http://localhost:8080`. JSON request/response bodies are shown below. The API is a local simulator; the payment endpoints do not call UPI or banks.

## Endpoint reference

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/health` | Liveness and `SIMULATION` mode |
| `POST` | `/api/auth/request-otp` | Generate local demo OTP challenge |
| `POST` | `/api/auth/verify-otp` | Verify challenge, save entered display name, establish HTTP session |
| `GET` | `/api/auth/me` | Current session profile |
| `POST` | `/api/auth/logout` | Invalidate current session |
| `GET` | `/api/server-key` | Current process public key and crypto profile |
| `GET` | `/api/accounts` | Seeded simulated account balances |
| `GET` | `/api/transactions` | Latest 20 ledger records |
| `POST` | `/api/demo/send` | Server-side demo packet creation and mesh injection |
| `GET` | `/api/mesh/state` | Virtual device and packet counts |
| `POST` | `/api/mesh/gossip` | One virtual gossip round |
| `POST` | `/api/mesh/flush` | Submit bridge-held packets and return outcomes |
| `POST` | `/api/mesh/reset` | Clear virtual mesh and JVM-local idempotency cache |
| `POST` | `/api/bridge/ingest` | Ingest one encrypted packet |

## Authentication (demo only)

Request:

```http
POST /api/auth/request-otp
Content-Type: application/json

{"contact":"person@example.com"}
```

Response includes `challengeId`, `demoOtp`, `expiresInSeconds`, and `deliveryStatus: "DEMO_ONLY_NOT_SENT"`. The code is shown only for local demonstration; no email/SMS is sent. Challenge lifetime is five minutes, with five attempts and a resend delay. Verification:

```json
{"challengeId":"<challengeId>","code":"<demoOtp>","displayName":"Alex Morgan"}
```

Successful verification creates an HTTP session and returns `authenticated`, `displayName`, and normalized `contact`. This is not real contact verification or production authentication. Payment endpoints do not currently require that session.

## Create and deliver a simulated payment

```http
POST /api/demo/send
Content-Type: application/json

{"senderVpa":"alice@demo","receiverVpa":"bob@demo","amount":25.00,"ttl":5,"startDevice":"phone-alice"}
```

A valid request returns `packetId`, `ciphertextPreview`, `ttl`, and `injectedAt`. Sender and recipient must exist, be distinct, and amount must be positive. `pin` is accepted for compatibility but is neither authenticated nor verified.

Run the demo stages:

```bash
curl -X POST http://localhost:8080/api/mesh/gossip
curl -X POST http://localhost:8080/api/mesh/flush
```

The flush response lists an outcome for each bridge upload. Outcomes include `SETTLED`, `REJECTED`, `DUPLICATE_DROPPED`, and `INVALID`. `SETTLED` means only that the local simulated ledger updated. `REJECTED` includes insufficient balance. `INVALID` covers invalid ciphertext, stale/future packets, and processing errors.

Direct ingestion accepts the `MeshPacket` JSON and optional headers `X-Bridge-Node-Id` and `X-Hop-Count`:

```http
POST /api/bridge/ingest
X-Bridge-Node-Id: bridge-lab
X-Hop-Count: 2
Content-Type: application/json

{"packetId":"<id>","ttl":5,"createdAt":<epochMillis>,"ciphertext":"<encryptedEnvelope>"}
```

## Database configuration

Default local storage is file-backed H2. Override `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` to use another compatible JDBC driver. Docker Compose persists H2 through a named volume. Tests use isolated in-memory H2.

## Errors and safety

Validation errors from demo endpoints use HTTP 400 with a JSON `error` message. `/api/bridge/ingest` returns an ingestion result record with an outcome/reason. Server errors and transport failures are not settlement confirmations. This API is unauthenticated for payment demo routes and is not suitable for internet exposure or real payment processing.
