# Gateway configuration endpoint

Status: proposal

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

A new unary RPC on `GatewayService`, following the existing `Get*` convention.

```proto
// Returns the configuration the gateway is running with, and where each value comes from.
// Read-only. Secrets are never returned: see GatewaySetting.secret.
rpc GetGatewayConfig(GetGatewayConfigRequest) returns (GetGatewayConfigResponse);

message GetGatewayConfigRequest {}

message GetGatewayConfigResponse {
    // Settings, sorted by key.
    repeated GatewaySetting settings = 1;
    // Absolute path of the external configuration file, if one was loaded: --config / -c, then
    // LEMLINE_CONFIG, then ./.lemline.yaml, ~/.config/lemline/config.yaml, ~/.lemline.yaml.
    optional string config_file = 2;
    // When the gateway process read its configuration (process start).
    google.protobuf.Timestamp loaded_at = 3;
    // The external configuration file as it was read at startup, secrets redacted. Absent when no
    // file was loaded. See §4.5.
    optional ConfigFile config_file_content = 4;
}

message ConfigFile {
    // Same as GetGatewayConfigResponse.config_file.
    string path = 1;
    // How the file was found.
    ConfigFileLocator located_by = 2;
    // Full text, comments and layout kept, secret values replaced by "••••••••".
    string text = 3;
    // One entry per key set in the file, to let the UI annotate lines.
    repeated ConfigFileKey keys = 4;
}

message ConfigFileKey {
    // Full key, e.g. "lemline.gateway.cors.origins".
    string key = 1;
    // 1-based line of the key in `text`.
    int32 line = 2;
    // True when a higher-priority origin overrides this value; `overridden_by` says which one.
    bool overridden = 3;
    SettingOrigin overridden_by = 4;
    // Environment variable or system property name of the override.
    string overridden_by_detail = 5;
    // True when the value was redacted.
    bool secret = 6;
}

enum ConfigFileLocator {
    CONFIG_FILE_LOCATOR_UNSPECIFIED = 0;
    CONFIG_FILE_LOCATOR_OPTION = 1;       // --config / -c
    CONFIG_FILE_LOCATOR_ENVIRONMENT = 2;  // LEMLINE_CONFIG
    CONFIG_FILE_LOCATOR_DEFAULT_PATH = 3; // ./.lemline.yaml, ~/.config/lemline/config.yaml, ~/.lemline.yaml
}

message GatewaySetting {
    // Full key, e.g. "lemline.gateway.cors.origins".
    string key = 1;
    // Effective value as a string. Absent when the key is not set and has no default,
    // or when the setting is a secret.
    optional string value = 2;
    // Where the effective value comes from.
    SettingOrigin origin = 3;
    // Name used at the origin, to let an operator find it:
    // the environment variable name (LEMLINE_GATEWAY_CORS_ORIGINS), the system property
    // (lemline.gateway.cors.origins), or the file path. Empty for DEFAULT and UNSET.
    string origin_detail = 4;
    // The built-in default, when the key has one. Lets the UI show "default: 9000".
    optional string default_value = 5;
    // True for secrets. The value is never sent; is_set tells whether one is configured.
    bool secret = 6;
    bool is_set = 7;
    // Lower-priority origins that also define this key and are hidden by `origin`
    // (e.g. a value in lemline.yaml overridden by an environment variable).
    repeated SettingOrigin shadowed = 8;
}

enum SettingOrigin {
    SETTING_ORIGIN_UNSPECIFIED = 0;
    SETTING_ORIGIN_SYSTEM_PROPERTY = 1; // -Dkey=value or a CLI option mapped to it
    SETTING_ORIGIN_ENVIRONMENT = 2;
    SETTING_ORIGIN_CONFIG_FILE = 3;     // external file passed with --config
    SETTING_ORIGIN_APPLICATION = 4;     // application.properties / application.yaml packaged with Lemline
    SETTING_ORIGIN_DEFAULT = 5;         // @WithDefault in LemlineConfiguration
    SETTING_ORIGIN_UNSET = 6;           // no value and no default
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

The origin cannot be read directly from SmallRye for keys loaded from the external file.
`LemlineConfigSource` merges the `--config` file, `application.*` values and higher-priority values into
a single `PropertiesConfigSource` named `LemlineConfigSource` (ordinal 275). After that merge,
`ConfigValue.sourceName` says `LemlineConfigSource` for all of them.

So the origin must be recorded while the merge happens:

1. In `LemlineConfigSource.buildProperties`, keep a `Map<String, Origin>` next to `lemlineProps`.
   Each time a key is written, record where it came from:
   - contextual value with ordinal ≤ 275: `APPLICATION` (or `SYSTEM_PROPERTY`/`ENVIRONMENT` if its
     source name says so);
   - value from `ExtraFileConfigFactory`: `CONFIG_FILE`, with `ConfigPathHolder.configPath` as detail
     (the file may have been passed with `--config`, set by `LEMLINE_CONFIG`, or found in a default
     location);
   - contextual value with ordinal > 275 (re-applied last): `ENVIRONMENT` when its source is the
     env-var source, `SYSTEM_PROPERTY` otherwise. For environment values, report the variable name
     derived from the key by the MicroProfile rule (`lemline.gateway.cors.origins` →
     `LEMLINE_GATEWAY_CORS_ORIGINS`). Checking which variant was actually set (`lemline_gateway_…`
     is also accepted) is not required.
   Every earlier writer of the same key goes to `shadowed`.
2. Publish this map in a small holder, `LemlineConfigOrigins`, readable after startup. The config
   source is built before CDI, so a plain object is acceptable, as `ConfigPathHolder` already does.
3. A key with no recorded origin falls back to `DEFAULT` when the mapping has a `@WithDefault`, or else
   `UNSET`.

The test-profile path (`context == null`) reads system properties only; record them as
`SYSTEM_PROPERTY`.

### 4.3 Values

- Booleans and numbers are rendered with `toString()`, durations with their configured text.
- Lists are rendered comma-separated, in their configured order.
- `default_value` is filled from the mapping default, whatever the origin, so the UI can show
  "default: X" next to an override.

### 4.4 Secrets

A key is a secret when its leaf name is `password`, ends with `-password`, or is in an explicit
allowlist kept next to the walker: `tls.trust-store-password`, plus any future `*.secret` or `*.token`.
For a secret, `value` is never set and `secret = true`. `is_set` tells whether a value is configured.

Paths such as `tls.certificate` and `tls.private-key` are not secrets: they are file locations, and
operators need them to diagnose TLS. The file contents are never read.

A test must fail when a new key matching the secret pattern is returned with a value.

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
  each secret scalar in the original text with `••••••••`. Comments, order and indentation stay as
  written. The secret rule is the one from §4.4, applied to every key in the file, not only gateway
  keys. Database and messaging passwords are the main case.
- **Line annotations.** For each key in the file, return its line and whether a higher-priority
  origin overrides it. This comes from the origin map of §4.2: a key whose final origin is not
  `CONFIG_FILE`, while the file also sets it, is overridden.
- **Size limit.** If the file is larger than 256 KiB, return `path`, `located_by` and `keys` without
  `text`. The UI then says that the file is too large to show.
- `.properties` files are allowed by `ExtraFileConfigFactory`. The same rules apply, with
  line-based positions instead of YAML nodes.

## 5. Access control

- With authentication enabled, the RPC requires a new scope, `lemline.config`. It is not implied by
  `lemline.watch` or `lemline.start`: configuration reveals infrastructure (hosts, paths, issuers).
- The namespace pin does not filter the response: configuration is global to the gateway process.
- With authentication disabled, the RPC follows the rest of the API and is open. This matches the
  current behaviour, where an unauthenticated gateway already serves every read RPC.
- Add `lemline.config` to the list of scopes documented in the gateway docs and in the README of
  `lemline-dashboard`.

## 6. Dashboard

The Settings page has three sections, in this order. It deliberately does not list every setting with
its default: operators think in terms of the file they wrote, plus what overrides it.

1. **Dashboard**: one row, the gateway URL the dashboard calls and where it comes from (`/config.js`
   or the built-in default). The browser knows it without calling the gateway, so it is shown even
   when the gateway is unreachable.
2. **lemline.yaml**: the configuration file (from `config_file_content`):
   - header: the file path, how it was found ("Passed with --config", "From LEMLINE_CONFIG", "Found
     at default location"), the line count, and a Copy button that copies the redacted text;
   - the text with line numbers, comments kept, secrets shown as `••••••••` in muted color;
   - an overridden line is struck through, with a left bar and a label in the overriding origin's
     color, for example "Overridden by LEMLINE_GATEWAY_CORS_ORIGINS";
   - no file loaded: the section says so, and names the places Lemline looks for one.
3. **Set outside the file**: the settings whose origin is `ENVIRONMENT` or `SYSTEM_PROPERTY`, one
   row each, with the key, the value, the variable or property name, and either "Replaces line N of
   lemline.yaml" or "Not in lemline.yaml". This catches values that the file cannot show: in a
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

## 8. Open questions

1. Should the endpoint also cover non-gateway settings (database, messaging) now? Same shape and
   scope; the cost is mostly the secret list, which grows a lot (database passwords, broker
   credentials).
2. With authentication disabled but a namespace pin set, should configuration stay readable by anyone?
   The current proposal says yes, for consistency with other read RPCs.
3. Should `loaded_at` be complemented by the Lemline version and build, to tell which release reports
   this configuration?
