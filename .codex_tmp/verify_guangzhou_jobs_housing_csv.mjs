import fs from "node:fs";
import fsp from "node:fs/promises";
import readline from "node:readline";
import { Workbook } from "@oai/artifact-tool";

const csvPath =
  "/Volumes/USB DISK/pt_data/广州市/真实数据/职住人口/广州市百米网格职住人口_WGS84.csv";
const previewPath =
  "/Users/a../模型算法/新公交平台/.codex_tmp/广州市百米网格职住人口_WGS84_抽样预览.png";

const reader = readline.createInterface({
  input: fs.createReadStream(csvPath, { encoding: "utf8" }),
  crlfDelay: Infinity,
});
const sampleLines = [];
for await (const line of reader) {
  sampleLines.push(line);
  if (sampleLines.length >= 30) {
    reader.close();
    break;
  }
}

const workbook = await Workbook.fromCSV(sampleLines.join("\n"), {
  sheetName: "抽样检查",
});
const sheet = workbook.worksheets.getItem("抽样检查");
sheet.getRange("A1:A30").format.columnWidth = 26;
sheet.getRange("B1:D30").format.columnWidth = 20;
sheet.getRange("A1:D1").format.wrapText = true;
sheet.getRange("A1:D1").format.rowHeight = 32;
const inspection = await workbook.inspect({
  kind: "table",
  range: "抽样检查!A1:D8",
  include: "values",
  tableMaxRows: 8,
  tableMaxCols: 4,
});
console.log(inspection.ndjson);

const preview = await workbook.render({
  sheetName: "抽样检查",
  range: "A1:D15",
  scale: 2,
  format: "png",
});
await fsp.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
console.log(JSON.stringify({ previewPath, sampledRows: sampleLines.length - 1 }));
