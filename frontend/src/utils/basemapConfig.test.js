import { readFileSync } from "node:fs";
import vm from "node:vm";
import { describe, expect, it } from "vitest";

const configSource = readFileSync(new URL("../../public/map-config.js", import.meta.url), "utf8");

function loadBasemapConfig(appConfig = {}, storedKey = null) {
  const window = {
    APP_CONFIG: appConfig,
    localStorage: {
      getItem: () => storedKey,
    },
  };
  vm.runInNewContext(configSource, { window });
  return window;
}

describe("basemap config", () => {
  it("provides real light and dark maps instead of blank color canvases", () => {
    const window = loadBasemapConfig();
    const options = Object.fromEntries(window.BASEMAP_OPTIONS.map((option) => [option.key, option]));

    expect(options["esri-light"].label).toBe("白色地图");
    expect(options["esri-light"].tiles[0]).toContain("World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}");
    expect(options["esri-dark"].label).toBe("黑色地图");
    expect(options["esri-dark"].tiles[0]).toContain("World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}");
    expect(options["esri-dark"].dark).toBe(true);
    expect(window.BASEMAP_OPTIONS.some((option) => option.blank)).toBe(false);
  });

  it("falls back to the tested Atlas map when no deployment tile URL is set", () => {
    const window = loadBasemapConfig();
    const configured = window.BASEMAP_OPTIONS.find((option) => option.key === "configured");

    expect(configured.tiles[0]).toContain("thunderforest.com/atlas");
    expect(configured.tiles[0]).not.toContain("cartocdn.com");
  });

  it("keeps an explicitly configured private XYZ template as the deployment default", () => {
    const customUrl = "https://tiles.example.test/{z}/{x}/{y}.png";
    const window = loadBasemapConfig({ mapTileUrlTemplate: customUrl });
    const configured = window.BASEMAP_OPTIONS.find((option) => option.key === "configured");

    expect(configured.tiles).toEqual([customUrl]);
    expect(configured.description).toBe("服务器自定义 XYZ");
  });

  it("provides Geoapify light and dark maps with an API key", () => {
    const window = loadBasemapConfig();
    const options = Object.fromEntries(window.BASEMAP_OPTIONS.map((option) => [option.key, option]));

    expect(options["geoapify-positron"].tiles[0]).toBe(
      "https://maps.geoapify.com/v1/tile/positron/{z}/{x}/{y}@2x.png?apiKey=8970dece88c04376b93a5614b52751e1",
    );
    expect(options["geoapify-dark-grey"].tiles[0]).toBe(
      "https://maps.geoapify.com/v1/tile/dark-matter-dark-grey/{z}/{x}/{y}@2x.png?apiKey=8970dece88c04376b93a5614b52751e1",
    );
    expect(options["geoapify-dark-grey"].dark).toBe(true);
    expect(options["geoapify-positron"].attribution).toContain("Geoapify");
  });

  it("allows deployments to override the Geoapify API key", () => {
    const window = loadBasemapConfig({ geoapifyApiKey: "deployment-key" });
    const positron = window.BASEMAP_OPTIONS.find((option) => option.key === "geoapify-positron");

    expect(positron.tiles[0]).toContain("apiKey=deployment-key");
  });

  it("offers Esri dark and uses it as the platform default", () => {
    const window = loadBasemapConfig();
    const hiddenKeys = window.BASEMAP_OPTIONS.filter((option) => option.hidden).map((option) => option.key);

    expect(hiddenKeys).toEqual([
      "configured",
      "esri-light",
      "esri-street",
      "carto-light",
      "carto-dark",
      "carto-voyager",
    ]);
    expect(window.DEFAULT_BASEMAP_KEY).toBe("esri-dark");
    expect(window.getSelectedBaseMapStyle().key).toBe("esri-dark");
  });

  it("ignores a previously stored basemap after that option is hidden", () => {
    const window = loadBasemapConfig({}, "esri-light");

    expect(window.getSelectedBaseMapStyle().key).toBe("esri-dark");
  });
});
