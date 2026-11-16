import { useTheme } from "next-themes";
import { useLocation, useNavigate } from "react-router-dom";
import { useGlobalNamespace } from "../../context/namespace-context";
import { useCatalog } from "../../hooks/use-catalog";
import { cn } from "../../lib/utils";
import { Dropdown } from "../lm/dropdown";
import { DefinitionsIcon, InstancesIcon, MoonIcon, SettingsIcon, SunIcon } from "../lm/icons";

type Section = "instances" | "definitions" | "settings";

function useActiveSection(): Section {
  const { pathname } = useLocation();
  if (pathname.startsWith("/settings")) return "settings";
  return pathname.startsWith("/definitions") ? "definitions" : "instances";
}

export function NamespaceDropdown({ block, stretch }: { block?: boolean; stretch?: boolean }) {
  const { namespace, setNamespace } = useGlobalNamespace();
  const { namespaces } = useCatalog();
  const options = [{ value: "", label: "All namespaces" }].concat(
    // Keep a namespace picked earlier visible even if it has no workflow any more.
    Array.from(new Set([...namespaces, ...(namespace ? [namespace] : [])])).map((ns) => ({ value: ns, label: ns })),
  );
  return (
    <Dropdown
      block={block}
      label={block ? undefined : "Namespace"}
      listLabel="Namespace"
      value={namespace}
      valueLabel={namespace || (block ? "All namespaces" : "All")}
      options={options}
      onChange={setNamespace}
      active={Boolean(namespace)}
      stretch={stretch}
    />
  );
}

export function ThemeToggle({ compact }: { compact: boolean }) {
  const { resolvedTheme, setTheme } = useTheme();
  const dark = resolvedTheme === "dark";
  const aria = dark ? "Switch to the light theme" : "Switch to the dark theme";
  return (
    <button
      type="button"
      onClick={() => setTheme(dark ? "light" : "dark")}
      aria-label={aria}
      title={aria}
      className="flex min-h-10 cursor-pointer items-center gap-2 rounded-[10px] border-[1.5px] border-lm-line bg-lm-panel px-2.5 text-[13px] font-semibold text-lm-text2"
    >
      {dark ? <SunIcon /> : <MoonIcon />}
      {!compact && (dark ? "Light theme" : "Dark theme")}
    </button>
  );
}

function NavButton({
  label,
  icon,
  current,
  compact,
  onClick,
}: {
  label?: string;
  icon: React.ReactNode;
  current: boolean;
  compact: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-current={current ? "page" : undefined}
      aria-label={label ? undefined : "Settings"}
      title={label ? undefined : "Settings"}
      className={cn(
        "flex min-h-11 cursor-pointer items-center rounded-[10px] border-[1.5px] border-transparent text-left text-[15px]",
        compact ? "gap-2 px-2.5" : "gap-2.5 px-3",
        current ? "bg-lm-nav-active font-[650] text-lm-title" : "bg-transparent font-medium text-lm-text2 hover:bg-lm-subtle2",
      )}
    >
      {icon}
      {label}
    </button>
  );
}

/**
 * Left menu on wide screens; a top bar under 900 px.
 * `hideTheme`: the layout shows the theme toggle elsewhere (phones, where the top bar is full).
 */
export function Sidebar({ compact, hideTheme = false }: { compact: boolean; hideTheme?: boolean }) {
  const navigate = useNavigate();
  const active = useActiveSection();

  return (
    <nav
      aria-label="Main"
      className={cn(
        "box-border flex h-full w-full bg-lm-nav-bg font-sans text-lm-text",
        compact
          ? "flex-row items-center gap-2 border-b border-lm-line px-3 py-2"
          : "min-h-[max(480px,100vh)] flex-col items-stretch gap-6 border-r border-lm-line px-4 py-6",
      )}
    >
      <p className="mx-1 my-0 flex shrink-0 items-center gap-2.5 font-display text-xl font-bold text-lm-title">
        <img
          src="/lemly.png"
          alt="Lemly, the Lemline mascot"
          className="rounded-xl object-cover"
          style={{ width: compact ? 34 : 44, height: compact ? 34 : 44, boxShadow: "var(--lm-logo-ring)" }}
        />
        {!compact && "lemline"}
      </p>

      {!compact && (
        <div className="relative flex flex-col gap-1.5">
          <span className="mx-1 text-xs font-semibold uppercase tracking-[0.06em] text-lm-muted">Namespace</span>
          <NamespaceDropdown block />
        </div>
      )}

      <div className={cn("flex gap-1", compact ? "flex-row" : "flex-col")}>
        <NavButton
          label="Instances"
          icon={<InstancesIcon />}
          current={active === "instances"}
          compact={compact}
          onClick={() => navigate("/instances")}
        />
        <NavButton
          label="Definitions"
          icon={<DefinitionsIcon />}
          current={active === "definitions"}
          compact={compact}
          onClick={() => navigate("/definitions")}
        />
      </div>

      <div className={cn("flex gap-2.5", compact ? "ml-auto flex-row items-center" : "mt-auto flex-col items-stretch")}>
        <NavButton
          label={compact ? undefined : "Settings"}
          icon={<SettingsIcon />}
          current={active === "settings"}
          compact={compact}
          onClick={() => navigate("/settings")}
        />
        {!hideTheme && (
          <div className={compact ? undefined : "self-start"}>
            <ThemeToggle compact={compact} />
          </div>
        )}
      </div>
    </nav>
  );
}
