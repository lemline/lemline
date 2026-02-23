import { useParams } from "react-router-dom";
import { OverviewContent } from "./overview-content";
import { buildPageTitle, useDocumentMeta } from "../hooks/use-document-meta";

export function NamespaceOverviewPage() {
  const { namespace } = useParams();
  useDocumentMeta(buildPageTitle([namespace]));

  return <OverviewContent level="namespace" namespace={namespace} />;
}
