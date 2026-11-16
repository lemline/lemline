import { lazy, Suspense, useEffect, type ReactNode } from "react";
import { Navigate, Route, Routes, useNavigate, useParams } from "react-router-dom";
import { useGlobalNamespace } from "./context/namespace-context";
import { instancesPath } from "./lib/paths";

const InstancesPage = lazy(() => import("./pages/instances").then((module) => ({ default: module.InstancesPage })));
const DefinitionsPage = lazy(() =>
  import("./pages/definitions").then((module) => ({ default: module.DefinitionsPage })),
);
const DefinitionPage = lazy(() => import("./pages/definition").then((module) => ({ default: module.DefinitionPage })));
const InstancePage = lazy(() => import("./pages/instance").then((module) => ({ default: module.InstancePage })));

export function DashboardRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/instances" replace />} />
      <Route path="/instances" element={withSuspense(<InstancesPage />)} />
      <Route path="/instances/:instanceId" element={withSuspense(<InstancePage />)} />
      <Route path="/definitions" element={withSuspense(<DefinitionsPage />)} />
      <Route path="/definitions/:namespace/:name" element={withSuspense(<DefinitionPage />)} />

      {/* Addresses of the previous dashboard keep working. */}
      <Route path="/id/:instanceId" element={<LegacyInstanceRedirect />} />
      <Route path="/ns/:namespace" element={<LegacyScopeRedirect />} />
      <Route path="/ns/:namespace/name/:name" element={<LegacyScopeRedirect />} />
      <Route path="/ns/:namespace/name/:name/version/:version" element={<LegacyScopeRedirect />} />
      <Route path="/ns/:namespace/name/:name/v/:version" element={<LegacyScopeRedirect />} />
      <Route path="/start" element={<Navigate to="/definitions" replace />} />
      <Route path="*" element={<Navigate to="/instances" replace />} />
    </Routes>
  );
}

function LegacyInstanceRedirect() {
  const { instanceId = "" } = useParams();
  return <Navigate to={`/instances/${encodeURIComponent(instanceId)}`} replace />;
}

function LegacyScopeRedirect() {
  const { namespace = "", name = "", version = "" } = useParams();
  if (!name) return <LegacyNamespaceRedirect namespace={namespace} />;
  return <Navigate to={instancesPath({ ns: namespace, wf: name, ver: version })} replace />;
}

/** "/ns/shop" showed one namespace: the workflows page shows it once it is the namespace picked. */
function LegacyNamespaceRedirect({ namespace }: { namespace: string }) {
  const { setNamespace } = useGlobalNamespace();
  const navigate = useNavigate();
  useEffect(() => {
    setNamespace(namespace);
    navigate("/definitions", { replace: true });
  }, [namespace, navigate, setNamespace]);
  return null;
}

function withSuspense(element: ReactNode) {
  return <Suspense fallback={<RouteLoading />}>{element}</Suspense>;
}

function RouteLoading() {
  return <p className="m-10 text-sm text-lm-muted">Loading…</p>;
}
