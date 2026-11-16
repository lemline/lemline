import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement> & { size?: number };

function Svg({ size = 16, children, strokeWidth = 2, ...rest }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...rest}
    >
      {children}
    </svg>
  );
}

export const ChevronDownIcon = (props: IconProps) => (
  <Svg size={14} {...props}>
    <path d="m6 9 6 6 6-6" />
  </Svg>
);

export const ChevronRightIcon = (props: IconProps) => (
  <Svg size={13} strokeWidth={2.2} {...props}>
    <path d="M9 6l6 6-6 6" />
  </Svg>
);

export const ArrowRightIcon = (props: IconProps) => (
  <Svg size={15} {...props}>
    <path d="M5 12h14" />
    <path d="m13 6 6 6-6 6" />
  </Svg>
);

export const PlayIcon = (props: IconProps) => (
  <Svg {...props}>
    <path d="M7 4v16l13-8z" />
  </Svg>
);

export const SearchIcon = (props: IconProps) => (
  <Svg {...props}>
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5" />
  </Svg>
);

export const CloseIcon = (props: IconProps) => (
  <Svg size={14} {...props}>
    <path d="M6 6l12 12M18 6 6 18" />
  </Svg>
);

export const CopyIcon = (props: IconProps) => (
  <Svg size={15} {...props}>
    <rect x="9" y="9" width="12" height="12" rx="2" />
    <path d="M5 15V5a2 2 0 0 1 2-2h10" />
  </Svg>
);

export const InstancesIcon = (props: IconProps) => (
  <Svg size={18} {...props}>
    <path d="M3 12h4l3 8 4-16 3 8h4" />
  </Svg>
);

export const DefinitionsIcon = (props: IconProps) => (
  <Svg size={18} {...props}>
    <rect x="3" y="3" width="7" height="7" rx="1" />
    <rect x="14" y="14" width="7" height="7" rx="1" />
    <path d="M10 6.5h4a2 2 0 0 1 2 2V14" />
  </Svg>
);

export const MoonIcon = (props: IconProps) => (
  <Svg {...props}>
    <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
  </Svg>
);

export const SunIcon = (props: IconProps) => (
  <Svg {...props}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </Svg>
);

export const BoltIcon = (props: IconProps) => (
  <Svg size={22} {...props}>
    <path d="M13 2 4 14h7l-1 8 9-12h-7z" />
  </Svg>
);

export const ClockIcon = (props: IconProps) => (
  <Svg size={22} {...props}>
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 2" />
  </Svg>
);

export const WarningIcon = (props: IconProps) => (
  <Svg size={20} {...props}>
    <path d="M12 3 2 21h20z" />
    <path d="M12 10v5" />
    <path d="M12 18v.5" />
  </Svg>
);

export function StatusDot({ size = 8 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
      <circle cx={size / 2} cy={size / 2} r={size / 2} fill="currentColor" />
    </svg>
  );
}
