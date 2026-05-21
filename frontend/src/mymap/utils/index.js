export const EARTH_RADIUS = 20037508.3427892;

export function send(obj, funcName, callback) {
  let oldFunc = obj[funcName];
  obj[funcName] = callback.bind(obj, oldFunc.bind(obj));
}

export async function isDoubleClick(key, timeout = 200, callback = () => {}) {
  const map = window.isDoubleClickMap || (window.isDoubleClickMap = new Map());
  if (map.has(key)) {
    const t = map.get(key);
    clearTimeout(t);
    map.delete(key);
    callback(true);
  } else {
    const t = setTimeout(() => {
      map.delete(key);
      callback(false);
    }, timeout);
    map.set(key, t);
  }
}