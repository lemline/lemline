import { useCallback, useMemo, useState, type ReactNode } from "react";
import { NamespaceContext } from "./namespace-context";

const STORAGE_KEY = "lemline.dashboard.namespace";

function readStored(): string {
  try {
    return window.localStorage.getItem(STORAGE_KEY) ?? "";
  } catch {
    return "";
  }
}

export function NamespaceProvider({ children }: { children: ReactNode }) {
  const [namespace, setState] = useState<string>(() => readStored());

  const setNamespace = useCallback((next: string) => {
    setState(next);
    try {
      if (next) window.localStorage.setItem(STORAGE_KEY, next);
      else window.localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Storage may be unavailable (private mode): the choice then lasts for this tab only.
    }
  }, []);

  const value = useMemo(() => ({ namespace, setNamespace }), [namespace, setNamespace]);
  return <NamespaceContext.Provider value={value}>{children}</NamespaceContext.Provider>;
}
