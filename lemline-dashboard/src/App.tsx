import { PageLayout } from "./components/layout/page-layout";
import { DashboardRoutes } from "./routes";

export default function App() {
  return (
    <PageLayout>
      <DashboardRoutes />
    </PageLayout>
  );
}
