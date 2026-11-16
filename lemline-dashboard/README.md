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

To develop against a gRPC-Web endpoint that does not allow the dev server origin (for example the
`docker/e2e` stack on `http://localhost:9446`), let the dev server forward the gateway calls:

```bash
LEMLINE_GATEWAY_PROXY=http://localhost:9446 npm run dev
```

and set `gatewayBaseUrl` to the dev server origin (`window.location.origin`) in a local, uncommitted `public/config.js`.

## Pages

- **Instances** (home): find an instance by the start of its ID, filter by workflow, version, period and status.
- **Definitions**: deployed workflows by namespace, with their running or faulted instances.
- **Definition**: how the workflow starts, its instances of the last 24 hours, its YAML, and a start panel
  (not offered for workflows that start on an event: the gateway refuses to start them by hand).
- **Instance**: status and cause, a timeline of each task (attempts, waits before retry, waits for an event,
  long waits shortened), the lifecycle events, and the instance input and output.

The namespace picked in the menu applies to every page. The light and dark themes follow the system until
one is picked in the menu.

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

`gateway.proto` is canonical in `lemline-runner-gateway`. The dashboard keeps a synced copy in `lemline-dashboard/proto`.

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
