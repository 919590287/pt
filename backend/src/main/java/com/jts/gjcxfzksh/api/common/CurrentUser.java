package com.jts.gjcxfzksh.api.common;

public final class CurrentUser {

    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    private CurrentUser() {
    }

    public static void setUsername(String username) {
        USERNAME.set(username);
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static void clear() {
        USERNAME.remove();
    }
}
