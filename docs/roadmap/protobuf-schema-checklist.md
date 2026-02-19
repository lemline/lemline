# Protobuf Schema Review Checklist (PROTO-07A)

Use this checklist for every change under `lemline-messages-proto/src/main/proto/internal/`.

## Field Rules

- [ ] New field numbers are unique and never reuse an existing number.
- [ ] Removed/renamed fields are marked as `reserved` (number and name).
- [ ] Presence-sensitive scalar fields use `optional` in `proto3`.
- [ ] Default-value behavior for non-optional fields is documented in mapper tests.

## Type Rules

- [ ] IDs (`IDV7`, workflow/node ids) are encoded as canonical string.
- [ ] Timestamps use `google.protobuf.Timestamp` in UTC.
- [ ] `oneof` is used for mutually exclusive payload/state variants.
- [ ] Enums define an explicit `*_UNSPECIFIED = 0` value.

## Evolution Rules

- [ ] Existing field numbers are not repurposed for a different semantic.
- [ ] Backward-incompatible changes are explicitly called out in PR description.
- [ ] Mapper tests include at least one optional/absent-field round-trip case.
- [ ] ProtoJSON decode path keeps `ignoringUnknownFields()` enabled.
