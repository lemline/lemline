import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";
import { InstanceDetailPage } from "../../src/pages/instance-detail";

const mockUseViewportWidth = vi.fn();
const mockUseWatchWorkflowProjection = vi.fn();

vi.mock("../../src/hooks/use-viewport-width", () => ({
  useViewportWidth: () => mockUseViewportWidth(),
}));

vi.mock("../../src/hooks/use-watch-workflow-projection", () => ({
  useWatchWorkflowProjection: (...args: unknown[]) => mockUseWatchWorkflowProjection(...args),
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
    mockUseWatchWorkflowProjection.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      reconnect: vi.fn(),
      liveConnected: false,
    });

    renderPage();

    expect(screen.getByRole("status", { name: "Loading instance header" })).toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Loading gantt timeline" })).toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Loading event log" })).toBeInTheDocument();
  });

  it("hides gantt skeleton during tablet loading", () => {
    mockUseViewportWidth.mockReturnValue(900);
    mockUseWatchWorkflowProjection.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      reconnect: vi.fn(),
      liveConnected: false,
    });

    renderPage();

    expect(screen.getByRole("status", { name: "Loading instance header" })).toBeInTheDocument();
    expect(screen.queryByRole("status", { name: "Loading gantt timeline" })).not.toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Loading event log" })).toBeInTheDocument();
  });

  it("renders retry state and forwards retry action to hook", () => {
    const reconnect = vi.fn();
    mockUseViewportWidth.mockReturnValue(1280);
    mockUseWatchWorkflowProjection.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      reconnect,
      liveConnected: false,
    });

    renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(reconnect).toHaveBeenCalledTimes(1);
  });

  it.each(["PENDING", "RUNNING", "UNKNOWN"])(
    "shows the stream connection for an active %s instance",
    (status) => {
      mockUseViewportWidth.mockReturnValue(900);
      mockUseWatchWorkflowProjection.mockReturnValue({
        data: baseTimelineData(status),
        isLoading: false,
        isError: false,
        reconnect: vi.fn(),
        liveConnected: true,
      });

      const { rerender } = renderPage();
      expect(screen.getByText("Live")).toBeInTheDocument();
      expect(screen.queryByText("Disconnected")).not.toBeInTheDocument();
      const connectedFavicon = document.head.querySelector('link[rel="icon"]')?.getAttribute("href");

      mockUseWatchWorkflowProjection.mockReturnValue({
        data: baseTimelineData(status),
        isLoading: false,
        isError: false,
        reconnect: vi.fn(),
        liveConnected: false,
      });
      rerender(
        <MemoryRouter initialEntries={["/id/01956f7a-7777-7777-8777-777777777777"]}>
          <Routes>
            <Route path="/id/:workflowId" element={<InstanceDetailPage />} />
          </Routes>
        </MemoryRouter>,
      );
      expect(screen.getByText("Disconnected")).toBeInTheDocument();
      expect(document.head.querySelector('link[rel="icon"]')?.getAttribute("href")).not.toBe(connectedFavicon);
    },
  );

  it.each([
    ["COMPLETED", true],
    ["COMPLETED", false],
    ["FAULTED", true],
    ["FAULTED", false],
  ])("hides stream status for a %s instance when connected is %s", (status, liveConnected) => {
    mockUseViewportWidth.mockReturnValue(900);
    mockUseWatchWorkflowProjection.mockReturnValue({
      data: baseTimelineData(status),
      isLoading: false,
      isError: false,
      reconnect: vi.fn(),
      liveConnected,
    });

    renderPage();
    expect(screen.queryByText("Live")).not.toBeInTheDocument();
    expect(screen.queryByText("Disconnected")).not.toBeInTheDocument();
  });

  it("shows copy failure toast when clipboard write fails", async () => {
    mockUseViewportWidth.mockReturnValue(1280);
    mockUseWatchWorkflowProjection.mockReturnValue({
      data: baseTimelineData("COMPLETED"),
      isLoading: false,
      isError: false,
      reconnect: vi.fn(),
      liveConnected: false,
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
