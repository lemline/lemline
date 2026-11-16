import type { ReactNode } from "react";
import { useViewportWidth } from "../../hooks/use-viewport-width";
import { NamespaceDropdown, Sidebar } from "./sidebar";

export function PageLayout({ children }: { children: ReactNode }) {
  const width = useViewportWidth();
  const narrow = width < 900;

  return (
    <div
      className="relative flex min-h-screen w-full items-stretch bg-lm-bg font-sans text-lm-text"
      style={{ flexDirection: narrow ? "column" : "row" }}
    >
      <div
        className="sticky top-0 z-20 flex flex-col bg-lm-nav-bg"
        style={
          narrow
            ? { flex: "0 0 auto", width: "auto", alignSelf: "stretch", boxShadow: "inset 0 -1px 0 var(--lm-line)" }
            : { flex: "0 0 240px", width: 240, alignSelf: "flex-start", height: "100vh", minHeight: 480 }
        }
      >
        <Sidebar compact={narrow} />
      </div>

      <main className="flex min-w-0 flex-col" style={{ flex: narrow ? "1 1 auto" : "1 1 0" }}>
        {narrow && (
          <div
            className="relative z-[25] flex w-full flex-wrap items-center gap-2.5 border-b border-lm-line bg-lm-panel"
            style={{ padding: width < 640 ? "10px 16px" : "12px 40px" }}
          >
            <NamespaceDropdown stretch={width < 640} />
          </div>
        )}
        {children}
      </main>
    </div>
  );
}
