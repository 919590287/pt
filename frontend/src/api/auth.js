import request from "@/utils/request";

export function login(data, config = {}) {
  return request({
    url: "/pt/auth/login",
    method: "POST",
    data,
    headers: { isToken: false },
    ...config,
  });
}

export function register(data, config = {}) {
  return request({
    url: "/pt/auth/register",
    method: "POST",
    data,
    headers: { isToken: false },
    ...config,
  });
}

export function resetPassword(data, config = {}) {
  return request({
    url: "/pt/auth/resetPassword",
    method: "POST",
    data,
    headers: { isToken: false },
    ...config,
  });
}

export function getProfile(config = {}) {
  return request({
    url: "/pt/auth/profile",
    method: "POST",
    ...config,
  });
}

export function renameUser(data, config = {}) {
  return request({
    url: "/pt/auth/rename",
    method: "POST",
    data,
    ...config,
  });
}

export function logout(config = {}) {
  return request({
    url: "/pt/auth/logout",
    method: "POST",
    ...config,
  });
}
