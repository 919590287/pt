importScripts(process.env.VITE_APP_PUBLIC_PATH + "static/js/proj4.js");
try {
  importScripts(process.env.VITE_APP_BASE_API + "/pt/main2/getJsConfig/config.js");
} catch (error) {
  importScripts(process.env.VITE_APP_PUBLIC_PATH + "static/js/config.js");
}

class GeoJSONParser {
  // static DEFAULT_CRS = { "type": "name", "properties": { "name": "urn:ogc:def:crs:EPSG::3857" } };

  // urn:ogc:def:crs:EPSG::3857
  // urn:ogc:def:crs:OGC:1.3:CRS84
  static decodeCrs(crs) {
    try {
      if (crs.properties.name.indexOf("EPSG") > -1) {
        return crs.properties.name.match(/EPSG::\d+/)[0].replace("::", ":");
      } else {
        return crs.properties.name;
      }
    } catch (error) {
      return "EPSG:3857";
    }
  }

  constructor(json) {
    this.crs = json.crs;
    this.coordSys = GeoJSONParser.decodeCrs(json.crs);
    console.time("GeoJSONParser");
    this.#parse(json);
    console.timeEnd("GeoJSONParser");
  }

  #parse(data) {
    const list = [data];

    const propertiesLabels = {};
    const propertiesList = [{}];
    const pointList = [];
    const lineList = [];
    const polygonList = [];

    while (list.length > 0) {
      const item = list.pop();
      // propertiesKey默认为父元素的propertiesKey 如果父元素没有propertiesKey，则默认为0
      let propertiesKey = item.propertiesKey || 0;
      // 如果有properties，则根据properties的长度生成propertiesKey，propertiesKey=0代表没有属性
      if (item.properties) {
        propertiesKey = propertiesList.length;
        propertiesList[propertiesKey] = JSON.parse(JSON.stringify(item.properties));
        for (const key in item.properties) {
          // 判断属性类型
          const type = Number.isFinite(Number(item.properties[key])) && item.properties[key] !== "" ? "Number" : "String";
          if (type === "String") {
            // 如果value是字符串类型，则创建一个map作为映射表，把字符串映射成数字再使用
            propertiesLabels[key] = { type: "String", name: key, map: new Map(), min: 0, max: 0, values: [] };
          } else if (type === "Number") {
            // 如果value是数字类型，则直接使用
            propertiesLabels[key] = { type: "Number", name: key, min: 0, max: 1, values: [] };
          }
        }
      }
      // 解析几何属性
      if (item.type === "FeatureCollection") {
        for (const f of item.features) {
          f.propertiesKey = propertiesKey;
          list.push(f);
        }
      } else if (item.type === "Feature") {
        item.geometry.propertiesKey = propertiesKey;
        list.push(item.geometry);
      } else if (item.type === "GeometryCollection") {
        for (const g of item.geometries) {
          g.propertiesKey = propertiesKey;
          list.push(g);
        }
      } else if (item.type === "Point") {
        const geometry = this.getMultiPoint([item.coordinates]);
        pointList[pointList.length] = {
          propertiesKey: propertiesKey || 0,
          geometry: geometry,
        };
      } else if (item.type === "MultiPoint") {
        const geometry = this.getMultiPoint(item.coordinates);
        pointList[pointList.length] = {
          propertiesKey: propertiesKey || 0,
          geometry: geometry,
        };
      } else if (item.type === "LineString") {
        const geometry = this.getMultiLineString([item.coordinates]);
        lineList[lineList.length] = {
          propertiesKey: propertiesKey || 0,
          geometry: geometry,
        };
      } else if (item.type === "MultiLineString") {
        const geometry = this.getMultiLineString(item.coordinates);
        lineList[lineList.length] = {
          propertiesKey: propertiesKey || 0,
          geometry: geometry,
        };
      } else if (item.type === "Polygon") {
        const geometry = this.getMultiPolygon([item.coordinates]);
        polygonList[polygonList.length] = {
          propertiesKey: propertiesKey || 0,
          geometry: geometry,
        };
      } else if (item.type === "MultiPolygon") {
        const geometry = this.getMultiPolygon(item.coordinates);
        polygonList[polygonList.length] = {
          propertiesKey: propertiesKey || 0,
          geometry: geometry,
        };
      }
    }
    // 创建点数据数组
    {
      const list = [];
      for (const { propertiesKey, geometry } of pointList) {
        for (const [x, y] of geometry) {
          list.push(x, y, propertiesKey);
        }
      }
      this.pointArray = new Float64Array(list);
    }
    // 创建线数据数组
    {
      const list = [];
      for (const { propertiesKey, geometry } of lineList) {
        for (const line of geometry) {
          const l = line.flat();
          list.push(l.length + 1);
          list.push(propertiesKey);
          list.push(...l);
        }
      }
      this.lineArray = new Float64Array(list);
    }
    // 创建多边形数据数组
    {
      const list = [];
      for (const { propertiesKey, geometry } of polygonList) {
        for (const polygon of geometry) {
          const shapeList = [];
          for (const shape of polygon) {
            const l = shape.flat();
            shapeList.push(l.length);
            shapeList.push(...l);
          }
          list.push(shapeList.length + 1);
          list.push(propertiesKey);
          list.push(...shapeList);
        }
      }
      this.polygonArray = new Float64Array(list);
    }
    // 创建属性数组
    {
      for (const [key, label] of Object.entries(propertiesLabels)) {
        if (label.type === "String") {
          for (const [index, properties] of propertiesList.entries()) {
            const value = properties[key];
            if (value !== undefined && !label.map.has(value)) {
              const v = label.map.size + 1;
              label.map.set(value, v);
              label.max = v;
            }
            label.values[index] = label.map.get(value) || 0;
          }
        } else if (label.type === "Number") {
          for (const [index, properties] of propertiesList.entries()) {
            const value = Number(properties[key] || 0);
            label.values[index] = value;
            label.max = Math.max(label.max, value);
            label.min = Math.min(label.min, value);
          }
        }
      }
      this.propertiesLabels = propertiesLabels;
      const encode = new TextEncoder();
      this.propertiesLabelsArray = encode.encode(JSON.stringify(propertiesLabels));
    }
  }

  getMultiPoint(coordinates) {
    const list = [];
    for (let i = 0; i < coordinates.length; i++) {
      let [x, y] = coordinates[i];
      if (this.coordSys !== "EPSG:3857") [x, y] = proj4(this.coordSys, "EPSG:3857", [x, y]);
      if (!this.center) this.center = [x, y];
      x -= this.center[0];
      y -= this.center[1];
      list[list.length] = [x, y];
    }
    return list;
  }

  getMultiLineString(coordinates) {
    const list = [];
    for (let i = 0; i < coordinates.length; i++) {
      const line = [];
      let fromCoord = null;
      let length = 0;
      for (let j = 0; j < coordinates[i].length; j++) {
        let [x, y] = coordinates[i][j];
        if (this.coordSys !== "EPSG:3857") [x, y] = proj4(this.coordSys, "EPSG:3857", [x, y]);
        if (!this.center) this.center = [x, y];
        x -= this.center[0];
        y -= this.center[1];
        if (j == 0) {
          line[0] = [x, y, 0];
        } else {
          const lineLength = Math.sqrt(Math.pow(fromCoord[0] - x, 2) + Math.pow(fromCoord[1] - y, 2));
          length += lineLength;
          const toLength = length;
          line[j] = [x, y, toLength];
        }
        fromCoord = [x, y];
      }
      list[list.length] = line;
    }
    return list;
  }

  getMultiPolygon(coordinates) {
    const list = [];
    for (let i = 0; i < coordinates.length; i++) {
      const coordinate = [];
      for (let j = 0; j < coordinates[i].length; j++) {
        coordinate[j] = [];
        for (let k = 0; k < coordinates[i][j].length; k++) {
          let [x, y] = coordinates[i][j][k];
          if (this.coordSys !== "EPSG:3857") [x, y] = proj4(this.coordSys, "EPSG:3857", [x, y]);
          if (!this.center) this.center = [x, y];
          x -= this.center[0];
          y -= this.center[1];
          coordinate[j][k] = [x, y];
        }
      }
      list[list.length] = coordinate;
    }
    return list;
  }
}

onmessage = function (e) {
  const decode = new TextDecoder();
  const str = decode.decode(e.data);
  const parser = new GeoJSONParser(JSON.parse(str));
  this.postMessage(
    {
      center: parser.center,
      propertiesLabelsArray: parser.propertiesLabelsArray,
      pointArray: parser.pointArray,
      lineArray: parser.lineArray,
      polygonArray: parser.polygonArray,
    },
    [parser.pointArray.buffer, parser.lineArray.buffer, parser.polygonArray.buffer, parser.propertiesLabelsArray.buffer],
  );
};
