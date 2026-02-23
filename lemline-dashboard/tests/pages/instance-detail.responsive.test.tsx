import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { InstanceDetailPage } from "../../src/pages/instance-detail";

const mockUseViewportWidth = vi.fn();
const mockUseInstanceTimeline = vi.fn();

vi.mock("../../src/hooks/use-viewport-width", () => ({
  useViewportWidth: () => mockUseViewportWidth(),
}));

vi.mock("../../src/hooks/use-instance-timeline", () => ({
  useInstanceTimeline: (...args: unknown[]) => mockUseInstanceTimeline(...args),
}));

vi.mock("../../src/components/timeline/gantt-chart", () => ({
  GanttChart: () => <div data-testid="gantt-chart">gantt</div>,
}));

vi.mock("../../src/components/timeline/event-log", () => ({
  EventLog: () => <div data-testid="event-log">event log</div>,
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/id/01956f7a-4444-7444-8444-444444444444"]}>
      <Routes>
        <Route path="/id/:workflowId" element={<InstanceDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("InstanceDetailPage responsive timeline rendering", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseInstanceTimeline.mockReturnValue({
      data: {
        workflowId: "01956f7a-4444-7444-8444-444444444444",
        namespace: "orders",
        name: "OrderWorkflow",
        version: "1.0.0",
        status: "COMPLETED",
        events: [
          {
            sequence: 1,
            eventType: "io.serverlessworkflow.workflow.started.v1",
            eventTime: "2025-01-01T10:00:00.000Z",
            category: "WORKFLOW",
            payloadJson: "{}",
          },
          {
            sequence: 2,
            eventType: "io.serverlessworkflow.workflow.completed.v1",
            eventTime: "2025-01-01T10:00:03.000Z",
            category: "WORKFLOW",
            payloadJson: "{}",
          },
        ],
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
  });

  it("hides Gantt and keeps event log on tablet widths", () => {
    mockUseViewportWidth.mockReturnValue(900);
    renderPage();

    expect(screen.queryByTestId("gantt-chart")).not.toBeInTheDocument();
    expect(screen.getByTestId("event-log")).toBeInTheDocument();
  });

  it("renders both Gantt and event log on desktop widths", () => {
    mockUseViewportWidth.mockReturnValue(1280);
    renderPage();

    expect(screen.getByTestId("gantt-chart")).toBeInTheDocument();
    expect(screen.getByTestId("event-log")).toBeInTheDocument();
  });
});
