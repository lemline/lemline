import { create } from "@bufbuild/protobuf";
import { useQueries } from "@tanstack/react-query";
import {
  GetDefinitionRequestSchema,
  ListInstanceEventsRequestSchema,
  type InstanceSummary,
} from "../gen/proto/lemline/gateway/v1/gateway_pb";
import { gatewayClient } from "../lib/grpc-client";
import { formatSpan } from "../lib/format";
import { uiStatusOf } from "../lib/status";
import { buildTimeline, instanceSummary } from "../lib/timeline";
import { timestampToIsoString } from "../lib/proto";
import { parseWorkflowSource } from "../lib/workflow-source";
import type { TimelineEventView } from "./use-watch-workflow-projection";

const REFRESH_MS = 5_000;

export interface RunningReason {
  reason: string;
  reasonSub: string;
}

/**
 * For the running instances shown in a list, what each one is doing now:
 * "Waiting for shipment.created · since 2 min 40 s", "Running callApi · since 3 s".
 * Reads the lifecycle events of each running instance on the page only.
 */
export function useRunningReasons(instances: InstanceSummary[], nowMs: number): Map<string, RunningReason> {
  const running = instances.filter((instance) => uiStatusOf(instance.status) === "running");

  const eventQueries = useQueries({
    queries: running.map((instance) => ({
      queryKey: ["instance-events", instance.instanceId],
      refetchInterval: REFRESH_MS,
      queryFn: async ({ signal }: { signal: AbortSignal }) => {
        const response = await gatewayClient.listInstanceEvents(
          create(ListInstanceEventsRequestSchema, { instanceId: instance.instanceId, pageSize: 500 }),
          { signal },
        );
        return response.events.map<TimelineEventView>((event) => {
          let data: Record<string, unknown> = {};
          try {
            data = (JSON.parse(event.cloudEventJson).data ?? {}) as Record<string, unknown>;
          } catch {
            // Unreadable event: no pointer.
          }
          const pointer = typeof data.task === "string" ? data.task : undefined;
          return {
            sequence: event.cursor,
            eventType: event.eventType,
            eventTime: timestampToIsoString(event.eventTime) ?? "",
            category: event.eventType.includes(".task.") ? "TASK" : "WORKFLOW",
            taskPointer: pointer,
            taskName: pointer?.split("/").pop(),
            payloadJson: event.cloudEventJson,
          };
        });
      },
    })),
  });

  const workflows = Array.from(new Set(running.map((instance) => `${instance.namespace}\n${instance.name}\n${instance.version}`)));
  const definitionQueries = useQueries({
    queries: workflows.map((key) => {
      const [namespace, name, version] = key.split("\n");
      return {
        queryKey: ["definition", namespace, name, version],
        staleTime: 60_000,
        queryFn: async () =>
          (await gatewayClient.getDefinition(create(GetDefinitionRequestSchema, { namespace, name, version }))).definition,
      };
    }),
  });

  const result = new Map<string, RunningReason>();
  running.forEach((instance, index) => {
    const events = eventQueries[index]?.data;
    if (!events) return;
    const key = `${instance.namespace}\n${instance.name}\n${instance.version}`;
    const source = definitionQueries[workflows.indexOf(key)]?.data?.source;
    const model = buildTimeline(events, "RUNNING", parseWorkflowSource(source).root, nowMs);
    const summary = instanceSummary(model.rows, "RUNNING", undefined);
    const active = model.rows.filter((row) => !row.isGroup && row.end === undefined);
    const current = active[active.length - 1];
    if (!summary.title) return;
    result.set(instance.instanceId, {
      reason: summary.title,
      reasonSub: current ? `Since ${formatSpan(Math.max(0, nowMs - current.start))}` : "",
    });
  });
  return result;
}
