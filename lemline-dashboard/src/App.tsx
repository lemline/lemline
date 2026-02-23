import { useLocation } from "react-router-dom";
import { PageLayout } from "./components/layout/page-layout";
import { DashboardRoutes } from "./routes";

export default function App() {
  const location = useLocation();
  const hideScopeBar = location.pathname.startsWith("/start");

  return (
    <PageLayout hideScopeBar={hideScopeBar}>
      <DashboardRoutes />
    </PageLayout>
  );
}
