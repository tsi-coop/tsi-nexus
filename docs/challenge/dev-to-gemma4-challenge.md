*This is a submission for the [Gemma 4 Challenge: Build with Gemma 4](https://dev.to/challenges/google-gemma-2026-05-06)*

## What I Built

TSI Nexus is a vertical-agnostic institutional intelligence platform. It offers a knowledge graph that stores every entity, relationship, rule etc, a liquid interface and connectors for other systems.

Liquid is the adaptive interface that brings the digital brain to life, surfacing exactly what you need at the right moment. Together, they turn complex institutional knowledge into simple, everyday actions.

Zero sector-specific logic is hardcoded. Every domain concept - entity types, terminology, policies, commands, templates - lives in database rows configured by the deploying institution.

## Demo

[Link to Demo Video / Live Deployment](https://youtu.be/vvCECWRBTms)

## Code

[\[GitHub Repository - Apache 2.0 License\]](https://techadvisory.substack.com/p/tsi-nexus-an-open-source-digital)

## How I Used Gemma 4

We needed a model that could parse complex institutional intent, enforce strict rules, and run entirely on-premise. Gemma 4 26B fit all three.

It handles two core jobs in TSI Nexus:

**Intent to command.** When a field user types a natural language request, Gemma 4 maps it to an exact registered command verb like `/disburse_loan` or `/verify_kyc`. No guessing, no drift.

**Policy evaluation.** Before any command runs, Gemma 4 checks it against the live context and business rules from the Context Graph. It either approves, blocks, or flags for escalation.

We run Gemma 4 27B locally so data stays fully on-premise, which is non-negotiable for institutional use cases. Gemma 4's instruction-following is what made this practical - earlier models would hallucinate field values or misroute commands. Gemma 4 holds the structure consistently, which matters when commands touch real business operations.
