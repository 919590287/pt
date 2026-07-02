import axios from "axios";
import { ElMessage } from "element-plus";
import { clearAuth, getToken } from "@/utils/auth";
import { appendErrorLog } from "@/utils/errorLog";
import { tansParams } from "./index";

axios.defaults.headers["Content-Type"] = "application/json;charset=utf-8";

const configuredBaseApi = import.meta.env.VITE_APP_BASE_API;
const runtimeConfig = typeof window !== "undefined" ? window.APP_CONFIG || {} : {};
const runtimeBackendPort = runtimeConfig.backendPort || 8090;
const defaultRequestTimeout = Number(import.meta.env.VITE_APP_REQUEST_TIMEOUT || runtimeConfig.requestTimeout || 60_000);
const runtimeBaseApi =
  runtimeConfig.apiBaseUrl ||
  (typeof window !== "undefined" && window.location?.hostname
    ? `${window.location.protocol}//${window.location.hostname}:${runtimeBackendPort}`
    : "");

function showErrorMessage(config, message) {
  if (config?.silentError) return;
  ElMessage.error(message);
}

// 读取后端下发的链路追踪 ID（X-Trace-Id 响应头），便于前后端串联排查
function getTraceId(headers) {
  if (!headers) return "";
  if (typeof headers.get === "function") {
    return headers.get("x-trace-id") || headers.get("X-Trace-Id") || "";
  }
  return headers["x-trace-id"] || headers["X-Trace-Id"] || "";
}

// 请求错误统一上报：console.error + localStorage 环形日志（键 gj_error_log），traceId 存在时一并拼入
function reportRequestError(message, { url = "", status, traceId = "", stack = "" } = {}) {
  const parts = [message];
  if (status != null) parts.push(`status=${status}`);
  if (traceId) parts.push(`traceId=${traceId}`);
  if (url) parts.push(`url=${url}`);
  const summary = parts.join(" | ");
  console.error(`[request] ${summary}`);
  appendErrorLog({ type: "request", message: summary, stack });
}

function redirectToLogin() {
  clearAuth(false);
  if (typeof window === "undefined") return;
  const hash = window.location.hash || "";
  if (!hash.includes("/login") && !hash.includes("/register") && !hash.includes("/reset-password")) {
    window.location.hash = "#/login";
  }
}

function normalizeErrorMessage(error) {
  if (axios.isCancel(error) || error?.message === "canceled") {
    return "请求已取消";
  }
  if (error?.code === "ECONNABORTED" || String(error?.message || "").includes("timeout")) {
    return "系统接口请求超时，请稍后重试";
  }
  if (error?.message === "Network Error") {
    return "后端接口连接异常，请检查服务是否启动";
  }

  const status = error?.response?.status;
  const statusMessages = {
    400: "请求参数有误，请检查后重试",
    401: "认证失败，请重新登录",
    403: "当前操作没有权限",
    404: "接口不存在或资源已删除",
    408: "请求等待超时，请稍后重试",
    429: "请求过于频繁，请稍后再试",
    500: "服务器处理失败，请稍后重试",
    502: "网关异常，请检查后端服务",
    503: "服务暂不可用，请稍后重试",
    504: "网关请求超时，请稍后重试",
  };

  return error?.response?.data?.msg || statusMessages[status] || error?.message || "系统未知错误，请反馈给管理员";
}

// 创建axios实例
const service = axios.create({
  // axios中请求配置有baseURL选项，表示请求URL公共部分
  baseURL: configuredBaseApi || runtimeBaseApi,
  // 超时
  timeout: Number.isFinite(defaultRequestTimeout) && defaultRequestTimeout > 0 ? defaultRequestTimeout : 60_000,
});

// request拦截器
service.interceptors.request.use(
  (config) => {

    let headers = config.headers || {};

    // 是否需要防止数据重复提交
    // const isRepeatSubmit = headers.repeatSubmit === false;

    // 是否需要设置 token
    const isToken = headers.isToken === false;
    const token = getToken();
    if (token && !isToken) {
      headers["token"] = token; // 让每个请求携带自定义token
    }
    if (token && !isToken) {
      headers["Authorization"] = "Bearer " + token;
    }

    // 设置国际化
    headers["Content-Language"] = "zh_CN";

    // FormData（如 SHP 文件上传）必须移除手动 Content-Type，
    // 交由浏览器自动生成带 boundary 的 multipart/form-data 头，否则后端无法解析。
    if (typeof FormData !== "undefined" && config.data instanceof FormData) {
      if (headers && typeof headers.delete === "function") {
        headers.delete("Content-Type");
      } else {
        delete headers["Content-Type"];
        delete headers["content-type"];
      }
    }

    config.headers = headers;
    // get请求映射params参数
    if (config.method === "get" && config.params) {
      let url = config.url + "?" + tansParams(config.params);
      url = url.slice(0, -1);
      config.params = {};
      config.url = url;
    }
    // if (
    //   !isRepeatSubmit &&
    //   (config.method === "post" || config.method === "put")
    // ) {
    //   const requestObj = {
    //     url: config.url,
    //     data:
    //       typeof config.data === "object"
    //         ? JSON.stringify(config.data)
    //         : config.data,
    //     time: new Date().getTime(),
    //   };
    //   const sessionObj = cache.session.getJSON("sessionObj");
    //   if (
    //     sessionObj === undefined ||
    //     sessionObj === null ||
    //     sessionObj === ""
    //   ) {
    //     cache.session.setJSON("sessionObj", requestObj);
    //   } else {
    //     const s_url = sessionObj.url; // 请求地址
    //     const s_data = sessionObj.data; // 请求数据
    //     const s_time = sessionObj.time; // 请求时间
    //     const interval = 1000; // 间隔时间(ms)，小于此时间视为重复提交
    //     if (
    //       s_data === requestObj.data &&
    //       requestObj.time - s_time < interval &&
    //       s_url === requestObj.url
    //     ) {
    //       const message = "数据正在处理，请勿重复提交";
    //       console.warn(`[${s_url}]: ` + message);
    //       return Promise.reject(new Error(message));
    //     } else {
    //       cache.session.setJSON("sessionObj", requestObj);
    //     }
    //   }
    // }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  },
);

// 响应拦截器
service.interceptors.response.use(
  (res) => {
    const codeList = {
      401: "认证失败，无法访问系统资源",
      403: "当前操作没有权限",
      404: "接口不存在或资源已删除",
      429: "请求过于频繁，请稍后再试",
      500: "服务器处理失败，请稍后重试",
      default: "系统未知错误，请反馈给管理员",
    };
    // 未设置状态码则默认成功状态
    const code = Number(res.data.code || 200);
    // 获取错误信息
    const msg = codeList[code] || res.data.msg || codeList["default"];
    // 二进制数据则直接返回
    if (res.request.responseType === "blob" || res.request.responseType === "arraybuffer") {
      return res;
    }
    if (code === 401) {
      redirectToLogin();
      showErrorMessage(res.config, msg);
      reportRequestError(msg, { url: res.config?.url, status: code, traceId: getTraceId(res.headers) });
      return Promise.reject(new Error(msg));
    } else if (code !== 200) {
      showErrorMessage(res.config, msg);
      reportRequestError(msg, { url: res.config?.url, status: code, traceId: getTraceId(res.headers) });
      return Promise.reject(new Error(msg));
    } else {
      return res.data;
    }
  },
  (error) => {
    const message = normalizeErrorMessage(error);
    const traceId = getTraceId(error?.response?.headers);
    if (error?.response?.status === 401) {
      redirectToLogin();
    }
    if (!axios.isCancel(error) && error?.message !== "canceled") {
      showErrorMessage(error?.config, message);
      reportRequestError(message, {
        url: error?.config?.url,
        status: error?.response?.status,
        traceId,
        stack: error?.stack,
      });
    }
    const normalizedError = new Error(message);
    normalizedError.cause = error;
    if (traceId) {
      normalizedError.traceId = traceId;
    }
    return Promise.reject(normalizedError);
  },
);

export default service;
