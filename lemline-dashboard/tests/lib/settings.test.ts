import { create } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import {
  ConfigFileKeySchema,
  ConfigFileLocator,
  ConfigFileSchema,
  GatewaySettingSchema,
  SettingOrigin,
} from "../../src/gen/proto/lemline/gateway/v1/gateway_pb";
import { fileLines, locatorLabel, outsideFileRows } from "../../src/lib/settings";

const file = create(ConfigFileSchema, {
  path: "/etc/lemline/lemline.yaml",
  locatedBy: ConfigFileLocator.OPTION,
  text: "# Production\nlemline:\n  gateway:\n    cors:\n      origins: http://localhost:5173\n    tls:\n      trust-store-password: ••••••••\n",
  keys: [
    create(ConfigFileKeySchema, {
      key: "lemline.gateway.cors.origins",
      line: 5,
      overriddenBy: SettingOrigin.ENVIRONMENT,
      overriddenByDetail: "LEMLINE_GATEWAY_CORS_ORIGINS",
    }),
    create(ConfigFileKeySchema, { key: "lemline.gateway.tls.trust-store-password", line: 7, secret: true }),
  ],
});

const setting = (key: string, origin: SettingOrigin, detail: string, value?: string, secret = false) =>
  create(GatewaySettingSchema, { key, origin, originDetail: detail, value, secret, isSet: true });

describe("fileLines", () => {
  it("numbers every line and keeps its indentation", () => {
    const lines = fileLines(file);
    expect(lines).toHaveLength(7);
    expect(lines[0]).toMatchObject({ number: 1, comment: true });
    expect(lines[4]).toMatchObject({ number: 5, indent: "      ", body: "origins: http://localhost:5173" });
  });

  it("marks the line another origin replaces", () => {
    const lines = fileLines(file);
    expect(lines[4].overriddenBy).toEqual({ kind: "env", label: "Overridden by LEMLINE_GATEWAY_CORS_ORIGINS" });
    expect(lines[6]).toMatchObject({ secret: true, overriddenBy: undefined });
  });

  it("names a system property override as it is typed", () => {
    const withCli = create(ConfigFileSchema, {
      ...file,
      keys: [
        create(ConfigFileKeySchema, {
          key: "lemline.gateway.cors.origins",
          line: 5,
          overriddenBy: SettingOrigin.SYSTEM_PROPERTY,
          overriddenByDetail: "lemline.gateway.cors.origins",
        }),
      ],
    });
    expect(fileLines(withCli)[4].overriddenBy?.label).toBe("Overridden by -Dlemline.gateway.cors.origins");
  });
});

describe("outsideFileRows", () => {
  const settings = [
    setting("lemline.gateway.enabled", SettingOrigin.ENVIRONMENT, "LEMLINE_GATEWAY_ENABLED", "true"),
    setting("lemline.gateway.cors.origins", SettingOrigin.ENVIRONMENT, "LEMLINE_GATEWAY_CORS_ORIGINS", "https://d.example"),
    setting("lemline.gateway.grpc.port", SettingOrigin.CONFIG_FILE, "/etc/lemline/lemline.yaml", "9443"),
    setting("lemline.gateway.watch.batch-size", SettingOrigin.DEFAULT, "", "256"),
    setting("lemline.gateway.tls.trust-store-password", SettingOrigin.SYSTEM_PROPERTY, "lemline.gateway.tls.trust-store-password", undefined, true),
  ];

  it("keeps only settings from the environment or the command line", () => {
    expect(outsideFileRows(settings, file).map((row) => row.key)).toEqual([
      "lemline.gateway.enabled",
      "lemline.gateway.cors.origins",
      "lemline.gateway.tls.trust-store-password",
    ]);
  });

  it("tells whether the setting replaces a line of the file", () => {
    const rows = outsideFileRows(settings, file);
    expect(rows[0]).toMatchObject({ label: "gateway.enabled", relation: "Not in lemline.yaml", origin: "Environment" });
    expect(rows[1].relation).toBe("Replaces line 5 of lemline.yaml");
    expect(rows[2]).toMatchObject({ value: "Set (hidden)", name: "-Dlemline.gateway.tls.trust-store-password" });
  });

  it("is empty when everything comes from the file or defaults", () => {
    expect(outsideFileRows(settings.slice(2, 4), file)).toEqual([]);
  });

  it("works without a configuration file", () => {
    expect(outsideFileRows(settings.slice(0, 1))[0].relation).toBe("");
  });
});

describe("locatorLabel", () => {
  it("says how the file was found", () => {
    expect(locatorLabel(ConfigFileLocator.ENVIRONMENT)).toBe("From LEMLINE_CONFIG");
    expect(locatorLabel(ConfigFileLocator.UNSPECIFIED)).toBe("");
  });
});
