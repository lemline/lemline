import { useSearchParams } from "react-router-dom";
import { StartForm } from "../components/workflow/start-form";
import { buildPageTitle, useDocumentMeta } from "../hooks/use-document-meta";

export function StartWorkflowPage() {
  const [searchParams] = useSearchParams();
  const namespace = searchParams.get("namespace") ?? undefined;
  const name = searchParams.get("name") ?? undefined;
  const version = searchParams.get("version") ?? undefined;

  useDocumentMeta(buildPageTitle(["Start Workflow"]));

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold">Start workflow</h2>
      <StartForm initialNamespace={namespace} initialName={name} initialVersion={version} />
    </div>
  );
}
