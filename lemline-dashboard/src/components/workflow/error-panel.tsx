import { useEffect, useState } from "react";
import { useTheme } from "next-themes";
import { Card } from "../ui/card";
import { Button } from "../ui/button";

interface ErrorPanelProps {
  title: string;
  type: string;
  status: number;
  payloadJson: string;
}

export function ErrorPanel({ title, type, status, payloadJson }: ErrorPanelProps) {
  const [open, setOpen] = useState(false);
  const [highlightedHtml, setHighlightedHtml] = useState("");
  const { resolvedTheme } = useTheme();

  useEffect(() => {
    if (!open) return;

    let active = true;
    const render = async () => {
      try {
        const { highlightJson } = await import("../../lib/shiki-highlighter");
        const rendered = await highlightJson(
          payloadJson,
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
  }, [open, payloadJson, resolvedTheme]);

  return (
    <Card className="border-red-300 bg-red-50 dark:border-red-900 dark:bg-red-950/30">
      <p className="text-sm font-semibold text-red-700 dark:text-red-300">Faulted workflow</p>
      <p className="mt-1 text-lg font-bold text-red-800 dark:text-red-200">{title}</p>
      <p className="text-sm text-red-700 dark:text-red-300">
        Type: {type} | Status: {status}
      </p>
      <div className="mt-3">
        <Button variant="outline" onClick={() => setOpen((current) => !current)}>
          {open ? "Hide full error details" : "Show full error details"}
        </Button>
      </div>
      {open && (
        highlightedHtml ? (
          <div
            className="mt-3 max-h-64 overflow-auto rounded border border-slate-200 bg-white p-2 text-xs dark:border-slate-700 dark:bg-slate-950/30"
            dangerouslySetInnerHTML={{ __html: highlightedHtml }}
          />
        ) : (
          <pre className="mt-3 max-h-64 overflow-auto rounded bg-slate-950 p-3 text-xs text-slate-100">
            {payloadJson}
          </pre>
        )
      )}
    </Card>
  );
}
