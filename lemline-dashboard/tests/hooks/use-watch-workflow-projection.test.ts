import { describe, expect, it } from "vitest";
import {
  buildWorkflowProjection,
  type WorkflowProjectionInputEvent,
} from "../../src/hooks/use-watch-workflow-projection";

describe("buildWorkflowProjection", () => {
  it("orders events by event_time with cursor as deterministic tie-breaker", () => {
    const events: WorkflowProjectionInputEvent[] = [
      timelineEvent(3n, "io.serverlessworkflow.workflow.started.v1", "2025-01-01T10:00:01.000Z"),
      timelineEvent(1n, "io.serverlessworkflow.workflow.created.v1", "2025-01-01T10:00:00.000Z"),
      timelineEvent(2n, "io.serverlessworkflow.workflow.completed.v1", "2025-01-01T10:00:02.000Z"),
    ];

    const projection = buildWorkflowProjection("wf-1", events, 3n);

    expect(projection.events.map((event) => event.sequence.toString())).toEqual(["1", "3", "2"]);
    expect(projection.status).toBe("COMPLETED");
  });

  it("reconstructs task lifecycle even when events arrive out of order", () => {
    const events: WorkflowProjectionInputEvent[] = [
      timelineEvent(30n, "io.serverlessworkflow.task.completed.v1", "2025-01-01T10:00:03.000Z", "/tasks/ship"),
      timelineEvent(10n, "io.serverlessworkflow.task.created.v1", "2025-01-01T10:00:01.000Z", "/tasks/ship"),
      timelineEvent(20n, "io.serverlessworkflow.task.started.v1", "2025-01-01T10:00:02.000Z", "/tasks/ship"),
    ];

    const projection = buildWorkflowProjection("wf-1", events, 30n);
    const task = projection.tasks["/tasks/ship"];

    expect(task).toMatchObject({
      createdAt: "2025-01-01T10:00:01.000Z",
      startedAt: "2025-01-01T10:00:02.000Z",
      terminalAt: "2025-01-01T10:00:03.000Z",
      terminalKind: "COMPLETED",
    });
  });

  it("falls back to cursor ordering when event_time is missing or invalid", () => {
    const events: WorkflowProjectionInputEvent[] = [
      timelineEvent(20n, "io.serverlessworkflow.workflow.started.v1", ""),
      timelineEvent(10n, "io.serverlessworkflow.workflow.created.v1", "not-a-time"),
      timelineEvent(30n, "io.serverlessworkflow.workflow.completed.v1", ""),
    ];

    const projection = buildWorkflowProjection("wf-1", events, 30n);

    expect(projection.events.map((event) => event.sequence.toString())).toEqual(["10", "20", "30"]);
  });
});

function timelineEvent(
  sequence: bigint,
  eventType: string,
  eventTime: string,
  taskPointer?: string,
): WorkflowProjectionInputEvent {
  return {
    sequence,
    eventType,
    eventTime,
    category: eventType.includes(".task.") ? "TASK" : "WORKFLOW",
    taskPointer,
    taskName: taskPointer ? taskPointer.split("/").at(-1) : undefined,
    payloadJson: "{}",
    sortTimeMs: Number.isFinite(Date.parse(eventTime)) ? Date.parse(eventTime) : undefined,
    scope: {
      namespace: "demo",
      name: "order",
      version: "1.0.0",
    },
  };
}
