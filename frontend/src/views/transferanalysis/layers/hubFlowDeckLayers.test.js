import { describe, expect, it } from "vitest";
import { buildHubFlowMotionTrips, buildHubFlowPaths } from "./hubFlowDeckLayers.js";

const busCoords = new Map([
  [3, [113.3, 23.1]],
  [10, [113.2, 23.2]],
  [20, [113.4, 23.2]],
  [30, [113.5, 23.25]],
]);
const metroCoords = new Map([[6, [113.305, 23.105]]]);

describe("换乘枢纽 Deck 两段式流向构建", () => {
  it("保留公交乘车段与站间接驳段，并按方向排列端点", () => {
    const paths = buildHubFlowPaths({
      busStopCoord: (idx) => busCoords.get(idx),
      metroStopCoord: (idx) => metroCoords.get(idx),
      tripLinks: [
        { originBusStop: 10, destinationBusStop: 3, flow: 5, b2m: 5, m2b: 0 },
        { originBusStop: 3, destinationBusStop: 20, flow: 7, b2m: 0, m2b: 7 },
      ],
      transferLinks: [
        { busStop: 3, metroStop: 6, flow: 12, b2m: 5, m2b: 7, avgSec: 320 },
      ],
    });

    expect(paths).toHaveLength(4);
    const b2mRide = paths.find((item) => item.stage === "ride" && item.direction === "busToMetro");
    const b2mTransfer = paths.find((item) => item.stage === "transfer" && item.direction === "busToMetro");
    const m2bRide = paths.find((item) => item.stage === "ride" && item.direction === "metroToBus");
    const m2bTransfer = paths.find((item) => item.stage === "transfer" && item.direction === "metroToBus");

    expect(b2mRide).toMatchObject({ source: busCoords.get(10), target: busCoords.get(3), externalStop: 10, transferStop: 3, flow: 5 });
    expect(b2mTransfer).toMatchObject({ source: busCoords.get(3), target: metroCoords.get(6), transferStop: 3, metroStop: 6, flow: 5 });
    expect(m2bTransfer).toMatchObject({ source: metroCoords.get(6), target: busCoords.get(3), transferStop: 3, metroStop: 6, flow: 7 });
    expect(m2bRide).toMatchObject({ source: busCoords.get(3), target: busCoords.get(20), externalStop: 20, transferStop: 3, flow: 7 });
    expect(b2mRide.path).toHaveLength(37);
    expect(b2mTransfer.path).toHaveLength(21);
  });

  it("过滤无坐标端点，并分别限制两类路径的 Top N", () => {
    const paths = buildHubFlowPaths({
      busStopCoord: (idx) => busCoords.get(idx),
      metroStopCoord: (idx) => metroCoords.get(idx),
      maxRideFlows: 2,
      maxTransferFlows: 1,
      tripLinks: [
        { originBusStop: 10, destinationBusStop: 3, b2m: 2, m2b: 0 },
        { originBusStop: 30, destinationBusStop: 3, b2m: 8, m2b: 0 },
        { originBusStop: 999, destinationBusStop: 3, b2m: 20, m2b: 0 },
        { originBusStop: 3, destinationBusStop: 20, b2m: 0, m2b: 5 },
      ],
      transferLinks: [
        { busStop: 3, metroStop: 6, b2m: 4, m2b: 3 },
      ],
    });

    expect(paths.filter((item) => item.stage === "ride").map((item) => item.flow)).toEqual([5, 8]);
    expect(paths.filter((item) => item.stage === "transfer")).toHaveLength(1);
    expect(paths.filter((item) => item.stage === "transfer")[0].flow).toBe(4);
  });

  it("缺少坐标解析器时返回空集合", () => {
    expect(buildHubFlowPaths({ tripLinks: [], transferLinks: [] })).toEqual([]);
  });

  it("将两段链合成带接驳站停顿的 TripsLayer 轨迹", () => {
    const flows = buildHubFlowPaths({
      busStopCoord: (idx) => busCoords.get(idx),
      metroStopCoord: (idx) => metroCoords.get(idx),
      tripLinks: [{ originBusStop: 10, destinationBusStop: 3, b2m: 5, m2b: 0 }],
      transferLinks: [{ busStop: 3, metroStop: 6, b2m: 5, m2b: 0 }],
    });
    const trips = buildHubFlowMotionTrips(flows, 1);

    expect(trips).toHaveLength(3);
    const trip = trips.find((item) => item.id.endsWith(":0"));
    expect(trip.path).toHaveLength(58);
    expect(trip.timestamps).toHaveLength(trip.path.length);
    expect(trip.path[36]).toEqual(busCoords.get(3));
    expect(trip.path[37]).toEqual(busCoords.get(3));
    expect(trip.timestamps[37] - trip.timestamps[36]).toBe(7);
  });
});
