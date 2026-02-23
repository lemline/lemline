import { OverviewContent } from "./overview-content";
import { buildPageTitle, useDocumentMeta } from "../hooks/use-document-meta";

export function RootOverviewPage() {
  useDocumentMeta(buildPageTitle([]));

  return <OverviewContent level="root" />;
}
