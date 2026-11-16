import { ClockIcon } from "./icons";

interface EmptyStateProps {
  title: string;
  text: string;
  primaryLabel?: string;
  onPrimary?: () => void;
  secondaryLabel?: string;
  onSecondary?: () => void;
}

export function EmptyState({ title, text, primaryLabel, onPrimary, secondaryLabel, onSecondary }: EmptyStateProps) {
  return (
    <section className="flex w-full flex-col items-center gap-3.5 rounded-[14px] border border-dashed border-lm-line-strong bg-lm-panel px-8 py-12 text-center">
      <ClockIcon width={40} height={40} strokeWidth={1.6} />
      <h2 className="m-0 text-xl font-bold">{title}</h2>
      <p className="m-0 max-w-[580px] text-[15px] leading-[1.55] text-lm-muted">{text}</p>
      {(secondaryLabel || primaryLabel) && (
        <div className="mt-1.5 flex flex-wrap justify-center gap-2">
          {secondaryLabel && (
            <button
              type="button"
              onClick={onSecondary}
              className="min-h-11 cursor-pointer rounded-lg border border-lm-line-strong bg-lm-panel px-4 text-[15px] font-semibold text-lm-text"
            >
              {secondaryLabel}
            </button>
          )}
          {primaryLabel && (
            <button
              type="button"
              onClick={onPrimary}
              className="min-h-11 cursor-pointer rounded-lg border-0 bg-lm-primary-bg px-4 text-[15px] font-semibold text-lm-primary-fg"
            >
              {primaryLabel}
            </button>
          )}
        </div>
      )}
    </section>
  );
}

/** Loading placeholder with the same frame as the content it replaces. */
export function LoadingBlock({ label, height = 240 }: { label: string; height?: number }) {
  return (
    <div
      role="status"
      aria-label={label}
      className="w-full animate-pulse rounded-[14px] border border-lm-line bg-lm-panel shadow-lm-card"
      style={{ height }}
    />
  );
}

export function ErrorBlock({ text, onRetry }: { text: string; onRetry?: () => void }) {
  return (
    <div className="flex flex-col items-start gap-3 rounded-[14px] border border-lm-fail-line bg-lm-fail-soft px-6 py-5">
      <p className="m-0 text-[15px] font-semibold text-lm-fail-strong">{text}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="min-h-10 cursor-pointer rounded-lg border border-lm-line-strong bg-lm-panel px-3.5 text-sm font-semibold text-lm-text"
        >
          Retry
        </button>
      )}
    </div>
  );
}
