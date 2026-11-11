import { fromJson, type JsonValue } from "@bufbuild/protobuf";
import { timestampFromDate, timestampDate, ValueSchema, type Duration, type Timestamp, type Value } from "@bufbuild/protobuf/wkt";

export const UNIX_EPOCH_ISO = "1970-01-01T00:00:00.000Z";

export function timestampFromIsoString(value?: string): Timestamp | undefined {
  if (!value) return undefined;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return undefined;
  return timestampFromDate(date);
}

export function timestampToIsoString(value?: Timestamp): string | undefined {
  return value ? timestampDate(value).toISOString() : undefined;
}

export function timestampToMilliseconds(value?: Timestamp | string): number | undefined {
  if (!value) return undefined;
  if (typeof value === "string") {
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? undefined : parsed;
  }
  return timestampDate(value).getTime();
}

export function durationToMilliseconds(
  value?: Duration | bigint | number | string,
): number | undefined {
  if (value === undefined) return undefined;
  if (typeof value === "object") {
    return Number(value.seconds) * 1000 + value.nanos / 1_000_000;
  }
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : undefined;
  }
  if (typeof value === "bigint") {
    return Number(value);
  }
  const parsed = Number(value);
  return Number.isNaN(parsed) ? undefined : parsed;
}

export function valueFromJsonText(jsonText: string): Value {
  return fromJson(ValueSchema, JSON.parse(jsonText) as JsonValue);
}
