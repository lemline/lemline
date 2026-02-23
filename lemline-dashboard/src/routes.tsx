import { lazy, Suspense, type ReactNode } from "react";
import { Navigate, Route, Routes } from "react-router-dom";

const RootOverviewPage = lazy(() =>
  import("./pages/root-overview").then((module) => ({ default: module.RootOverviewPage })),
);
const NamespaceOverviewPage = lazy(() =>
  import("./pages/namespace-overview").then((module) => ({ default: module.NamespaceOverviewPage })),
);
const WorkflowOverviewPage = lazy(() =>
  import("./pages/workflow-overview").then((module) => ({ default: module.WorkflowOverviewPage })),
);
const VersionOverviewPage = lazy(() =>
  import("./pages/version-overview").then((module) => ({ default: module.VersionOverviewPage })),
);
const InstanceDetailPage = lazy(() =>
  import("./pages/instance-detail").then((module) => ({ default: module.InstanceDetailPage })),
);
const StartWorkflowPage = lazy(() =>
  import("./pages/start-workflow").then((module) => ({ default: module.StartWorkflowPage })),
);

export function DashboardRoutes() {
  return (
    <Routes>
      <Route path="/" element={withSuspense(<RootOverviewPage />)} />
      <Route path="/ns/:namespace" element={withSuspense(<NamespaceOverviewPage />)} />
      <Route path="/ns/:namespace/name/:name" element={withSuspense(<WorkflowOverviewPage />)} />
      <Route
        path="/ns/:namespace/name/:name/v/:version"
        element={withSuspense(<VersionOverviewPage />)}
      />
      <Route path="/id/:workflowId" element={withSuspense(<InstanceDetailPage />)} />
      <Route path="/start" element={withSuspense(<StartWorkflowPage />)} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function withSuspense(element: ReactNode) {
  return (
    <Suspense fallback={<RouteLoading />}>
      {element}
    </Suspense>
  );
}

function RouteLoading() {
  return <p className="text-sm text-slate-500">Loading page...</p>;
}
