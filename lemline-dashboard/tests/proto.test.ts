import { create, toJson } from "@bufbuild/protobuf";
import { DurationSchema, ValueSchema } from "@bufbuild/protobuf/wkt";
import { describe, expect, it } from "vitest";
import {
  durationToMilliseconds,
  timestampFromIsoString,
  timestampToIsoString,
  timestampToMilliseconds,
  valueFromJsonText,
} from "../src/lib/proto";

describe("protobuf value conversions", () => {
  it("round-trips timestamps with milliseconds", () => {
    const iso = "2026-11-11T12:34:56.789Z";
    const timestamp = timestampFromIsoString(iso);
    expect(timestampToIsoString(timestamp)).toBe(iso);
    expect(timestampToMilliseconds(timestamp)).toBe(Date.parse(iso));
    expect(timestampFromIsoString("invalid")).toBeUndefined();
    expect(timestampToIsoString(undefined)).toBeUndefined();
  });

  it("converts protobuf durations and legacy millisecond values", () => {
    expect(durationToMilliseconds(create(DurationSchema, { seconds: 2n, nanos: 500_000_000 }))).toBe(2500);
    expect(durationToMilliseconds(2500n)).toBe(2500);
    expect(durationToMilliseconds("2500")).toBe(2500);
    expect(durationToMilliseconds(undefined)).toBeUndefined();
  });

  it("preserves structured workflow input as a protobuf Value", () => {
    const input = { nested: [null, true, 42, "hello"], object: { value: "test" } };
    expect(toJson(ValueSchema, valueFromJsonText(JSON.stringify(input)))).toEqual(input);
    expect(() => valueFromJsonText("invalid")).toThrow();
  });
});
