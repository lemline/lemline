import { describe, expect, it } from "vitest";
import type { TimelineEventView } from "../../src/hooks/use-watch-workflow-projection";
import { toEventRows } from "../../src/lib/event-rows";

describe("toEventRows", () => {
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
