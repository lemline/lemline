# Environment Variables Reference

This reference lists the environment variables the Lemline runner reads.

## Configuration Properties as Environment Variables

Every property of the [Runner Configuration Reference](lemline-ref-config.md) can be set with an environment variable. The variable name is the property name in upper case, with every character that is not a letter or a digit replaced by `_`:

| Property | Environment variable |
|----------|----------------------|
| `lemline.database.type` | `LEMLINE_DATABASE_TYPE` |
| `lemline.database.postgresql.password` | `LEMLINE_DATABASE_POSTGRESQL_PASSWORD` |
| `lemline.messaging.kafka.brokers` | `LEMLINE_MESSAGING_KAFKA_BROKERS` |
| `lemline.messaging.kafka.commands.consumer.group-id` | `LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_GROUP_ID` |
| `lemline.gateway.authentication.jwt.jwks-url` | `LEMLINE_GATEWAY_AUTHENTICATION_JWT_JWKS_URL` |

Environment variables override the configuration file and are overridden by Java system properties (`-D...`).

The same rule applies to Quarkus properties, for example `QUARKUS_LOG_LEVEL` for `quarkus.log.level`.

## Variables Read Directly by Lemline

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `LEMLINE_CONFIG` | - | Path of the configuration file, used when `--config` is not given. The file must exist. |
| `LEMLINE_PYTHON_EXEC` | `python3` | Python executable used by `run.script` tasks with `language: python` (also settable with the `lemline.python.exec` system property) |
| `LEMLINE_NODE_EXEC` | `node` | Node.js executable used by `run.script` tasks with `language: js` (also settable with the `lemline.node.exec` system property) |

## Secrets Environment Variables

Workflow secrets are read from environment variables named exactly like the secrets the workflow declares in `use.secrets`: no prefix and no name conversion.

| Declared secret | Environment variable read by the runner |
|-----------------|-----------------------------------------|
| `PAYMENT_API_KEY` | `PAYMENT_API_KEY` |
| `DB_CREDENTIALS` | `DB_CREDENTIALS` (a JSON object value is parsed) |

See [Secrets Reference](lemline-ref-secrets.md).

## Commonly Used Quarkus Variables

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `QUARKUS_LOG_LEVEL` | `ERROR` | Root log level. The `--debug`, `--info`, `--warn` and `--error` options set it too. |
| `QUARKUS_LOG_CONSOLE_JSON_ENABLED` | `false` | JSON console logs. The `--json` option sets it too. |

## Environment Variable Usage Example

```bash
# Database configuration
export LEMLINE_DATABASE_TYPE="postgresql"
export LEMLINE_DATABASE_POSTGRESQL_HOST="postgres.example.com"
export LEMLINE_DATABASE_POSTGRESQL_DATABASE="lemline_prod"
export LEMLINE_DATABASE_POSTGRESQL_USERNAME="lemline_user"
export LEMLINE_DATABASE_POSTGRESQL_PASSWORD="secret-password"

# Messaging configuration
export LEMLINE_MESSAGING_TYPE="kafka"
export LEMLINE_MESSAGING_KAFKA_BROKERS="kafka-1:9092,kafka-2:9092"
export LEMLINE_MESSAGING_KAFKA_COMMANDS_CONSUMER_GROUP_ID="lemline-prod"

# Logging configuration
export QUARKUS_LOG_LEVEL="INFO"

# Workflow secrets (named exactly like the entries of use.secrets)
export PAYMENT_API_KEY="your-api-key"
```

## Related Resources

- [Configuring the Lemline Runner](lemline-howto-config.md)
- [Runner Configuration Reference](lemline-ref-config.md)
- [Secrets Management](lemline-howto-secrets.md)
