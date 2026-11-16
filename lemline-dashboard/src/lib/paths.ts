import type { UiStatus } from "./status";
import type { PeriodKey } from "./period";

export interface InstancesScope {
  ns?: string;
  wf?: string;
  ver?: string;
  statuses?: UiStatus[];
  period?: PeriodKey;
}

/** /instances?wf=shop/order-processing&ver=1.2.0&status=faulted&period=all */
export function instancesPath(scope: InstancesScope = {}): string {
  const params = new URLSearchParams();
  if (scope.wf) params.set("wf", `${scope.ns ?? ""}/${scope.wf}`);
  if (scope.wf && scope.ver) params.set("ver", scope.ver);
  if (scope.statuses && scope.statuses.length > 0 && scope.statuses.length < 3) {
    params.set("status", scope.statuses.join(","));
  }
  if (scope.period && scope.period !== "24h") params.set("period", scope.period);
  const query = params.toString();
  return query ? `/instances?${query}` : "/instances";
}

export function definitionPath(ns: string, wf: string, version?: string): string {
  const base = `/definitions/${encodeURIComponent(ns)}/${encodeURIComponent(wf)}`;
  return version ? `${base}?version=${encodeURIComponent(version)}` : base;
}

export function instancePath(id: string): string {
  return `/instances/${encodeURIComponent(id)}`;
}
