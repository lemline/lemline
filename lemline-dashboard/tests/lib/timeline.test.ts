import { describe, expect, it } from "vitest";
import { buildWorkflowProjection, type TimelineEventView } from "../../src/hooks/use-watch-workflow-projection";
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
      // As the runtime emits it: on the try block it re-enters, stamped with the planned retry
      // time (8 s after the failure).
      event("task.retried", 10_000, { task: "/do/0/chargePayment/try", retryCount: 1 }),
    ];

    const model = buildTimeline(events, "RUNNING", tryRoot, T0 + 5_000);
    const row = model.rows.find((item) => item.pointer === call);

    expect(row?.state).toBe("retrying");
    expect(row?.segments.map((segment) => segment.kind)).toEqual(["failed", "backoff"]);
    // The wait runs from the failure to now, not from the planned retry time.
    expect(row?.segments[1]).toMatchObject({ start: T0 + 2_000, end: T0 + 5_000 });
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

  it("keeps a retry in its row when it resumes before its planned time", () => {
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: "/do/1/chargePayment" }),
      event("task.faulted", 2_000, { task: "/do/1/chargePayment", error: { status: 503 } }),
      event("task.retried", 10_000, { task: "/do/1/chargePayment", retryCount: 1 }),
      // Stamped a little before the planned time (clocks of two workers).
      event("task.created", 9_900, { task: "/do/1/chargePayment" }),
      event("task.started", 9_950, { task: "/do/1/chargePayment" }),
      event("task.completed", 11_000, { task: "/do/1/chargePayment" }),
    ];

    // Given in stream order, and as the instance page gives them: with opaque cursors, numbered
    // as they arrive, then sorted by time.
    const timeSorted = buildWorkflowProjection(
      "wf-1",
      events.map((item, index) => ({
        ...item,
        sequence: btoa(JSON.stringify({ instanceId: "wf-1", sequence: 2_000 + index })),
        sortTimeMs: Date.parse(item.eventTime),
        streamIndex: index,
      })),
    ).events;
    expect(timeSorted.map((item) => item.eventType)).not.toEqual(events.map((item) => item.eventType));

    for (const given of [events, timeSorted]) {
      const model = buildTimeline(given, "RUNNING", root, T0 + 20_000);
      const rows = model.rows.filter((row) => row.label === "chargePayment");

      expect(rows).toHaveLength(1);
      expect(rows[0].attempts).toHaveLength(2);
      expect(rows[0].state).toBe("completed");
      expect(rows[0].segments.map((segment) => segment.kind)).toEqual(["failed", "backoff", "ran"]);
      expect(rows[0].segments[1]).toMatchObject({ start: T0 + 2_000, end: T0 + 9_900 });
    }
  });

  it("orders a task's events as they happened, not as the runtime wrote them", () => {
    const tryRoot = parseWorkflowSource(`
document: { dsl: '1.0.0', namespace: test, name: retry-check, version: '0.1.0' }
do:
  - tryIt:
      try:
        - fail:
            raise:
              error:
                type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
                status: 503
      catch:
        errors:
          with: { status: 503 }
        retry:
          delay: { seconds: 4 }
          limit:
            attempt: { count: 2 }
`).root;
    const block = "/do/0/tryIt/try";
    const fail = `${block}/0/fail`;
    const error = { status: 503, title: "Service Unavailable" };
    // The stream order recorded from a real instance: within one step, a retry is written
    // before the failure that caused it, and a start before its creation.
    const events = [
      event("workflow.started", 72),
      event("task.started", 88, { task: fail }),
      event("task.retried", 4_093, { task: block, retryCount: 1 }),
      event("task.created", 87, { task: fail }),
      event("task.faulted", 90, { task: fail, error }),
      event("task.created", 9_554, { task: fail }),
      event("task.started", 9_557, { task: fail }),
      event("task.retried", 13_560, { task: block, retryCount: 2 }),
      event("task.faulted", 9_558, { task: fail, error }),
      event("task.started", 19_508, { task: fail }),
      event("task.faulted", 19_509, { task: fail, error }),
      event("task.created", 19_506, { task: fail }),
      event("workflow.completed", 19_538),
    ];

    const model = buildTimeline(events, "COMPLETED", tryRoot, T0 + 60_000);
    const rows = model.rows.filter((row) => row.pointer === fail);

    expect(rows).toHaveLength(1);
    expect(rows[0].attempts.map((attempt) => attempt.outcome)).toEqual(["failed", "failed", "failed"]);
    expect(rows[0].segments.map((segment) => segment.kind)).toEqual(["failed", "backoff", "failed", "backoff", "failed"]);
    expect(rows[0].segments[1]).toMatchObject({ start: T0 + 90, end: T0 + 9_554 });
  });

  it("draws each run of a loop on its own row, the next one waiting to start", () => {
    const loopRoot = parseWorkflowSource(`
document: { dsl: '1.0.0', namespace: shop, name: batch, version: '1.0.0' }
do:
  - loop:
      for:
        each: item
        in: \${ .items }
      do:
        - step:
            set: { done: true }
`).root;
    const step = "/do/0/loop/do/0/step";
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: "/do/0/loop" }),
      event("task.created", 0, { task: step }),
      event("task.started", 100, { task: step }),
      event("task.completed", 1_000, { task: step }),
      event("task.created", 1_000, { task: step }),
      event("task.started", 1_100, { task: step }),
      event("task.completed", 2_000, { task: step }),
      event("task.created", 2_000, { task: step }),
    ];

    const model = buildTimeline(events, "RUNNING", loopRoot, T0 + 5_000);
    const runs = model.rows.filter((row) => row.pointer === step);

    expect(runs.map((row) => row.state)).toEqual(["completed", "completed", "scheduled"]);
    expect(runs.map((row) => row.iteration)).toEqual([1, 2, 3]);
    expect(new Set(model.rows.map((row) => row.id)).size).toBe(model.rows.length);
    expect(runs[1].start).toBe(T0 + 1_100);
    expect(runs[1].end).toBe(T0 + 2_000);
    expect(runs[2].typeLabel).toContain("run 3");
    expect(instanceSummary(model.rows, "RUNNING", undefined).title).toBe("Waiting to start step");
  });

  it("keeps the loop's lifetime separate from its repeated internal do block", () => {
    const { root } = parseWorkflowSource(`
do:
  - eachItem:
      for: { each: item, in: '\${ [1, 2] }' }
      do:
        - pause: { wait: { seconds: 4 } }
`);
    const loop = "/do/0/eachItem";
    const body = `${loop}/do`;
    const pause = `${body}/0/pause`;
    const events = [event("workflow.started", 0), event("task.created", 0, { task: loop }), event("task.started", 1, { task: loop })];
    for (let index = 0; index < 2; index++) {
      const start = index * 5_000;
      events.push(
        event("task.created", start + 2, { task: body }),
        event("task.started", start + 3, { task: body }),
        event("task.created", start + 4, { task: pause }),
        event("task.started", start + 5, { task: pause }),
        event("task.completed", start + 4_005, { task: pause }),
        event("task.completed", start + 4_006, { task: body }),
      );
    }
    events.push(event("task.completed", 9_007, { task: loop }), event("workflow.completed", 9_008));

    const model = buildTimeline(events, "COMPLETED", root, T0 + 60_000);

    expect(model.rows.map((row) => row.pointer)).toEqual([loop, pause, pause]);
    expect(model.rows[0]).toMatchObject({ typeLabel: "for", state: "completed", start: T0 + 1, end: T0 + 9_007, isGroup: true });
    expect(model.rows.slice(1).map((row) => row.iteration)).toEqual([1, 2]);
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

describe("extension lifecycle projection", () => {
  const owner = "/do/0/guarded/try/0/charge";
  const audit = `${owner}/_ext/0/after/do/0/writeAudit`;
  const source = parseWorkflowSource(`
use:
  extensions:
    - audit:
        extend: call
        after:
          - writeAudit:
              call: http
              with: { endpoint: https://example.com/audit }
do:
  - guarded:
      try:
        - charge:
            call: http
            with: { endpoint: https://example.com/charge }
      catch: {}
`).root;

  it("attaches a retry to the fault chain, closes owners, and preserves body duration", () => {
    const error = { status: 503, instance: audit };
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: owner }),
      event("task.started", 60_000, { task: audit }),
      event("task.faulted", 61_000, { task: audit, error }),
      event("task.faulted", 61_001, { task: owner, error }),
      event("task.retried", 62_000, { task: "/do/0/guarded/try", retryCount: 1 }),
      event("task.started", 62_000, { task: owner }),
      event("task.started", 122_000, { task: audit }),
      event("task.completed", 123_000, { task: audit, output: { logged: true } }),
      event("task.completed", 123_001, { task: owner, output: { paymentId: "p-2" } }),
      event("workflow.completed", 123_002),
    ];
    const model = buildTimeline(events, "COMPLETED", source, T0 + 124_000);
    const parent = model.rows.find((row) => row.pointer === owner)!;
    const child = model.rows.find((row) => row.pointer === audit)!;
    expect(parent.attempts).toHaveLength(2);
    expect(child.attempts).toHaveLength(2);
    expect(parent.state).toBe("completed");
    expect(parent.output).toEqual({ paymentId: "p-2" });
    expect(child.output).toEqual({ logged: true });
    expect(child.typeLabel).toContain("audit · after");
    expect(child.indent).toBe(parent.indent + 1);
    expect(child.ownerId).toBe(parent.id);
    expect(child.attempts.map((attempt) => attempt.ownerAttempt)).toEqual([1, 2]);
    expect(parent.typeLabel).toContain("2 attempts");
    expect(parent.detail).toContain("Attempt 1 failed");
    // The minute spent in the HTTP body is real activity, not a compressed gap.
    expect(model.toPct(T0 + 60_000)).toBeGreaterThan(40);
    expect(model.ticks.some((tick) => tick.strong)).toBe(false);
    const streaming = buildTimeline(events.slice(0, 5), "RUNNING", source, T0 + 61_010);
    expect(streaming.rows.find((row) => row.pointer === owner)?.state).toBe("failed");
  });
  it("associates extension steps with their owner iteration", () => {
    const events = [event("workflow.started", 0)];
    for (const start of [0, 10_000]) events.push(
      event("task.started", start, { task: owner }),
      event("task.started", start + 1000, { task: audit }),
      event("task.completed", start + 2000, { task: audit }),
      event("task.completed", start + 3000, { task: owner }),
    );
    events.push(event("workflow.completed", 14_000));
    const model = buildTimeline(events, "COMPLETED", source, T0 + 14_000);
    const children = model.rows.filter((row) => row.pointer === audit);
    expect(children.map((row) => row.ownerId)).toEqual([`${owner}#1`, `${owner}#2`]);
    expect(children.map((row) => row.ownerAttempt)).toEqual([1, 1]);
  });

  it("keeps a local after retry within one owner attempt", () => {
    const localSource = parseWorkflowSource(`
use:
  extensions:
    - audit:
        extend: call
        after:
          - auditRetry:
              try:
                - writeAudit: { call: http }
              catch: { retry: { delay: { seconds: 1 } } }
do:
  - guarded:
      try:
        - charge: { call: http }
      catch: {}
`).root;
    const local = `${owner}/_ext/0/after/do/0/auditRetry/try/0/writeAudit`;
    const error = { status: 503, instance: local };
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: owner }),
      event("task.started", 500, { task: local }),
      event("task.faulted", 600, { task: local, error }),
      event("task.retried", 1000, { task: `${owner}/_ext/0/after/do/0/auditRetry/try` }),
      event("task.started", 1000, { task: local }),
      event("task.completed", 1500, { task: local }),
      event("task.completed", 2000, { task: owner }),
      event("workflow.completed", 2001),
    ];
    const model = buildTimeline(events, "COMPLETED", localSource, T0 + 2001);
    expect(model.rows.find((row) => row.pointer === owner)?.attempts).toHaveLength(1);
    expect(model.rows.find((row) => row.pointer === local)?.attempts.map((attempt) => attempt.ownerAttempt)).toEqual([1, 1]);
  });

  it("draws a for task once, without a row for its synthetic body", () => {
    const loopRoot = parseWorkflowSource(`
document: { dsl: '1.0.0', namespace: shop, name: loop, version: '1.0.0' }
do:
  - loop:
      for: { each: item, in: '\${ .items }' }
      do:
        - step: { set: { seen: '\${ $item }' } }
`).root;
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: "/do/0/loop" }),
      ...[0, 1].flatMap((index) => [
        event("task.started", index * 100, { task: "/do/0/loop/do" }),
        event("task.started", index * 100, { task: "/do/0/loop/do/0/step" }),
        event("task.completed", index * 100 + 50, { task: "/do/0/loop/do/0/step" }),
        event("task.completed", index * 100 + 50, { task: "/do/0/loop/do" }),
      ]),
      event("task.completed", 200, { task: "/do/0/loop" }),
      event("workflow.completed", 200),
    ];

    const model = buildTimeline(events, "COMPLETED", loopRoot, T0 + 1_000);

    expect(model.rows.filter((row) => row.label === "loop").map((row) => row.pointer)).toEqual(["/do/0/loop"]);
  });

  it("shortens a long wait inside a function call", () => {
    const functionRoot = parseWorkflowSource(`
document: { dsl: '1.0.0', namespace: shop, name: slow, version: '1.0.0' }
use:
  functions:
    slow:
      do:
        - pause: { wait: { hours: 1 } }
do:
  - first: { set: { ok: true } }
  - invoke: { call: slow }
`).root;
    const hour = 3_600_000;
    const events = [
      event("workflow.started", 0),
      event("task.started", 0, { task: "/do/0/first" }),
      event("task.completed", 4_000, { task: "/do/0/first" }),
      event("task.started", 4_000, { task: "/do/1/invoke" }),
      event("task.started", 4_000, { task: "/do/1/invoke/_fn/do/0/pause" }),
      event("task.completed", 4_000 + hour, { task: "/do/1/invoke/_fn/do/0/pause" }),
      event("task.completed", 4_000 + hour, { task: "/do/1/invoke" }),
      event("workflow.completed", 4_000 + hour),
    ];

    const model = buildTimeline(events, "COMPLETED", functionRoot, T0 + 2 * hour);

    expect(model.rows.find((row) => row.pointer === "/do/1/invoke")?.hasActiveBody).toBe(false);
    expect(model.ticks.some((tick) => tick.strong)).toBe(true);
  });
});
