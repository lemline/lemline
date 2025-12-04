# Architectural Decision Records

This directory contains the Architectural Decision Records (ADRs) for the Lemline project.

## What is an ADR?

An Architectural Decision Record (ADR) is a document that captures an important architectural decision made along with its context and consequences.

## ADR Index

| ID | Title | Status |
|----|-------|--------|
| [ADR-0001](0001-modular-architecture.md) | Modular Architecture | Accepted |
| [ADR-0002](0002-workflow-execution-model.md) | Workflow Execution Model | Accepted |
| [ADR-0003](0003-messaging-architecture.md) | Messaging Architecture | Accepted |
| [ADR-0004](0004-database-storage-strategy.md) | Database Storage Strategy | Accepted |
| [ADR-0005](0005-error-handling-approach.md) | Error Handling Approach | Accepted |
| [ADR-0006](0006-logging-strategy.md) | Logging Strategy | Accepted |
| [ADR-0007](0007-config-strategy.md) | Config Strategy | Accepted |
| [ADR-0008](0008-fork-error-management.md) | Fork Error Management | Accepted |
| [ADR-0009](0009-dynamic-step-index.md) | Dynamic Step Index | Accepted |
| [ADR-0010](0010-emitting-events.md) | Emitting Events | Accepted |
| [ADR-0011](0011-listen-correlation-matching.md) | Listen Correlation Matching | Accepted |

## Creating a New ADR

1. Copy the template file `0000-adr-template.md` to a new file named `NNNN-title-with-hyphens.md`, where `NNNN` is the next available ADR number.
2. Fill in the template with the details of your architectural decision.
3. Update this README.md file to add your ADR to the index.
4. Submit a pull request for review.