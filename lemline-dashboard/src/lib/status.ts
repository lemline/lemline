import { InstanceStatus } from "../gen/proto/lemline/gateway/v1/gateway_pb";

/** The three statuses a person sees. A pending instance is shown as running. */
export type UiStatus = "running" | "faulted" | "completed";

export const UI_STATUSES: UiStatus[] = ["running", "faulted", "completed"];

export function uiStatusOf(status: InstanceStatus | string | undefined): UiStatus {
  if (status === InstanceStatus.FAULTED || status === "FAULTED") return "faulted";
  if (status === InstanceStatus.COMPLETED || status === "COMPLETED") return "completed";
  return "running";
}

export const STATUS_LABEL: Record<UiStatus, string> = {
  running: "Running",
  faulted: "Faulted",
  completed: "Completed",
};

export const STATUS_TEXT_CLASS: Record<UiStatus, string> = {
  running: "text-lm-run",
  faulted: "text-lm-fail",
  completed: "text-lm-done",
};
