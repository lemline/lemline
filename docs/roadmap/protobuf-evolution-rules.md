# Protobuf Evolution Rules (PROTO-22)

Scope: internal schemas under `lemline-messages-proto/src/main/proto/internal/`.

## Compatibility Contract

1. Never reuse a field number once published.
2. When removing a field, mark both its number and name as `reserved`.
3. Do not change field wire types in place.
4. Do not repurpose an existing field for a different semantic.

## Presence and Defaults

1. Use `optional` for scalar fields when absence/presence has semantic meaning.
2. For non-optional `proto3` scalars, document default behavior in mapper tests.
3. Keep domain nullability mapping explicit in `*ProtobufMapper` classes.

## Enums and Oneofs

1. Every enum must define `*_UNSPECIFIED = 0`.
2. Enum numeric values are immutable.
3. Use `oneof` for mutually exclusive payload variants and keep exhaustiveness in mappers.

## IDs and Timestamps

1. IDs (`IDV7`, workflow/node IDs) are encoded as canonical string values.
2. Time fields use `google.protobuf.Timestamp` and UTC semantics.

## Runtime Rules

1. Domain runtime models (`NodeStack`, `WorkflowState`, etc.) remain decoupled from generated protobuf classes.
2. Transport encoding uses protobuf envelope bytes (currently Base64-wrapped in string channels).
3. Database `workflow_state` storage uses ProtoJSON produced from protobuf schema.

## Review Requirement

Any protobuf schema change must include:

1. Mapper updates (`domain <-> proto`) and related tests.
2. A checklist pass against `docs/roadmap/protobuf-schema-checklist.md`.
3. Confirmation that the serialization guard test remains green.
