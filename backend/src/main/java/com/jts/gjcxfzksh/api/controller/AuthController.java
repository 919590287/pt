package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.model.params.AuthParam;
import com.jts.gjcxfzksh.api.model.params.RenameUserParam;
import com.jts.gjcxfzksh.api.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pt/auth")
@Tag(name = "用户认证", description = "用户认证")
public class AuthController {

    @Resource
    private AuthService authService;

    @Operation(summary = "注册")
    @PostMapping("/register")
    public AjaxResult register(@RequestBody AuthParam param) {
        return AjaxResult.ok(authService.register(param.getUsername(), param.getPassword()));
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public AjaxResult login(@RequestBody AuthParam param) {
        return AjaxResult.ok(authService.login(param.getUsername(), param.getPassword()));
    }

    @Operation(summary = "重置密码")
    @PostMapping("/resetPassword")
    public AjaxResult resetPassword(@RequestBody AuthParam param) {
        return AjaxResult.ok(authService.resetPassword(param.getUsername(), param.getNewPassword()));
    }

    @Operation(summary = "当前用户")
    @PostMapping("/profile")
    public AjaxResult profile(@RequestHeader(value = "token", required = false) String token, HttpServletRequest request) {
        return AjaxResult.ok(authService.profile(readToken(token, request)));
    }

    @Operation(summary = "修改用户名")
    @PostMapping("/rename")
    public AjaxResult rename(@RequestHeader(value = "token", required = false) String token,
                             HttpServletRequest request,
                             @RequestBody RenameUserParam param) {
        return AjaxResult.ok(authService.rename(readToken(token, request), param.getUsername()));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public AjaxResult logout(@RequestHeader(value = "token", required = false) String token, HttpServletRequest request) {
        authService.logout(readToken(token, request));
        return AjaxResult.ok();
    }

    private String readToken(String token, HttpServletRequest request) {
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
