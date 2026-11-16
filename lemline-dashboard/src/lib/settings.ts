import {
  ConfigFileLocator,
  SettingOrigin,
  type ConfigFile,
  type GatewaySetting,
} from "../gen/proto/lemline/gateway/v1/gateway_pb";

/** Origins that win over the configuration file. */
const ABOVE_FILE = new Set([SettingOrigin.SYSTEM_PROPERTY, SettingOrigin.ENVIRONMENT]);

export type OriginKind = "cli" | "env" | "file" | "app" | "default";

export function originKind(origin: SettingOrigin): OriginKind {
  switch (origin) {
    case SettingOrigin.SYSTEM_PROPERTY:
      return "cli";
    case SettingOrigin.ENVIRONMENT:
      return "env";
    case SettingOrigin.CONFIG_FILE:
      return "file";
    case SettingOrigin.APPLICATION:
      return "app";
    default:
      return "default";
  }
}

export function originLabel(origin: SettingOrigin): string {
  switch (origin) {
    case SettingOrigin.SYSTEM_PROPERTY:
      return "Command line";
    case SettingOrigin.ENVIRONMENT:
      return "Environment";
    case SettingOrigin.CONFIG_FILE:
      return "Configuration file";
    case SettingOrigin.APPLICATION:
      return "Packaged with Lemline";
    case SettingOrigin.DEFAULT:
      return "Default";
    default:
      return "Not set";
  }
}

/** What to look for at the origin: "-Dlemline.gateway.cors.origins", "LEMLINE_GATEWAY_CORS_ORIGINS". */
export function originName(origin: SettingOrigin, detail: string): string {
  if (!detail) return "";
  return origin === SettingOrigin.SYSTEM_PROPERTY ? `-D${detail}` : detail;
}

export function locatorLabel(locator: ConfigFileLocator): string {
  switch (locator) {
    case ConfigFileLocator.OPTION:
      return "Passed with --config";
    case ConfigFileLocator.ENVIRONMENT:
      return "From LEMLINE_CONFIG";
    case ConfigFileLocator.DEFAULT_PATH:
      return "Found at a default location";
    default:
      return "";
  }
}

export function fileName(path: string): string {
  return path.split(/[\\/]/).pop() || path;
}

export interface FileLine {
  number: number;
  indent: string;
  body: string;
  comment: boolean;
  secret: boolean;
  /** Set when a higher-priority origin replaces the value written on this line. */
  overriddenBy?: { kind: OriginKind; label: string };
}

/** Splits the file into numbered lines and marks the ones another origin replaces. */
export function fileLines(file: ConfigFile): FileLine[] {
  const byLine = new Map(file.keys.map((key) => [key.line, key]));
  return (file.text ?? "")
    .replace(/\n$/, "")
    .split("\n")
    .map((text, index) => {
      const key = byLine.get(index + 1);
      const overridden = key && ABOVE_FILE.has(key.overriddenBy);
      const body = text.trimStart();
      return {
        number: index + 1,
        indent: text.slice(0, text.length - body.length),
        body,
        comment: body.startsWith("#"),
        secret: Boolean(key?.secret),
        overriddenBy: overridden
          ? {
              kind: originKind(key.overriddenBy),
              label: `Overridden by ${originName(key.overriddenBy, key.overriddenByDetail)}`,
            }
          : undefined,
      };
    });
}

export interface OutsideRow {
  key: string;
  /** "gateway.cors.origins": the key without its "lemline." prefix. */
  label: string;
  value: string;
  secret: boolean;
  kind: OriginKind;
  origin: string;
  name: string;
  /** "Replaces line 26 of lemline.yaml", "Not in lemline.yaml", or empty without a file. */
  relation: string;
}

/** Settings the gateway received from its environment or command line: the file cannot show them. */
export function outsideFileRows(settings: GatewaySetting[], file?: ConfigFile): OutsideRow[] {
  const lines = new Map((file?.keys ?? []).map((key) => [key.key, key.line]));
  const name = file ? fileName(file.path) : "";
  return settings
    .filter((setting) => ABOVE_FILE.has(setting.origin))
    .map((setting) => {
      const line = lines.get(setting.key);
      return {
        key: setting.key,
        label: setting.key.replace(/^lemline\./, ""),
        value: setting.secret ? (setting.isSet ? "Set (hidden)" : "Not set") : (setting.value ?? ""),
        secret: setting.secret,
        kind: originKind(setting.origin),
        origin: originLabel(setting.origin),
        name: originName(setting.origin, setting.originDetail),
        relation: !file ? "" : line ? `Replaces line ${line} of ${name}` : `Not in ${name}`,
      };
    });
}
