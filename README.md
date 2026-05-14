# TSI Nexus

A sovereign institutional intelligence platform for small and medium enterprises. It gives any organisation a private "company brain" - a single system that stores every entity, relationship, rule, and interaction, and surfaces them through a natural language interface.

Zero sector-specific logic is hardcoded. Every domain concept - entity types, terminology, policies, commands, templates - lives in database rows configured by the deploying institution.

---

## What it does

**For end users (the Liquid interface)**
- Search for any entity by name or ID using plain English
- View a live context card showing the entity's current state, graph relationships, and external data
- Submit structured forms (Input Manifests) to record actions and update state
- All actions are logged to an append-only interaction stream

**For admins (the Admin UI)**
- Define entity types, relationships, and their data shapes
- Build context cards (HTML templates with live variable substitution)
- Create input forms for data capture
- Configure guardrails (SQL policies that block or flag invalid actions)
- Register external service integrations (PULL / PUSH / INGEST)
- Seed a fully configured demo instance from a plain-English industry description

---

## Architecture

See [`docs/architecture.md`](docs/architecture.md) for a full breakdown of the six pillars, service registry patterns, seeder pipeline, LLM integration points, and database schema.

---

## Getting started

### Prerequisites
- Docker and Docker Compose
- A running LLM endpoint (vLLM or compatible OpenAI API)

### 1. Configure environment

Copy the example env file and edit it:

```
cp .env.example .env
```

Key variables:

| Variable | Default | Description |
|---|---|---|
| `VLLM_URL` | `http://192.168.1.77:8001` | Your LLM endpoint |
| `VLLM_MODEL` | `gemma-4-26B-A4B-it` | Model name |
| `APP_PORT_MAP` | `8084:8080` | Host port mapping |
| `DB_PORT_MAP` | `5436:5432` | Postgres port mapping |
| `POSTGRES_PASSWD` | `secure_dev_password` | Change for production |
| `TSI_NEXUS_JWT_SECRET` | *(dev default)* | Change for production |

### 2. Build and run

```bash
mvn package -q
docker compose up -d
```

The app is available at `http://localhost:8084` (or whichever port you mapped).

### 3. First-time setup

1. Open the app and complete the setup wizard to create your admin account
2. Go to **Admin → Seeding** and enter a plain-English description of your organisation (e.g. *"microfinance cooperative serving rural farmers"*)
3. The seeder generates entity types, sample data, context cards, forms, policies, and mock service integrations in one pass
4. Open the **Liquid** interface and start exploring your entities

---

## Development

### Build

```bash
mvn package
```

Output: `target/tsi_nexus.war`

### Run locally with Docker

```bash
docker compose up --build
```

### Database

The schema lives in `db/init.sql`. It is applied automatically on first container start. To reset:

```bash
docker compose down -v
docker compose up -d
```

### Mock external services

After seeding, download `mock-data.json` from the Seeding page and run the standalone mock server:

```bash
javac mock/MockServer.java
java -cp mock MockServer
```

This starts a PULL server on port 9090 that serves synthetic data for every registered entity type, and a scheduled INGEST push thread that sends periodic state updates into Nexus - no real external systems needed for a full demo.

---

## Integrating external systems

TSI Nexus integrates via three patterns registered in the Admin UI:

| Pattern | Direction | Use case |
|---|---|---|
| **PULL** | Nexus → your system | Enrich entity context at read time (credit scores, live balances, HR data) |
| **PUSH** | Nexus → your system | Notify your system after a form is submitted |
| **INGEST** | Your system → Nexus | Push state updates into Nexus from an external source |

See [`docs/integration-guide.md`](docs/integration-guide.md) for the full API contract, request/response examples, and registration instructions.

---

## Project structure

```
src/          Java source (Jakarta EE, no framework dependencies)
web/          Frontend - admin UI and Liquid interface (plain HTML/JS)
db/           init.sql - full schema, applied on first DB start
mock/         MockServer.java - standalone mock PULL/INGEST server
docs/         architecture.md, integration-guide.md
```

---

## License

See [LICENSE](LICENSE).
