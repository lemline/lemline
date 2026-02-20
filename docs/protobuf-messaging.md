# Protobuf Messages Guide

This document defines how Lemline uses Protobuf for internal messages and which rules must be followed when evolving schemas.

## Scope

Applies to internal message schemas under:

- `lemline-messages-proto/src/main/proto/internal/`

## Canonical Contract

1. Protobuf is the canonical internal message contract.
2. Runner transport channels (commands/events) use Protobuf binary payloads.
3. Database payload storage uses ProtoJSON generated from Protobuf schemas.
4. Runtime domain models stay decoupled from generated Protobuf classes through dedicated mappers.

## Message Design Rules

### IDs and Time

1. Encode `IDV7` and other identifiers as canonical lowercase strings.
2. Use `google.protobuf.Timestamp` for time values.
3. Treat timestamps as UTC.

### Fields and Presence

1. Never reuse a published field number.
2. If a field is removed or renamed, reserve both the old number and name.
3. Do not change a field wire type in place.
4. Use `optional` for scalar fields when presence/absence has business meaning.
5. For non-optional `proto3` scalars, document default-value behavior in mapper tests.

### Enums and oneof

1. Every enum must define an explicit `*_UNSPECIFIED = 0` value.
2. Enum values must follow Buf naming rules (prefix with enum name, including `_PROTO_` when present).
3. Enum numeric values are immutable after release.
4. Use `oneof` for mutually exclusive payload variants.

## Runtime Mapping Rules

1. Keep all wire-domain conversion in mapper classes (for example `*ProtobufMapper`).
2. Make nullability/presence mapping explicit in both directions.
3. Keep mapping exhaustive for enum and `oneof` cases.
4. Preserve `ignoringUnknownFields()` behavior on ProtoJSON decode paths.

## Envelope and Versioning

1. Envelope and payload schemas expose `schema_version` for operational visibility.
2. Treat `schema_version` changes as schema lifecycle events and document them in PRs.

## CI and Validation

Run checks from the proto module directory:

```bash
cd lemline-messages-proto
buf lint
```

Expected safety net:

1. Buf lint and breaking checks in CI.
2. Mapper tests covering:
   - optional/absent-field round trips
   - enum and `oneof` exhaustiveness
   - ProtoJSON decode compatibility

## PR Checklist for Schema Changes

1. `.proto` updates are compatible with existing field-number and enum rules.
2. Required `reserved` entries are added for removed/renamed fields.
3. Mapper updates (`domain <-> proto`) are included.
4. Tests are updated for new fields/variants and presence behavior.
5. `buf lint` passes locally.
6. PR description explicitly calls out any intentional incompatible change.
