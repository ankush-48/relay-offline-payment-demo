# Offline request flow

1. The browser checks connectivity for presentation. `navigator.onLine` does not prove backend reachability.
2. When offline, a request is written to IndexedDB with a localStorage fallback and a unique client ID. No PIN, password, or token is stored. Queueing is not a transfer.
3. The UI shows distinct waiting, syncing, server-confirmed, server-rejected, and duplicate-packet states. Transient request failures retain the queue entry and show an exponential retry time. Users can also request manual sync.
4. When online and the demo session is active, the browser calls the existing server APIs. The demo server creates and injects an encrypted packet into its virtual mesh.
5. Peer gossip forwards the envelope; bridge ingestion decrypts the instruction, checks freshness and idempotency, then attempts settlement.
6. The browser displays confirmation only after a `SETTLED` bridge result. Other results remain clearly separate from success.

Limitations: device storage can be cleared and same-origin scripts can access it. The service worker caches the app shell but does not implement native Background Sync. The sender packet is created server-side in this simulation. Offline queueing neither authorizes a payment nor reserves funds or guarantees later settlement.
