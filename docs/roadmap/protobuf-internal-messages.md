# Protobuf Internal Messages Migration Reference Plan

## Status

- `Owner`: Platform team
- `Scope`: Internal runner commands and events
- `Current status`: In progress
- `Last updated`: 2026-02-19

## Goal

Migrate internal runner message serialization to Protobuf while keeping database payload storage as JSON for developer
experience.

## Key Decisions

1. Protobuf is the canonical internal contract.
2. Transport channels (commands/events) use Protobuf binary.
3. Database payloads are stored as ProtoJSON (JSON generated from Protobuf), not from runtime Kotlin classes.
4. Compatibility with existing production payloads is out of scope (no production backlog to migrate).
5. Future schema evolution is controlled at `.proto` level.
6. `InternalMessageEnvelope` uses typed `oneof` payload variants (no `google.protobuf.Any`).
7. Schema conventions are explicit and enforced (`IDV7`, timestamps, enums, optional semantics).

## Wire Conventions (Must Be Applied)

1. `IDV7` is transported as canonical lower-case string.
2. Timestamps use `google.protobuf.Timestamp` in UTC only.
3. Enums use explicit `*_UNSPECIFIED = 0` value and no semantic reuse of numeric values.
4. Field numbers are immutable once released; removed fields are marked `reserved`.
5. Optional semantics are explicit (`optional` for presence-sensitive scalars, otherwise documented default behavior).
6. Envelope and payload schemas include `schema_version` as integer for operational visibility.

## Scope

### In Scope

- `NodeStack`, `StackFrame`, `NodeState`, and dependent structures.
- Internal command and event message contracts.
- Runner serialization/deserialization paths.
- Outbox/repository integration for DB payload persistence.
- Validation and test coverage for round-trips and integration flows.

### Out of Scope

- Legacy payload migration tooling.
- Cross-version runtime compatibility with already persisted production messages.
- External/public API serialization changes.

## Architecture Target

1. Domain model remains independent from wire format.
2. `.proto` module defines all internal message contracts.
3. Dedicated mapping layer converts:
   - Domain -> Protobuf
   - Protobuf -> Domain
4. `InternalMessageEnvelope` carries metadata + typed payload via `oneof`.
5. Dedicated codec layer handles:
   - `BinaryProtoCodec` for messaging transport.
   - `ProtoJsonCodec` for DB storage/readability.
6. Repositories and messaging handlers use codec abstraction only.

## Milestones

| Milestone | Objective | Exit Criteria |
|---|---|---|
| M1 | Define canonical protobuf contracts | All targeted internal messages are modeled in `.proto` and generated in CI |
| M2 | Implement domain/proto mapping | No direct JSON serialization for targeted messages in runtime code |
| M3 | Integrate codecs in transport and persistence | Commands/events use binary protobuf; DB writes use ProtoJSON |
| M4 | Validate reliability and DX | Round-trip and integration tests green; DB payload remains readable JSON |
| M5 | Enable governance | CI check for protobuf breaking changes + documented schema rules |

## Implementation Backlog

### M1 - Protobuf Contracts

- [x] `PROTO-00` Create explicit message inventory (all producer/consumer paths) and keep it as a checked artifact.
- [x] `PROTO-01` Create module `lemline-messages-proto`.
- [x] `PROTO-02` Add protobuf build/codegen setup in Gradle.
- [x] `PROTO-03` Define `InternalMessageEnvelope` (`message_type`, `schema_version`, metadata, payload).
- [x] `PROTO-04` Define `NodeStack` and `StackFrame`.
- [x] `PROTO-05` Define `NodeState` and state variants via `oneof`.
- [x] `PROTO-06` Define internal command messages.
- [x] `PROTO-07` Define internal event messages.
- [x] `PROTO-07A` Add schema convention checks/review checklist (`IDV7`, timestamps, enums, optional fields).

### M2 - Mapping Layer

- [x] `PROTO-08` Implement domain -> proto mappers for core runner structures.
- [x] `PROTO-09` Implement proto -> domain mappers for core runner structures.
- [x] `PROTO-10` Add mapper unit tests for nullability/default-value edge cases.

### M3 - Codec Integration

- [x] `PROTO-11` Implement `BinaryProtoCodec`.
- [x] `PROTO-12` Implement `ProtoJsonCodec`.
- [x] `PROTO-13` Replace serialization in messaging commands path.
- [x] `PROTO-14` Replace serialization in messaging events path.
- [x] `PROTO-15` Update outbox/repository writes to persist ProtoJSON payloads.
- [x] `PROTO-16` Update outbox/repository reads to decode from ProtoJSON.

### M4 - Validation

- [x] `PROTO-17` Add round-trip tests: `domain -> proto -> protojson -> proto -> domain`.
- [x] `PROTO-18` Add integration tests for command flow.
- [x] `PROTO-19` Add integration tests for event flow.
- [x] `PROTO-20` Add payload readability checks for DB storage.
- [x] `PROTO-21` Add size/latency benchmark JSON vs protobuf transport.
- [x] `PROTO-21A` Define and validate benchmark target thresholds (payload size reduction and serialization latency budget).
- [x] `PROTO-21B` Validate integration tests on PostgreSQL, MySQL, and H2 for persistence paths.

### M5 - Governance

- [x] `PROTO-22` Define protobuf evolution rules in project docs.
- [x] `PROTO-23` Add CI check for protobuf breaking changes (Buf or equivalent).
- [x] `PROTO-24` Add contribution checklist for schema updates.
- [x] `PROTO-25` Add CI scan preventing legacy direct Jackson serialization on targeted internal messages.

## Definition of Done (Feature)

All conditions must be true:

1. Internal transport for commands/events uses binary protobuf.
2. Database payload storage for internal messages uses ProtoJSON generated from protobuf.
3. Runtime code no longer serializes these messages directly from Kotlin classes to JSON.
4. Tests cover round-trip and integration flows for targeted message paths.
5. Persistence-path integration tests pass on PostgreSQL, MySQL, and H2.
6. Benchmark thresholds are defined and met.
7. Schema governance and legacy-serialization CI checks run and pass.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Incomplete message inventory | Missing paths still use old serialization | Start with explicit inventory and enforce usage search in CI |
| Mapper drift from domain model | Runtime bugs and subtle state loss | Keep mappers centralized + targeted tests for each state variant |
| ProtoJSON assumptions mismatch | DB payload not readable/useful | Add readability assertions and golden examples |
| Schema changes without discipline | Future incompatibilities | Enforce evolution rules + CI breaking-change check |
| `proto3` default/presence ambiguity | Incorrect business semantics for absent fields | Use explicit `optional` where needed and mapper tests for presence behavior |

## Tracking Template

Use the following status format in weekly updates:

| Ticket | Status | Owner | Notes |
|---|---|---|---|
| PROTO-01 | TODO / IN_PROGRESS / BLOCKED / DONE | @owner | short note |

Use milestone summary format:

- `M1`: `% complete`, blockers, ETA
- `M2`: `% complete`, blockers, ETA
- `M3`: `% complete`, blockers, ETA
- `M4`: `% complete`, blockers, ETA
- `M5`: `% complete`, blockers, ETA

## Suggested PR Sequence

1. PR-1: `PROTO-00..07A` (inventory + protobuf module + schema contracts)
2. PR-2: `PROTO-08..10` (mapping layer)
3. PR-3: `PROTO-11..16` (codec + runner integration)
4. PR-4: `PROTO-17..21B` (tests + benchmark + 3-DB validation)
5. PR-5: `PROTO-22..25` (governance + docs + CI checks)
