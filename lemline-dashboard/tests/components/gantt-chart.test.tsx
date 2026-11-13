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

function buildStructuredSortEvents(): TimelineEventLike[] {
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
      taskPointer: "/do/10/alphaTask",
      taskName: "Alpha Task",
      payloadJson: "{}",
    },
    {
      sequence: 4,
      eventType: "io.serverlessworkflow.task.completed.v1",
      eventTime: "2025-01-01T10:00:03.000Z",
      category: "TASK",
      taskPointer: "/do/10/alphaTask",
      taskName: "Alpha Task",
      payloadJson: "{}",
    },
    {
      sequence: 5,
      eventType: "io.serverlessworkflow.task.created.v1",
      eventTime: "2025-01-01T10:00:04.000Z",
      category: "TASK",
      taskPointer: "/do/2/zuluTask",
      taskName: "Zulu Task",
      payloadJson: "{}",
    },
    {
      sequence: 6,
      eventType: "io.serverlessworkflow.task.completed.v1",
      eventTime: "2025-01-01T10:00:05.000Z",
      category: "TASK",
      taskPointer: "/do/2/zuluTask",
      taskName: "Zulu Task",
      payloadJson: "{}",
    },
    {
      sequence: 7,
      eventType: "io.serverlessworkflow.task.created.v1",
      eventTime: "2025-01-01T10:00:06.000Z",
      category: "TASK",
      taskPointer: "/do/2/zuluTask/try/do/1/nestedTask",
      taskName: "Nested Task",
      payloadJson: "{}",
    },
    {
      sequence: 8,
      eventType: "io.serverlessworkflow.task.completed.v1",
      eventTime: "2025-01-01T10:00:07.000Z",
      category: "TASK",
      taskPointer: "/do/2/zuluTask/try/do/1/nestedTask",
      taskName: "Nested Task",
      payloadJson: "{}",
    },
    {
      sequence: 9,
      eventType: "io.serverlessworkflow.workflow.completed.v1",
      eventTime: "2025-01-01T10:00:08.000Z",
      category: "WORKFLOW",
      payloadJson: "{}",
    },
  ];
}

function buildPointerFallbackEvents(): TimelineEventLike[] {
  return [
    {
      sequence: 1,
      eventType: "io.serverlessworkflow.workflow.created.v1",
      eventTime: "2025-01-01T11:00:00.000Z",
      category: "WORKFLOW",
      payloadJson: "{}",
    },
    {
      sequence: 2,
      eventType: "io.serverlessworkflow.workflow.started.v1",
      eventTime: "2025-01-01T11:00:01.000Z",
      category: "WORKFLOW",
      payloadJson: "{}",
    },
    {
      sequence: 3,
      eventType: "io.serverlessworkflow.task.created.v1",
      eventTime: "2025-01-01T11:00:02.000Z",
      category: "TASK",
      taskName: "Zulu Without Pointer",
      payloadJson: "{}",
    },
    {
      sequence: 4,
      eventType: "io.serverlessworkflow.task.completed.v1",
      eventTime: "2025-01-01T11:00:03.000Z",
      category: "TASK",
      taskName: "Zulu Without Pointer",
      payloadJson: "{}",
    },
    {
      sequence: 5,
      eventType: "io.serverlessworkflow.task.created.v1",
      eventTime: "2025-01-01T11:00:04.000Z",
      category: "TASK",
      taskName: "Alpha Without Pointer",
      payloadJson: "{}",
    },
    {
      sequence: 6,
      eventType: "io.serverlessworkflow.task.completed.v1",
      eventTime: "2025-01-01T11:00:05.000Z",
      category: "TASK",
      taskName: "Alpha Without Pointer",
      payloadJson: "{}",
    },
    {
      sequence: 7,
      eventType: "io.serverlessworkflow.workflow.completed.v1",
      eventTime: "2025-01-01T11:00:06.000Z",
      category: "WORKFLOW",
      payloadJson: "{}",
    },
  ];
}

function ganttRowLabels(container: HTMLElement, labels: string[]): string[] {
  const expectedLabels = new Set(labels);

  return Array.from(container.querySelectorAll("svg text"))
    .map((node) => node.textContent?.trim() ?? "")
    .filter((label) => expectedLabels.has(label));
}

describe("GanttChart", () => {
  it.each(["completed", "faulted"])("stops open task bars when the workflow is %s", (status) => {
    const events = buildEvents().filter((event) => !event.eventType.includes(".task.completed."));
    events[events.length - 1] = {
      ...events[events.length - 1],
      eventType: `io.serverlessworkflow.workflow.${status}.v1`,
    };

    render(<GanttChart events={events} />);

    expect(screen.getByRole("button", { name: "SendEmail: Running, 5s" })).toBeInTheDocument();
  });

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

  it("sorts task rows by structured task pointer order instead of label order", () => {
    const { container } = render(<GanttChart events={buildStructuredSortEvents()} />);

    expect(ganttRowLabels(container, ["Workflow", "Zulu Task", "Nested Task", "Alpha Task"])).toEqual([
      "Workflow",
      "Zulu Task",
      "Nested Task",
      "Alpha Task",
    ]);
  });

  it("falls back to first event sequence when rows do not have a structured pointer", () => {
    const { container } = render(<GanttChart events={buildPointerFallbackEvents()} />);

    expect(ganttRowLabels(container, ["Workflow", "Zulu Without Pointer", "Alpha Without Pointer"])).toEqual([
      "Workflow",
      "Zulu Without Pointer",
      "Alpha Without Pointer",
    ]);
  });
});
