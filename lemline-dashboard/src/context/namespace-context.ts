import { createContext, useContext } from "react";

/** The namespace picked once for the whole dashboard ("" = all namespaces). */
export interface NamespaceContextValue {
  namespace: string;
  setNamespace: (namespace: string) => void;
}

export const NamespaceContext = createContext<NamespaceContextValue>({
  namespace: "",
  setNamespace: () => {},
});

export function useGlobalNamespace(): NamespaceContextValue {
  return useContext(NamespaceContext);
}
