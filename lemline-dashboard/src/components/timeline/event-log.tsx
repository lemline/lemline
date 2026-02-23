import { Fragment, useEffect, useMemo, useState } from "react";
import { useTheme } from "next-themes";
import { Card } from "../ui/card";

interface TimelineEventLike {
  sequence: bigint | number | string;
  eventType: string;
  eventTime: string;
  taskName?: string;
  payloadJson: string;
}

interface EventLogProps {
  events: TimelineEventLike[];
}

export function EventLog({ events }: EventLogProps) {
  const [expanded, setExpanded] = useState<string | null>(null);
  const [highlightedHtml, setHighlightedHtml] = useState<string>("");
  const { resolvedTheme } = useTheme();

  const rows = useMemo(
    () =>
      events.map((event) => ({
        key: event.sequence.toString(),
        event,
      })),
    [events],
  );

  const expandedEvent = useMemo(
    () => rows.find((row) => row.key === expanded)?.event,
    [expanded, rows],
  );

  useEffect(() => {
    if (!expandedEvent) return;

    let active = true;
    const render = async () => {
      const source = prettyJson(expandedEvent.payloadJson);
      try {
        const { highlightJson } = await import("../../lib/shiki-highlighter");
        const rendered = await highlightJson(
          source,
          resolvedTheme === "dark" ? "github-dark" : "github-light",
        );
        if (active) setHighlightedHtml(rendered);
      } catch {
        if (active) setHighlightedHtml("");
      }
    };

    void render();

    return () => {
      active = false;
    };
  }, [expandedEvent, resolvedTheme]);

  return (
    <Card className="overflow-hidden p-0">
      <table className="w-full text-sm">
        <thead className="bg-slate-50 text-left text-slate-500 dark:bg-slate-900 dark:text-slate-400">
          <tr>
            <th className="px-4 py-2">Time</th>
            <th className="px-4 py-2">Event Type</th>
            <th className="px-4 py-2">Task</th>
            <th className="px-4 py-2">Details</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ key, event }) => {
            const isOpen = expanded === key;
            return (
              <Fragment key={key}>
                <tr className="border-t border-slate-200 dark:border-slate-800">
                  <td className="px-4 py-2">{event.eventTime ? new Date(event.eventTime).toLocaleString() : "-"}</td>
                  <td className="px-4 py-2">{event.eventType}</td>
                  <td className="px-4 py-2">{event.taskName ?? "Workflow"}</td>
                  <td className="px-4 py-2">
                    <button
                      type="button"
                      className="text-cyan-700 hover:underline dark:text-cyan-300"
                      onClick={() => setExpanded(isOpen ? null : key)}
                    >
                      {isOpen ? "Hide" : "View JSON"}
                    </button>
                  </td>
                </tr>
                {isOpen && (
                  <tr className="border-t border-slate-200 bg-slate-50 dark:border-slate-800 dark:bg-slate-900">
                    <td className="px-4 py-3" colSpan={4}>
                      {highlightedHtml && expandedEvent?.sequence.toString() === key ? (
                        <div
                          className="max-h-64 overflow-auto rounded border border-slate-200 bg-white p-2 text-xs dark:border-slate-700 dark:bg-slate-950/30"
                          dangerouslySetInnerHTML={{ __html: highlightedHtml }}
                        />
                      ) : (
                        <pre className="max-h-64 overflow-auto rounded bg-slate-950 p-3 text-xs text-slate-100">
                          {prettyJson(event.payloadJson)}
                        </pre>
                      )}
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
          {rows.length === 0 && (
            <tr>
              <td className="px-4 py-6 text-slate-500" colSpan={4}>
                No events recorded for this instance.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </Card>
  );
}

function prettyJson(value: string): string {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}
