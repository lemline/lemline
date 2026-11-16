import { useEffect, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { useDrawerClose, useDrawerPadding } from "../../hooks/use-layout";
import { CloseIcon } from "./icons";

interface DrawerProps {
  label: string;
  closeLabel: string;
  onClose: () => void;
  children: (close: () => void) => ReactNode;
}

export function Drawer({ label, closeLabel, onClose, children }: DrawerProps) {
  const { closing, close } = useDrawerClose(onClose);
  const panelRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") close();
    };
    window.addEventListener("keydown", onKey);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    panelRef.current?.focus();
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = previousOverflow;
    };
  }, [close]);

  return createPortal(
    <div
      className="lm-drawer-overlay fixed inset-0 z-50 flex justify-end bg-[var(--lm-overlay)] font-sans text-lm-text"
      data-closing={closing}
    >
      <button
        type="button"
        aria-label={closeLabel}
        onClick={close}
        className="flex-auto cursor-default border-0 bg-transparent"
      />
      <aside
        ref={panelRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className="lm-drawer-panel flex h-full min-w-0 flex-[0_1_560px] flex-col overflow-y-auto bg-lm-panel shadow-[-12px_0_32px_var(--lm-pop-shadow)] outline-none"
      >
        {children(close)}
      </aside>
    </div>,
    document.body,
  );
}

interface DrawerHeaderProps {
  title: ReactNode;
  subtitle?: ReactNode;
  onClose: () => void;
}

export function DrawerHeader({ title, subtitle, onClose }: DrawerHeaderProps) {
  const pad = useDrawerPadding();
  return (
    <header className="flex items-start justify-between gap-4 border-b border-lm-line" style={{ padding: pad.head }}>
      <div className="flex min-w-0 flex-col gap-2">
        {title}
        {subtitle}
      </div>
      <button
        type="button"
        aria-label="Close"
        onClick={onClose}
        className="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-lg border-0 bg-transparent text-lm-text2 hover:bg-lm-subtle"
      >
        <CloseIcon width={20} height={20} />
      </button>
    </header>
  );
}

export function DrawerBody({ children }: { children: ReactNode }) {
  const pad = useDrawerPadding();
  return (
    <div className="flex grow flex-col gap-6" style={{ padding: pad.body }}>
      {children}
    </div>
  );
}
