<div align="center">

# RELAY

### Offline-First Payment Network Simulator

**What happens to a payment request when the network disappears?**

Relay is an interactive full-stack demo that follows a payment request from local browser storage through an encrypted virtual mesh to a simulated server ledger.

<br>

[**Open the live demo**](https://relay-offline-payment-demo.onrender.com/) · [**Check API health**](https://relay-offline-payment-demo.onrender.com/api/health)

<br>

![Java 17](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![JavaScript](https://img.shields.io/badge/JavaScript-ES2022-F7DF1E?style=for-the-badge&logo=javascript&logoColor=black)
![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![Render](https://img.shields.io/badge/Deployed%20on-Render-5A3EE8?style=for-the-badge&logo=render&logoColor=white)

</div>

---

## The idea

Most payment demos show a single request and response. Relay makes the steps visible: a request can wait in the browser while offline, become an encrypted packet, pass through simulated peers, and reach a backend that validates and records a **simulated** result.

> **This is a learning project.** Relay is not UPI, does not connect to banks or payment networks, and never moves real money.

## Explore the flow

```mermaid
flowchart LR
    A[Browser request] --> B[Local offline queue]
    B --> C[Encrypted packet]
    C --> D[Virtual peer mesh]
    D --> E[Bridge ingestion]
    E --> F[Validation and deduplication]
    F --> G[Simulated H2 ledger]
