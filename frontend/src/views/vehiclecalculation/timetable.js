function timeToMinutes(time) {
  if (typeof time === "number") return time;
  if (!time) return null;
  const [hour = "0", minute = "0"] = String(time).split(":");
  const value = Number.parseInt(hour, 10) * 60 + Number.parseInt(minute, 10);
  return Number.isFinite(value) ? value : null;
}

function validPeak(start, end, interval) {
  return start !== null && end !== null && end > start && interval >= 1;
}

/**
 * 生成单方向发车时刻。first/last 都是发车时刻，因此首末班以及高峰切换边界均须保留。
 * 每个相邻边界之间按该段间隔展开，最后用 Set 去掉相邻时段共享边界的重复班次。
 */
export function generateDirectionTimeline(params) {
  const serviceStart = Number(params.serviceStart);
  const serviceEnd = Number(params.serviceEnd);
  if (!Number.isFinite(serviceStart) || !Number.isFinite(serviceEnd) || serviceEnd <= serviceStart) return [];

  const amStart = timeToMinutes(params.amStart);
  const amEnd = timeToMinutes(params.amEnd);
  const pmStart = timeToMinutes(params.pmStart);
  const pmEnd = timeToMinutes(params.pmEnd);
  const amInterval = Number(params.amInterval);
  const pmInterval = Number(params.pmInterval);
  const offInterval = Number(params.offInterval);
  if (!Number.isFinite(offInterval) || offInterval < 1) return [];

  const peaks = [
    { start: amStart, end: amEnd, interval: amInterval },
    { start: pmStart, end: pmEnd, interval: pmInterval },
  ].filter((peak) => validPeak(peak.start, peak.end, peak.interval));

  const boundaries = new Set([serviceStart, serviceEnd]);
  peaks.forEach((peak) => {
    const start = Math.max(serviceStart, peak.start);
    const end = Math.min(serviceEnd, peak.end);
    if (end > start) {
      boundaries.add(start);
      boundaries.add(end);
    }
  });
  const sortedBoundaries = [...boundaries].sort((a, b) => a - b);
  const times = new Set();

  for (let index = 0; index < sortedBoundaries.length - 1; index += 1) {
    const segmentStart = sortedBoundaries[index];
    const segmentEnd = sortedBoundaries[index + 1];
    const peak = peaks.find((item) => segmentStart >= item.start && segmentStart < item.end);
    const interval = peak?.interval || offInterval;
    times.add(segmentStart);
    for (let cursor = segmentStart + interval; cursor < segmentEnd; cursor += interval) {
      times.add(cursor);
    }
  }

  times.add(serviceEnd);
  return [...times].sort((a, b) => a - b);
}
