# Gateway configuration endpoint

Status: implemented (gateway `GetGatewayConfig`, dashboard Settings page)

## 1. Why

The dashboard Settings page shows the configuration the dashboard runs with. Today it can only show
what the browser knows: the gateway URL read from `/config.js`, and the namespace and theme saved in
the browser. Most deployment problems come from the gateway side instead:

- the dashboard origin is missing from `lemline.gateway.cors.origins`, so every call fails in the browser;
- authentication is enabled, but the dashboard sends no token;
- TLS is enabled with the wrong certificate, or disabled where it is expected;
- a namespace pin (`lemline.namespaces`) hides namespaces the operator expected to see;
- an environment variable silently overrides a value the operator edited in `lemline.yaml`.

An operator diagnoses all of these by reading the server's configuration and asking "where does this
value come from?". The gateway knows both answers; it must expose them, read-only.

## 2. Scope

In scope:

- every key under `lemline.gateway.*` (see `LemlineConfiguration.GatewayConfig`);
- `lemline.namespaces` (the namespace pin), because it changes what the gateway serves;
- for each key: its effective value, its origin, and the value it would have without that origin's
  override when relevant (see §4.3);
- the external configuration file, when one is loaded: its path and its full text with secrets
  redacted (see §4.5).

Out of scope:

- changing configuration at runtime (the endpoint never writes);
- non-gateway settings (database, messaging, runners). They may be added later with the same message
  shape, behind the same scope;
- the dashboard's own `config.js` (the browser already knows it).

## 3. API

A new unary RPC on `GatewayService`, following the existing `Get*` convention. The canonical
definition, with field comments, is in `lemline-runner-gateway/src/main/proto/lemline/gateway/v1/gateway.proto`.

```proto
rpc GetGatewayConfig(GetGatewayConfigRequest) returns (GetGatewayConfigResponse);

message GetGatewayConfigRequest {}

message GetGatewayConfigResponse {
    repeated GatewaySetting settings = 1;  // lemline.gateway.* and lemline.namespaces, sorted by key
    optional ConfigFile config_file = 2;   // absent when no file was loaded
    google.protobuf.Timestamp loaded_at = 3;
}

message GatewaySetting {
    string key = 1;
    optional string value = 2;             // absent when unset, or for a secret
    SettingOrigin origin = 3;
    string origin_detail = 4;              // env var name, system property, or file path
    optional string default_value = 5;     // absent for a secret
    bool secret = 6;
    bool is_set = 7;
    repeated SettingOrigin shadowed = 8;   // lower-priority origins hidden by `origin`
}

enum SettingOrigin {
    SETTING_ORIGIN_UNSPECIFIED = 0;
    SETTING_ORIGIN_SYSTEM_PROPERTY = 1;    // -Dkey=value, or a CLI option mapped to it
    SETTING_ORIGIN_ENVIRONMENT = 2;
    SETTING_ORIGIN_CONFIG_FILE = 3;
    SETTING_ORIGIN_APPLICATION = 4;        // application.* packaged with Lemline
    SETTING_ORIGIN_DEFAULT = 5;
    SETTING_ORIGIN_UNSET = 6;
    SETTING_ORIGIN_COMMAND = 7;            // forced by the Lemline command (gateway start) or its options
}

message ConfigFile {
    string path = 1;
    ConfigFileLocator located_by = 2;
    optional string text = 3;              // redacted; absent when too large or unparsable
    repeated ConfigFileKey keys = 4;
}

message ConfigFileKey {
    string key = 1;
    int32 line = 2;
    SettingOrigin overridden_by = 3;       // UNSPECIFIED when the file value applies, or is set again unchanged
    string overridden_by_detail = 4;
    bool secret = 5;
    optional string overriding_value = 6;  // the value that applies instead; absent for secrets
}

enum ConfigFileLocator {
    CONFIG_FILE_LOCATOR_UNSPECIFIED = 0;
    CONFIG_FILE_LOCATOR_OPTION = 1;        // --config / -c
    CONFIG_FILE_LOCATOR_ENVIRONMENT = 2;   // LEMLINE_CONFIG
    CONFIG_FILE_LOCATOR_DEFAULT_PATH = 3;  // ./.lemline.yaml, ~/.config/lemline/config.yaml, ~/.lemline.yaml
}
```

Rules from `docs/protobuf-messaging.md` apply: `*_UNSPECIFIED = 0`, prefixed enum values, `optional`
for presence-sensitive scalars. Sync the dashboard copy with `npm run proto:sync`.

## 4. Behaviour

### 4.1 Which keys

The list is fixed by the configuration interfaces, not by what happens to be set. Walk
`GatewayConfig` (and `lemline.namespaces`) and emit one `GatewaySetting` per leaf, including leaves
that are unset. An unset optional key such as `lemline.gateway.authentication.jwt.issuer` is useful
information ("not configured"), so it is returned with `origin = UNSET`.

### 4.2 Origin resolution

`LemlineConfigSource` (ordinal 275) merges the external file, the packaged `application.*` values and
the higher-priority values into one source. SmallRye alone therefore cannot tell a file value from a
packaged one. Everything else it can tell:

- environment variables and system properties keep their own sources (ordinals 300 and 400), so
  `getConfigValue(key).sourceName` names them directly;
- mapping defaults have an ordinal ≤ 0.

So only the file needs extra bookkeeping:

1. While merging, `LemlineConfigSource` passes the `lemline.*` values read from the file to
   `LoadedConfigFile.capture`, together with the file path and how it was found (`ConfigPathHolder.locator`,
   set by `LemlineApplication.setConfigPath`). The first capture of a path wins: the source can be built
   several times, and the file must be reported as it was at startup.
2. `ConfigOriginResolver` classifies the winning source: system property, environment, then — for the
   merged Lemline source — `CONFIG_FILE` when the file set the key, `APPLICATION` otherwise. A key without
   a value falls back to `DEFAULT` when the mapping declares a `@WithDefault`, or else `UNSET`.
3. `shadowed` lists the lower-priority sources that also hold the key, plus `CONFIG_FILE` when the file
   set a key that a higher origin replaces.
4. Some system properties are set by Lemline's own command, not typed by anyone: `gateway start`
   always sets `lemline.gateway.enabled=true` and turns lifecycle event consumption off. `LemlineApplication`
   records the `lemline.*` properties the command adds or changes (`CommandSetProperties`), and those keys get
   the `COMMAND` origin, with the command name as detail.
5. The environment name is derived from the key by the MicroProfile rule (`lemline.gateway.cors.origins`
   → `LEMLINE_GATEWAY_CORS_ORIGINS`).

SmallRye may decorate source names (`PropertiesConfigSource[source=LemlineConfigSource]`): the merged
source is recognised by type or by a name containing `LemlineConfigSource`.

### 4.3 Values

- Booleans and numbers are rendered with `toString()`, durations with their configured text.
- Lists are rendered comma-separated, in their configured order.
- `default_value` is filled from the mapping default, whatever the origin, so the UI can show
  "default: X" next to an override.

### 4.4 Secrets

A key is a secret when its leaf name is `password`, `secret` or `token`, ends with `-password`,
`-secret` or `-token`, or is one of `jaas-config`, `sasl-jaas-config`, `api-key`, `access-key`,
`secret-key`, `credentials`, `passphrase`, or ends with `jaas.config` (`ConfigFileRedactor.isSecret`).
A value is treated as a secret whatever its key when it contains, anywhere in it, a URL carrying
`user:password@` (for instance as the fallback of a `${…}` property expression) or a credential
assignment such as `password="…"` (Kafka JAAS lines) or `?password=…` (JDBC URLs).
For a secret, `value` is never set and `secret = true`. `is_set` tells whether a value is configured.

Paths such as `tls.certificate` and `tls.private-key` are not secrets: they are file locations, and
operators need them to diagnose TLS. The file contents are never read.

A secret's `default_value` is not sent either. Tests check that no secret string appears anywhere in the
serialized response.

### 4.5 Configuration file

Operators edit `lemline.yaml`, not keys one by one, so the file is returned whole and shown apart from
the effective settings. It answers "what did I write?", while the settings answer "what is running?".

- **Whole file, not only `lemline.gateway`.** The file is one unit for the operator. Showing part of it
  hides why a section is there. This widens what the endpoint reveals (database host, messaging),
  which is one more reason for the dedicated `lemline.config` scope.
- **Read once, at startup.** `LemlineConfigSource` already reads the file during the merge. Keep
  its text there. If the file changes on disk after startup, the page must still show what the
  process runs with, not the new content.
- **Redaction keeps the layout.** Parse the file with SnakeYAML's node API (the parser behind
  `YamlConfigSource`), which gives each scalar's start and end position. Replace the characters of
  each secret scalar in the original text with `••••••••`, keeping its line breaks so line numbers stay
  valid. Comments, order and indentation stay as written. The secret rule is the one from §4.4, applied
  to every key in the file, not only gateway keys. Database and messaging passwords are the main case.
- **Aliases.** A secret reached through an alias (`password: *pw`) resolves to the anchored node, so the
  anchor's value is masked where it is written.
- **Unparsable text is never returned.** If SnakeYAML cannot parse the file, its secrets cannot be
  located: `text` is omitted.
- **Comments are not redacted.** A credential written in a comment is returned as written. Keep
  credentials out of comments.
- **Line annotations.** For each key in the file, return its line and whether a higher-priority
  origin overrides it with a different value, and that value (unless it is a secret). A higher origin
  that sets the same value is not an override: the line still applies as written. Values are compared
  after trimming, booleans without case.
- **Size limit.** If the file is larger than 256 KiB, return `path`, `located_by` and `keys` without
  `text`. The UI then says that the file is too large to show.
- `ExtraFileConfigFactory` accepts `.properties` names but always parses with `YamlConfigSource`, so
  one YAML path covers every accepted file.

## 5. Access control

- With authentication enabled, the RPC requires a new scope, `lemline.config`. It is not implied by
  `lemline.watch` or `lemline.start`: configuration reveals infrastructure (hosts, paths, issuers).
- The namespace pin does not filter the response: configuration is global to the gateway process.
- With authentication disabled, the RPC follows the rest of the API and is open. This matches the
  current behaviour, where an unauthenticated gateway already serves every read RPC.
- `lemline.config` is listed with the other scopes in the README of `lemline-dashboard`.

## 6. Dashboard

The Settings page has three sections, in this order. It deliberately does not list every setting with
its default: operators think in terms of the file they wrote, plus what overrides it.

1. **Dashboard**: one row, the gateway URL the dashboard calls and where it comes from (`/config.js`
   or the built-in default). The browser knows it without calling the gateway, so it is shown even
   when the gateway is unreachable.
2. **lemline.yaml** (titled with the file name): the configuration file (from `config_file`):
   - header: the file path, how it was found ("Passed with --config", "From LEMLINE_CONFIG", "Found
     at default location"), the line count, and a Copy button that copies the redacted text;
   - the text with line numbers, comments kept, secrets shown as `••••••••` in muted color;
   - an overridden line is struck through, with a left bar and a label in the overriding origin's
     color, with the value that applies: "Overridden by LEMLINE_GATEWAY_CORS_ORIGINS → https://…",
     "Forced by the lemline gateway start command → false", "Overridden by a system property → 128".
     A typed system property is not shown as "-D…", since it may come from a launcher script;
   - no file loaded: the section says so, and names the places Lemline looks for one.
3. **Set outside the file**: the settings whose origin is `ENVIRONMENT` or `SYSTEM_PROPERTY`, one
   row each (`COMMAND` included), with the key, the value, the variable name or command, and one of
   "Replaces line N of lemline.yaml", "Same value as line N of lemline.yaml" or "Not in lemline.yaml". This catches values that the file cannot show: in a
   container configured only through environment variables, this list is the whole configuration.
   The section is hidden when the list is empty.

Values coming from defaults or from the packaged `application.*` files are not shown. `settings`
still returns them, so a later "Show all settings" view needs no API change.

Error states:

- `PERMISSION_DENIED` (missing `lemline.config`): the section says the token does not allow reading
  the configuration, and shows the scope to grant.
- `UNIMPLEMENTED` (older gateway): the section says this gateway version does not report its
  configuration.
- Network or CORS failure: the section shows the gateway URL the dashboard tried, from `config.js`.
  This is the case the page helps most with.

## 7. Tests

- Origin resolution, one test per origin: system property, environment variable, external file,
  application file, default, unset.
- Override chain: file value overridden by an environment variable gives
  `origin = ENVIRONMENT, shadowed = [CONFIG_FILE]`.
- Secrets: a configured `tls.trust-store-password` returns `secret = true, is_set = true`, without
  `value`.
- Configuration file: comments and indentation are returned unchanged; every password in the file
  (database, messaging, analytics, TLS) is redacted; an override by environment variable marks the
  right line; a file edited after startup is still returned as it was at startup.
- Access control: with authentication enabled, a call without `lemline.config` gets
  `PERMISSION_DENIED`; with authentication disabled, the call succeeds.
- Completeness: every leaf of `GatewayConfig` appears exactly once, so new keys cannot be forgotten.

## 8. Decisions

1. Settings cover the gateway only (`lemline.gateway.*` and `lemline.namespaces`). The file is returned
   whole, so database and messaging values are visible there, redacted; overrides of non-gateway keys are
   still marked on their file line.
2. With authentication disabled, the configuration is readable by anyone who can reach the gateway, even
   with a namespace pin, as for the other read RPCs.
3. The response does not carry the Lemline version yet.
