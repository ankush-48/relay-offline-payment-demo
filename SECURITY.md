# Security notes

This is an educational simulation, not a production financial service. Existing packet encryption uses RSA-OAEP and AES-GCM, ingestion checks packet age, and settlement checks the simulated account balance. The OTP demo generates a one-time code locally and returns it in its response so a local user can complete the UI flow; no email/SMS is sent. Challenges expire after five minutes and allow five attempts. Demo sign-in creates an HTTP session and stores the user display name/contact in the H2 demo database.

The app does not implement Google OAuth, real OTP delivery, or production-grade authorization; payment APIs remain unauthenticated. The existing PIN value is not verified. The RSA key, OTP challenge map, and session data are not a production identity system; H2 is ephemeral. H2 console is enabled. Do not expose this configuration to the public internet.

Browser queue data is localStorage data, not secure device storage. The UI does not store OTPs, passwords, or access tokens. Use a real email/SMS provider, protect provider secrets, add delivery abuse controls, persistent storage, authentication and endpoint authorization, managed private keys, bridge credentials, TLS, audit logging, and security review before any hosted deployment. No banking API, UPI network, or real money movement is integrated.
