import { useEffect, useState } from "react";
import { useTheme } from "next-themes";
import { Card } from "../ui/card";

interface DefinitionViewerProps {
  definition: string;
  namespace?: string;
  name?: string;
  version?: string;
}

export function DefinitionViewer({ definition, namespace, name, version }: DefinitionViewerProps) {
  const { resolvedTheme } = useTheme();
  const [html, setHtml] = useState("");

  useEffect(() => {
    let active = true;

    const render = async () => {
      try {
        const { highlightYaml } = await import("../../lib/shiki-highlighter");
        const rendered = await highlightYaml(
          definition,
          resolvedTheme === "dark" ? "github-dark" : "github-light",
        );
        if (active) setHtml(rendered);
      } catch {
        if (active) setHtml("");
      }
    };

    void render();

    return () => {
      active = false;
    };
  }, [definition, resolvedTheme]);

  return (
    <Card className="space-y-4">
      <div className="grid gap-3 rounded-lg border border-slate-200 bg-slate-50 px-4 py-3 text-sm dark:border-slate-800 dark:bg-slate-900 sm:grid-cols-3">
        <MetadataField label="Namespace" value={namespace ?? "\u2014"} />
        <MetadataField label="Name" value={name ?? "\u2014"} />
        <MetadataField label="Version" value={version ?? "\u2014"} />
      </div>
      {html ? (
        <div className="overflow-auto rounded-lg border border-slate-200 bg-white p-3 dark:border-slate-800 dark:bg-slate-950" dangerouslySetInnerHTML={{ __html: html }} />
      ) : (
        <pre className="overflow-auto rounded-lg border border-slate-200 bg-white p-3 text-sm dark:border-slate-800 dark:bg-slate-950">{definition}</pre>
      )}
    </Card>
  );
}

interface MetadataFieldProps {
  label: string;
  value: string;
}

function MetadataField({ label, value }: MetadataFieldProps) {
  return (
    <div className="min-w-0">
      <p className="text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">{label}</p>
      <p className="truncate font-medium text-slate-800 dark:text-slate-100">{value}</p>
    </div>
  );
}
