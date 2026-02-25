import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { Code, ConnectError } from "@connectrpc/connect";
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { Toaster, toast } from "sonner";
import App from "./App";
import "./index.css";

const QUERY_TOAST_THROTTLE_MS = 8_000;
const lastToastByKey = new Map<string, number>();
const GLOBAL_ERROR_TOASTS_KEY = "__lemline_global_error_toasts_registered__";

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

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value);
  } catch {
    return "[unserializable]";
  }
}

function toErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Unknown request error";
}

function shouldSkipToast(error: unknown): boolean {
  const message = toErrorMessage(error).toLowerCase();
  return message.includes("abort") || message.includes("cancel");
}

function logRequestError(source: string, error: unknown, context: Record<string, unknown> = {}) {
  const connectError = ConnectError.from(error);
  const diagnostics = {
    source,
    ...context,
    code: connectError.code,
    codeName: Code[connectError.code] ?? "Unknown",
    rawMessage: connectError.rawMessage,
    message: connectError.message,
    metadata: headersToRecord(connectError.metadata),
    cause: summarizeCause(connectError.cause),
    timestamp: new Date().toISOString(),
  };

  // Internal dashboard: keep request diagnostics verbose to investigate transport/parsing issues.
  console.error("[lemline][request-error]", diagnostics, error);
}

function showRequestErrorToast(source: string, error: unknown, key: string) {
  if (shouldSkipToast(error)) return;

  const now = Date.now();
  const previous = lastToastByKey.get(key);
  if (previous != null && now - previous < QUERY_TOAST_THROTTLE_MS) return;

  lastToastByKey.set(key, now);
  if (lastToastByKey.size > 300) {
    lastToastByKey.clear();
  }
  const message = toErrorMessage(error);
  toast.error("Request failed", {
    description: `${source}: ${message}`,
  });
}

function showRuntimeErrorToast(source: string, error: unknown, key: string) {
  showRequestErrorToast(source, error, `runtime:${key}:${toErrorMessage(error)}`);
}

if (
  typeof window !== "undefined" &&
  !(window as Window & { [GLOBAL_ERROR_TOASTS_KEY]?: boolean })[GLOBAL_ERROR_TOASTS_KEY]
) {
  (window as Window & { [GLOBAL_ERROR_TOASTS_KEY]?: boolean })[GLOBAL_ERROR_TOASTS_KEY] = true;

  window.addEventListener("unhandledrejection", (event) => {
    logRequestError("runtime.unhandledrejection", event.reason);
    showRuntimeErrorToast("runtime", event.reason, "unhandledrejection");
  });

  window.addEventListener("error", (event) => {
    logRequestError("runtime.error", event.error ?? event.message);
    showRuntimeErrorToast("runtime", event.error ?? event.message, "window-error");
  });
}

const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error, query) => {
      logRequestError("query", error, {
        queryHash: query.queryHash,
        queryKey: safeStringify(query.queryKey),
      });
      showRequestErrorToast("request", error, `query:${query.queryHash}:${toErrorMessage(error)}`);
    },
  }),
  mutationCache: new MutationCache({
    onError: (error, _variables, _context, mutation) => {
      let mutationKey = "[]";
      try {
        mutationKey = JSON.stringify(mutation.options.mutationKey ?? []);
      } catch {
        mutationKey = "unserializable-mutation-key";
      }
      logRequestError("mutation", error, {
        mutationKey,
      });
      showRequestErrorToast("mutation", error, `mutation:${mutationKey}:${toErrorMessage(error)}`);
    },
  }),
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
        <Toaster richColors position="top-right" />
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);
