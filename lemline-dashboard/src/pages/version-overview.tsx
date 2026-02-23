import { lazy, Suspense, useState } from "react";
import { useParams } from "react-router-dom";
import { Tabs } from "../components/ui/tabs";
import { OverviewContent } from "./overview-content";
import { useDefinition } from "../hooks/use-definition";
import { buildPageTitle, useDocumentMeta } from "../hooks/use-document-meta";

const DefinitionViewer = lazy(() =>
  import("../components/workflow/definition-viewer").then((module) => ({
    default: module.DefinitionViewer,
  })),
);

export function VersionOverviewPage() {
  const { namespace, name, version } = useParams();
  const [tab, setTab] = useState("instances");
  const definition = useDefinition(namespace, name, version);

  useDocumentMeta(buildPageTitle([name ? `${name} v${version ?? "latest"}` : undefined, namespace]));

  return (
    <div className="space-y-4">
      <Tabs
        value={tab}
        onValueChange={setTab}
        options={[
          { value: "instances", label: "Instances" },
          { value: "definition", label: "Definition" },
        ]}
      />
      {tab === "instances" ? (
        <OverviewContent level="version" namespace={namespace} name={name} version={version} />
      ) : definition.data ? (
        <Suspense fallback={<p className="text-sm text-slate-500">Loading definition...</p>}>
          <DefinitionViewer
            definition={definition.data.definition}
            namespace={namespace}
            name={name}
            version={version}
          />
        </Suspense>
      ) : (
        <p className="text-sm text-slate-500">Loading definition...</p>
      )}
    </div>
  );
}
