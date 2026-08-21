import { performance } from "node:perf_hooks";
import {
  buildBinarySecondIndex,
  segmentFrameFromBinary,
} from "../src/views/datavisualization/layers/vehicleTrajectory.worker.js";

// v15 的 4096m 服务端索引只交付命中视口的空间块；全市统计走 manifest sidecar。
// 因此帧预算应按“全市源规模 + 实际视口交付规模”建模，而非把全市记录重复交给 Worker。
const citywideTotal = Math.max(1, Number(process.env.TRAJECTORY_BENCH_TOTAL) || 200_000);
const delivered = Math.min(citywideTotal,
  Math.max(1, Number(process.env.TRAJECTORY_BENCH_VISIBLE) || 2_000));
const frames = Math.max(60, Number(process.env.TRAJECTORY_BENCH_FRAMES) || 360);
const segments = new Float32Array(delivered * 9);
const ints = new Int32Array(segments.buffer);
for (let index = 0; index < delivered; index += 1) {
  const offset = index * 9;
  const x = index % 100;
  const y = Math.floor(index / 100);
  segments[offset] = 100;
  segments[offset + 1] = 102;
  segments[offset + 2] = x;
  segments[offset + 3] = y;
  segments[offset + 4] = x + 10;
  segments[offset + 5] = y + 2;
  segments[offset + 6] = index % 3;
  ints[offset + 7] = index;
  segments[offset + 8] = Math.hypot(10, 2);
}

const data = {
  binary: true,
  origin: [0, 0],
  chunk: { start: 100, end: 109 },
  chunkSeconds: 10,
  segments,
};
const indexStarted = performance.now();
data.index = buildBinarySecondIndex(data);
const indexMs = performance.now() - indexStarted;
const bounds = { minX: -50, minY: -50, maxX: 200, maxY: 100 };

for (let index = 0; index < 30; index += 1) {
  segmentFrameFromBinary(100 + (index % 60) / 60, data, 1, bounds);
}
const samples = [];
let lastVisible = 0;
for (let index = 0; index < frames; index += 1) {
  const started = performance.now();
  const result = segmentFrameFromBinary(100 + (index % 120) / 60, data, 1, bounds);
  samples.push(performance.now() - started);
  lastVisible = result.segmentFrame.count;
}
samples.sort((left, right) => left - right);
const percentile = (ratio) => samples[Math.min(samples.length - 1, Math.floor(samples.length * ratio))];
const p50 = percentile(0.5);
const p95 = percentile(0.95);
const p99 = percentile(0.99);
process.stdout.write(`${JSON.stringify({
  citywideSourceSegments: citywideTotal,
  deliveredSpatialSegments: delivered,
  visibleSegments: lastVisible,
  frames,
  indexMs: Number(indexMs.toFixed(3)),
  frameP50Ms: Number(p50.toFixed(3)),
  frameP95Ms: Number(p95.toFixed(3)),
  frameP99Ms: Number(p99.toFixed(3)),
  frameBudget60FpsMs: 16.667,
  p95BudgetHeadroomPct: Number(((16.667 - p95) / 16.667 * 100).toFixed(2)),
  theoreticalFpsAtP95: Number((1000 / Math.max(0.001, p95)).toFixed(1)),
})}\n`);
