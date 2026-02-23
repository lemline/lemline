import { useParams } from "react-router-dom";
import { OverviewContent } from "./overview-content";
import { buildPageTitle, useDocumentMeta } from "../hooks/use-document-meta";

export function WorkflowOverviewPage() {
  const { namespace, name } = useParams();
  useDocumentMeta(buildPageTitle([name, namespace]));

  return <OverviewContent level="workflow" namespace={namespace} name={name} />;
}
