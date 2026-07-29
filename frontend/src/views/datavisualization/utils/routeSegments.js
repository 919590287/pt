export function segmentEndpointNames(segment = {}) {
  const stationNames = Array.isArray(segment.stationNames) ? segment.stationNames : [];
  let fromName = String(segment.fromName || stationNames[0] || "").trim();
  let toName = String(segment.toName || stationNames[1] || "").trim();

  if ((!fromName || !toName) && segment.name) {
    const parts = String(segment.name).split(" - ");
    if (parts.length >= 2) {
      fromName ||= parts[0].trim();
      toName ||= parts[parts.length - 1].trim();
    }
  }

  return { fromName, toName };
}

export function segmentDisplayName(segment = {}) {
  const name = String(segment.name || "").trim();
  if (name) return name;
  const { fromName, toName } = segmentEndpointNames(segment);
  return fromName && toName ? `${fromName} - ${toName}` : fromName || toName;
}
