import { describe, expect, it } from "vitest";
import type { TimelineEventView } from "../../src/hooks/use-watch-workflow-projection";
import { toEventRows } from "../../src/lib/event-rows";
import { parseWorkflowSource } from "../../src/lib/workflow-source";

describe("toEventRows", () => {
  it("distinguishes a loop from its internal body without dropping lifecycle events", () => {
    const { root } = parseWorkflowSource("do:\n  - eachItem:\n      for: { each: item, in: '${ [1, 2] }' }\n      do: []");
    const pointers = ["/do/0/eachItem", "/do/0/eachItem/do", "/do/0/eachItem/do"];
    const events: TimelineEventView[] = pointers.flatMap((taskPointer, index) =>
      ["created", "started", "completed"].map((kind) => ({
        sequence: `${index}-${kind}`,
        eventType: `io.serverlessworkflow.task.${kind}.v1`,
        eventTime: "2026-11-16T09:15:19.643Z",
        category: "TASK",
        taskPointer,
        payloadJson: JSON.stringify({ data: { task: taskPointer } }),
      })),
    );

    const rows = toEventRows(events, Date.parse(events[0].eventTime), root);

    expect(rows).toHaveLength(events.length);
    expect(rows.map((row) => row.task)).toEqual([
      "eachItem", "eachItem", "eachItem",
      "eachItem / do", "eachItem / do", "eachItem / do",
      "eachItem / do", "eachItem / do", "eachItem / do",
    ]);
    expect(rows[4].what).toBe("eachItem / do started");
  });

  it("keeps the name of a declared do task", () => {
    const { root } = parseWorkflowSource("do:\n  - group:\n      do: []");
    const event: TimelineEventView = {
      sequence: "1",
      eventType: "io.serverlessworkflow.task.started.v1",
      eventTime: "2026-11-16T09:15:19.643Z",
      category: "TASK",
      taskPointer: "/do/0/group/do",
      payloadJson: "{}",
    };
    expect(toEventRows([event], Date.parse(event.eventTime), root)[0].task).toBe("group");
  });

  it("labels a root do-block failure as a task event", () => {
    const event: TimelineEventView = {
      sequence: 219n,
      eventType: "io.serverlessworkflow.task.faulted.v1",
      eventTime: "2026-11-16T00:34:30.592Z",
      category: "TASK",
      taskPointer: "/do",
      payloadJson: JSON.stringify({ data: { task: "/do", error: { title: "Not Implemented" } } }),
    };

    const [row] = toEventRows([event], Date.parse(event.eventTime));

    expect(row.task).toBe("do");
    expect(row.what).toBe("do failed: Not Implemented");
  });
});
