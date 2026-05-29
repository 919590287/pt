package com.jts.gjcxfzksh.config;

import com.alibaba.fastjson2.JSON;
import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.CurrentUser;
import com.jts.gjcxfzksh.api.service.AuthService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Resource
    private AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = readToken(request);
        String username = authService.resolveUsername(token);
        if (username == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSON.toJSONString(AjaxResult.unauthorized("登录状态已过期，请重新登录")));
            return false;
        }

        CurrentUser.setUsername(username);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentUser.clear();
    }

    private String readToken(HttpServletRequest request) {
        String token = request.getHeader("token");
        if (token != null && !token.isBlank()) {
            return token;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length());
        }
        return null;
    }
}
