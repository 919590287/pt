export function displayRangeNetworkState(requestedModel, loadedModel, lines) {
  const requested = String(requestedModel || "");
  const loaded = String(loadedModel || "");
  if (!requested || requested !== loaded) return "pending";
  return Array.isArray(lines) && lines.length > 0 ? "ready" : "empty";
}
