import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GanttChart } from "../../src/components/timeline/gantt-chart";

interface TimelineEventLike {
  sequence: number;
  eventType: string;
  eventTime: string;
  category: string;
  taskPointer?: string;
  taskName?: string;
  payloadJson: string;
}

function buildEvents(): TimelineEventLike[] {
  return [
    {
      sequence: 1,
      eventType: "io.serverlessworkflow.workflow.created.v1",
      eventTime: "2025-01-01T10:00:00.000Z",
      category: "WORKFLOW",
      payloadJson: "{}",
    },
    {
      sequence: 2,
      eventType: "io.serverlessworkflow.workflow.started.v1",
      eventTime: "2025-01-01T10:00:01.000Z",
      category: "WORKFLOW",
      payloadJson: "{}",
    },
    {
      sequence: 3,
      eventType: "io.serverlessworkflow.task.created.v1",
      eventTime: "2025-01-01T10:00:02.000Z",
      category: "TASK",
      taskPointer: "/do/steps/0/sendEmail",
      taskName: "SendEmail",
      payloadJson: "{\"data\":{\"input\":{\"customerId\":\"c-1\"}}}",
    },
    {
      sequence: 4,
      eventType: "io.serverlessworkflow.task.started.v1",
      eventTime: "2025-01-01T10:00:03.000Z",
      category: "TASK",
      taskPointer: "/do/steps/0/sendEmail",
      taskName: "SendEmail",
      payloadJson: "{}",
    },
    {
      sequence: 5,
      eventType: "io.serverlessworkflow.task.completed.v1",
      eventTime: "2025-01-01T10:00:06.000Z",
      category: "TASK",
      taskPointer: "/do/steps/0/sendEmail",
      taskName: "SendEmail",
      payloadJson: "{\"data\":{\"output\":{\"ok\":true}}}",
    },
    {
      sequence: 6,
      eventType: "io.serverlessworkflow.workflow.completed.v1",
      eventTime: "2025-01-01T10:00:07.000Z",
      category: "WORKFLOW",
      payloadJson: "{}",
    },
  ];
}

describe("GanttChart", () => {
  it("supports zoom controls via buttons and keyboard reset", () => {
    render(<GanttChart events={buildEvents()} />);

    expect(screen.getByText("100%")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Zoom in timeline" }));
    expect(screen.getByText("115%")).toBeInTheDocument();

    const chartRegion = screen.getByRole("region", { name: "Workflow timeline chart" });
    fireEvent.keyDown(chartRegion, { key: "0" });
    expect(screen.getByText("100%")).toBeInTheDocument();

    fireEvent.keyDown(chartRegion, { key: "+" });
    expect(screen.getByText("115%")).toBeInTheDocument();
    fireEvent.keyDown(chartRegion, { key: "-" });
    expect(screen.getByText("100%")).toBeInTheDocument();
  });

  it("opens and closes selected details using keyboard actions on a task bar", () => {
    render(<GanttChart events={buildEvents()} />);

    const taskBar = screen.getByRole("button", {
      name: /SendEmail: Completed/,
    });

    fireEvent.keyDown(taskBar, { key: "Enter" });
    expect(screen.getByRole("region", { name: "Selected timeline details" })).toBeInTheDocument();

    fireEvent.keyDown(taskBar, { key: "Escape" });
    expect(screen.queryByRole("region", { name: "Selected timeline details" })).not.toBeInTheDocument();
  });
});
