import { beforeEach, describe, expect, it, vi } from "vitest";

const requestMock = vi.hoisted(() => vi.fn());

vi.mock("@/utils/request.js", () => ({
  default: requestMock,
}));

import {
  REAL_AVERAGE_DATE,
  buildRealTransitNetwork,
  clearRealPassengerFlowCache,
  encodeCorridorLinks,
  getRealPassengerFlowCapabilities,
  isRealDatasource,
  realAreaFromDatasource,
  realDatasource,
  realLineGroupName,
  realServiceDateFromDatasource,
} from "./realPassengerFlow.js";
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
});
