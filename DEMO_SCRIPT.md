# Demo script (about 5 minutes)

## 1. Introduce the scope (30 seconds)

“Relay is an offline-first payment **simulator**. It demonstrates how a payment intent can wait locally, travel as an encrypted packet through virtual peers, and receive a server result after a bridge reconnects. It does not move money or connect to UPI.”

## 2. Show the product and sign-in (45 seconds)

Open the landing page and point out the product lifecycle, architecture sections, and explicit simulation boundary. Choose **Sign in**, enter a display name and email or phone, request the demo OTP, and use the code shown on the page. Explain that real email/SMS delivery and third-party identity are not implemented.

## 3. Walk through the dashboard (60 seconds)

Show the seeded accounts and demo balance, recent ledger transactions, analytics, and the responsive navigation. Open **Network Monitor** to show the live health response, measured API latency, five virtual nodes, public-key fingerprint, and in-session event trace.

## 4. Trace a request (90 seconds)

Open **Send money** and create a small request from `alice@demo` to `bob@demo`. Explain the visible states: waiting locally, syncing, then confirmed by the demo server or rejected by the demo server. Open **Transactions** to show that only server-recorded results appear in the ledger. Explain that the simulator creates the encrypted packet on the server, rather than on a trusted phone.

To demonstrate queued behavior, use browser offline mode before submitting a request, then return online and choose **Sync queue**. Point out the retry timing if the server is temporarily unavailable. An offline request is not a completed or guaranteed payment.

## 5. Close with engineering decisions (60 seconds)

Describe the AES-GCM payload and RSA-OAEP wrapped key, packet freshness validation, ciphertext-hash idempotency, and database transaction around debit/credit. Mention the automated tests for duplicate deliveries, tampering, insufficient funds, invalid amounts, and stale packets. Be clear that the idempotency cache is per process and the H2 database is a local demo choice.

## Interview prompts

- Why use a unique packet hash and what does the database uniqueness constraint add?
- Why can “queued offline” not promise settlement?
- What changes for multiple backend instances? (A distributed idempotency store, durable packet/ledger state, bridge authentication, and conflict-safe processing.)
- How would real offline signing and funds reservation change the trust model?
