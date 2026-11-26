# Connect the dashboard to the gateway

Deploy the Lemline dashboard to browse workflow definitions, start instances and inspect their lifecycle events. The dashboard serves static files. The browser sends gRPC-Web requests to a proxy that forwards native gRPC to the gateway. You can run all three on separate hosts.

Before deploying the dashboard, [start and verify the gateway](lemline-howto-deploy-gateway.md) and keep a worker running with lifecycle analytics enabled. You need a Lemline source checkout matching your gateway version and Node.js 24 with npm to build the dashboard.

## Build and serve the dashboard

From the source checkout:

```bash
cd lemline-dashboard
npm ci
npm run build
```

For a same-origin deployment, place this in `dist/config.js`:

```javascript
window.__CONFIG__ = {
  gatewayBaseUrl: window.location.origin,
};
```

Serve `dist/` over HTTPS, return `index.html` for application routes such as `/instances`, and serve `/config.js` without caching so you can change the endpoint after deployment. The repository's [nginx configuration](https://github.com/lemline/lemline/blob/main/docker/e2e/dashboard/nginx.conf) shows this routing.

## Route browser requests to the gateway

1. On the dashboard origin, forward requests under `/lemline.gateway.v1.GatewayService/` to a gRPC-Web proxy. Disable response buffering and allow long-lived requests for live watches.
2. Configure the proxy to translate gRPC-Web to gRPC over HTTP/2. Set its upstream to the gateway's configured gRPC port. The repository's [local Envoy configuration](https://github.com/lemline/lemline/blob/main/docker/e2e/grpc-web-proxy/envoy.yaml) shows the filter and route. For a shared deployment, replace the example's permissive upstream trust settings with CA and hostname verification.
3. If you serve the proxy at a separate browser-facing origin, set `gatewayBaseUrl` to that full HTTPS origin. In the proxy's CORS policy, allow the dashboard's origin and gRPC-Web request headers, and expose the gRPC status headers.

### Authenticate dashboard users

The browser client sends gRPC-Web requests without an `Authorization` header. With gateway authentication enabled, put an authentication-aware proxy in the request path. Authenticate the browser session and supply an `Authorization: Bearer …` header using a token authorized for that user's namespaces and actions. Keep tokens on the server side, since `config.js` is public.

With the default claims, give the token a `lemline_namespaces` string and include the relevant permissions in its `scope` string. Use `lemline.watch` for live updates, `lemline.start` for the start button and `lemline.config` for gateway configuration in **Settings**. See [the gateway security model](lemline-ref-gateway.md#security-model) for the complete authorization rules.

## Start an instance and check its timeline

Use a published workflow that accepts manual starts. To use the order example, publish version `0.1.2` of `tutorial/acknowledge-order` from the [gateway tutorial](lemline-tutorial-gateway.md).

1. Open the dashboard. In **Definitions**, open `tutorial / acknowledge-order` and select **Version 0.1.2**.
2. Select **Start instance**, enter the input below and confirm **Start instance** in the panel.

   <!-- workflow-example: data -->
   ```json
   {"orderId":"ORD-102"}
   ```

3. The dashboard opens the new instance. Wait for **Completed** and select **Details**. Under **Output**, look for `{"message":"Order received for ORD-102!"}`. The timeline shows the `acknowledge` task.

| Symptom | What to check |
|---|---|
| Definitions fail to load | In **Settings**, check the configured gateway URL. Verify that the browser-facing endpoint serves gRPC-Web and that the proxy can reach the gateway's gRPC port. |
| The browser reports a CORS error | For separate origins, check the allowed origins and headers in the gRPC-Web proxy's CORS policy. |
| A request returns `UNAUTHENTICATED` or `PERMISSION_DENIED` | Verify the token supplied by the authentication proxy, its namespace claim and the scope needed for the request. |
| The start is accepted, but the timeline stays empty | Check that a worker serves the workflow namespace and that Lifecycle Analytics ingestion writes to the store read by the gateway. |
