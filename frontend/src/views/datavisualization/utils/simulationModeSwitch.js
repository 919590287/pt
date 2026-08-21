/**
 * Ensures both levels of the simulation catalog are available.
 *
 * A real-first visit has neither local scheme nor model rows. Fetching schemes
 * alone is insufficient when the persisted scheme value does not change: its
 * watcher will not fire, so the model catalog must be requested explicitly.
 */
export async function ensureSimulationCatalog({
  getScheme,
  hasSchemeCatalog,
  hasModelCatalog,
  fetchSchemes,
  fetchModels,
  isCurrent = () => true,
}) {
  if (!hasSchemeCatalog()) {
    await fetchSchemes();
  }
  if (!isCurrent()) return false;

  const scheme = getScheme();
  if (scheme && !hasModelCatalog(scheme)) {
    await fetchModels(scheme);
  }
  return isCurrent();
}
