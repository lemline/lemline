import { useState } from "react";
import { Header } from "./header";
import { CommandPalette } from "./command-palette";
import { CascadingSelects } from "./cascading-selects";
import { useViewportWidth } from "../../hooks/use-viewport-width";

interface PageLayoutProps {
  children: React.ReactNode;
  hideScopeBar?: boolean;
}

export function PageLayout({ children, hideScopeBar }: PageLayoutProps) {
  const [paletteOpen, setPaletteOpen] = useState(false);
  const viewportWidth = useViewportWidth();
  const isMobile = viewportWidth < 768;

  return (
    <div className="min-h-screen bg-gradient-to-b from-slate-100 to-slate-50 text-slate-900 dark:from-slate-950 dark:to-slate-900 dark:text-slate-100">
      <Header onOpenPalette={() => setPaletteOpen(true)} />
      <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} />
      <main className="mx-auto flex w-full max-w-7xl flex-col gap-6 px-6 py-6">
        {isMobile && (
          <div className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-200">
            Best viewed on desktop. Mobile support is limited in v1.
          </div>
        )}
        {!hideScopeBar && <CascadingSelects />}
        {children}
      </main>
    </div>
  );
}
