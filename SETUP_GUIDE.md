# Setup guide

## Local run

1. Install JDK 17 or newer.
2. Open a terminal in this folder.
3. Run `.\mvnw.cmd spring-boot:run` in Windows PowerShell or `./mvnw spring-boot:run` on macOS/Linux.
4. Open `http://localhost:8080` and choose **Sign in**.
5. Enter a display name and email/phone, request the demo OTP, then enter the code displayed on the page. It is not sent externally.
6. Check `http://localhost:8080/api/health` for the simulation status.

H2 is file-backed under `./data` by default and survives app restarts. Override `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` for a different JDBC database driver. The local H2 console at `/h2-console` is for local development only.

## Docker Compose

Requires Docker Engine and Compose. Run `docker compose up --build`, then browse to `http://localhost:8080`. A named volume retains H2 data. `docker compose down` stops/removes containers while keeping data; `docker compose down -v` deletes that volume and resets it.

## Build and checks

- Compile: `.\mvnw.cmd -DskipTests compile`
- Tests: `.\mvnw.cmd test`
- Package: `.\mvnw.cmd clean package`

macOS/Linux equivalents use `./mvnw`.

For interview flow, demo account names, and talking points, see `DEMO_SCRIPT.md`. For endpoint payloads, see `API_DOCUMENTATION.md`.
