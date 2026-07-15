/**
 * Resolve a MapLibre style layer to the page group that owns it.
 *
 * A shared MapLibre instance outlives individual route pages. Async work from a
 * deactivated page can therefore finish late and add its layers while another
 * page is visible. Ownership is derived from stable layer-id prefixes so the
 * layout can quarantine those late additions before they leak into the active
 * page.
 */
export function styleLayerOwner(layerId, pageGroups = {}) {
  const id = String(layerId || "");
  if (!id) return "";
  for (const group of Object.values(pageGroups)) {
    if (!group?.key || !Array.isArray(group.stylePrefixes)) continue;
    if (group.stylePrefixes.some((prefix) => prefix && id.startsWith(prefix))) {
      return group.key;
    }
  }
  return "";
}

/**
 * Hide style layers owned by inactive pages and remember the visibility they
 * requested. This is intentionally idempotent: MapLibre emits styledata again
 * after setLayoutProperty, and repeated reconciliation must not lose state.
 */
export function quarantineInactiveStyleLayers(map, pageGroups, activeGroupKey, visibilityStash) {
  if (!map?.getStyle || !visibilityStash) return 0;
  let hiddenCount = 0;
  const layers = map.getStyle()?.layers || [];

  for (const layer of layers) {
    const ownerKey = styleLayerOwner(layer?.id, pageGroups);
    if (!ownerKey || ownerKey === activeGroupKey) continue;

    let ownerVisibility = visibilityStash.get(ownerKey);
    if (!ownerVisibility) {
      ownerVisibility = new Map();
      visibilityStash.set(ownerKey, ownerVisibility);
    }

    const visibility = map.getLayoutProperty?.(layer.id, "visibility") || "visible";
    // A late inactive callback may try to show a layer again. Preserve that as
    // its desired state, then immediately quarantine it once more.
    if (!ownerVisibility.has(layer.id) || visibility !== "none") {
      ownerVisibility.set(layer.id, visibility);
    }
    if (visibility === "none") continue;

    map.setLayoutProperty?.(layer.id, "visibility", "none");
    hiddenCount += 1;
  }

  return hiddenCount;
}

