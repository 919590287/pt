const AUTH_STORAGE_KEY = "gjcxfzksh_auth";
const LEGACY_TOKEN_KEY = "token";
const LEGACY_AUTHORIZATION_KEY = "Authorization";

function parseAuth() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (error) {
    return null;
  }
}

function notifyAuthChanged(auth) {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent("auth:changed", { detail: auth || null }));
}

export function getAuth() {
  const auth = parseAuth();
  if (!auth?.token || !auth?.username || Number(auth.expiresAt) <= Date.now()) {
    clearAuth(false);
    return null;
  }
  return auth;
}

export function getToken() {
  return getAuth()?.token || "";
}

export function getUsername() {
  return getAuth()?.username || "";
}

export function isAuthenticated() {
  return Boolean(getAuth());
}

export function saveAuth(payload) {
  if (!payload?.token || !payload?.username) {
    clearAuth();
    return null;
  }
  const auth = {
    token: payload.token,
    username: payload.username,
    expiresAt: Number(payload.expiresAt) || Date.now(),
    lastLoginAt: Number(payload.lastLoginAt) || Date.now(),
  };
  localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(auth));
  localStorage.setItem(LEGACY_TOKEN_KEY, auth.token);
  localStorage.setItem(LEGACY_AUTHORIZATION_KEY, auth.token);
  notifyAuthChanged(auth);
  return auth;
}

export function clearAuth(shouldNotify = true) {
  localStorage.removeItem(AUTH_STORAGE_KEY);
  localStorage.removeItem(LEGACY_TOKEN_KEY);
  localStorage.removeItem(LEGACY_AUTHORIZATION_KEY);
  if (shouldNotify) {
    notifyAuthChanged(null);
  }
}
