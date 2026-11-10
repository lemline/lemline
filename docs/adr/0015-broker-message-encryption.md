# [ADR-0015] Broker Message Encryption

## Status

Proposed

## Context

Lemline currently transports internal workflow messages over broker channels as Base64-encoded Protobuf payloads. This
works across Kafka, RabbitMQ, and PGMQ, but anyone with broker read access can inspect workflow state, inputs, outputs,
and internal error details.

TLS and broker-side encryption at rest remain required, but they do not prevent a broker operator, DLQ dump reader, or
PGMQ table reader from seeing plaintext message contents after transport termination. Lemline needs an application-layer
protection mechanism that preserves the existing broker-agnostic transport model.

## Decision

We adopt application-layer envelope encryption for Lemline internal broker messages.

The decision applies to:

1. Internal broker channels: `commands-*` and `events-*`.
2. Broker-adjacent persistence that stores broker-bound internal messages before publication or after terminal failure,
   including outbox rows and failure payload storage.

The design principles are:

1. Serialize the existing internal Protobuf message first, then encrypt it before broker publication or broker-adjacent
   persistence.
2. Use `AES-256-GCM` for payload encryption with authenticated encryption semantics.
3. Use envelope encryption with a short-lived DEK and a long-lived KEK stored only in an external KMS, HSM, or Vault
   Transit backend.
4. Bind ciphertext integrity to transport metadata using the existing producer-assigned transport `messageId`, not a
   new envelope-specific correlation identifier.
5. Keep broker connectors broker-agnostic and preserve the current `String` payload transport model.
6. Support rollout with compatibility modes (`disabled`, `optional`, `required`), with encrypted-only transport as the
   target steady state.
7. Fail closed on key-provider failures. There is no automatic downgrade to cleartext when encryption is enabled.

The full implementation contract, message envelope shape, key-management model, rotation policy, and rollout procedure
are specified in `docs/features/broker-message-encryption.md`.

## Consequences

### Positive

1. Broker access alone is no longer sufficient to read Lemline internal message contents.
2. The design remains portable across Kafka, RabbitMQ, and PGMQ.
3. Existing transport connectors can remain structurally unchanged because encryption happens above the transport layer.
4. The same protection model covers broker-bound persistence such as outbox rows, reducing plaintext exposure outside
   the hot path.

### Negative

1. Runtime complexity increases: key-provider integrations, DEK caching, envelope codecs, metrics, and operational
   failure handling must be implemented.
2. Key rotation and retention windows become operationally coupled, especially for DLQ recovery and encrypted outbox
   rows.
3. Break-glass decryption tooling and stronger audit requirements become mandatory for operations.
4. Message processing can fail for security reasons that did not exist before, such as AAD mismatch, missing keys, or
   revoked decrypt permission.

### Neutral

1. TLS, broker ACLs, and secret-management policies remain required; this decision complements them rather than
   replacing them.
2. Public interoperability-facing channels such as `cloudevents-*` remain outside the v1 scope.

## Alternatives Considered

1. TLS only.
   - Rejected: protects the transport hop, not broker-side visibility after TLS termination.

2. Broker-native encryption at rest only.
   - Rejected: does not prevent broker operators or queue-table readers from inspecting plaintext handled by the broker.

3. Direct KMS encryption of each full payload.
   - Rejected: adds a remote provider dependency to every message on the hot path and does not fit Lemline throughput
     goals.

4. Field-level encryption inside workflow payloads.
   - Rejected: leaves too much workflow context exposed and adds high mapper complexity.

5. A new envelope-specific `correlation_id` for AAD binding.
   - Rejected: Lemline already assigns a transport `messageId` used across brokers; reusing it keeps the integrity
     model aligned with existing transport semantics.

## References

1. [docs/features/broker-message-encryption.md](../features/broker-message-encryption.md)
2. [0003-messaging-architecture.md](0003-messaging-architecture.md)
3. [0004-database-storage-strategy.md](0004-database-storage-strategy.md)
4. [0007-config-strategy.md](0007-config-strategy.md)
