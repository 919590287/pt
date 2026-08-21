import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const requestMock = vi.hoisted(() => vi.fn());

vi.mock("@/utils/request.js", () => ({
  default: requestMock,
}));

import {
  REAL_AVERAGE_DATE,
  authorityDirectionKey,
  buildRealTransitNetwork,
  clearRealPassengerFlowCache,
  encodeCorridorLinks,
  getRealPanelBundle,
  getRealPassengerFlowCapabilities,
  isRealPassengerAggregatePending,
  getRealOverallFlow,
  getRealTripEndsGrid,
  getRealTripEndsStreets,
  isRealDatasource,
  realAreaFromDatasource,
  realDatasource,
  realLineGroupName,
  realPassengerCapabilityError,
  primeRealPassengerFlowDates,
  realServiceDateFromDatasource,
  uniqueAuthorityDirectionRoutes,
} from "./realPassengerFlow.js";
import {
  configureBundleStoreBackend,
  resetBundleStoreBackend,
} from "./realPanelBundleStore.js";
import {
  CORRIDOR_U16_SENTINEL,
  parseCorridorLinks,
} from "@/views/datavisualization/utils/corridorLinks.js";

describe("realPassengerFlow datasource", () => {
  beforeEach(() => {
    requestMock.mockReset();
    clearRealPassengerFlowCache();
  });

  it("平均值保持旧数据源标识，兼容已有真实线网缓存", () => {
    const datasource = realDatasource("广州市", REAL_AVERAGE_DATE);
    expect(datasource).toBe("real::广州市");
    expect(isRealDatasource(datasource)).toBe(true);
    expect(realAreaFromDatasource(datasource)).toBe("广州市");
    expect(realServiceDateFromDatasource(datasource)).toBe(REAL_AVERAGE_DATE);
  });

  it("日期写入数据源标识并可无损解析", () => {
    const datasource = realDatasource("广州市", "2026-03-10");
    expect(realAreaFromDatasource(datasource)).toBe("广州市");
    expect(realServiceDateFromDatasource(datasource)).toBe("2026-03-10");
  });

  it("真实线路分组兼容南/南沙别名和端点中的嵌套括号", () => {
    expect(realLineGroupName("南10路(新兴村委总站--地铁万顷沙站)"))
      .toBe("南沙10路");
    expect(realLineGroupName("南沙10路(地铁万顷沙站--新兴村委总站)"))
      .toBe("南沙10路");
    expect(realLineGroupName("南14路(香港科技大学(广州)站--横沥地铁站公交总站)"))
      .toBe("南沙14路");
    expect(realLineGroupName("南沙65路(大站快线)(大岗公交总站--市桥汽车站西门站)"))
      .toBe("南沙65路(大站快线)");
    expect(realLineGroupName("40路/南40路(大岗公交总站--新兴村委总站)"))
      .toBe("南沙40路");
  });

  it("真实方向按权威 line_id 分组，不会把同端点的不同方向记录合并", () => {
    const routes = [
      { authorityLineId: "route-up", routeId: "route-up", facilities: [{ facilityName: "甲" }, { facilityName: "乙" }] },
      { authorityLineId: "route-down", routeId: "route-down", facilities: [{ facilityName: "甲" }, { facilityName: "乙" }] },
      { authorityLineId: "route-up", routeId: "route-up-copy", facilities: [{ facilityName: "旧甲" }, { facilityName: "旧乙" }] },
    ];

    expect(authorityDirectionKey(routes[0])).toBe("authority:route-up");
    expect(uniqueAuthorityDirectionRoutes(routes).map((route) => route.authorityLineId))
      .toEqual(["route-up", "route-down"]);
  });

  it("无客流现行线路保留绘图记录，并以名称哨兵排除右侧排名", () => {
    const parsed = parseCorridorLinks(encodeCorridorLinks([
      [12600000, 2600000, 12600100, 2600100, 3, 0xffff, 8, 0],
    ]));

    expect(parsed.count).toBe(1);
    expect(parsed.coeff[0]).toBe(3);
    expect(parsed.nameIdx[0]).toBe(CORRIDOR_U16_SENTINEL);
    expect(parsed.street[0]).toBe(8);
    expect(parsed.flow[0]).toBe(0);
  });

  it("从真实线路 shp 属性保留票价和发车间隔，供关联线路面板展示", () => {
    const network = buildRealTransitNetwork({
      lines: {
        features: [{
          type: "Feature",
          geometry: { type: "LineString", coordinates: [[113.2, 23.1], [113.3, 23.2]] },
          properties: {
            line_id: "route-1",
            name: "测试1路（上行）",
            price: "2",
            interval: "10",
          },
        }],
      },
      routeStops: {
        features: [{
          type: "Feature",
          geometry: { type: "Point", coordinates: [113.2, 23.1] },
          properties: {
            line_id: "route-1",
            stop_id: "stop-1",
            stop_name: "测试站",
            seq: 1,
          },
        }],
      },
      stations: { features: [] },
    });

    const route = network.lines[0].routes[0];
    expect(route.price).toBe("2");
    expect(route.fare).toBe("2");
    expect(route.interval).toBe("10");
    expect(route.headway).toBe("10");
    expect(route.info.price).toBe("2");
  });

  it("能力信息预热与切换读取复用同一请求，重复切换不再冷加载", async () => {
    requestMock.mockResolvedValue({
      data: { serviceDates: ["2026-03-10"] },
    });
    const datasource = realDatasource("广州市");

    const [warmResult, switchResult] = await Promise.all([
      getRealPassengerFlowCapabilities(datasource),
      getRealPassengerFlowCapabilities(datasource),
    ]);
    const secondSwitchResult = await getRealPassengerFlowCapabilities(datasource);

    expect(requestMock).toHaveBeenCalledTimes(1);
    expect(warmResult).toEqual({ serviceDates: ["2026-03-10"] });
    expect(switchResult).toBe(warmResult);
    expect(secondSwitchResult).toBe(warmResult);
  });

  it("面板缓存待生成时不阻断 preload，只有原始聚合未产出日期才等待", () => {
    expect(isRealPassengerAggregatePending({
      status: "ready",
      panelCacheStatus: "building",
      serviceDates: ["2026-03-10"],
    })).toBe(false);
    expect(isRealPassengerAggregatePending({
      status: "building",
      panelCacheStatus: "building",
      serviceDates: [],
    })).toBe(true);
    expect(realPassengerCapabilityError({
      status: "failed",
      aggregationMessage: "原始文件格式错误",
    })).toBe("原始文件格式错误");
  });

  it("首次预加载可直接灌入各日期总体客流，切换日期不再请求后端", async () => {
    primeRealPassengerFlowDates("广州市", {
      dates: {
        "2026-03-10": { overallFlow: { selectedServiceDate: "2026-03-10", hourlyByMode: { bus: [1] } } },
        "2026-03-11": { overallFlow: { selectedServiceDate: "2026-03-11", hourlyByMode: { bus: [2] } } },
      },
    });

    const result = await getRealOverallFlow(realDatasource("广州市", "2026-03-11"));
    expect(result.selectedServiceDate).toBe("2026-03-11");
    expect(requestMock).not.toHaveBeenCalled();
  });

  it("真实出行网格通过街道空间索引聚合，并复用同一次匹配结果", async () => {
    requestMock
      .mockResolvedValueOnce({
        data: {
          cellSizeMeters: 100,
          cells: [[0, 0, 3, 4], [1, 1, 5, 6]],
          pairs: [[0, 1, 2]],
        },
      })
      .mockResolvedValueOnce({
        data: {
          type: "FeatureCollection",
          features: [{
            type: "Feature",
            properties: { code: "s1", name: "测试街道", district: "测试区" },
            geometry: {
              type: "Polygon",
              coordinates: [[[-0.01, -0.01], [0.01, -0.01], [0.01, 0.01], [-0.01, 0.01], [-0.01, -0.01]]],
            },
          }],
        },
      });
    const datasource = realDatasource("广州市", "2026-03-10");

    const streets = await getRealTripEndsStreets(datasource);
    const grid = await getRealTripEndsGrid(datasource);

    expect(streets.streets[0]).toMatchObject({ origin: 8, destination: 10 });
    expect(grid).toBeInstanceOf(ArrayBuffer);
    expect(new DataView(grid).getUint32(6, true)).toBe(2);
    expect(requestMock).toHaveBeenCalledTimes(2);
  });
});

describe("getRealPanelBundle 持久缓存", () => {
  let rows;

  beforeEach(() => {
    requestMock.mockReset();
    clearRealPassengerFlowCache();
    rows = new Map();
    configureBundleStoreBackend({
      async get(key) { return rows.get(key) || null; },
      async put(record) { rows.set(record.key, record); },
      async remove(keys) { keys.forEach((key) => rows.delete(key)); },
      async listMeta() {
        return [...rows.values()].map(({ key, area, signature, usedAt }) => ({ key, area, signature, usedAt }));
      },
    });
  });

  afterEach(() => {
    resetBundleStoreBackend();
  });

  it("未命中时请求后端并回写，命中后刷新不再发请求", async () => {
    const bundle = { overallFlow: { selectedServiceDate: "2026-03-10" } };
    requestMock.mockResolvedValue({ data: { dates: { "2026-03-10": bundle } } });

    const first = await getRealPanelBundle("广州市", "78b33138", "2026-03-10");
    expect(first).toEqual(bundle);
    expect(requestMock).toHaveBeenCalledTimes(1);

    // 刷新等价于清空会话内存缓存后重来：这次应当由持久缓存直接命中。
    clearRealPassengerFlowCache();
    const second = await getRealPanelBundle("广州市", "78b33138", "2026-03-10");
    expect(second).toEqual(bundle);
    expect(requestMock).toHaveBeenCalledTimes(1);
  });

  it("指纹变化后不吃旧记录，重新请求后端", async () => {
    requestMock.mockResolvedValue({ data: { dates: { "2026-03-10": { overallFlow: { v: 1 } } } } });
    await getRealPanelBundle("广州市", "旧指纹", "2026-03-10");
    expect(requestMock).toHaveBeenCalledTimes(1);

    clearRealPassengerFlowCache();
    requestMock.mockResolvedValue({ data: { dates: { "2026-03-10": { overallFlow: { v: 2 } } } } });
    const fresh = await getRealPanelBundle("广州市", "新指纹", "2026-03-10");
    expect(fresh).toEqual({ overallFlow: { v: 2 } });
    expect(requestMock).toHaveBeenCalledTimes(2);
  });

  it("后端按 selectedServiceDate 回退时仍取得 bundle", async () => {
    const bundle = { overallFlow: { selectedServiceDate: "2026-03-31" } };
    requestMock.mockResolvedValue({
      data: { selectedServiceDate: "2026-03-31", dates: { "2026-03-31": bundle } },
    });
    expect(await getRealPanelBundle("广州市", "78b33138", "不存在的日期")).toEqual(bundle);
  });
});
