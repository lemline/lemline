# Start and follow an order through the gRPC gateway

Send an order ID to Lemline over gRPC and read the workflow's acknowledgement from its event history.

Complete [the SQLite setup](lemline-tutorial-setup.md) and keep the worker running. Install [grpcurl](https://github.com/fullstorydev/grpcurl/releases) and `jq`, and have a Lemline source checkout matching the version you run. The checkout provides the gateway service definition. Use a Bash-compatible shell for the commands below.

## Run the Examples

In the setup directory, save this version of the [order acknowledgement workflow](lemline-tutorial-hello.md) as `acknowledge-order.yaml`. If you already registered version `0.1.2`, skip the publish command. With the JAR, substitute `java -jar lemline.jar` for `bin/lemline` in the commands below.

<code-block lang="yaml" src="workflows/lemline-tutorial-hello/5-adding-user-input.yaml"/>

```bash
bin/lemline definition post -f acknowledge-order.yaml
```

Add this block under the existing `lemline:` key in `.lemline.yaml`. This example uses a plaintext endpoint without authentication on the local loopback interface. For a shared installation, follow [Deploy the gRPC gateway](lemline-howto-deploy-gateway.md).

<!-- workflow-example: data -->
```yaml
  gateway:
    grpc:
      host: 127.0.0.1
    tls:
      enabled: false
    authentication:
      enabled: false
```

Start the gateway from the setup directory in another terminal. Use a metrics port distinct from the worker's.

```bash
bin/lemline gateway start --grpc-port 9000 --metrics-port 8082
```

You should see `Started gRPC server on 127.0.0.1:9000`.

### Submit the order

In the terminal you used to publish the workflow, set `LEMLINE_SOURCE` to the absolute path to your source checkout and define a helper that uses its service definition:

```bash
export LEMLINE_SOURCE=/path/to/lemline

gateway_rpc() {
  grpcurl -plaintext -max-time 5 \
    -import-path "$LEMLINE_SOURCE/lemline-runner-gateway/src/main/proto" \
    -proto lemline/gateway/v1/gateway.proto -d "$2" \
    127.0.0.1:9000 "lemline.gateway.v1.GatewayService/$1"
}

start_reply=$(gateway_rpc StartInstance \
  '{"namespace":"tutorial","name":"acknowledge-order","version":"0.1.2","input":{"orderId":"ORD-101"}}')
printf '%s\n' "$start_reply"
instance_id=$(printf '%s\n' "$start_reply" | jq -er '.instanceId')
```

### Read the acknowledgement

Use the returned instance ID to read its history. The worker executes the workflow and writes lifecycle events to analytics asynchronously. If you get `NotFound` or the history has no `workflow.completed` event yet, wait a moment and rerun this command:

```bash
gateway_rpc ListInstanceEvents "{\"instanceId\":\"$instance_id\"}" |
  jq -c '.events[]?.cloudEventJson | fromjson
    | select(.type == "org.open-workflow-specification.workflow.completed.v1")
    | .data.output'
```

| Call | Expected result |
|---|---|
| `StartInstance` | `START_INSTANCE_RESULT_ACCEPTED_NEW` with a new `instanceId` |
| `ListInstanceEvents` | The completed event contains `{"message":"Order received for ORD-101!"}` |

Press Ctrl+C in the gateway terminal when you finish. Continue with [safe start retries](lemline-howto-publish-run.md#retry-a-gateway-start-safely) or [following a live timeline](lemline-howto-monitor.md#resume-an-instance-event-stream).
