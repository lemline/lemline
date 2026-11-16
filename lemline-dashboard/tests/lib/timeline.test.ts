import { describe, expect, it } from "vitest";
import type { TimelineEventView } from "../../src/hooks/use-watch-workflow-projection";
import { buildTimeline, instanceSummary } from "../../src/lib/timeline";
import { parseWorkflowSource } from "../../src/lib/workflow-source";

const T0 = Date.parse("2026-11-15T09:14:00.000Z");

let sequence = 0;
function event(type: string, offsetMs: number, data: Record<string, unknown> = {}): TimelineEventView {
  sequence += 1;
  const eventTime = new Date(T0 + offsetMs).toISOString();
  const pointer = typeof data.task === "string" ? data.task : undefined;
  return {
    sequence: String(sequence),
    eventType: `io.serverlessworkflow.${type}.v1`,
    eventTime,
    category: type.startsWith("task") ? "TASK" : "WORKFLOW",
    taskPointer: pointer,
    taskName: pointer?.split("/").pop(),
    payloadJson: JSON.stringify({ type: `io.serverlessworkflow.${type}.v1`, time: eventTime, data }),
  };
}

const ORDER_SOURCE = `
document: { dsl: '1.0.0', namespace: shop, name: order-processing, version: '1.2.0' }
do:
  - validateOrder:
      set:
        order: \${ . }
  - chargePayment:
      call: http
      with: { method: post, endpoint: https://payments.example.com/charges }
  - waitForShipment:
      listen:
        to:
          one:
            with:
              type: com.example.shipment.created
  - notifyCustomer:
      emit:
        event:
          with:
            type: com.example.order.completed
`;

describe("buildTimeline", () => {
  const root = parseWorkflowSource(ORDER_SOURCE).root;

  it("draws a failed attempt, the wait before retry, then the successful attempt", () => {
    const events = [
      event("workflow.created", 0, { input: { orderId: "A-1042" } }),
      event("workflow.started", 0),
      event("task.created", 0, { task: "/do/0/validateOrder", input: { orderId: "A-1042" } }),
      event("task.started", 0, { task: "/do/0/validateOrder" }),
      event("task.completed", 500, { task: "/do/0/validateOrder", output: { valid: true } }),
      event("task.created", 500, { task: "/do/1/chargePayment", input: {} }),
      event("task.started", 500, { task: "/do/1/chargePayment" }),
      event("task.faulted", 2_500, {
        task: "/do/1/chargePayment",
        error: { type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication", status: 503, title: "Service Unavailable" },
      }),
      event("task.retried", 2_500, { task: "/do/1/chargePayment", retryCount: 1 }),
      event("task.started", 10_500, { task: "/do/1/chargePayment" }),
      event("task.completed", 14_500, { task: "/do/1/chargePayment", output: { paymentId: "pay_1" } }),
      event("workflow.completed", 15_000, { output: { ok: true } }),
    ];

    const model = buildTimeline(events, "COMPLETED", root, T0 + 60_000);
    const charge = model.rows.find((row) => row.label === "chargePayment");

    expect(model.rows.map((row) => row.label)).toEqual(["validateOrder", "chargePayment"]);
    expect(charge?.typeLabel).toBe("call http · 2 attempts");
    expect(charge?.segments.map((segment) => segment.kind)).toEqual(["failed", "backoff", "ran"]);
    expect(charge?.note).toBe("503 → retry in 8 s → ok");
    expect(charge?.state).toBe("completed");
    expect(model.rows[0].output).toEqual({ valid: true });
  });

  it("puts a retry announced on the try block on the task that failed in it", () => {
    const tryRoot = parseWorkflowSource(`
document: { dsl: '1.0.0', namespace: shop, name: payment, version: '1.0.0' }
do:
  - chargePayment:
      try:
        - callPayments:
            call: http
            with: { method: post, endpoint: https://payments.example.com/charges }
      catch:
        errors:
          with: { status: 503 }
        retry:
          delay: { seconds: 8 }
          limit:
            attempt: { count: 3 }
`).root;
    const call = "/do/0/chargePayment/try/0/callPayments";
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: "/do/0/chargePayment" }),
      event("task.started", 0, { task: call }),
      event("task.faulted", 2_000, { task: call, error: { status: 503, title: "Service Unavailable" } }),
      // As the runtime emits it: on the try block it re-enters, not on the task.
      event("task.retried", 2_000, { task: "/do/0/chargePayment/try", retryCount: 1 }),
    ];

    const model = buildTimeline(events, "RUNNING", tryRoot, T0 + 5_000);
    const row = model.rows.find((item) => item.pointer === call);

    expect(row?.state).toBe("retrying");
    expect(row?.segments.map((segment) => segment.kind)).toEqual(["failed", "backoff"]);
    expect(instanceSummary(model.rows, "RUNNING", undefined).title).toBe("Retrying callPayments");
  });

  it("keeps a task announced but not started yet as waiting to start", () => {
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: "/do/0/validateOrder" }),
      event("task.completed", 500, { task: "/do/0/validateOrder" }),
      event("task.created", 500, { task: "/do/1/chargePayment", input: {} }),
    ];

    const model = buildTimeline(events, "RUNNING", root, T0 + 3_000);
    const charge = model.rows.find((row) => row.label === "chargePayment");

    expect(charge?.state).toBe("scheduled");
    expect(charge?.end).toBeUndefined();
    expect(charge?.segments.map((segment) => segment.kind)).toEqual(["scheduled"]);
    expect(instanceSummary(model.rows, "RUNNING", undefined).title).toBe("Waiting to start chargePayment");
  });

  describe("a task still open when the instance ends", () => {
    const forkRoot = parseWorkflowSource(`
document: { dsl: '1.0.0', namespace: shop, name: race, version: '1.0.0' }
do:
  - race:
      fork:
        compete: true
        branches:
          - fast:
              set: { winner: fast }
          - slow:
              call: http
              with: { method: get, endpoint: https://slow.example.com }
`).root;
    const fast = "/do/0/race/fork/branches/0/fast";
    const slow = "/do/0/race/fork/branches/1/slow";
    const opened = [
      event("workflow.started", 0),
      event("task.started", 0, { task: "/do/0/race" }),
      event("task.started", 0, { task: fast }),
      event("task.started", 0, { task: slow }),
      event("task.completed", 1_000, { task: fast }),
    ];

    it.each(["COMPLETED", "FAULTED"] as const)("is interrupted, not succeeded, when the instance is %s", (status) => {
      const ending = status === "COMPLETED" ? "workflow.completed" : "workflow.faulted";
      const model = buildTimeline([...opened, event(ending, 2_000)], status, forkRoot, T0 + 60_000);
      const row = model.rows.find((item) => item.pointer === slow);

      expect(row?.state).toBe("interrupted");
      expect(row?.segments.map((segment) => segment.kind)).toEqual(["interrupted"]);
      expect(row?.end).toBe(T0 + 2_000);
      expect(model.rows.find((item) => item.pointer === fast)?.state).toBe("completed");
    });

    it("leaves every task of a normal run completed", () => {
      const events = [
        ...opened,
        event("task.completed", 1_500, { task: slow }),
        event("task.completed", 1_500, { task: "/do/0/race" }),
        event("workflow.completed", 2_000),
      ];
      const model = buildTimeline(events, "COMPLETED", forkRoot, T0 + 60_000);

      expect(model.rows.map((row) => row.state)).toEqual(["completed", "completed", "completed"]);
    });
  });

  it("shows a listen task as waiting while the instance runs, and shortens the long wait", () => {
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: "/do/0/validateOrder" }),
      event("task.completed", 4_000, { task: "/do/0/validateOrder" }),
      event("task.started", 4_000, { task: "/do/2/waitForShipment" }),
    ];

    const now = T0 + 160_000;
    const model = buildTimeline(events, "RUNNING", root, now);
    const wait = model.rows.find((row) => row.label === "waitForShipment");

    expect(wait?.state).toBe("waiting");
    expect(wait?.segments[0].kind).toBe("waiting");
    expect(wait?.note).toBe("waiting for 2 min 36 s…");
    expect(model.ticks.some((tick) => tick.strong && tick.label.includes("2 min 36 s"))).toBe(true);
    expect(model.ticks[model.ticks.length - 1].label).toBe("now");
    // The four seconds of work keep a readable width despite the long wait.
    expect(model.toPct(T0 + 4_000)).toBeGreaterThan(50);

    expect(instanceSummary(model.rows, "RUNNING", undefined)).toEqual({
      title: "Waiting for shipment.created",
      text: "at /do/2/waitForShipment",
    });
  });

  it("names the failing task and the readable error of a faulted instance", () => {
    const error = { type: "https://serverlessworkflow.io/spec/1.0.0/errors/communication", status: 403, title: "HTTP 403: <!DOCTYPE html>" };
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: "/do/1/chargePayment" }),
      event("task.faulted", 4_900, { task: "/do/1/chargePayment", error }),
      event("workflow.faulted", 5_000, { error }),
    ];

    const model = buildTimeline(events, "FAULTED", root, T0 + 10_000);

    expect(model.rows[0].note).toBe("HTTP 403 after 4.9 s");
    expect(instanceSummary(model.rows, "FAULTED", error)).toEqual({
      title: "HTTP 403 Forbidden",
      text: "at /do/1/chargePayment",
    });
  });

  it("ignores blocks that are not named tasks", () => {
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: "/do" }),
      event("task.started", 0, { task: "/do/0/tryIt" }),
      event("task.started", 0, { task: "/do/0/tryIt/try" }),
      event("task.started", 1, { task: "/do/0/tryIt/try/0/getPet" }),
      event("task.completed", 5, { task: "/do/0/tryIt/try/0/getPet" }),
      event("task.completed", 6, { task: "/do/0/tryIt" }),
    ];

    const model = buildTimeline(events, "COMPLETED", undefined, T0 + 10);

    expect(model.rows.map((row) => [row.label, row.indent, row.isGroup])).toEqual([
      ["tryIt", 0, true],
      ["getPet", 1, false],
    ]);
  });
});
