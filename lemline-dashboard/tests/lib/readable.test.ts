import { describe, expect, it } from "vitest";
import { readableError } from "../../src/lib/errors";
import { formatDayClock, formatRelative, formatSpan, shortInstanceId } from "../../src/lib/format";
import { instancesPath } from "../../src/lib/paths";
import { exampleInput, parseWorkflowSource, shortEventType, taskAt } from "../../src/lib/workflow-source";

describe("readableError", () => {
  it("replaces an HTML body sent back by the called server with the HTTP status", () => {
    expect(
      readableError({
        type: "https://open-workflow-specification.org/spec/1.0.0/errors/communication",
        status: 403,
        title: "HTTP 403: <!DOCTYPE html> <html lang..",
      }),
    ).toEqual({ headline: "HTTP 403 Forbidden", explanation: "The called server refused access" });
  });

  it("recognizes an HTTP error typed as runtime by its reason phrase", () => {
    expect(readableError({ type: "https://open-workflow-specification.org/spec/1.0.0/errors/runtime", status: 404, title: "Not Found" }).headline).toBe(
      "HTTP 404 Not Found",
    );
  });

  it("keeps the title of an error raised by the workflow", () => {
    expect(
      readableError({ type: "https://open-workflow-specification.org/errors/not-implemented", status: 500, title: "Not Implemented", detail: "Work in progress" }),
    ).toEqual({ headline: "Not Implemented", explanation: "Work in progress" });
  });
});

describe("formatting", () => {
  const now = Date.parse("2026-11-15T09:41:00");

  it("writes durations a person reads at a glance", () => {
    expect(formatSpan(12)).toBe("12 ms");
    expect(formatSpan(4_900)).toBe("4.9 s");
    expect(formatSpan(149_000)).toBe("2 min 29 s");
  });

  it("writes relative and day-clock dates on one line", () => {
    expect(formatRelative(now - 8 * 60_000, now)).toBe("8 min ago");
    expect(formatDayClock(Date.parse("2026-11-15T09:33:00"), now)).toBe("Today, 09:33");
    expect(formatDayClock(Date.parse("2026-11-14T18:02:00"), now)).toBe("Yesterday, 18:02");
  });

  it("keeps the start and the end of an instance ID", () => {
    expect(shortInstanceId("01a1d738-1f0b-7c44-8e21-77ad03c4da2f")).toBe("01a1d738…da2f");
  });
});

describe("workflow source", () => {
  it("tells how a workflow starts", () => {
    const event = parseWorkflowSource(
      "document: {name: set}\nschedule:\n  on:\n    one:\n      with:\n        type: com.lemline.samples.events.trigger.v1\ndo: []",
    );
    expect(event.trigger).toEqual({ kind: "event", eventTypes: ["com.lemline.samples.events.trigger.v1"] });
    expect(parseWorkflowSource("schedule:\n  every:\n    minutes: 5\ndo: []").trigger).toEqual({
      kind: "schedule",
      sentence: "A new instance starts every 5 min.",
    });
    expect(parseWorkflowSource("do: []").trigger).toEqual({ kind: "manual" });
    expect(exampleInput({ kind: "manual" })).toBe("{}");
  });

  it("describes a task from its position", () => {
    const { root } = parseWorkflowSource(
      "do:\n  - fan:\n      fork:\n        branches:\n          - a:\n              call: http\n          - b:\n              wait:\n                seconds: 30",
    );
    expect(taskAt(root, "/do/0/fan").typeLabel).toBe("fork · 2 branches");
    expect(taskAt(root, "/do/0/fan/fork/branches/0/a").typeLabel).toBe("call http");
    expect(taskAt(root, "/do/0/fan/fork/branches/1/b").typeLabel).toBe("wait · 30 s");
    expect(shortEventType("com.example.shipment.created")).toBe("shipment.created");
  });
});

describe("instancesPath", () => {
  it("keeps only the filters that differ from the defaults", () => {
    expect(instancesPath({})).toBe("/instances");
    expect(instancesPath({ ns: "shop", wf: "order-processing", ver: "1.2.0", statuses: ["faulted"], period: "all" })).toBe(
      "/instances?wf=shop%2Forder-processing&ver=1.2.0&status=faulted&period=all",
    );
  });
});
