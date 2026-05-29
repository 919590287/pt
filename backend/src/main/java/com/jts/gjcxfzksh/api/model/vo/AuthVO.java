package com.jts.gjcxfzksh.api.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthVO {

    private String token;

    private String username;

    private long expiresAt;

    private long lastLoginAt;

}
