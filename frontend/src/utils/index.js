import { inject, watch, ref } from "vue";
import { saveAs } from "file-saver";

export function injectSync(name, rule = (v) => !!v) {
  return new Promise((resolve, reject) => {
    const injectValue = inject(name, null, true);
    if (rule(injectValue.value)) {
      resolve(injectValue);
    } else {
      const stopWatch = watch(
        injectValue,
        (value) => {
          if (rule(injectValue.value)) {
            resolve(injectValue);
            stopWatch();
          }
        },
        { immediate: true },
      );
    }
  });
}

export function addWatch(source, callback, options) {
  const stopWatch = watch(source, callback, options);
  stopWatch.callback = callback;
  return {
    pause: () => stopWatch.pause(),
    resume: () => stopWatch.resume(),
    stop: () => stopWatch.stop(),
    callback,
  };
}

/**
 * 参数处理
 * @param {*} params  参数
 */
export function tansParams(params) {
  let result = "";
  for (const propName of Object.keys(params)) {
    const value = params[propName];
    var part = encodeURIComponent(propName) + "=";
    if (value !== null && typeof value !== "undefined") {
      if (typeof value === "object") {
        for (const key of Object.keys(value)) {
          if (value[key] !== null && typeof value[key] !== "undefined") {
            let params = propName + "[" + key + "]";
            var subPart = encodeURIComponent(params) + "=";
            result += subPart + encodeURIComponent(value[key]) + "&";
          }
        }
      } else {
        result += part + encodeURIComponent(value) + "&";
      }
    }
  }
  return result;
}

// 验证是否为blob格式
export async function blobValidate(data) {
  try {
    const text = await data.text();
    JSON.parse(text);
    return false;
  } catch (error) {
    return true;
  }
}

/**
 * @param {string} url
 * @returns {Object}
 */
export function getQueryObject(url) {
  url = url == null ? window.location.href : url;
  const search = url.substring(url.lastIndexOf("?") + 1);
  const obj = {};
  const reg = /([^?&=]+)=([^?&=]*)/g;
  search.replace(reg, (rs, $1, $2) => {
    const name = decodeURIComponent($1);
    let val = decodeURIComponent($2);
    val = String(val);
    obj[name] = val;
    return rs;
  });
  return obj;
}

/**
 * @param {string} input value
 * @returns {number} output value
 */
export function byteLength(str) {
  // returns the byte length of an utf8 string
  let s = str.length;
  for (var i = str.length - 1; i >= 0; i--) {
    const code = str.charCodeAt(i);
    if (code > 0x7f && code <= 0x7ff) s++;
    else if (code > 0x7ff && code <= 0xffff) s += 2;
    if (code >= 0xdc00 && code <= 0xdfff) i--;
  }
  return s;
}

/**
 * @param {Array} actual
 * @returns {Array}
 */
export function cleanArray(actual) {
  const newArray = [];
  for (let i = 0; i < actual.length; i++) {
    if (actual[i]) {
      newArray.push(actual[i]);
    }
  }
  return newArray;
}

/**
 * @param {Object} json
 * @returns {Array}
 */
export function param(json) {
  if (!json) return "";
  return cleanArray(
    Object.keys(json).map((key) => {
      if (json[key] === undefined) return "";
      return encodeURIComponent(key) + "=" + encodeURIComponent(json[key]);
    }),
  ).join("&");
}

/**
 * @param {string} url
 * @returns {Object}
 */
export function param2Obj(url) {
  const search = decodeURIComponent(url.split("?")[1]).replace(/\+/g, " ");
  if (!search) {
    return {};
  }
  const obj = {};
  const searchArr = search.split("&");
  searchArr.forEach((v) => {
    const index = v.indexOf("=");
    if (index !== -1) {
      const name = v.substring(0, index);
      const val = v.substring(index + 1, v.length);
      obj[name] = val;
    }
  });
  return obj;
}

/**
 * @param {string} val
 * @returns {string}
 */
export function html2Text(val) {
  const div = document.createElement("div");
  div.innerHTML = val;
  return div.textContent || div.innerText;
}

/**
 * Merges two objects, giving the last one precedence
 * @param {Object} target
 * @param {(Object|Array)} source
 * @returns {Object}
 */
export function objectMerge(target, source) {
  if (typeof target !== "object") {
    target = {};
  }
  if (Array.isArray(source)) {
    return source.slice();
  }
  Object.keys(source).forEach((property) => {
    const sourceProperty = source[property];
    if (typeof sourceProperty === "object") {
      target[property] = objectMerge(target[property], sourceProperty);
    } else {
      target[property] = sourceProperty;
    }
  });
  return target;
}

/**
 * @param {HTMLElement} element
 * @param {string} className
 */
export function toggleClass(element, className) {
  if (!element || !className) {
    return;
  }
  let classString = element.className;
  const nameIndex = classString.indexOf(className);
  if (nameIndex === -1) {
    classString += "" + className;
  } else {
    classString = classString.substr(0, nameIndex) + classString.substr(nameIndex + className.length);
  }
  element.className = classString;
}

/**
 * @param {string} type
 * @returns {Date}
 */
export function getTime(type) {
  if (type === "start") {
    return new Date().getTime() - 3600 * 1000 * 24 * 90;
  } else {
    return new Date(new Date().toDateString());
  }
}

/**
 * @param {Function} func
 * @param {number} wait
 * @param {boolean} immediate
 * @return {*}
 */
export function debounce(func, wait, immediate) {
  let timeout, args, context, timestamp, result;

  const later = function () {
    // 据上一次触发时间间隔
    const last = +new Date() - timestamp;

    // 上次被包装函数被调用时间间隔 last 小于设定时间间隔 wait
    if (last < wait && last > 0) {
      timeout = setTimeout(later, wait - last);
    } else {
      timeout = null;
      // 如果设定为immediate===true，因为开始边界已经调用过了此处无需调用
      if (!immediate) {
        result = func.apply(context, args);
        if (!timeout) context = args = null;
      }
    }
  };

  return function (...args) {
    context = this;
    timestamp = +new Date();
    const callNow = immediate && !timeout;
    // 如果延时不存在，重新设定延时
    if (!timeout) timeout = setTimeout(later, wait);
    if (callNow) {
      result = func.apply(context, args);
      context = args = null;
    }

    return result;
  };
}

/**
 * This is just a simple version of deep copy
 * Has a lot of edge cases bug
 * If you want to use a perfect deep copy, use lodash's _.cloneDeep
 * @param {Object} source
 * @returns {Object}
 */
export function deepClone(source) {
  if (!source && typeof source !== "object") {
    throw new Error("error arguments", "deepClone");
  }
  const targetObj = source.constructor === Array ? [] : {};
  Object.keys(source).forEach((keys) => {
    if (source[keys] && typeof source[keys] === "object") {
      targetObj[keys] = deepClone(source[keys]);
    } else {
      targetObj[keys] = source[keys];
    }
  });
  return targetObj;
}

/**
 * @param {Array} arr
 * @returns {Array}
 */
export function uniqueArr(arr) {
  return Array.from(new Set(arr));
}

/**
 * @returns {string}
 */
export function createUniqueString() {
  const timestamp = +new Date() + "";
  const randomNum = parseInt((1 + Math.random()) * 65536) + "";
  return (+(randomNum + timestamp)).toString(32);
}

/**
 * Check if an element has a class
 * @param {HTMLElement} elm
 * @param {string} cls
 * @returns {boolean}
 */
export function hasClass(ele, cls) {
  return !!ele.className.match(new RegExp("(\\s|^)" + cls + "(\\s|$)"));
}

/**
 * Add class to element
 * @param {HTMLElement} elm
 * @param {string} cls
 */
export function addClass(ele, cls) {
  if (!hasClass(ele, cls)) ele.className += " " + cls;
}

/**
 * Remove class from element
 * @param {HTMLElement} elm
 * @param {string} cls
 */
export function removeClass(ele, cls) {
  if (hasClass(ele, cls)) {
    const reg = new RegExp("(\\s|^)" + cls + "(\\s|$)");
    ele.className = ele.className.replace(reg, " ");
  }
}

export function makeMap(str, expectsLowerCase) {
  const map = Object.create(null);
  const list = str.split(",");
  for (let i = 0; i < list.length; i++) {
    map[list[i]] = true;
  }
  return expectsLowerCase ? (val) => map[val.toLowerCase()] : (val) => map[val];
}

export const exportDefault = "export default ";

export const beautifierConf = {
  html: {
    indent_size: "2",
    indent_char: " ",
    max_preserve_newlines: "-1",
    preserve_newlines: false,
    keep_array_indentation: false,
    break_chained_methods: false,
    indent_scripts: "separate",
    brace_style: "end-expand",
    space_before_conditional: true,
    unescape_strings: false,
    jslint_happy: false,
    end_with_newline: true,
    wrap_line_length: "110",
    indent_inner_html: true,
    comma_first: false,
    e4x: true,
    indent_empty_lines: true,
  },
  js: {
    indent_size: "2",
    indent_char: " ",
    max_preserve_newlines: "-1",
    preserve_newlines: false,
    keep_array_indentation: false,
    break_chained_methods: false,
    indent_scripts: "normal",
    brace_style: "end-expand",
    space_before_conditional: true,
    unescape_strings: false,
    jslint_happy: true,
    end_with_newline: true,
    wrap_line_length: "110",
    indent_inner_html: true,
    comma_first: false,
    e4x: true,
    indent_empty_lines: true,
  },
};

// 首字母大小
export function titleCase(str) {
  return str.replace(/( |^)[a-z]/g, (L) => L.toUpperCase());
}

// 下划转驼峰
export function camelCase(str) {
  return str.replace(/_[a-z]/g, (str1) => str1.substr(-1).toUpperCase());
}

export function isNumberStr(str) {
  return /^[+-]?(0|([1-9]\d*))(\.\d+)?$/g.test(str);
}

export function getUrlParams(url) {
  var data = {};
  if (!url) {
    return data;
  }
  var urlArr = url.split("?");
  if (urlArr.length < 2) {
    return data;
  }
  var psArr = urlArr[1].split("#")[0].split("&");
  var kvArr;
  for (var i = 0; i < psArr.length; i++) {
    kvArr = psArr[i].split("=");
    data[kvArr[0]] = kvArr[1];
  }
  return data;
}

export function JsonParse(string, defaultValue = null) {
  try {
    return JSON.parse(string) || defaultValue;
  } catch (error) {
    return defaultValue;
  }
}

export function guid() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
    var r = (Math.random() * 16) | 0,
      v = c == "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function pxtorem(px) {
  // 750px = 10rem
  return Number(px / 75).toFixed(6);
}

//加
Number.prototype.add = function (n) {
  function multiply(a, b) {
    var _m = 0,
      _s1 = String(a),
      _s2 = String(b),
      _r1,
      _r2;
    try {
      _m += _s1.split(".")[1].length;
    } catch (e) {}
    try {
      _m += _s2.split(".")[1].length;
    } catch (e) {}
    _r1 = Number(_s1.replace(".", ""));
    _r2 = Number(_s2.replace(".", ""));
    return Number((_r1 * _r2) / Math.pow(10, _m));
  }

  var r1, r2, m;
  try {
    r1 = this.toString().split(".")[1].length;
  } catch (e) {
    r1 = 0;
  }
  try {
    r2 = n.toString().split(".")[1].length;
  } catch (e) {
    r2 = 0;
  }
  m = Math.pow(10, Math.max(r1, r2));
  return Number((multiply(this, m) + multiply(n, m)) / m);
};
// 减
Number.prototype.subtract = function (n) {
  var r1, r2, m;
  try {
    r1 = String(this).split(".")[1].length;
  } catch (e) {
    r1 = 0;
  }
  try {
    r2 = String(n).split(".")[1].length;
  } catch (e) {
    r2 = 0;
  }
  m = Math.pow(10, Math.max(r1, r2));
  return Number((this * m - n * m) / m);
};
// 乘
Number.prototype.multiply = function (n) {
  var m = 0,
    s1 = String(this),
    s2 = String(n),
    r1,
    r2;
  try {
    m += s1.split(".")[1].length;
  } catch (e) {}
  try {
    m += s2.split(".")[1].length;
  } catch (e) {}
  r1 = Number(s1.replace(".", ""));
  r2 = Number(s2.replace(".", ""));
  return Number((r1 * r2) / Math.pow(10, m));
};
// 除
Number.prototype.divide = function (n) {
  var t1 = 0,
    t2 = 0,
    r1,
    r2;
  try {
    t1 = String(this).split(".")[1].length;
  } catch (e) {}
  try {
    t2 = String(n).split(".")[1].length;
  } catch (e) {}
  r1 = Number(String(this).replace(".", ""));
  r2 = Number(String(n).replace(".", ""));
  return Number((r1 / r2) * Math.pow(10, t2 - t1));
};

Number.prototype.ceil = function (n = 0) {
  let m = Math.pow(10, n),
    a = Math.ceil(this * m);
  return Number(a / m);
};

export const formatTime = (date) => {
  const year = date.getFullYear();
  const month = date.getMonth() + 1;
  const day = date.getDate();
  const hour = date.getHours();
  const minute = date.getMinutes();
  const second = date.getSeconds();
  return `${[year, month, day].map(formatNumber).join("/")} ${[hour, minute, second].map(formatNumber).join(":")}`;
};

export const formatHour = (num) => {
  if (Number(num) !== Number(num)) return String(num);
  const hour = Math.floor(num / 3600);
  const minute = Math.floor((num % 3600) / 60);
  const second = Math.floor(num % 60);
  return `${[hour, minute, second].map(formatNumber).join(":")}`;
};

const formatNumber = (n) => {
  n = n.toString();
  return n[1] ? n : `0${n}`;
};

export function copyText(textValue = "", success = () => {}, error = () => {}) {
  let textarea = null;
  try {
    // 动态创建 textarea 标签
    textarea = document.createElement("textarea");
    // 将该 textarea 设为 readonly 防止 iOS 下自动唤起键盘，同时将 textarea 移出可视区域
    textarea.readOnly = "readonly";
    textarea.style.position = "absolute";
    textarea.style.left = "-9999px";
    // 将要 copy 的值赋给 textarea 标签的 value 属性
    textarea.value = textValue;
    // 将 textarea 插入到 body 中
    document.body.appendChild(textarea);
    // 选中值并复制
    textarea.select();
    const result = document.execCommand("Copy");
    if (result) success("复制成功！");
  } catch (err) {
    error("复制失败！");
  } finally {
    if (textarea) document.body.removeChild(textarea);
  }
}

export function downloadFile(url, options = {}) {
  fetch(url, options)
    .then((res) => res.blob())
    .then((blob) => {
      const fileName = options.fileName || url.split("/").pop().split(".")[0];
      saveAs(blob, fileName);
    });
}

export function selectFile(accept = "*/*") {
  return new Promise((resolve) => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = accept;
    input.style = "position:absolute;width:0;height:0;top: -100px;";
    document.body.appendChild(input);
    input.onchange = () => {
      resolve(input.files[0]);
    };
    input.click();
    document.body.removeChild(input);
  });
}

//string转file
export function stringToFile(str, filename) {
  let blob = new Blob([str]);
  return new File([blob], filename);
}
//file转string

export function fileToString(file) {
  return new Promise((resolve, reject) => {
    const fileReader = new FileReader();
    fileReader.onload = (e) => {
      resolve(e.target.result);
    };
    // readAsDataURL
    fileReader.readAsText(file);
    fileReader.onerror = () => {
      reject(new Error("fileToString error"));
    };
  });
}

// 校验网络文件是否存在
export function checkFileExist(url) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("HEAD", url, true);
    xhr.withCredentials = true;
    xhr.onreadystatechange = function () {
      if (xhr.readyState === 4) {
        if (xhr.status === 200) {
          resolve(true);
        } else {
          resolve(false);
        }
      }
    };
  });
}
