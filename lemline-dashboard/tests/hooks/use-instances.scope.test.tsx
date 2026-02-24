import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { useInstances, type InstanceFilters } from "../../src/hooks/use-instances";
import type { ListInstancesRequest } from "../../src/gen/proto/lemline/gateway/v1/workflow_gateway_pb";

const mockListInstances = vi.fn();

vi.mock("../../src/lib/grpc-client", () => ({
  gatewayClient: {
    listInstances: (...args: unknown[]) => mockListInstances(...args),
  },
}));

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });

  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

function renderScope(filters: InstanceFilters) {
  const wrapper = createWrapper();
  return renderHook(() => useInstances(filters), { wrapper });
}

describe("useInstances scope filters", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListInstances.mockResolvedValue({
      instances: [],
      totalCount: 0,
      nextCursor: undefined,
    });
  });

  it("requests all instances on root scope", async () => {
    renderScope({ pageSize: 50 });

    await waitFor(() => {
      expect(mockListInstances).toHaveBeenCalledTimes(1);
    });

    const request = mockListInstances.mock.calls[0][0] as ListInstancesRequest;
    expect(request.namespace).toBeUndefined();
    expect(request.name).toBeUndefined();
    expect(request.version).toBeUndefined();
  });

  it("filters by namespace on namespace scope", async () => {
    renderScope({ namespace: "examples", pageSize: 50 });

    await waitFor(() => {
      expect(mockListInstances).toHaveBeenCalledTimes(1);
    });

    const request = mockListInstances.mock.calls[0][0] as ListInstancesRequest;
    expect(request.namespace).toBe("examples");
    expect(request.name).toBeUndefined();
    expect(request.version).toBeUndefined();
  });

  it("filters by namespace and name on workflow scope", async () => {
    renderScope({ namespace: "examples", name: "hello", pageSize: 50 });

    await waitFor(() => {
      expect(mockListInstances).toHaveBeenCalledTimes(1);
    });

    const request = mockListInstances.mock.calls[0][0] as ListInstancesRequest;
    expect(request.namespace).toBe("examples");
    expect(request.name).toBe("hello");
    expect(request.version).toBeUndefined();
  });

  it("filters by namespace, name and version on version scope", async () => {
    renderScope({
      namespace: "examples",
      name: "hello",
      version: "1.0.0",
      pageSize: 50,
    });

    await waitFor(() => {
      expect(mockListInstances).toHaveBeenCalledTimes(1);
    });

    const request = mockListInstances.mock.calls[0][0] as ListInstancesRequest;
    expect(request.namespace).toBe("examples");
    expect(request.name).toBe("hello");
    expect(request.version).toBe("1.0.0");
  });
});
