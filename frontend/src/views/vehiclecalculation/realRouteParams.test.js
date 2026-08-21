import { describe, expect, it } from "vitest";
import {
  buildRouteOptions,
  extractRouteFormValues,
  parseClockTime,
  parsePeakWindow,
  parseRouteDurations,
  searchRouteOptions,
  splitRouteName,
} from "./realRouteParams.js";

function lineFeature(properties) {
  return { type: "Feature", geometry: null, properties };
}

const UP = lineFeature({
  name: "南沙65路(市桥汽车站东门(番禺人才市场)站--潭洲车站总站)",
  line_id: "440100016174",
  company: "巴士集团",
  first: "06:10:00",
  last: "23:00:00",
  am_peak: "07:00-09:00",
  pm_peak: "17:00-19:00",
  am_gap: "10",
  pm_gap: "10",
  off_gap: "15",
  op_time: "60-65",
});
const DOWN = lineFeature({
  name: "南沙65路(潭洲车站总站--市桥汽车站西门站)",
  line_id: "440100016173",
  company: "巴士集团",
  first: "05:00:00",
  last: "21:55:00",
  am_peak: "07:00-09:00",
  pm_peak: "17:00-19:00",
  am_gap: "10",
  pm_gap: "10",
  off_gap: "15",
  op_time: "60-65",
});

describe("splitRouteName", () => {
  it("剥掉尾部端点括号，站名自带的嵌套括号不会截断线路名", () => {
    expect(splitRouteName("102路(广钢新城总站(崇文二路)--东山总站)").family).toBe("102路");
    expect(splitRouteName("101路(机场路总站--海印桥总站)").family).toBe("101路");
  });

  it("只剥端点括号，保留走向后缀，快线不与普线并线", () => {
    expect(splitRouteName("南沙65路(快)(大岗公交总站--市桥汽车站西门站)").family).toBe("南沙65路(快)");
    expect(splitRouteName("番108路(环线)(番禺体校总站--番禺体校总站)").family).toBe("番108路(环线)");
  });
});

describe("parseClockTime", () => {
  it("24 时以上的夜班末班拆成钟面时刻加次日标记", () => {
    expect(parseClockTime("06:00:00")).toEqual({ time: "06:00", nextDay: false });
    expect(parseClockTime("24:30:00")).toEqual({ time: "00:30", nextDay: true });
    expect(parseClockTime("29:50")).toEqual({ time: "05:50", nextDay: true });
  });

  it("空值与非法值返回 null，不做兜底猜测", () => {
    expect(parseClockTime("")).toBeNull();
    expect(parseClockTime("暂无")).toBeNull();
    expect(parseClockTime("48:00")).toBeNull();
  });
});

describe("parsePeakWindow", () => {
  it("解析 HH:MM-HH:MM，24:00 收口到 23:59", () => {
    expect(parsePeakWindow("07:00-09:00")).toMatchObject({ start: "07:00", end: "09:00" });
    expect(parsePeakWindow("06:00-24:00")).toMatchObject({ start: "06:00", end: "23:59", clamped: true });
    expect(parsePeakWindow("")).toBeNull();
  });
});

describe("parseRouteDurations", () => {
  it("单个数字同时作为上下行单程时间", () => {
    expect(parseRouteDurations("50")).toEqual([50, 50]);
    expect(parseRouteDurations("50 分钟")).toEqual([50, 50]);
  });

  it("两个数字按上行、下行顺序解析常见分隔符", () => {
    expect(parseRouteDurations("70-75")).toEqual([70, 75]);
    expect(parseRouteDurations("35~45")).toEqual([35, 45]);
    expect(parseRouteDurations("55～65")).toEqual([55, 65]);
  });

  it("空值、非数字和非正数不自动填入", () => {
    expect(parseRouteDurations("")).toBeNull();
    expect(parseRouteDurations("暂无")).toBeNull();
    expect(parseRouteDurations("0")).toBeNull();
  });
});

describe("buildRouteOptions / searchRouteOptions", () => {
  it("同名线路的两个走向归为一条可选线路", () => {
    const options = buildRouteOptions({ features: [UP, DOWN] });
    expect(options).toHaveLength(1);
    expect(options[0].name).toBe("南沙65路");
    expect(options[0].features).toHaveLength(2);
  });

  it("按线路名与编号检索，全等优先于前缀优先于包含", () => {
    const options = buildRouteOptions({
      features: [UP, DOWN, lineFeature({ name: "夜65路(A--B)" }), lineFeature({ name: "65路(A--B)" })],
    });
    // 同为"包含"命中时按命中位置排序：夜65路 命中于第 1 位，南沙65路 命中于第 2 位
    expect(searchRouteOptions(options, "65路").map((item) => item.name)).toEqual(["65路", "夜65路", "南沙65路"]);
    expect(searchRouteOptions(options, "440100016174").map((item) => item.name)).toEqual(["南沙65路"]);
    expect(searchRouteOptions(options, "").length).toBe(3);
  });
});

describe("extractRouteFormValues", () => {
  it("op_time 只有一个数字时同时填入上下行单程时间", () => {
    const [option] = buildRouteOptions({
      features: [
        lineFeature({ name: "南沙16路(A--B)", op_time: "50" }),
        lineFeature({ name: "南沙16路(B--A)", op_time: "50" }),
      ],
    });
    const { values, missing } = extractRouteFormValues(option);
    expect(values.upDuration).toBe(50);
    expect(values.downDuration).toBe(50);
    expect(missing.map((item) => item.key)).not.toContain("upDuration");
    expect(missing.map((item) => item.key)).not.toContain("downDuration");
  });

  it("op_time 为空时清空上下行单程时间并标记为待填写", () => {
    const [option] = buildRouteOptions({
      features: [lineFeature({ name: "无时间线路(A--B)" })],
    });
    const { values, missing } = extractRouteFormValues(option);
    expect(values.upDuration).toBeNull();
    expect(values.downDuration).toBeNull();
    expect(missing.map((item) => item.key)).toEqual(expect.arrayContaining(["upDuration", "downDuration"]));
  });

  it("字段齐全时填满服务时段、高峰时段与三档发车间隔", () => {
    const [option] = buildRouteOptions({ features: [UP, DOWN] });
    const { values, missing, notes } = extractRouteFormValues(option);
    expect(missing).toEqual([]);
    expect(notes).toEqual([]);
    expect(values).toMatchObject({
      upServiceStart: "06:10",
      upServiceEnd: "23:00",
      upServiceEndNextDay: false,
      downServiceStart: "05:00",
      downServiceEnd: "21:55",
      upAmStart: "07:00",
      upAmEnd: "09:00",
      upPmStart: "17:00",
      upPmEnd: "19:00",
      upAmInterval: 10,
      upPmInterval: 10,
      upOffInterval: 15,
      downAmStart: "07:00",
      downAmEnd: "09:00",
      downPmStart: "17:00",
      downPmEnd: "19:00",
      downAmInterval: 10,
      downPmInterval: 10,
      downOffInterval: 15,
      upDuration: 60,
      downDuration: 65,
    });
  });

  it("交换上下行后服务时段互换", () => {
    const [option] = buildRouteOptions({ features: [UP, DOWN] });
    const { values, line } = extractRouteFormValues(option, { swapped: true });
    expect(values.upServiceStart).toBe("05:00");
    expect(values.downServiceStart).toBe("06:10");
    expect(values.upDuration).toBe(65);
    expect(values.downDuration).toBe(60);
    expect(line.upLabel).toBe("潭洲车站总站 → 市桥汽车站西门站");
  });

  it("高峰时段与间隔同时为空时视为没有该高峰，只提示其他真正缺项", () => {
    const [option] = buildRouteOptions({
      features: [
        lineFeature({ name: "试1路(A--B)", first: "06:00:00", last: "22:00:00", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "", op_time: "40" }),
        lineFeature({ name: "试1路(B--A)", first: "06:00:00", last: "22:00:00", am_peak: "07:00-09:00", pm_peak: "17:00-19:00", am_gap: "12", pm_gap: "12", off_gap: "20" }),
      ],
    });
    const { values, missing } = extractRouteFormValues(option);
    // 下行字段齐全就不该因为上行为空而被标缺，反之亦然
    expect(missing.map((item) => item.key)).toEqual(["upOffInterval"]);
    expect(missing.every((item) => item.group === "上行")).toBe(true);
    expect(values).toMatchObject({
      upAmStart: "",
      upAmInterval: null,
      upOffInterval: null,
      downAmStart: "07:00",
      downAmInterval: 12,
      downOffInterval: 20,
    });
    expect(values.upServiceStart).toBe("06:00");
  });

  it("上下行高峰不同时各填各的，不跨方向兜底", () => {
    const [option] = buildRouteOptions({
      features: [
        lineFeature({ name: "南沙10路(A--B)", first: "07:00:00", last: "22:00:00", am_peak: "07:00-08:00", pm_peak: "17:30-18:30", am_gap: "30", pm_gap: "30", off_gap: "60", op_time: "70-75" }),
        lineFeature({ name: "南沙10路(B--A)", first: "06:00:00", last: "21:00:00", am_peak: "06:00-07:00", pm_peak: "", am_gap: "25", pm_gap: "", off_gap: "45" }),
      ],
    });
    const { values, missing, notes } = extractRouteFormValues(option);
    expect(values).toMatchObject({
      upAmStart: "07:00",
      upAmEnd: "08:00",
      upAmInterval: 30,
      upOffInterval: 60,
      downAmStart: "06:00",
      downAmEnd: "07:00",
      downAmInterval: 25,
      downOffInterval: 45,
      downPmStart: "",
      downPmInterval: null,
    });
    expect(missing).toEqual([]);
    expect(notes).toContain("下行未设置晚高峰，该时段按平峰间隔计算。");
  });

  it("高峰时段与间隔只缺一项时仍标记为待补全", () => {
    const [option] = buildRouteOptions({
      features: [
        lineFeature({ name: "试3路(A--B)", first: "06:00:00", last: "22:00:00", am_peak: "07:00-09:00", am_gap: "", pm_peak: "", pm_gap: "20", off_gap: "30", op_time: "30" }),
        lineFeature({ name: "试3路(B--A)", first: "06:00:00", last: "22:00:00", am_peak: "07:00-09:00", am_gap: "15", pm_peak: "17:00-19:00", pm_gap: "15", off_gap: "30" }),
      ],
    });
    const { missing } = extractRouteFormValues(option);
    expect(missing.map((item) => item.key)).toEqual(["upAmInterval", "upPmPeak"]);
  });

  it("单走向线路把下行整套标为缺失并给出说明", () => {
    const [option] = buildRouteOptions({
      features: [lineFeature({ name: "109路(中山八路总站--中山八路总站)", first: "06:00:00", last: "22:00:00", am_gap: "8", pm_gap: "8", off_gap: "12", am_peak: "07:00-09:00", pm_peak: "17:00-19:00", op_time: "30" })],
    });
    const { missing, notes, values } = extractRouteFormValues(option);
    expect(missing.map((item) => item.key)).toEqual([
      "downService", "downAmPeak", "downPmPeak", "downAmInterval", "downPmInterval", "downOffInterval",
    ]);
    expect(notes[0]).toContain("只有一个走向");
    expect(values.downServiceStart).toBe("");
    expect(values.upAmInterval).toBe(8);
  });

  it("末班跨零点时置次日标记", () => {
    const [option] = buildRouteOptions({
      features: [
        lineFeature({ name: "夜101路(A--B)", first: "22:00:00", last: "24:30:00" }),
        lineFeature({ name: "夜101路(B--A)", first: "22:00:00", last: "25:00:00" }),
      ],
    });
    const { values } = extractRouteFormValues(option);
    expect(values).toMatchObject({
      upServiceEnd: "00:30",
      upServiceEndNextDay: true,
      downServiceEnd: "01:00",
      downServiceEndNextDay: true,
    });
  });

  it("交换上下行后高峰参数随方向一起换", () => {
    const [option] = buildRouteOptions({
      features: [
        lineFeature({ name: "试2路(A--B)", first: "06:00:00", last: "22:00:00", am_peak: "07:00-09:00", pm_peak: "17:00-19:00", am_gap: "20", pm_gap: "20", off_gap: "30" }),
        lineFeature({ name: "试2路(B--A)", first: "05:40:00", last: "21:30:00", am_peak: "07:00-08:00", pm_peak: "17:00-18:00", am_gap: "25", pm_gap: "25", off_gap: "40" }),
      ],
    });
    const { values } = extractRouteFormValues(option, { swapped: true });
    expect(values).toMatchObject({
      upServiceStart: "05:40",
      upAmEnd: "08:00",
      upAmInterval: 25,
      upOffInterval: 40,
      downServiceStart: "06:00",
      downAmEnd: "09:00",
      downAmInterval: 20,
      downOffInterval: 30,
    });
  });
});
