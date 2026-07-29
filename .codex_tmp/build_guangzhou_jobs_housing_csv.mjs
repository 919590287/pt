import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import readline from "node:readline";

const sourcePath =
  "/Volumes/USB DISK/数据/高德OD数据/202305广州通勤数据V2/广州职住表_202305.txt";
const outputDir = "/Volumes/USB DISK/pt_data/广州市/真实数据/职住人口";
const csvPath = path.join(outputDir, "广州市百米网格职住人口_WGS84.csv");
const descriptionPath = path.join(
  outputDir,
  "广州市百米网格职住人口_WGS84_字段说明.txt",
);
const csvTempPath = `${csvPath}.tmp`;
const descriptionTempPath = `${descriptionPath}.tmp`;

const PI = Math.PI;
const A = 6378245.0;
const EE = 0.00669342162296594323;

function outOfChina(lon, lat) {
  return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
}

function transformLat(x, y) {
  let result =
    -100.0 +
    2.0 * x +
    3.0 * y +
    0.2 * y * y +
    0.1 * x * y +
    0.2 * Math.sqrt(Math.abs(x));
  result +=
    ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) /
    3.0;
  result +=
    ((20.0 * Math.sin(y * PI) + 40.0 * Math.sin((y / 3.0) * PI)) * 2.0) /
    3.0;
  result +=
    ((160.0 * Math.sin((y / 12.0) * PI) +
      320 * Math.sin((y * PI) / 30.0)) *
      2.0) /
    3.0;
  return result;
}

function transformLon(x, y) {
  let result =
    300.0 +
    x +
    2.0 * y +
    0.1 * x * x +
    0.1 * x * y +
    0.1 * Math.sqrt(Math.abs(x));
  result +=
    ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) /
    3.0;
  result +=
    ((20.0 * Math.sin(x * PI) + 40.0 * Math.sin((x / 3.0) * PI)) * 2.0) /
    3.0;
  result +=
    ((150.0 * Math.sin((x / 12.0) * PI) +
      300.0 * Math.sin((x / 30.0) * PI)) *
      2.0) /
    3.0;
  return result;
}

function wgs84ToGcj02(lon, lat) {
  if (outOfChina(lon, lat)) return [lon, lat];
  let dLat = transformLat(lon - 105.0, lat - 35.0);
  let dLon = transformLon(lon - 105.0, lat - 35.0);
  const radLat = (lat / 180.0) * PI;
  let magic = Math.sin(radLat);
  magic = 1 - EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat =
    (dLat * 180.0) /
    (((A * (1 - EE)) / (magic * sqrtMagic)) * PI);
  dLon = (dLon * 180.0) / ((A / sqrtMagic) * Math.cos(radLat) * PI);
  return [lon + dLon, lat + dLat];
}

function gcj02ToWgs84(lon, lat) {
  if (outOfChina(lon, lat)) return [lon, lat];
  let wgsLon = lon;
  let wgsLat = lat;
  for (let i = 0; i < 8; i += 1) {
    const [calculatedLon, calculatedLat] = wgs84ToGcj02(wgsLon, wgsLat);
    const errorLon = calculatedLon - lon;
    const errorLat = calculatedLat - lat;
    wgsLon -= errorLon;
    wgsLat -= errorLat;
    if (Math.abs(errorLon) < 1e-11 && Math.abs(errorLat) < 1e-11) break;
  }
  return [wgsLon, wgsLat];
}

function parsePopulation(value, lineNumber, fieldName) {
  if (value === "") return 0;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new Error(
      `第 ${lineNumber} 行的 ${fieldName} 不是非负整数：${JSON.stringify(value)}`,
    );
  }
  return parsed;
}

async function writeLine(stream, text) {
  if (!stream.write(text)) {
    await new Promise((resolve) => stream.once("drain", resolve));
  }
}

await fsp.mkdir(outputDir, { recursive: true });
await Promise.all([
  fsp.rm(csvTempPath, { force: true }),
  fsp.rm(descriptionTempPath, { force: true }),
]);

const input = fs.createReadStream(sourcePath, { encoding: "utf8" });
const output = fs.createWriteStream(csvTempPath, { encoding: "utf8" });
const reader = readline.createInterface({ input, crlfDelay: Infinity });

let sourceLineNumber = 0;
let rowCount = 0;
let homeSum = 0;
let companySum = 0;
let residentSum = 0;
let minLon = Infinity;
let maxLon = -Infinity;
let minLat = Infinity;
let maxLat = -Infinity;
let blankHomeCount = 0;
let blankCompanyCount = 0;
let blankResidentCount = 0;

await writeLine(
  output,
  "\uFEFF百米网格坐标（WGS-84）,通勤居住人口数量,通勤就业人口数量,常住人口数量\n",
);

try {
  for await (const rawLine of reader) {
    sourceLineNumber += 1;
    const line = rawLine.replace(/\r$/, "");
    if (sourceLineNumber === 1) {
      const normalizedHeader = line.replace(/^\uFEFF/, "");
      if (
        normalizedHeader !==
        "grid_id,center_xy,grid_len,home_uv,company_uv,resident_uv"
      ) {
        throw new Error(`源文件表头与预期不符：${normalizedHeader}`);
      }
      continue;
    }
    if (line === "") continue;

    const fields = line.split(",");
    if (fields.length !== 6) {
      throw new Error(`第 ${sourceLineNumber} 行字段数不是 6：${line}`);
    }
    const [lonText, latText] = fields[1].split(";");
    const gcjLon = Number(lonText);
    const gcjLat = Number(latText);
    if (!Number.isFinite(gcjLon) || !Number.isFinite(gcjLat)) {
      throw new Error(`第 ${sourceLineNumber} 行坐标无效：${fields[1]}`);
    }

    if (fields[3] === "") blankHomeCount += 1;
    if (fields[4] === "") blankCompanyCount += 1;
    if (fields[5] === "") blankResidentCount += 1;
    const home = parsePopulation(fields[3], sourceLineNumber, "home_uv");
    const company = parsePopulation(fields[4], sourceLineNumber, "company_uv");
    const resident = parsePopulation(fields[5], sourceLineNumber, "resident_uv");
    const [wgsLon, wgsLat] = gcj02ToWgs84(gcjLon, gcjLat);

    minLon = Math.min(minLon, wgsLon);
    maxLon = Math.max(maxLon, wgsLon);
    minLat = Math.min(minLat, wgsLat);
    maxLat = Math.max(maxLat, wgsLat);
    homeSum += home;
    companySum += company;
    residentSum += resident;
    rowCount += 1;

    await writeLine(
      output,
      `${wgsLon.toFixed(6)};${wgsLat.toFixed(6)},${home},${company},${resident}\n`,
    );
  }
} catch (error) {
  output.destroy();
  await fsp.rm(csvTempPath, { force: true });
  throw error;
}

await new Promise((resolve, reject) => {
  output.end(resolve);
  output.once("error", reject);
});

if (rowCount !== 273733) {
  await fsp.rm(csvTempPath, { force: true });
  throw new Error(`输出网格数异常：预期 273733，实际 ${rowCount}`);
}
if (
  homeSum !== 12139877 ||
  companySum !== 12140244 ||
  residentSum !== 22852869
) {
  await fsp.rm(csvTempPath, { force: true });
  throw new Error(
    `人口总量校验失败：home=${homeSum}, company=${companySum}, resident=${residentSum}`,
  );
}

const description = `广州市百米网格职住人口（WGS-84）字段说明

一、文件信息
数据文件：广州市百米网格职住人口_WGS84.csv
编码格式：UTF-8（带 BOM，便于 Excel 正确显示中文）
分隔符：英文逗号
数据行数：${rowCount} 行（不含表头）
空间粒度：100 米 × 100 米网格
原始数据：${sourcePath}

二、字段说明
1. 百米网格坐标（WGS-84）
   含义：百米网格中心点坐标。
   格式：经度;纬度，例如 113.123456;23.123456。
   坐标系：WGS-84（EPSG:4326）。
   精度：经纬度各保留 6 位小数。
   来源与处理：由源文件 center_xy 字段的 GCJ-02 坐标采用迭代反算方法转换。

2. 通勤居住人口数量
   来源字段：home_uv。
   含义：居住在该网格内、且被识别为有通勤行为的人数。
   单位：人。
   空值处理：源数据空值按 0 人处理。

3. 通勤就业人口数量
   来源字段：company_uv。
   含义：工作地点位于该网格内、且被识别为有通勤行为的人数。
   单位：人。
   空值处理：源数据空值按 0 人处理。

4. 常住人口数量
   来源字段：resident_uv。
   含义：居住在该网格内的常驻人数，不限于有通勤行为的人群。
   单位：人。
   空值处理：源数据空值按 0 人处理。

三、数据校验结果
网格总数：${rowCount}
通勤居住人口合计：${homeSum}
通勤就业人口合计：${companySum}
常住人口合计：${residentSum}
WGS-84 经度范围：${minLon.toFixed(6)} 至 ${maxLon.toFixed(6)}
WGS-84 纬度范围：${minLat.toFixed(6)} 至 ${maxLat.toFixed(6)}
源数据 home_uv 空值数：${blankHomeCount}
源数据 company_uv 空值数：${blankCompanyCount}
源数据 resident_uv 空值数：${blankResidentCount}

四、使用注意事项
1. 每行代表一个百米网格，坐标是网格中心点，不是个人住宅或工作单位的精确位置。
2. GCJ-02 转 WGS-84 属于坐标反算；在百米网格尺度分析中可直接使用。
3. 若软件不能自动识别“经度;纬度”格式，可按英文分号拆分为经度、纬度两列。
`;

await fsp.writeFile(descriptionTempPath, description, "utf8");
await Promise.all([
  fsp.rename(csvTempPath, csvPath),
  fsp.rename(descriptionTempPath, descriptionPath),
]);

const result = {
  csvPath,
  descriptionPath,
  rowCount,
  totals: { homeSum, companySum, residentSum },
  bboxWgs84: { minLon, minLat, maxLon, maxLat },
  blankCounts: { blankHomeCount, blankCompanyCount, blankResidentCount },
};
console.log(JSON.stringify(result, null, 2));
