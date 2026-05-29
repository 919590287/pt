package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.vo.AuthVO;

public interface AuthService {

    AuthVO register(String username, String password);

    AuthVO login(String username, String password);

    AuthVO resetPassword(String username, String newPassword);

    AuthVO profile(String token);

    AuthVO rename(String token, String username);

    void logout(String token);

    String resolveUsername(String token);
}
