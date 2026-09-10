# TSI Nexus Example Test Agents

This directory contains small headless agents that test a seeded TSI Nexus
instance through the Intelligence API.

The agents are domain-specific only in their prompts and expected workflows. The
client logic is shared, so each example exercises the same platform contract:

- resolve natural language through `/api/intent`
- load entity context through `/api/context`
- check guardrails through `/api/governance`
- discover available capture forms through `/api/capture`
- inspect entity and graph metadata through `/api/entities` and `/api/graph`

## Prerequisites

1. Start TSI Nexus.
2. Seed one of the domains from `docs/seed/`.
3. Create an API key from Admin UI -> API Keys with these scopes:

```json
["intent:read", "context:read", "governance:read", "capture:write"]
```

4. Export connection details:

```bash
export NEXUS_BASE_URL=http://localhost:8084
export NEXUS_API_KEY=nxs_your_key
export NEXUS_API_SECRET=your_secret
```

## Run

Run a specific domain:

```bash
python3 examples/agents/run_domain_agent.py microfinance
python3 examples/agents/run_domain_agent.py healthcare
python3 examples/agents/run_domain_agent.py manufacturing
python3 examples/agents/run_domain_agent.py edtech
python3 examples/agents/run_domain_agent.py services
python3 examples/agents/run_domain_agent.py hr_services
```

Or use the thin wrappers:

```bash
python3 examples/agents/microfinance_agent.py
python3 examples/agents/healthcare_agent.py
python3 examples/agents/manufacturing_agent.py
python3 examples/agents/edtech_agent.py
python3 examples/agents/services_agent.py
python3 examples/agents/hr_services_agent.py
```

## What Passes Mean

These examples are smoke-test agents, not full business test suites. A passing
run means the seeded domain can be reached by a headless assistant and Nexus can
resolve prompts, return institutional context, expose configured actions, and
evaluate guardrails.

The scripts choose a sample entity from `/api/entities`, so they do not depend on
fixed seed IDs.

