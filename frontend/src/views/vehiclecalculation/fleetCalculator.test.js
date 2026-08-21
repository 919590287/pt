import { describe, expect, it } from "vitest";
import { scheduleVehiclesOnly } from "./fleetCalculator.js";

describe("scheduleVehiclesOnly", () => {
  it("returns one physical-line fleet shared by both directions", () => {
    const schedule = scheduleVehiclesOnly(
      [360, 390, 420],
      [375, 405, 435],
      60,
      60,
      25,
      3,
      20,
      20,
      400,
      250,
    );

    expect(schedule.vehicles).toBe(6);
    expect(schedule.vehicleTasks).toHaveLength(6);
    expect(schedule.vehicleTasks[0].tasks[0]).toMatchObject({ planned: 360, mileage: 20 });
  });
});
