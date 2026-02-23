import { Moon, Sun, Search } from "lucide-react";
import { useTheme } from "next-themes";
import { Button } from "../ui/button";

interface HeaderProps {
  onOpenPalette: () => void;
}

export function Header({ onOpenPalette }: HeaderProps) {
  const { resolvedTheme, setTheme } = useTheme();
  const isDark = resolvedTheme === "dark";

  return (
    <header className="sticky top-0 z-20 border-b border-slate-200 bg-white/95 backdrop-blur dark:border-slate-800 dark:bg-slate-950/95">
      <div className="mx-auto flex w-full max-w-7xl items-center justify-between px-6 py-4">
        <div>
          <p className="text-sm font-semibold uppercase tracking-[0.2em] text-cyan-600">Lemline</p>
          <h1 className="text-xl font-bold text-slate-900 dark:text-slate-100">Dashboard</h1>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={onOpenPalette} className="gap-2">
            <Search className="h-4 w-4" />
            <span>Cmd/Ctrl+K</span>
          </Button>
          <Button
            variant="outline"
            onClick={() => setTheme(isDark ? "light" : "dark")}
            aria-label="Toggle theme"
            className="w-10 px-0"
          >
            {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          </Button>
        </div>
      </div>
    </header>
  );
}
