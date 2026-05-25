import { webMercatorToLngLat } from "@/mymap/index.js";

export function colorToCss(color, fallback = "#1f78b4") {
  if (typeof color === "string") return color;
  if (!Number.isFinite(Number(color))) return fallback;
  return `#${Number(color).toString(16).padStart(6, "0").slice(-6)}`;
}

export function lineWidthToPixels(value) {
  const width = Number(value);
  if (!Number.isFinite(width)) return 4;
  return Math.max(1, Math.min(32, width / 10));
}

export function linkToFeature(link) {
  if (!link?.from || !link?.to) return null;
  const from = webMercatorToLngLat(link.from.x, link.from.y);
  const to = webMercatorToLngLat(link.to.x, link.to.y);
  if (![...from, ...to].every(Number.isFinite)) return null;
  return {
    type: "Feature",
    geometry: {
      type: "LineString",
      coordinates: [from, to],
    },
    properties: {
      linkId: link.linkId || "",
      flow: Number(link.flow) || 0,
      length: Number(link.length) || 0,
      lanes: Number(link.lanes) || 1,
    },
  };
}

export function linksToFeatureCollection(links = []) {
  return {
    type: "FeatureCollection",
    features: (Array.isArray(links) ? links : []).map(linkToFeature).filter(Boolean),
  };
}

export function stationsToFeatureCollection(stations = []) {
  return {
    type: "FeatureCollection",
    features: (Array.isArray(stations) ? stations : [])
      .map((station) => {
        const coords = webMercatorToLngLat(station.x, station.y);
        if (!coords.every(Number.isFinite)) return null;
        return {
          type: "Feature",
          geometry: {
            type: "Point",
            coordinates: coords,
          },
          properties: {
            name: station.name || station.facilityName || "",
            facilityId: station.facilityId || "",
            type: station.type || "bus",
          },
        };
      })
      .filter(Boolean),
  };
}

export function emptyFeatureCollection() {
  return {
    type: "FeatureCollection",
    features: [],
  };
}
