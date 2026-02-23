import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";
import { InstanceDetailPage } from "../../src/pages/instance-detail";

const mockUseViewportWidth = vi.fn();
const mockUseInstanceTimeline = vi.fn();
const mockWatchWorkflow = vi.fn();

vi.mock("../../src/hooks/use-viewport-width", () => ({
  useViewportWidth: () => mockUseViewportWidth(),
}));

vi.mock("../../src/hooks/use-instance-timeline", () => ({
  useInstanceTimeline: (...args: unknown[]) => mockUseInstanceTimeline(...args),
}));

vi.mock("../../src/lib/grpc-client", () => ({
  gatewayClient: {
    watchWorkflow: (...args: unknown[]) => mockWatchWorkflow(...args),
  },
}));

vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock("../../src/components/timeline/gantt-chart", () => ({
  GanttChart: () => <div data-testid="gantt-chart">gantt</div>,
}));

vi.mock("../../src/components/timeline/event-log", () => ({
  EventLog: () => <div data-testid="event-log">event log</div>,
}));

vi.mock("../../src/components/workflow/error-panel", () => ({
  ErrorPanel: () => <div data-testid="error-panel">error</div>,
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/id/01956f7a-7777-7777-8777-777777777777"]}>
      <Routes>
        <Route path="/id/:workflowId" element={<InstanceDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

function createFailingAsyncStream() {
  return {
    [Symbol.asyncIterator]() {
      return {
        next: async () => {
          throw new Error("stream disconnected");
        },
      };
    },
  };
}

function baseTimelineData(status: string) {
  return {
    workflowId: "01956f7a-7777-7777-8777-777777777777",
    namespace: "orders",
    name: "OrderWorkflow",
    version: "1.0.0",
    status,
    events: [
      {
        sequence: 1,
        eventType: "io.serverlessworkflow.workflow.started.v1",
        eventTime: "2025-01-01T10:00:00.000Z",
        category: "WORKFLOW",
        payloadJson: "{}",
      },
    ],
  };
}

describe("InstanceDetailPage loading and stream behavior", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows header + gantt + event-log skeletons during desktop loading", () => {
    mockUseViewportWidth.mockReturnValue(1280);
    mockUseInstanceTimeline.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    });

    renderPage();

    expect(screen.getByRole("status", { name: "Loading instance header" })).toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Loading gantt timeline" })).toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Loading event log" })).toBeInTheDocument();
  });

  it("hides gantt skeleton during tablet loading", () => {
    mockUseViewportWidth.mockReturnValue(900);
    mockUseInstanceTimeline.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    });

    renderPage();

    expect(screen.getByRole("status", { name: "Loading instance header" })).toBeInTheDocument();
    expect(screen.queryByRole("status", { name: "Loading gantt timeline" })).not.toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Loading event log" })).toBeInTheDocument();
  });

  it("shows reconnect success toast and schedules refetch after stream recovery", async () => {
    vi.useFakeTimers();

    const refetch = vi.fn();
    mockUseViewportWidth.mockReturnValue(1280);
    mockUseInstanceTimeline.mockReturnValue({
      data: baseTimelineData("RUNNING"),
      isLoading: false,
      isError: false,
      refetch,
    });

    mockWatchWorkflow
      .mockImplementationOnce(() => createFailingAsyncStream())
      .mockImplementationOnce(
        async function* recoveredStream(_request: unknown, options?: { signal?: AbortSignal }) {
          yield {};
          await new Promise<void>((resolve) => {
            if (options?.signal?.aborted) {
              resolve();
              return;
            }
            options?.signal?.addEventListener("abort", () => resolve(), { once: true });
          });
        },
      );

    const view = renderPage();

    await act(async () => {
      await Promise.resolve();
    });

    expect(toast.error).toHaveBeenCalledWith("Connection error — retrying...");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });

    expect(mockWatchWorkflow).toHaveBeenCalledTimes(2);
    expect(toast.success).toHaveBeenCalledWith("Reconnected — live updates resumed", { duration: 3000 });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(250);
    });

    expect(refetch).toHaveBeenCalledTimes(1);

    view.unmount();
    vi.useRealTimers();
  });

  it("shows persistent toast after max stream retries", async () => {
    vi.useFakeTimers();

    const refetch = vi.fn();
    mockUseViewportWidth.mockReturnValue(1280);
    mockUseInstanceTimeline.mockReturnValue({
      data: baseTimelineData("RUNNING"),
      isLoading: false,
      isError: false,
      refetch,
    });
    mockWatchWorkflow.mockImplementation(() => createFailingAsyncStream());

    const view = renderPage();

    await act(async () => {
      await Promise.resolve();
    });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000);
    });

    expect(mockWatchWorkflow).toHaveBeenCalledTimes(6);
    expect(toast.error).toHaveBeenCalledWith("Live updates unavailable. Reload page.", { duration: Infinity });
    expect(refetch).not.toHaveBeenCalled();

    view.unmount();
    vi.useRealTimers();
  });

  it("shows copy failure toast when clipboard write fails", async () => {
    mockUseViewportWidth.mockReturnValue(1280);
    mockUseInstanceTimeline.mockReturnValue({
      data: baseTimelineData("COMPLETED"),
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });

    const writeText = vi.fn().mockRejectedValue(new Error("clipboard unavailable"));
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });

    renderPage();

    fireEvent.click(screen.getByRole("button", { name: "Copy workflow ID" }));
    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith("Unable to copy workflow ID");
    });
    expect(toast.success).not.toHaveBeenCalled();
  });
});
