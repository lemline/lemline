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

  it("shows a terminal workflow event after task events with the same timestamp", () => {
    const events: WorkflowProjectionInputEvent[] = [
      timelineEvent(217n, "io.serverlessworkflow.workflow.faulted.v1", "2026-11-16T00:34:30.592Z"),
      timelineEvent(219n, "io.serverlessworkflow.task.faulted.v1", "2026-11-16T00:34:30.592Z", "/do"),
    ];

    const projection = buildWorkflowProjection("wf-1", events);

    expect(projection.events.map((event) => event.sequence.toString())).toEqual(["219", "217"]);
  });

  it("shows workflow started before task started with the same timestamp", () => {
    const events: WorkflowProjectionInputEvent[] = [
      timelineEvent(212n, "io.serverlessworkflow.task.started.v1", "2026-11-16T00:34:30.545Z", "/do"),
      timelineEvent(213n, "io.serverlessworkflow.workflow.started.v1", "2026-11-16T00:34:30.545Z"),
    ];

    const projection = buildWorkflowProjection("wf-1", events);

    expect(projection.events.map((event) => event.sequence.toString())).toEqual(["213", "212"]);
  });

  it("keeps submillisecond event times ahead of the terminal tie-breaker", () => {
    const task = timelineEvent(219n, "io.serverlessworkflow.task.faulted.v1", "2026-11-16T00:34:30.592Z", "/do");
    const workflow = timelineEvent(217n, "io.serverlessworkflow.workflow.faulted.v1", "2026-11-16T00:34:30.592Z");
    task.sortTimeMs = Date.parse(task.eventTime) + 0.865;
    workflow.sortTimeMs = Date.parse(workflow.eventTime) + 0.864;

    const projection = buildWorkflowProjection("wf-1", [task, workflow]);

    expect(projection.events.map((event) => event.sequence.toString())).toEqual(["217", "219"]);
  });
  it("preserves extension failures and owner completion across streaming and history reload", () => {
    const owner = "/do/0/charge";
    const child = `${owner}/_ext/0/after/do/0/audit`;
    const trace = [
      timelineEvent(1n, "io.serverlessworkflow.task.started.v1", "2026-01-01T10:00:00Z", owner),
      timelineEvent(2n, "io.serverlessworkflow.task.started.v1", "2026-01-01T10:00:02Z", child),
      timelineEvent(3n, "io.serverlessworkflow.task.faulted.v1", "2026-01-01T10:00:03Z", child),
      timelineEvent(4n, "io.serverlessworkflow.task.faulted.v1", "2026-01-01T10:00:03Z", owner),
      timelineEvent(5n, "io.serverlessworkflow.workflow.faulted.v1", "2026-01-01T10:00:04Z"),
    ];
    trace[2].payloadJson = JSON.stringify({ data: { task: child, error: { status: 503, instance: child } } });
    trace[3].payloadJson = JSON.stringify({ data: { task: owner, error: { status: 503, instance: child } } });
    const pending = buildWorkflowProjection("wf-1", trace.slice(0, 2), 2n);
    expect(pending.tasks[owner].terminalKind).toBeUndefined();
    const streamed = buildWorkflowProjection("wf-1", trace, 5n);
    const reloaded = buildWorkflowProjection("wf-1", [...trace].reverse(), 5n);
    expect(reloaded).toEqual(streamed);
    expect(streamed.tasks[owner].terminalKind).toBe("FAILED");
    expect(streamed.tasks[child].terminalKind).toBe("FAILED");
    expect(streamed.events.find((item) => item.taskPointer === child && item.eventType.includes("faulted"))?.payloadJson).toContain(child);
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
