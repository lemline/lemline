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

- **Instances** (home): find an instance from a piece of its ID, filter by workflow, version, period and status.
- **Definitions**: deployed workflows by namespace, with their running or faulted instances.
- **Definition**: how the workflow starts, its instances of the last 24 hours, its YAML, and a start panel.
  For a workflow that starts on an event, the panel explains that only its event starts it: the runtime
  refuses to start such a workflow by hand.
- **Instance**: status and cause, a timeline of each task (attempts, waits before retry, waits for an event,
  long waits shortened), the lifecycle events, and the instance input and output.
- **Settings**: the gateway URL the dashboard calls and where it comes from, the configuration file the gateway
  loaded (passwords hidden, lines replaced by another origin struck through), and the settings the gateway
  received from its environment or command line. Reading the gateway configuration needs the `lemline.config`
  scope when authentication is enabled.

The namespace picked in the menu applies to every page. The light and dark themes follow the system until
one is picked in the menu.

## Configure Gateway Access

The dashboard reads its browser-facing gRPC-Web endpoint from `window.__CONFIG__.gatewayBaseUrl` in `config.js`.
For a same-origin deployment, route `/lemline.gateway.v1.GatewayService/` through a gRPC-Web proxy that forwards
native gRPC to the gateway, and serve the following configuration with the dashboard:

```js
window.__CONFIG__ = {
  gatewayBaseUrl: window.location.origin,
};
```

For a separate proxy origin, set `gatewayBaseUrl` to its full origin URL and allow the dashboard origin in the
proxy's CORS policy. For container deployments, override `config.js` in the served static files.

When gateway authentication is enabled, use a proxy that authenticates dashboard users and supplies the bearer
token for each gateway call. See [Connect the dashboard to the gateway](../lemline-docs/topics/lemline-howto-connect-dashboard.md)
for deployment steps, scopes and a smoke test.

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
