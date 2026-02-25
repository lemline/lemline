# lemline-dashboard

Standalone React dashboard for Lemline workflow observability and start actions.

## Requirements

- Node.js 22+
- npm 10+
- `buf` CLI (`buf --version`) only when regenerating protobuf clients

## Run locally

```bash
npm install
npm run dev
```

If the gateway proto changed, regenerate clients first:

```bash
npm run proto:generate
```

## Configure Gateway Access

The dashboard reads the gateway endpoint at runtime from `window.__CONFIG__.gatewayBaseUrl` in `public/config.js`.

```js
window.__CONFIG__ = {
  gatewayBaseUrl: "https://localhost:9000",
};
```

Notes:
- Use a full origin URL (`https://host:port`).
- If `window.__CONFIG__.gatewayBaseUrl` is missing, the default is `https://localhost:9000`.
- For container deployments, override `config.js` in the served static files.

Gateway-side requirements:
- `lemline.gateway.enabled=true`
- `lemline.gateway.grpc.host` / `lemline.gateway.grpc.port` reachable from browser clients
- TLS configured under `lemline.gateway.tls.*` (for example `lemline.gateway.tls.enabled=true`, `lemline.gateway.tls.certificate`, `lemline.gateway.tls.private-key`)
- gRPC-Web and CORS are enabled by the gateway config source when gateway mode is enabled
- Allow dashboard origin with `lemline.gateway.cors.origins` (default: `http://localhost:5173`)

Security behavior:
- The dashboard client does not currently attach an `Authorization` header.
- For direct browser access, run gateway with `lemline.gateway.authentication.enabled=false`.
- If security stays enabled, place an auth-aware edge/proxy in front that injects auth metadata for gateway calls.

## Protobuf Sync

`workflow_gateway.proto` is canonical in `lemline-runner-gateway`. The dashboard keeps a synced copy in `lemline-dashboard/proto`.

```bash
# Copy canonical proto from gateway into dashboard
npm run proto:sync

# Fail if dashboard proto drifted from gateway proto
npm run proto:check
```

## Build

```bash
npm run build
```

Build with proto regeneration (CI / release checks):

```bash
npm run build:with-proto
```

## Lint

```bash
npm run lint
```

## Docker

```bash
docker build -t lemline-dashboard .
```

The container serves static assets via nginx on port `80`.
