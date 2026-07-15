/**
 * 模型加载/缓存生成的统一进度口径。
 *
 * 后端一个模型从"未加载"到"可用"要经过两条流水线：
 *  1) 模型基础数据加载（Datasource.load，字段 load*）：真实进度 = 检查点 + 时间插值；
 *  2) 可视化缓存生成（ModelCacheManager，字段 cache*）：真实进度 1→100，
 *     其中 8→25 段是"等待模型基础数据"，可用 load* 细化。
 *
 * 本工具把两条流水线合成一个 1-100 的整体进度，供全局加载门禁、
 * 页面加载卡片和模型切换气泡共用，避免各处再各写一套假百分比。
 */

function toFiniteNumber(value) {
  const num = Number(value);
  return Number.isFinite(num) ? num : null;
}

function clampPercent(value, min = 0, max = 100) {
  return Math.max(min, Math.min(max, Math.round(value)));
}

export function isModelUsable(item) {
  return Boolean(item?.loadStatus && item?.cacheStatus === "ready");
}

export function formatDuration(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value) || value < 0) return "计算中";
  const total = Math.round(value);
  if (total < 60) return `${total} 秒`;
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  if (minutes < 60) return rest > 0 ? `${minutes} 分 ${rest} 秒` : `${minutes} 分`;
  const hours = Math.floor(minutes / 60);
  const minuteRest = minutes % 60;
  return minuteRest > 0 ? `${hours} 小时 ${minuteRest} 分` : `${hours} 小时`;
}

/**
 * @returns {{
 *   state: 'checking'|'queued'|'loading'|'building'|'ready'|'failed',
 *   percent: number, title: string, message: string,
 *   elapsedSeconds: number, etaSeconds: number,
 *   ready: boolean, failed: boolean,
 * }}
 */
export function unifiedModelProgress(item) {
  if (!item) {
    return {
      state: "checking",
      percent: 0,
      title: "正在检查模型状态",
      message: "正在读取方案与模型列表",
      elapsedSeconds: -1,
      etaSeconds: -1,
      ready: false,
      failed: false,
    };
  }

  if (isModelUsable(item)) {
    return {
      state: "ready",
      percent: 100,
      title: "模型已就绪",
      message: "模型基础数据与可视化缓存均已就绪",
      elapsedSeconds: Math.max(0, toFiniteNumber(item.loadElapsedSeconds) ?? 0),
      etaSeconds: 0,
      ready: true,
      failed: false,
    };
  }

  if (item.loadStage === "failed" || item.cacheStatus === "failed") {
    const cacheFailed = item.cacheStatus === "failed";
    return {
      state: "failed",
      percent: clampPercent(
        (cacheFailed ? toFiniteNumber(item.cacheProgressPercent) : toFiniteNumber(item.loadProgressPercent)) ?? 0,
        0,
        99,
      ),
      title: cacheFailed ? "缓存生成失败" : "模型加载失败",
      message: (cacheFailed ? item.cacheMessage : item.loadMessage) || "加载失败，请重试",
      elapsedSeconds: -1,
      etaSeconds: -1,
      ready: false,
      failed: true,
    };
  }

  const loadPercent = toFiniteNumber(item.loadProgressPercent);
  const hasLoadDetail = loadPercent !== null && loadPercent > 0;
  const loadInfo = {
    message: item.loadProgressMessage || item.loadMessage || "正在加载模型基础数据",
    elapsedSeconds: Math.max(0, toFiniteNumber(item.loadElapsedSeconds) ?? 0),
    etaSeconds: toFiniteNumber(item.loadEtaSeconds) ?? -1,
  };

  if (item.cacheStatus !== "ready") {
    // 缓存流水线覆盖全过程：queued(1) → 建目录(3) → 等模型(8..25) → events 流式解析(25..95) → 索引(98)
    const cachePercent = clampPercent(toFiniteNumber(item.cacheProgressPercent) ?? 1, 1, 99);
    if (!item.loadStatus && hasLoadDetail && cachePercent <= 25) {
      // 处于"等待模型基础数据"段：用真实加载进度把 8→25 细化出来
      return {
        state: "loading",
        percent: clampPercent(8 + loadPercent * 0.17, 1, 25),
        title: "正在加载模型基础数据",
        message: loadInfo.message,
        elapsedSeconds: loadInfo.elapsedSeconds,
        etaSeconds: loadInfo.etaSeconds,
        ready: false,
        failed: false,
      };
    }
    const queued = item.cacheStatus === "queued" || (!item.cacheStatus && item.loadStage === "queued");
    return {
      state: queued ? "queued" : "building",
      percent: cachePercent,
      title: queued ? "排队等待生成缓存" : "正在生成模型缓存",
      message: item.cacheProgressMessage || item.cacheMessage || "正在生成模型缓存",
      elapsedSeconds: Math.max(0, toFiniteNumber(item.cacheElapsedSeconds) ?? 0),
      etaSeconds: toFiniteNumber(item.cacheEtaSeconds) ?? -1,
      ready: false,
      failed: false,
    };
  }

  // 缓存已就绪，只差模型基础数据本体
  if (hasLoadDetail) {
    return {
      state: item.loadStage === "queued" ? "queued" : "loading",
      percent: clampPercent(loadPercent, 1, 99),
      title: item.loadStage === "queued" ? "排队等待加载" : "正在加载模型基础数据",
      message: loadInfo.message,
      elapsedSeconds: loadInfo.elapsedSeconds,
      etaSeconds: loadInfo.etaSeconds,
      ready: false,
      failed: false,
    };
  }

  // 老后端字段缺失时的兜底：不再伪造精确百分比，只给阶段位
  const fallbackPercent = item.loadStage === "loading_config" ? 50 : item.loadStage === "queued" ? 3 : 1;
  return {
    state: item.loadStage === "queued" ? "queued" : "loading",
    percent: fallbackPercent,
    title: "正在加载模型基础数据",
    message: item.loadMessage || "正在加载模型基础数据",
    elapsedSeconds: -1,
    etaSeconds: -1,
    ready: false,
    failed: false,
  };
}
