/** 真实数据能力表中某个菜单功能是否可用。 */
export function realCapabilityAvailable(modules, platform, panel) {
  if (!panel) return false;
  // 旧后端或能力接口尚未返回 modules 时保持向后兼容，不误隐藏功能。
  if (!Array.isArray(modules)) return true;
  const match = modules.find((item) =>
    item?.platformModule === platform && item?.leftPanelModule === panel
  );
  return Boolean(match?.available);
}

/**
 * 同一数据功能可能因历史导航归属登记在多个平台下。
 * 任一候选明确可用即可展示；候选全部缺失或不可用才隐藏。
 */
export function realCapabilityAvailableInAny(modules, candidates) {
  if (!Array.isArray(modules)) return true;
  return (Array.isArray(candidates) ? candidates : []).some(({ platform, panel }) =>
    realCapabilityAvailable(modules, platform, panel)
  );
}
