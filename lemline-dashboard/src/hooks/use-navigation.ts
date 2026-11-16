import { useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { useGlobalNamespace } from "../context/namespace-context";
import { definitionPath, instancePath, instancesPath, type InstancesScope } from "../lib/paths";

/** Navigation shared by every page, keeping the global namespace consistent with the target. */
export function useDashboardNavigation() {
  const navigate = useNavigate();
  const { namespace, setNamespace } = useGlobalNamespace();

  const seeInstances = useCallback(
    (scope: InstancesScope) => {
      if (namespace && scope.ns && scope.ns !== namespace) setNamespace(scope.ns);
      navigate(instancesPath(scope));
      window.scrollTo(0, 0);
    },
    [namespace, navigate, setNamespace],
  );

  const openDefinition = useCallback(
    (ns: string, wf: string, version?: string) => {
      navigate(definitionPath(ns, wf, version));
      window.scrollTo(0, 0);
    },
    [navigate],
  );

  /** `justStarted`: the instance was just started, so the page waits for it to appear. */
  const openInstance = useCallback(
    (id: string, options?: { justStarted?: boolean }) => {
      navigate(instancePath(id), options?.justStarted ? { state: { justStarted: true } } : undefined);
      window.scrollTo(0, 0);
    },
    [navigate],
  );

  return { navigate, seeInstances, openDefinition, openInstance };
}
