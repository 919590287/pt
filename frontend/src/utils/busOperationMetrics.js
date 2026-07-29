function finiteNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

/**
 * 公交运营效率的唯一公式入口。
 *
 * 仿真与真实模式都只提供同一统计日口径下的分子/分母，三个比值在这里统一计算：
 * 车均日载客量 = 日上车人次 / 去重运营车辆；
 * 单班次载客量 = 日上车人次 / 日发车班次；
 * 客流强度 = 日上车人次 / 日运营车公里。
 */
export function busOperationRatios(passenger, operation = {}) {
  const dailyPassenger = finiteNumber(passenger);
  const vehicles = finiteNumber(operation?.vehicles);
  const departures = finiteNumber(operation?.departures);
  const operatedKm = finiteNumber(operation?.operatedKm);
  return {
    perVehicle: vehicles > 0 ? dailyPassenger / vehicles : Number.NaN,
    perTrip: departures > 0 ? dailyPassenger / departures : Number.NaN,
    intensity: operatedKm > 0 ? dailyPassenger / operatedKm : Number.NaN,
  };
}
