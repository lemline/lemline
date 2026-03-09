# Broker Message Encryption Specification

> **Document scope:** This document is an implementation specification, not a standalone ADR. The architectural
> decision to adopt application-layer envelope encryption belongs in a companion ADR (suggested: `ADR-0015-broker-
> message-encryption.md`) that records the decision, rationale, and consequences in short form. This document
> elaborates the full design and serves as the implementation contract. See §18 for acceptance criteria.

Status: Proposed

Last updated: 2026-03-09

Related documents:

- [../adr/0003-messaging-architecture.md](../adr/0003-messaging-architecture.md)
- [../runner-architecture.md](../runner-architecture.md)
- [../protobuf-messaging.md](../protobuf-messaging.md)

## 1. Problem Statement

Lemline currently sends internal broker messages as Base64-encoded Protobuf payloads. This is compatible with the
current Kafka, RabbitMQ, and PGMQ transports, but anyone with read access to the broker payloads can inspect workflow
state, inputs, outputs, and error details.

TLS and broker-side encryption at rest remain mandatory, but they are not sufficient for the following threat model:

- a broker administrator can inspect messages after TLS termination
- a broker backup or DLQ dump can expose payloads
- a PGMQ queue row can be read directly from PostgreSQL

The goal of this feature is to ensure that broker access alone is not enough to read Lemline internal messages.

## 2. Scope

This specification applies to Lemline internal broker channels:

- `commands-in` / `commands-out`
- `events-in` / `events-out`
- broker-adjacent persistence tables that store broker-bound internal messages before publication or after terminal
  failure, including outbox tables and failure payload storage

Optional future scope:

- `lifecycleevents-in` / `lifecycleevents-out`

Out of scope for v1:

- `cloudevents-*`, because it is an interoperability-facing channel
- database rows that do not persist broker-bound internal messages, including analytics storage and unrelated read-side
  tables
- protection against a compromised runner process, compromised KMS/Vault identity, or memory scraping on worker nodes

## 3. Goals

- Prevent broker operators from reading internal message payloads.
- Keep the design broker-agnostic across Kafka, RabbitMQ, and PGMQ.
- Preserve the current `String` transport payload model used by SmallRye emitters/subscribers.
- Use authenticated encryption so payload tampering is detected.
- Keep long-lived key material outside Lemline and outside the broker.
- Support key rotation without breaking decryption of older retained messages.
- Avoid one KMS round-trip per message on the hot path.

## 4. Non-Goals

- Hiding all metadata from the broker. Some routing and transport metadata will remain visible.
- Re-encrypting or rewrapping old broker messages already in retention.
- Replacing broker ACLs, TLS, mTLS, or secret-management policies.
- Encrypting only selected JSON fields. The feature protects the full internal payload.

## 5. Threat Model

The feature assumes an attacker can:

- read broker payloads, DLQs, queue tables, or broker snapshots
- replay or copy ciphertext
- inspect broker metadata such as topic, queue, channel, and message headers

The feature assumes the attacker cannot:

- read Lemline worker memory
- use Lemline service credentials against the key-management system
- extract key material from the KMS, HSM, or Vault transit backend

Replay is still handled by existing broker semantics and Lemline idempotency keys. This feature adds confidentiality and
integrity of payloads, not replay prevention.

## 6. Architectural Decision

Lemline should add application-layer envelope encryption above the existing transport codecs.

The broker must only see:

- an encrypted transport envelope
- message metadata required for routing and idempotency

The broker must never see:

- workflow input or output
- node stack or workflow state
- internal error details
- plaintext data-encryption keys
- master wrapping keys

The encrypted payload is produced after Protobuf serialization and before the current broker `String` emission. This
keeps the transport connectors unchanged.

## 7. Cryptographic Design

### 7.1 Algorithms

- Payload encryption algorithm: `AES-256-GCM`
- Nonce size: 96 bits
- Tag size: 128 bits (explicit; do not rely on provider or JVM defaults)
- Wrapping model: envelope encryption with one symmetric DEK wrapped by an external KEK

`AES-256-GCM` is the default because it is widely supported on the JVM, works well with managed KMS products, and
provides confidentiality and integrity in a single primitive.

### 7.2 Key Hierarchy

- `KEK` or `CMK`: long-lived wrapping key stored only in an external KMS, HSM, or Vault transit backend
- `DEK`: short-lived symmetric key used by Lemline to encrypt message payload bytes locally

Rules:

- KEKs are never stored in Lemline configuration.
- DEKs are never stored in the broker.
- Plaintext DEKs exist only transiently in producer and consumer memory.
- Wrapped DEKs are safe to store inside the message envelope.

### 7.3 Why Envelope Encryption

Direct broker-native encryption does not protect against broker administrators. Direct KMS encryption per message would
add avoidable latency and cost to the hot path. Envelope encryption is the standard compromise:

- KMS protects the long-lived wrapping keys
- Lemline performs local AES-GCM on the payload
- KMS is used only to create or unwrap short-lived DEKs

## 8. Message Format

### 8.1 Transport Encoding

To remain compatible with the existing `MutinyEmitter<String>` usage, the encrypted transport stays ASCII-safe:

1. Serialize the internal `InstanceMessageProto` to bytes.
2. Encrypt those bytes into a transport envelope.
3. Encode the envelope as Base64.
4. Prefix the final payload with `tpb64:`.

Legacy cleartext payloads remain the current unprefixed Base64 format during migration.

### 8.2 Proposed Protobuf Envelope

```proto
message TransportEnvelopeProto {
    int32 schema_version = 1;
    oneof body {
        InstanceMessageProto cleartext = 2;
        EncryptedInstanceMessageProto encrypted = 3;
    }
}

message EncryptedInstanceMessageProto {
    string encryption_version = 1;   // "lemline.message-envelope.v1"
    string key_provider = 2;         // "aws-kms", "vault-transit", "local-static"
    string key_id = 3;               // Opaque KEK reference used for unwrapping
    optional uint32 key_version = 4; // Provider-specific version when available
    bytes wrapped_dek = 5;           // Wrapped DEK returned by the provider
    bytes nonce = 6;                 // 12 bytes
    bytes ciphertext = 7;            // AES-GCM output over InstanceMessageProto bytes
}
```

`cleartext` exists only for migration compatibility and testing. Production emitters must use `encrypted` whenever
encryption is enabled.

The transport `messageId` remains in broker metadata, not in the encrypted envelope. It is assigned by the producer
before encryption and recovered by the consumer before decryption.

The `cleartext` field is deprecated as of this version and must be removed from the schema in a subsequent v2 envelope
revision once all deployments have completed the rollout described in §16. Until then, consumers must reject cleartext
envelopes when the mode is `required` and must not log the payload contents.

`encryption_version` versioning contract:

- `lemline.message-envelope.v1`: AES-256-GCM with explicit 128-bit tag, 96-bit nonce, wrapped DEK, per-message AAD as
  defined in §8.3
- A new version string is required when any of the following change: cipher suite, tag size, AAD construction, or
  envelope field layout in a breaking way
- Consumers must reject versions they do not recognise rather than attempting a best-effort decode

### 8.3 Additional Authenticated Data

Each AES-GCM operation must bind the ciphertext to transport metadata using Additional Authenticated Data, built from:

- transport `messageId` (see PGMQ note below)
- logical channel name such as `commands` or `events`
- `TransportEnvelopeProto.schema_version`
- `EncryptedInstanceMessageProto.encryption_version`
- `key_provider`
- `key_id`
- `SHA-256(wrapped_dek)`

This ensures that changing message headers, swapping envelopes, or mutating key metadata causes decryption failure.

**Canonical AAD serialization:** Fields must be concatenated in the order listed above, each encoded as its UTF-8 byte
representation, separated by a single `0x00` byte delimiter. The exception is `schema_version`, which is encoded as a
4-byte big-endian integer before the delimiter, and `SHA-256(wrapped_dek)`, which is appended as its 32 raw bytes
with no trailing delimiter. The resulting byte sequence is passed directly as the AEAD AAD argument. Any change to
this encoding constitutes an `encryption_version` bump.

**PGMQ note:** PGMQ may expose a server-assigned internal row ID, but that identifier is not used for cryptographic
binding. Lemline already assigns a producer-side `messageId` before publication and stores it in transport metadata for
all supported brokers. For PGMQ, the producer-assigned `messageId` carried in the headers JSON is the transport
`messageId` used in AAD. For Kafka and RabbitMQ, the same producer-assigned `messageId` is written to the native
message metadata. All transports therefore share the same contract: the producer assigns `messageId` before encryption,
and the consumer must recover the same value from transport metadata before decryption.

### 8.4 KMS Encryption Context

The KMS or Vault wrapping request should use a stable, non-secret context so DEKs can be cached safely. The context must
not contain workflow data because provider logs may record it.

Recommended context fields:

- `system=lemline`
- `purpose=broker-message`
- `environment=<deployment-environment>`
- `channel=<commands|events|lifecycleevents>`
- `scope=<opaque-key-scope-id>`

This provider context is distinct from AEAD Additional Authenticated Data:

- provider context is stable for DEK reuse
- AEAD AAD is per message

## 9. Key Management Model

### 9.1 Recommended Key Scope

Recommended default: one KEK per environment and per logical channel scope, with namespace isolation when needed.

Recommended policy levels:

- minimum: one KEK per environment and channel class
- recommended for multi-tenant deployments: one KEK per environment, namespace, and channel class
- not recommended: one KEK per workflow, because operational overhead is too high

`key_id` values in the broker payload must be opaque. They must not embed business names such as workflow namespace or
customer identifiers. The mapping from namespace to `key_id` belongs in configuration or KMS metadata, not in the
message.

### 9.2 Who Must Know What

| Actor | Must know | Must not know |
|------|------|------|
| Producer identity | provider endpoint, auth identity, target `key_id` or key scope, plaintext payload, transient plaintext DEK | KEK material, unrelated key scopes |
| Consumer identity | provider endpoint, auth identity, envelope `key_id`, wrapped DEK, transient plaintext DEK after unwrap | KEK material, unrelated key scopes |
| Broker | ciphertext, channel, message headers, wrapped DEK, opaque `key_id` | plaintext payload, plaintext DEK, KEK material |
| Security operator | key policies, aliases, scope mapping, rotation schedule | plaintext payload by default |
| Break-glass operator | same as security operator plus audited decrypt tooling when explicitly granted | standing access to all runtime keys |
| KMS or Vault | KEK material and wrap/unwrap policy | workflow payload when using envelope encryption |

### 9.3 Required Permissions

The model is capability-based, not secret-sharing-based:

- publish-only identity: permission to obtain encryption materials for the configured scope
- read-only identity: permission to unwrap the DEK for the configured scope
- publish-and-read identity: both permissions

In Lemline, the same worker deployment often both publishes and consumes on internal channels. In that case the runtime
identity will need both capabilities for the channels it handles.

### 9.4 Provider Mapping

Recommended provider implementations:

- `vault-transit`
- `aws-kms`
- `gcp-kms` (future; GCP Cloud KMS symmetric key, `CryptoKeyVersions.encrypt` / `CryptoKeyVersions.decrypt`)
- `azure-kv` (future; Azure Key Vault keys, `wrapKey` / `unwrapKey` operations)
- `local-static`: **test and local development only.** Key material is a 32-byte AES key read from configuration
  property `lemline.messaging.encryption.local-static.key` (Base64-encoded). A startup guard must reject this
  provider when `quarkus.profile` is `prod`, or when
  `lemline.messaging.encryption.local-static.allow-in-production` is absent or `false` (the default). The guard
  must cause a hard startup failure, not a warning.

Provider-specific examples:

- AWS KMS producer role: `GenerateDataKey` on the configured CMK, constrained by encryption context
- AWS KMS consumer role: `Decrypt` on the configured CMK, constrained by encryption context
- Vault Transit producer role: permission to generate a data key for the configured transit key
- Vault Transit consumer role: permission to decrypt wrapped DEKs for the configured transit key
- GCP KMS producer role: `cloudkms.cryptoKeyVersions.useToEncrypt` on the configured key ring key
- GCP KMS consumer role: `cloudkms.cryptoKeyVersions.useToDecrypt` on the configured key ring key

The production spec does not require raw symmetric keys to be exported to YAML, environment variables, or broker
headers.

## 10. Publish Flow

For each message publication:

1. Resolve the logical encryption scope from the message and channel.
2. Read encryption materials from a local cache keyed by scope.
3. On cache miss, call the key provider to obtain a fresh plaintext DEK and wrapped DEK.
4. Serialize the `InstanceMessageProto` to bytes.
5. Build AEAD AAD from the message metadata.
6. Encrypt locally with `AES-256-GCM`.
7. Build `TransportEnvelopeProto.encrypted`.
8. Emit `tpb64:` plus the Base64-encoded envelope bytes.

Rules:

- there is no cleartext fallback on provider failure
- if no cached DEK is available and the provider is unavailable, publishing fails closed and the message is retried
- wrapped DEKs may be reused across multiple messages during the DEK cache lifetime

## 11. Read Flow

For each incoming message:

1. Read broker metadata, including `messageId`.
2. If the payload does not start with `tpb64:`, treat it as legacy cleartext only when compatibility mode allows it.
3. Decode `TransportEnvelopeProto`.
4. Extract `key_provider`, `key_id`, and `wrapped_dek`.
5. Look up the plaintext DEK in a local decrypt cache keyed by `SHA-256(wrapped_dek)`.
6. On cache miss, call the key provider to unwrap the DEK.
7. Rebuild the AEAD AAD from the message metadata and envelope metadata.
8. Decrypt with `AES-256-GCM`.
9. Deserialize the resulting bytes into `InstanceMessageProto`.

Rules:

- if decryption fails, the message must be treated as corrupted or unauthorized
- failure handling must not log plaintext payloads or plaintext DEKs
- ciphertext may be retained in failure storage, but plaintext must not be reconstructed in logs

## 12. DEK Caching and Nonce Management

### 12.1 Producer-Side Cache

To avoid one provider round-trip per message, producers should cache encryption materials with both a time limit and a
usage limit.

Recommended defaults:

- DEK TTL: `PT5M`
- maximum messages per DEK: `100000`

JVM zeroization note: plaintext DEK `ByteArray` values must be overwritten with zeroes immediately after use or
eviction. Due to JVM garbage collection, zeroization is best-effort and does not provide a hard guarantee; the intent
is to reduce the in-memory exposure window. This is a known constraint of JVM-based implementations.

### 12.2 Consumer-Side Cache

Consumers should cache unwrapped DEKs by wrapped-DEK digest to reduce decrypt calls for bursty workloads.

Recommended defaults:

- cache TTL aligned with broker retention expectations and local memory budget
- LRU eviction
- best-effort zeroization of evicted `ByteArray` content

### 12.3 Nonce Generation

Nonce reuse with the same DEK is forbidden.

Recommended implementation:

- generate one cached DEK per producer process, not shared across nodes
- for each cached DEK, create a 32-bit random prefix and a 64-bit monotonic counter
- build nonce as `prefix || counter`
- retire the DEK before counter exhaustion

This avoids random-collision risk while keeping the implementation deterministic and cheap.

**DEK cache persistence:** The DEK cache must be in-memory only and must never be serialised to disk, shared across
processes, or persisted across restarts. A process restart always starts with an empty cache, which triggers a fresh
provider call on the first publish. This is the structural guarantee that a restarted process cannot reuse a
previously-seen DEK with its counter reset to zero against a prefix that was already used before the restart.

## 13. Rotation and Revocation

### 13.1 DEK Rotation

DEKs rotate automatically through cache expiry and usage caps. No broker-side re-encryption is required.

### 13.2 KEK Rotation

KEKs must be rotated in the external provider according to the platform security policy.

Recommended policy:

- separate keys for each environment
- regular scheduled rotation, at least every 90 days unless provider or compliance policy requires shorter intervals
- old key versions remain decrypt-capable until the maximum broker retention window has elapsed

### 13.3 Revocation

If a scope is compromised:

1. stop granting new encrypt permission for that scope
2. rotate the KEK for the scope
3. keep old versions decryptable only as long as operationally required
4. remove old decrypt rights after broker retention and DLQ retention have expired

**DLQ recovery before KEK retirement:** Messages in DLQs may be encrypted under wrapped DEKs that require an older
KEK version. Before retiring a KEK version, operators must:

1. drain or re-process all DLQ messages encrypted under DEKs wrapped by the retiring KEK version
2. confirm via KMS or Vault audit logs that no unwrap calls against the retiring version remain active
3. only then revoke decrypt rights for the retiring KEK version

If a DLQ message cannot be re-processed before a KEK version must be retired for compliance reasons, the message is
permanently unrecoverable. This outcome must be explicitly accepted and recorded in the incident runbook rather than
discovered at decryption time.

## 14. Configuration Model

Proposed configuration shape:

```yaml
lemline:
  messaging:
    encryption:
      mode: optional
      provider: vault-transit
      environment: prod
      scope-strategy: namespace-and-channel
      dek:
        ttl: PT5M
        max-messages: 100000
      keys:
        commands:
          default: msg-prod-cmd-a1
        events:
          default: msg-prod-evt-b4
      vault-transit:
        mount-path: transit
```

Mode semantics:

- `disabled`: emit cleartext, accept cleartext only
- `optional`: emit encrypted, accept encrypted and legacy cleartext
- `required`: emit encrypted, reject cleartext

`scope-strategy` values:

- `channel`: one KEK per channel class (`commands`, `events`). Minimum isolation; suitable for single-tenant
  deployments.
- `environment-and-channel`: one KEK per environment and channel class. Recommended default.
- `namespace-and-channel`: one KEK per namespace and channel class. Required for multi-tenant deployments where
  namespace isolation is a contractual requirement. Namespace is resolved from the workflow definition namespace
  field in the message, not from Quarkus profile or routing metadata.

Operational rules:

- `optional` is a rollout mode, not a security steady state
- no mode may silently downgrade to cleartext because the provider is unavailable

**Outbox interaction:** The outbox pattern stores unsent messages in a database table before broker publication.
Outbox rows must store the ciphertext envelope, not plaintext. Encryption must occur before the row is written to the
outbox table. When the outbox scheduler flushes a row, the wrapped DEK embedded in the envelope is used directly;
no re-encryption occurs at flush time. If the KEK version used at write time has since been retired, the unwrap call
at flush time will fail and the row must be moved to a failure table for manual review. The DEK TTL and usage cap
govern only the in-memory plaintext DEK cache; they do not affect already-encrypted outbox rows.

## 15. Operational Requirements

- TLS and broker authentication remain required.
- KMS or Vault access must use least-privilege service identities.
- Provider audit logs must be enabled.
- Lemline logs must never print plaintext payloads, plaintext DEKs, or raw provider credentials.

**Metrics** (Micrometer / MicroProfile Metrics, `lemline.messaging.crypto.*` prefix):

| Metric | Type | Tags | Purpose |
|--------|------|------|---------|
| `lemline.messaging.crypto.encrypt.latency` | timer | `channel` | Hot-path encrypt time (cache hit path only) |
| `lemline.messaging.crypto.decrypt.latency` | timer | `channel` | Hot-path decrypt time (cache hit path only) |
| `lemline.messaging.crypto.dek.cache.hit` | counter | `side=producer\|consumer` | Tracks KMS call avoidance rate |
| `lemline.messaging.crypto.dek.cache.miss` | counter | `side=producer\|consumer` | Each miss is a KMS round-trip |
| `lemline.messaging.crypto.provider.failure` | counter | `provider`, `operation=wrap\|unwrap` | Alert: any increment in `required` mode |
| `lemline.messaging.crypto.decryption.failure` | counter | `reason=aad_mismatch\|tag_invalid\|key_unavailable` | Alert: rate above baseline may indicate tampering |

Alerting recommendation: page on any `provider.failure` increment when mode is `required`; alert on
`decryption.failure` rate exceeding baseline.

**Latency budget:** The expected p99 overhead per message on a cached-DEK path is under 1 ms for AES-256-GCM on
modern JVM hardware. A KMS round-trip on cache miss adds provider-specific latency, typically 5–50 ms depending on
region and provider. Alert if `encrypt.latency` p99 exceeds 5 ms on the hot path, which indicates cache misses.

**Break-glass tooling:** A separate CLI binary (`lemline-decrypt-tool`) must be provided for authorised incident
response. Requirements:

- authenticates with a dedicated IAM role or Vault policy that has decrypt-only access, separate from the runtime
  identity
- accepts a raw broker payload (ciphertext envelope, `tpb64:`-prefixed or raw Base64) from stdin or a file path
- outputs plaintext `InstanceMessageProto` as human-readable JSON to stdout
- every invocation is recorded in the KMS or Vault audit log with operator identity and timestamp
- the tool must not be bundled with the main runner binary; it is a separate artifact requiring explicit deployment
  approval
- access requires a break-glass IAM role activation that itself generates an audit event (e.g. AWS IAM role
  assumption with MFA, or Vault `auth/approle` with a short-lived secret ID)

## 16. Compatibility and Rollout

Recommended rollout:

1. ship reader support for both legacy and encrypted payloads
2. enable `optional` mode cluster-wide
3. enable encrypted publishing on `commands-*`
4. enable encrypted publishing on `events-*`
5. wait until the broker retention window for legacy cleartext has elapsed; exit criterion: the oldest unprocessed
   message timestamp on each channel is newer than the timestamp of the last cleartext-mode publish, confirmed by
   consumer-group lag tooling (e.g. `kafka-consumer-groups.sh`, RabbitMQ management API, or PGMQ queue stats); no
   `decryption.failure{reason=aad_mismatch}` spike should occur when `required` mode is enabled in the next step
6. switch all consumers to `required`

This rollout prevents mixed-version workers from breaking while still converging to encrypted-only transport.

## 17. Implementation Outline for Lemline

### 17.1 Protobuf

Add a new transport envelope schema under `lemline-messages-proto`, for example:

- `lemline-messages-proto/src/main/proto/internal/workflow/transport.proto`

### 17.2 Common Messaging Layer

Add broker-agnostic crypto abstractions in `lemline-runner-common`:

- `MessageCryptoService`
- `MessageKeyProvider`
- `EncryptionMaterialsCache`
- `DecryptionKeyCache`
- `TransportEnvelopeCodec`

### 17.3 Existing Codec Integration

Refactor `InstanceMessageCodec` into two layers:

- Protobuf serialization of `InstanceMessageProto` bytes
- transport protection that can emit legacy cleartext or encrypted envelopes

This keeps database ProtoJSON paths unchanged and limits the feature to broker transport.

### 17.4 Runtime Integration Points

Expected touch points:

- `lemline-runner-common/src/main/kotlin/com/lemline/runner/common/messaging/InstanceMessageCodec.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/MessageEmitter.kt`
- `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/MessageMetaData.kt`
- command and event deserialization paths in the runner handlers
- `lemline-runner-config` for encryption configuration and provider wiring

### 17.5 Testing

Required tests:

- envelope encode and decode round-trip for all `TransportEnvelopeProto` body variants (`cleartext` and `encrypted`)
- decryption failure on AAD mismatch, with each AAD field mutated in isolation to confirm binding
- decryption failure on truncated ciphertext and on incorrect tag size
- key rotation: consumer must successfully decrypt a message produced under a previous DEK after the producer has
  rotated to a new DEK
- mixed-mode rollout: `optional` producer with `required` consumer must be rejected; `optional` on both sides must
  accept both legacy and encrypted payloads
- in-memory transport with `local-static` provider: the default unit-test profile must exercise the full
  encrypt/decrypt path, not bypass it; this catches codec regressions without requiring a broker
- Kafka, RabbitMQ, and PGMQ integration tests covering the full publish/read flow
- PGMQ message ID binding: confirm the producer-assigned `messageId` in transport headers is what the consumer uses to
  reconstruct AAD, and that a mismatched ID causes decryption failure
- outbox flush: encrypt a message, clear the DEK cache, then flush the outbox row and confirm the embedded wrapped
  DEK is unwrapped and decryption succeeds
- log capture assertion: confirm that no failure path emits plaintext payload bytes or plaintext DEK bytes to any
  log appender

## 18. Acceptance Criteria

The feature is considered complete and shippable when all of the following are true:

- [ ] All tests listed in §17.5 pass on CI across Kafka, RabbitMQ, PGMQ, and in-memory profiles.
- [ ] `lemline-decrypt-tool` exists as a separate artifact and can decrypt a test ciphertext envelope using
  `local-static`.
- [ ] `local-static` provider causes a hard startup failure when `quarkus.profile=prod`.
- [ ] All six metrics in §15 are emitted and visible in a local Prometheus scrape or MicroProfile metrics endpoint.
- [ ] No log-capture test emits plaintext payload or plaintext DEK bytes under any failure condition.
- [ ] The rollout sequence in §16 has been validated end-to-end in a staging environment, including the `required`
  mode cutover step.
- [ ] The `cleartext` field in `TransportEnvelopeProto` is marked deprecated with a TODO referencing the v2 cleanup
  milestone.
- [ ] Architecture review sign-off confirming the AAD serialization format and transport `messageId` binding contract.

## 19. Rejected Alternatives

### 19.1 TLS Only

Rejected because TLS protects transport hops, not broker-side visibility after TLS termination.

### 19.2 Broker-Native At-Rest Encryption Only

Rejected because broker administrators or queue-table readers can still inspect plaintext after the broker decrypts or
reads its own storage.

### 19.3 Direct KMS Encryption of Whole Payloads

Rejected because it makes the hot path depend on a remote provider call per message and does not fit Lemline's
high-throughput command flow.

### 19.4 Field-Level Encryption

Rejected because too much sensitive workflow context would remain exposed, and mapper complexity would increase
substantially.

### 19.5 Per-Consumer Asymmetric Encryption

Rejected for v1 because Lemline internal channels are consumed by service roles, not human users, and per-consumer key
fan-out would add operational complexity without clear benefit for the current internal topology.

## 20. Best-Practice Summary

The steady-state recommendation for production is:

- application-layer envelope encryption on all internal broker channels
- KEKs stored only in KMS, HSM, or Vault transit
- opaque key identifiers in broker payloads
- least-privilege publish and read permissions
- short-lived cached DEKs
- authenticated encryption with message metadata bound as AAD
- no automatic cleartext fallback
- encrypted-only mode after migration

## 21. External References

These references informed the proposed design and match the industry-standard model used for broker-adjacent systems:

- AWS KMS envelope encryption and data key generation:
  [https://docs.aws.amazon.com/kms/latest/APIReference/API_GenerateDataKey.html](https://docs.aws.amazon.com/kms/latest/APIReference/API_GenerateDataKey.html)
- AWS KMS decrypt API for wrapped data keys:
  [https://docs.aws.amazon.com/kms/latest/APIReference/API_Decrypt.html](https://docs.aws.amazon.com/kms/latest/APIReference/API_Decrypt.html)
- HashiCorp Vault Transit secrets engine:
  [https://developer.hashicorp.com/vault/docs/secrets/transit](https://developer.hashicorp.com/vault/docs/secrets/transit)
- HashiCorp Vault Transit API for data keys:
  [https://developer.hashicorp.com/vault/api-docs/secret/transit#generate-data-key](https://developer.hashicorp.com/vault/api-docs/secret/transit#generate-data-key)
- Apache Kafka security overview:
  [https://kafka.apache.org/documentation/#security_ssl](https://kafka.apache.org/documentation/#security_ssl)
- RabbitMQ TLS support:
  [https://www.rabbitmq.com/docs/ssl](https://www.rabbitmq.com/docs/ssl)

Inference explicitly applied from those sources: TLS and broker-side security remain necessary, but payload secrecy from
broker operators requires application-layer encryption because the broker terminates transport security and still handles
message bytes.
