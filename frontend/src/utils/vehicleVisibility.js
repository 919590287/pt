export const VEHICLE_MODE_KEYS = ["bus", "subway", "car"];

const LEGACY_VISIBILITY_MODES = {
  all: VEHICLE_MODE_KEYS,
  public: ["bus", "subway"],
  private: ["car"],
};

export function normalizeVehicleVisibility(value = VEHICLE_MODE_KEYS) {
  const selected = Array.isArray(value)
    ? value
    : value instanceof Set
      ? [...value]
      : LEGACY_VISIBILITY_MODES[value] || String(value || "").split(",");
  const selectedSet = new Set(selected);
  return VEHICLE_MODE_KEYS.filter((mode) => selectedSet.has(mode)).join(",");
}

export function isVehicleModeVisible(mode, visibility = VEHICLE_MODE_KEYS) {
  return normalizeVehicleVisibility(visibility).split(",").includes(mode);
}

