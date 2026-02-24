import { Code, ConnectError, createPromiseClient, type Interceptor } from "@connectrpc/connect";
import { createGrpcWebTransport } from "@connectrpc/connect-web";
import { WorkflowGateway } from "../gen/proto/lemline/gateway/v1/workflow_gateway_connect";
import { getRuntimeConfig } from "./runtime-config";

const runtime = getRuntimeConfig();

function headersToRecord(headers: Headers): Record<string, string> {
  const entries: Record<string, string> = {};
  headers.forEach((value, key) => {
    entries[key] = entries[key] ? `${entries[key]}, ${value}` : value;
  });
  return entries;
}

function summarizeCause(cause: unknown): unknown {
  if (cause instanceof Error) {
    return {
      name: cause.name,
      message: cause.message,
      stack: cause.stack,
    };
  }
  return cause;
}

function isExpectedCancellation(connectError: ConnectError): boolean {
  if (connectError.code === Code.Canceled) {
    return true;
  }

  const message = `${connectError.message} ${connectError.rawMessage ?? ""}`.toLowerCase();
  if (message.includes("abort") || message.includes("cancel")) {
    return true;
  }

  if (connectError.cause instanceof Error) {
    const causeMessage = `${connectError.cause.name} ${connectError.cause.message}`.toLowerCase();
    if (causeMessage.includes("abort") || causeMessage.includes("cancel")) {
      return true;
    }
  }

  return false;
}

const grpcErrorLoggingInterceptor: Interceptor = (next) => async (request) => {
  try {
    return await next(request);
  } catch (error) {
    const connectError = ConnectError.from(error);
    if (isExpectedCancellation(connectError)) {
      throw error;
    }

    const diagnostics = {
      service: request.service.typeName,
      method: request.method.name,
      url: request.url,
      stream: request.stream,
      code: connectError.code,
      codeName: Code[connectError.code] ?? "Unknown",
      rawMessage: connectError.rawMessage,
      message: connectError.message,
      metadata: headersToRecord(connectError.metadata),
      requestHeaders: headersToRecord(request.header),
      cause: summarizeCause(connectError.cause),
      timestamp: new Date().toISOString(),
    };

    // Internal dashboard: keep transport diagnostics verbose for protocol troubleshooting.
    console.error("[lemline][grpc-web] request failed", diagnostics, error);
    throw error;
  }
};

const transport = createGrpcWebTransport({
  baseUrl: runtime.gatewayBaseUrl,
  useBinaryFormat: true,
  interceptors: [grpcErrorLoggingInterceptor],
});

export const gatewayClient = createPromiseClient(WorkflowGateway, transport);
