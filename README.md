<div align="center">

# RELAY

### Offline-first payment network simulator

Explore how a payment request can wait on a device, travel across a virtual peer network, and reach a server for simulated settlement.

**Java 17 · Spring Boot · JavaScript · H2 · Docker**

</div>

---

## What is Relay?

Relay is a full-stack learning project that demonstrates an offline-first payment flow. When the browser is offline, it can save a request locally. When connectivity returns, a virtual mesh and bridge simulate how the request could reach a server, where the demo ledger validates and records the result.

> **Demo only:** Relay is not UPI, does not connect to a bank, and never moves real money. An offline request is not a payment or a promise that funds are available.

## Explore the project

- **Interactive dashboard** with simulated balances, transaction history, charts, and export.
- **Offline queue** stored in IndexedDB, with a local-storage fallback and clear sync states.
- **Virtual mesh** that demonstrates packet forwarding between simulated devices.
- **Encrypted packets** using AES-GCM payload protection and RSA-OAEP key wrapping.
- **Safe settlement flow** with amount and balance validation, freshness checks, duplicate detection, and atomic ledger updates.
- **Network monitor** for API health, response time, mesh state, and the packet-protection profile.

## How a request moves

```text
Browser queue → Encrypted demo packet → Virtual peer mesh → Bridge ingestion → Simulated ledger
```

The current project simulates the devices and network in one server process. It does not use Bluetooth or real payment infrastructure.

## Technology

| Area | Tools |
| --- | --- |
| Backend | Java 17, Spring Boot, Spring Data JPA |
| Frontend | HTML, CSS, JavaScript, Thymeleaf |
| Persistence | H2, Flyway |
| Offline browser storage | IndexedDB with localStorage fallback |
| Build and packaging | Maven Wrapper, Docker Compose |

## Run locally

**Requirements:** Java 17 or newer.

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

macOS or Linux:

```bash
./mvnw spring-boot:run
```

Then open [http://localhost:8080](http://localhost:8080). The local Maven run enables the writable simulator. Demo sign-in codes are generated locally; they are not sent by email or SMS.

### Run with Docker

```bash
docker compose up --build
```

Open [http://localhost:8080](http://localhost:8080). To run the guest-only read-only preview locally:

```bash
docker compose -f compose.yaml -f compose.preview.yaml up --build
```

## Public demo

The hosted version is configured for **guest access and read-only behavior**. It starts with simulated demo data; server-side payment, mesh, and OTP actions are disabled. The free host may sleep when idle, so its first request after inactivity can take longer.

## Project guides

- [Architecture](ARCHITECTURE.md)
- [API reference](API_DOCUMENTATION.md)
- [Offline payment flow](OFFLINE_PAYMENT_FLOW.md)
- [Database schema](DATABASE_SCHEMA.md)
- [Security notes and limitations](SECURITY.md)
- [Demo walkthrough](DEMO_SCRIPT.md)

## About

**Project by Ankush Yadav**
