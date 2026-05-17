# TSI Nexus

A sovereign institutional intelligence platform for small and medium enterprises. It provides a private "company brain" - a single system that stores every entity, relationship, rule, and interaction, and surfaces them through a natural language interface.

Zero sector-specific logic is hardcoded. Every domain concept - entity types, terminology, policies, commands, templates - lives in database rows configured by the deploying institution.

Checkout our [soft launch announcement](https://techadvisory.substack.com/p/tsi-nexus-an-open-source-digital).

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


## How organisations use it

**Intent to command:** Field staff type natural language requests. TSI Nexus maps them to exact registered command verbs like `/disburse_loan` or `/verify_kyc`, with no ambiguity and no drift from institutional policy.

**Policy evaluation:** Before any command executes, TSI Nexus checks it against the live context and business rules stored in the knowledge graph. It either approves, blocks, or flags for escalation - keeping every action within the institution's defined guardrails.

## Architecture

See [`docs/architecture.md`](docs/architecture.md) for a full breakdown of the six pillars, service registry patterns, seeder pipeline, LLM integration points, and database schema.


## Getting started

Watch the [video walkthrough](https://youtu.be/vvCECWRBTms) for a full end-to-end demo before diving in.

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
| `TSI_NEXUS_JWT_SECRET` | **Required** | Generate with `openssl rand -hex 32`. Container refuses to start without it. |

### 2. Build and run

```bash
mvn package -q
docker compose up -d
```

Once running, the platform is accessible at these URLs (default port `8084`):

| Tool | URL | Purpose |
|---|---|---|
| Setup wizard | `http://localhost:8084/setup` | First-run account creation |
| Seed tool | `http://localhost:8084/seed` | Bootstrap a demo institution |
| Liquid interface | `http://localhost:8084/liquid` | End-user natural language search and forms |
| Admin UI | `http://localhost:8084/admin` | Configure entities, templates, policies, and services |

### 3. First-time setup

**Step 1 - Create your admin account**

Open `http://localhost:8084/setup` and complete the wizard.

**Step 2 - Explore the admin console**

Open `http://localhost:8084/admin`. This is where you configure entity types, context card templates, input forms, guardrails, and external service integrations.

**Step 3 - Seed your institution**

Open `http://localhost:8084/seed` and fill in the four steps: institutional context, entity types, commands, and simulation parameters. The seeder generates digital twins, interaction history, context cards, forms, policies, and mock service integrations in one pass.

Ready-to-use input values for common domains:

| Domain | Guide |
|---|---|
| Microfinance (JLG / rural lending) | [`docs/seed/microfinance.md`](docs/seed/microfinance.md) |
| Healthcare clinic | [`docs/seed/healthcare-clinic.md`](docs/seed/healthcare-clinic.md) |
| Manufacturing firm | [`docs/seed/manufacturing.md`](docs/seed/manufacturing.md) |
| EdTech platform | [`docs/seed/edtech.md`](docs/seed/edtech.md) |
| Professional services | [`docs/seed/services.md`](docs/seed/services.md) |

Each guide provides exact copy-paste text for every field in the seed form.

**Step 4 - Start the mock server**

After seeding, download `mock-data.json` from the seeding page, place it in `mock/`, then run:

```bash
javac mock/MockServer.java
java -cp mock MockServer
```

This starts a PULL server on port 9090 and a background INGEST push thread. Without this step, context cards in Liquid will have empty live-data fields.

**Step 5 - Explore in Liquid**

Open `http://localhost:8084/liquid` and start exploring your entities. Search by name or ID, view context cards with live external data, and submit forms to record actions.


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

See Step 4 in [First-time setup](#3-first-time-setup). The mock server (`mock/MockServer.java`) serves as a standalone PULL endpoint and INGEST push source - no real external systems needed for a full demo.


## Integrating external systems

TSI Nexus integrates via three patterns registered in the Admin UI:

| Pattern | Direction | Use case |
|---|---|---|
| **PULL** | Nexus → your system | Enrich entity context at read time (credit scores, live balances, HR data) |
| **PUSH** | Nexus → your system | Notify your system after a form is submitted |
| **INGEST** | Your system → Nexus | Push state updates into Nexus from an external source |

See [`docs/integration-guide.md`](docs/integration-guide.md) for the full API contract, request/response examples, and registration instructions.

For headless access to the intelligence API from external apps or AI agents, see [`docs/api-client-sdk.md`](docs/api-client-sdk.md).


## Project structure

```
src/          Java source (Jakarta EE, no framework dependencies)
web/          Frontend - admin UI and Liquid interface (plain HTML/JS)
db/           init.sql - full schema, applied on first DB start
mock/         MockServer.java - standalone mock PULL/INGEST server
docs/         architecture.md, integration-guide.md, api-client-sdk.md
```


## License & Contributions

This project is fully open-source and distributed under the **Apache 2.0 License**. You are completely free to fork, modify, and customize the codebase to fit your specific technical or enterprise needs without any restriction.

### Contributing Back to the Main Project
If you have built an optimization, bug fix, or feature extension that you believe would add value to the core platform, we would love to review it. To ensure the main repository remains highly stable and securely managed, direct commits to the `main` branch are restricted.

If you wish to give back your changes to the project, please follow this process:

* **Email the Repository Owner:** Send a brief summary of your modifications and a link to your code branch directly to **[admin@tsicoop.org]**.

Every contribution is manually evaluated for architectural alignment, readability, and long-term maintenance impact before integration. Thank you for respecting this workflow and helping us maintain a clean, resilient core!
