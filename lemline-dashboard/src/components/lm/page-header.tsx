import type { ReactNode } from "react";
import { useViewportWidth } from "../../hooks/use-viewport-width";

export interface TrailItem {
  label: string;
  onClick: () => void;
}

interface PageHeaderProps {
  title: ReactNode;
  /** Parent pages, shown before the title: "Definitions / ". */
  trail?: TrailItem[];
  /** Muted text before the title: "test / ". */
  prefix?: string;
  /** Monospace title (instance IDs). */
  mono?: boolean;
}

export function PageHeader({ title, trail = [], prefix, mono }: PageHeaderProps) {
  const narrow = useViewportWidth() < 720;
  return (
    <header
      className="flex w-full flex-col gap-3.5 border-b border-lm-line bg-lm-panel"
      style={{ padding: narrow ? "16px 16px 0" : "20px 40px 0" }}
    >
      <div className="flex flex-wrap items-center justify-between gap-4 pb-5">
        <h1
          className="m-0 font-display font-bold text-lm-text [overflow-wrap:anywhere]"
          style={{ fontSize: narrow ? 22 : 26 }}
        >
          {trail.map((item) => (
            <span key={item.label}>
              <button
                type="button"
                onClick={item.onClick}
                className="cursor-pointer border-0 bg-transparent p-0 font-[inherit] text-[length:inherit] font-medium text-lm-muted hover:text-lm-accent"
              >
                {item.label}
              </button>
              <span className="font-medium text-lm-muted"> / </span>
            </span>
          ))}
          {prefix && <span className="font-medium text-lm-muted">{prefix} / </span>}
          <span className={mono ? "font-mono text-[0.86em] font-semibold" : undefined}>{title}</span>
        </h1>
      </div>
    </header>
  );
}

export function SectionTitle({ children }: { children: ReactNode }) {
  return <h2 className="m-0 font-display text-lg font-semibold text-lm-title">{children}</h2>;
}
