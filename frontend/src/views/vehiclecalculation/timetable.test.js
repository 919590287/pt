import { describe, expect, it } from "vitest";
import { generateDirectionTimeline } from "./timetable.js";

describe("generateDirectionTimeline", () => {
  it("南沙10路上行保留高峰边界和末班，共生成19班", () => {
    const times = generateDirectionTimeline({
      serviceStart: 7 * 60,
      serviceEnd: 22 * 60,
      amStart: "07:00",
      amEnd: "08:00",
      amInterval: 30,
      pmStart: "17:30",
      pmEnd: "18:30",
      pmInterval: 30,
      offInterval: 60,
    });

    expect(times).toEqual([
      420, 450, 480,
      540, 600, 660, 720, 780, 840, 900, 960, 1020,
      1050, 1080, 1110,
      1170, 1230, 1290, 1320,
    ]);
  });

  it("没有高峰时仍同时保留首班和末班", () => {
    expect(generateDirectionTimeline({
      serviceStart: 6 * 60,
      serviceEnd: 7 * 60,
      amStart: "",
      amEnd: "",
      amInterval: 0,
      pmStart: "",
      pmEnd: "",
      pmInterval: 0,
      offInterval: 40,
    })).toEqual([360, 400, 420]);
  });

  it("服务时间截断高峰时以实际首末班为边界", () => {
    expect(generateDirectionTimeline({
      serviceStart: 7 * 60 + 15,
      serviceEnd: 8 * 60,
      amStart: "07:00",
      amEnd: "09:00",
      amInterval: 30,
      pmStart: "",
      pmEnd: "",
      pmInterval: 0,
      offInterval: 60,
    })).toEqual([435, 465, 480]);
  });
});
