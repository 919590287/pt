import { generateDirectionTimeline } from "./timetable.js";

export const DEFAULT_FLEET_PARAMS = Object.freeze({
  turnTime: 25,
  errorMargin: 3,
  largeRange: 400,
  smallRange: 250,
});

export function scheduleVehiclesOnly(
  upTimes,
  downTimes,
  upDuration,
  downDuration,
  turnTime = DEFAULT_FLEET_PARAMS.turnTime,
  errorMargin = DEFAULT_FLEET_PARAMS.errorMargin,
  upLength = 20,
  downLength = 20,
  largeRange = DEFAULT_FLEET_PARAMS.largeRange,
  smallRange = DEFAULT_FLEET_PARAMS.smallRange,
) {
  const tasks = [];
  upTimes.forEach((time, index) => tasks.push({
    id: `up_${index}`, direction: "上行", start: time, duration: upDuration,
    from: "A", to: "B", idx: index, length: upLength,
  }));
  downTimes.forEach((time, index) => tasks.push({
    id: `down_${index}`, direction: "下行", start: time, duration: downDuration,
    from: "B", to: "A", idx: index, length: downLength,
  }));
  tasks.sort((left, right) => left.start - right.start);

  const vehicles = [];
  const taskToVehicle = new Map();
  const actualDepart = new Map();
  for (const task of tasks) {
    const candidates = vehicles
      .map((vehicle, index) => ({ vehicle, index }))
      .filter(({ vehicle }) => vehicle.availableTime <= task.start + errorMargin
        && vehicle.currentStation === task.from
        && vehicle.totalMileage + task.length <= largeRange);
    let best = null;
    for (const candidate of candidates) {
      const taskCount = candidate.vehicle.tasks.length;
      const key = [taskCount % 2 === 1 ? 0 : 1, taskCount,
        Math.max(0, task.start - candidate.vehicle.availableTime)];
      const better = best && key.some((value, index) => {
        if (value < best.key[index]) return true;
        if (value > best.key[index]) return false;
        return index === key.length - 1;
      });
      if (!best || better) {
        best = { ...candidate, key };
      }
    }
    if (best) {
      const depart = Math.max(best.vehicle.availableTime, task.start);
      best.vehicle.tasks.push(task);
      best.vehicle.currentStation = task.to;
      best.vehicle.availableTime = depart + task.duration + turnTime;
      best.vehicle.totalMileage += task.length;
      actualDepart.set(task.id, depart);
      taskToVehicle.set(task.id, best.index);
    } else {
      vehicles.push({ tasks: [task], currentStation: task.to,
        availableTime: task.start + task.duration + turnTime, totalMileage: task.length });
      actualDepart.set(task.id, task.start);
      taskToVehicle.set(task.id, vehicles.length - 1);
    }
  }

  const vehicleTypes = vehicles.map((vehicle) => vehicle.totalMileage <= smallRange ? "small" : "large");
  const upVehicle = new Array(upTimes.length).fill("");
  const upType = new Array(upTimes.length).fill("");
  const downVehicle = new Array(downTimes.length).fill("");
  const downType = new Array(downTimes.length).fill("");
  tasks.forEach((task) => {
    const vehicleIndex = taskToVehicle.get(task.id);
    const type = vehicleTypes[vehicleIndex] === "small" ? "小" : "大";
    if (task.direction === "上行") { upVehicle[task.idx] = `C${vehicleIndex + 1}`; upType[task.idx] = type; }
    else { downVehicle[task.idx] = `C${vehicleIndex + 1}`; downType[task.idx] = type; }
  });
  return {
    vehicles: vehicles.length,
    largeCount: vehicles.filter((_, index) => vehicleTypes[index] === "large").length,
    smallCount: vehicles.filter((_, index) => vehicleTypes[index] === "small").length,
    upVehicle, upType, downVehicle, downType,
    vehicleTasks: vehicles.map((vehicle, index) => {
      let mileage = 0;
      const vehicleTasks = vehicle.tasks
        .map((task) => ({
          direction: task.direction,
          planned: task.start,
          actual: actualDepart.get(task.id),
          duration: task.duration,
          idx: task.idx,
          from: task.from,
          to: task.to,
          length: task.length,
        }))
        .sort((left, right) => left.actual - right.actual)
        .map((task) => {
          mileage += task.length;
          return { ...task, mileage };
        });
      return {
        vehicleId: `C${index + 1}`,
        type: vehicleTypes[index] === "small" ? "小" : "大",
        totalMileage: vehicle.totalMileage,
        tasks: vehicleTasks,
      };
    }),
  };
}

export function calculateFleetCount({ upTimes = [], downTimes = [], upDuration, downDuration, turnTime, errorMargin, upLength, downLength, largeRange, smallRange } = {}) {
  if (!upTimes.length && !downTimes.length) return 0;
  return scheduleVehiclesOnly(upTimes, downTimes, upDuration, downDuration, turnTime, errorMargin, upLength, downLength, largeRange, smallRange).vehicles;
}

export { generateDirectionTimeline };
