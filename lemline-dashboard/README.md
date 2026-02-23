# lemline-dashboard

Standalone React dashboard for Lemline workflow observability and start actions.

## Requirements

- Node.js 22+
- npm 10+
- `buf` CLI (`buf --version`)

## Run locally

```bash
npm install
npm run proto:generate
npm run dev
```

## Protobuf Sync

`workflow_gateway.proto` is canonical in `lemline-runner-gateway`. The dashboard keeps a synced copy in `lemline-dashboard/proto`.

```bash
# Copy canonical proto from gateway into dashboard
npm run proto:sync

# Fail if dashboard proto drifted from gateway proto
npm run proto:check
```

Default gateway URL is `https://localhost:9000` and can be changed in `public/config.js`.

## Build

```bash
npm run build
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
