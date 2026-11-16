import { useMemo } from "react";
import { useDefinitions } from "./use-definitions";
import { compareVersionsDesc } from "../lib/format";

/** One deployed workflow and its versions, newest first. */
export interface WorkflowEntry {
  ns: string;
  wf: string;
  versions: string[];
}

export function workflowKey(ns: string, wf: string): string {
  return `${ns}/${wf}`;
}

/** Every deployed workflow, sorted by namespace then name. */
export function useCatalog() {
  const query = useDefinitions({ latestOnly: false });
  const workflows = useMemo<WorkflowEntry[]>(() => {
    const map = new Map<string, WorkflowEntry>();
    for (const definition of query.data ?? []) {
      const key = workflowKey(definition.namespace, definition.name);
      const entry = map.get(key) ?? { ns: definition.namespace, wf: definition.name, versions: [] };
      if (!entry.versions.includes(definition.version)) entry.versions.push(definition.version);
      map.set(key, entry);
    }
    return Array.from(map.values())
      .map((entry) => ({ ...entry, versions: [...entry.versions].sort(compareVersionsDesc) }))
      .sort((left, right) => left.ns.localeCompare(right.ns) || left.wf.localeCompare(right.wf));
  }, [query.data]);

  const namespaces = useMemo(() => Array.from(new Set(workflows.map((entry) => entry.ns))), [workflows]);

  return { ...query, workflows, namespaces };
}
