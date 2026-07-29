const DEFAULT_CHUNK_SECONDS = 3600;
const MAX_EVENT_GAP_SECONDS = 30 * 60;
const LAST_POSITION_HOLD_SECONDS = 20 * 60;
const WEB_MERCATOR_RADIUS = 6378137;

function lngLatToWebMercator(lng, lat) {
  const safeLat = Math.max(-85.05112878, Math.min(85.05112878, Number(lat) || 0));
  return [
    WEB_MERCATOR_RADIUS * (Number(lng) || 0) * Math.PI / 180,
    WEB_MERCATOR_RADIUS * Math.log(Math.tan(Math.PI / 4 + safeLat * Math.PI / 360)),
  ];
}

function normalizedRange(range = {}) {
  const min = Number(range.min);
  const max = Number(range.max);
  return {
    min: Number.isFinite(min) ? min : 0,
    max: Number.isFinite(max) && max > min ? max : 86400,
  };
}

function chunkStartOf(time, chunkSeconds) {
  return Math.max(0, Math.floor((Number(time) || 0) / chunkSeconds) * chunkSeconds);
}

function appendSegment(chunks, vehicle, segment, range, chunkSeconds) {
  const visibleStart = Math.max(range.min, segment[0]);
  // Segment ends are exclusive. Subtracting a tiny epsilon avoids copying an
  // exactly boundary-aligned segment into the following hour.
  const visibleEnd = Math.min(range.max + 1, segment[1]) - 1e-6;
  if (visibleEnd < visibleStart) return;
  const first = chunkStartOf(visibleStart, chunkSeconds);
  const last = chunkStartOf(visibleEnd, chunkSeconds);
  for (let start = first; start <= last; start += chunkSeconds) {
    let vehicles = chunks.get(start);
    if (!vehicles) {
      vehicles = new Map();
      chunks.set(start, vehicles);
    }
    let entry = vehicles.get(vehicle.id);
    if (!entry) {
      entry = {
        id: vehicle.id,
        mode: "bus",
        lineId: vehicle.lineId,
        routeId: vehicle.lineId,
        segments: [],
      };
      vehicles.set(vehicle.id, entry);
    }
    entry.segments.push(segment);
  }
}

/**
 * Converts real vehicle observations into model-scoped hourly chunks.
 *
 * The expensive grouping/projection pass is performed once and can be stored in
 * modelDataCache. Playback then activates only the current hour in the trajectory
 * Worker, so seeking no longer rebuilds an all-day second index.
 */
export function buildRealTrajectoryChunkSource(events = [], range = {}, requestedChunkSeconds = DEFAULT_CHUNK_SECONDS) {
  const timeRange = normalizedRange(range);
  const chunkSeconds = Math.max(60, Math.floor(Number(requestedChunkSeconds) || DEFAULT_CHUNK_SECONDS));
  const tracks = new Map();

  for (const row of Array.isArray(events) ? events : []) {
    const time = Number(row?.[0]);
    const id = String(row?.[1] || "").trim();
    const lon = Number(row?.[3]);
    const lat = Number(row?.[4]);
    if (!id || !Number.isFinite(time) || !Number.isFinite(lon) || !Number.isFinite(lat)) continue;
    const track = tracks.get(id) || [];
    track.push({
      time,
      id,
      lineId: String(row?.[2] || ""),
      lon,
      lat,
    });
    tracks.set(id, track);
  }

  const chunks = new Map();
  let segmentCount = 0;
  let pointCount = 0;
  for (const [id, track] of tracks) {
    track.sort((left, right) => left.time - right.time);
    pointCount += track.length;
    const vehicle = { id, lineId: track[0]?.lineId || "" };
    for (let index = 0; index < track.length - 1; index += 1) {
      const start = track[index];
      const end = track[index + 1];
      if (end.time <= start.time || end.time - start.time > MAX_EVENT_GAP_SECONDS) continue;
      const [startX, startY] = lngLatToWebMercator(start.lon, start.lat);
      const [endX, endY] = lngLatToWebMercator(end.lon, end.lat);
      appendSegment(
        chunks,
        { ...vehicle, lineId: start.lineId || vehicle.lineId },
        [start.time, end.time, startX, startY, endX, endY],
        timeRange,
        chunkSeconds,
      );
      segmentCount += 1;
    }

    // Keep the last observation visible briefly, matching the previous real
    // playback behavior for vehicles with only one usable observation.
    const last = track[track.length - 1];
    const endTime = Math.min(timeRange.max + 1, last.time + LAST_POSITION_HOLD_SECONDS);
    if (endTime > last.time) {
      const [x, y] = lngLatToWebMercator(last.lon, last.lat);
      appendSegment(
        chunks,
        { ...vehicle, lineId: last.lineId || vehicle.lineId },
        [last.time, endTime, x, y, x, y],
        timeRange,
        chunkSeconds,
      );
      segmentCount += 1;
    }
  }

  return {
    chunkSeconds,
    timeRange,
    chunks,
    payloads: new Map(),
    vehicleCount: tracks.size,
    pointCount,
    segmentCount,
  };
}

export function realTrajectoryChunkAt(source, requestedStart) {
  if (!source) return null;
  const start = chunkStartOf(requestedStart, source.chunkSeconds);
  if (source.payloads.has(start)) return source.payloads.get(start);
  const entries = source.chunks.get(start);
  const vehicles = entries ? Array.from(entries.values()) : [];
  const segmentCount = vehicles.reduce((sum, vehicle) => sum + vehicle.segments.length, 0);
  const payload = {
    status: "ready",
    chunk: {
      start,
      end: Math.min(source.timeRange.max, start + source.chunkSeconds - 1),
      vehicleCount: vehicles.length,
      pointCount: 0,
      segmentCount,
    },
    vehicles,
  };
  source.payloads.set(start, payload);
  return payload;
}

