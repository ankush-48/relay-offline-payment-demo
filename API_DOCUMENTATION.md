# API documentation

Base URL: `http://localhost:8080`. JSON request/response bodies are shown below. The API is a local simulator; the payment endpoints do not call UPI or banks.

In hosted public mode, workspace data is scoped to the HTTP session. A visitor may set a display name with `POST /api/auth/guest`; no email address, phone number, or OTP is used. The workspace has its own demo accounts, transactions, and mesh. Guest workspaces expire after inactivity and are lost when the in-memory service restarts.

## Endpoint reference

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/health` | Liveness and `SIMULATION` mode |
| `POST` | `/api/auth/request-otp` | Generate local demo OTP challenge |
| `POST` | `/api/auth/verify-otp` | Verify challenge, save entered display name, establish HTTP session |
| `POST` | `/api/auth/guest` | Create or rename the current hosted guest workspace |
| `GET` | `/api/auth/me` | Current session profile |
| `POST` | `/api/auth/logout` | Invalidate current session |
| `GET` | `/api/server-key` | Current process public key and crypto profile |
| `GET` | `/api/accounts` | Current workspace accounts in hosted mode; seeded accounts locally |
| `GET` | `/api/transactions` | Latest 20 current-workspace records in hosted mode; all demo records locally |
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

Response includes `challengeId`, `demoOtp`, `expiresInSeconds`, and `deliveryStatus: "DEMO_ONLY_NOT_SENT"`. The code is shown only for local demonstration; no email/SMS is sent. Public mode disables OTP sign-in. Challenge lifetime is five minutes, with five attempts and a resend delay. Verification:

```json
{"challengeId":"<challengeId>","code":"<demoOtp>","displayName":"Alex Morgan"}
```

Successful local verification creates an HTTP session and returns `authenticated`, `displayName`, and normalized `contact`. This is not real contact verification or production authentication.

For the hosted guest workspace, send `{"displayName":"Alex Morgan"}` to `POST /api/auth/guest`. The response contains the temporary `accountVpa`. Keep the session cookie to use the same workspace. Workspace state is not a persistent account and cannot be recovered after cookies or the in-memory service are cleared.

## Create and deliver a simulated payment

```http
POST /api/demo/send
Content-Type: application/json

{"senderVpa":"alice@demo","receiverVpa":"bob@demo","amount":25.00,"ttl":5,"startDevice":"phone-alice"}
```

A valid request returns `packetId`, `ciphertextPreview`, `ttl`, and `injectedAt`. Sender and recipient must exist and be distinct. Amounts must be positive, fit the ledger's two-decimal precision, and, in public mode, be no more than ₹500. Public mode also requires both accounts to belong to the current workspace and rate-limits writes. `pin` is accepted for compatibility but is neither authenticated nor verified.

Run the demo stages:

```bash
curl -X POST http://localhost:8080/api/mesh/gossip
curl -X POST http://localhost:8080/api/mesh/flush
```

The hosted UI uses `/api/mesh/gossip/{packetId}` and `/api/mesh/flush/{packetId}` so it only processes the current visitor's packet. Public mode disables global gossip/flush, mesh reset, and direct bridge ingestion.

The flush response lists an outcome for each bridge upload. Outcomes include `SETTLED`, `REJECTED`, `DUPLICATE_DROPPED`, and `INVALID`. `SETTLED` means only that the local simulated ledger updated. `REJECTED` includes insufficient balance. `INVALID` covers invalid ciphertext, stale/future packets, and processing errors.

Direct ingestion accepts a `MeshPacket` JSON body and optional headers `X-Bridge-Node-Id` and `X-Hop-Count`. Ciphertext is limited to 16 KB, bridge IDs to 80 safe characters, and hop count to 0–5:

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

Validation errors from demo endpoints use HTTP 400 with a JSON `error` message. Public demo writes can return HTTP 429 with a `Retry-After` header. `/api/bridge/ingest` returns an ingestion result record with an outcome/reason locally and is disabled in public mode. Unexpected processing failures return a generic error reason; server logs retain diagnostic detail. Server errors and transport failures are not settlement confirmations. Public session scoping and in-process rate limits are for an educational single-instance demo, not production authorization or real payment processing.
