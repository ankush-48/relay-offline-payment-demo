

## 1. scope 

“Relay is an offline-first payment **simulator**. It demonstrates how a payment intent can wait locally, travel as an encrypted packet through virtual peers, and receive a server result after a bridge reconnects. It does not move money or connect to UPI.”

## 2. Show the product and sign-in (45 seconds)

Open the landing page and point out the product lifecycle, architecture sections, and explicit simulation boundary. Choose **Guest access**, enter a display name, and open a private browser-session workspace. Explain that this is temporary session identity: Gmail/SMS verification and persistent third-party identity are not configured, and no contact details are collected.

## 3. Walk through the dashboard (60 seconds)

Show the private demo accounts and wallet balance, recent workspace transactions, analytics, and the responsive navigation. Explain that a separate session gets separate accounts, ledger history, and mesh. Open **Network Monitor** to show the live health response, measured API latency, five virtual nodes, public-key fingerprint, and in-session event trace.

## 4. Trace a request (90 seconds)

Open **Send money** and create a small request from your workspace wallet to one of its demo contacts. Explain the visible states: waiting locally, syncing, then confirmed by the demo server or rejected by the demo server. Open **Transactions** to show that only server-recorded results for this workspace appear in the ledger. Explain that the simulator creates the encrypted packet on the server, rather than on a trusted phone.

To demonstrate queued behavior, use browser offline mode before submitting a request, then return online and choose **Sync queue**. Point out the retry timing if the server is temporarily unavailable. An offline request is not a completed or guaranteed payment.

## 5.engineering decisions (60 seconds)

Describe the AES-GCM payload and RSA-OAEP wrapped key, packet freshness validation, ciphertext-hash idempotency, and database transaction around debit/credit. Mention the automated tests for duplicate deliveries, tampering, insufficient funds, invalid amounts, and stale packets. Be clear that the idempotency cache is per process and the H2 database is a local demo choice.
