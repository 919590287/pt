package com.jts.gjcxfzksh.api.service.impl;

import com.alibaba.fastjson2.JSON;
import com.jts.gjcxfzksh.api.model.vo.AuthVO;
import com.jts.gjcxfzksh.api.service.AuthService;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AuthServiceImpl implements AuthService {

    private static final long SESSION_TTL = 7L * 24 * 60 * 60 * 1000;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_.-]{2,32}$");
    private static final String STORE_FILE = ".gjcxfzksh-users.json";

    private final SecureRandom secureRandom = new SecureRandom();

    @Resource
    private MatsimConfig matsimConfig;

    @PostConstruct
    public void ensureDefaultFolders() {
        try {
            Files.createDirectories(dataRoot());
        } catch (IOException e) {
            throw new BusinessException("初始化用户目录失败", e);
        }
    }

    @Override
    public synchronized AuthVO register(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        AuthStore store = loadStore();
        if (store.users.containsKey(normalizedUsername)) {
            throw new BusinessException("用户名已存在");
        }

        UserRecord user = new UserRecord();
        long now = System.currentTimeMillis();
        user.username = normalizedUsername;
        user.passwordHash = createPasswordHash(password);
        user.createdAt = now;
        user.updatedAt = now;
        user.lastLoginAt = now;
        store.users.put(normalizedUsername, user);
        ensureUserFolder(normalizedUsername);
        matsimConfig.init();
        AuthVO auth = createSession(store, normalizedUsername, now);
        saveStore(store);
        return auth;
    }

    @Override
    public synchronized AuthVO login(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        AuthStore store = loadStore();
        UserRecord user = store.users.get(normalizedUsername);
        if (user == null || !verifyPassword(password, user.passwordHash)) {
            throw new BusinessException("用户名或密码错误");
        }

        long now = System.currentTimeMillis();
        user.lastLoginAt = now;
        user.updatedAt = now;
        ensureUserFolder(normalizedUsername);
        matsimConfig.init();
        AuthVO auth = createSession(store, normalizedUsername, now);
        saveStore(store);
        return auth;
    }

    @Override
    public synchronized AuthVO resetPassword(String username, String newPassword) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(newPassword);
        AuthStore store = loadStore();
        UserRecord user = store.users.get(normalizedUsername);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        long now = System.currentTimeMillis();
        user.passwordHash = createPasswordHash(newPassword);
        user.lastLoginAt = now;
        user.updatedAt = now;
        ensureUserFolder(normalizedUsername);
        matsimConfig.init();
        AuthVO auth = createSession(store, normalizedUsername, now);
        saveStore(store);
        return auth;
    }

    @Override
    public synchronized AuthVO profile(String token) {
        AuthStore store = loadStore();
        SessionRecord session = requireSession(store, token);
        UserRecord user = store.users.get(session.username);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return toAuthVO(token, session, user);
    }

    @Override
    public synchronized AuthVO rename(String token, String username) {
        String newUsername = normalizeUsername(username);
        AuthStore store = loadStore();
        SessionRecord session = requireSession(store, token);
        String oldUsername = session.username;
        if (oldUsername.equals(newUsername)) {
            return profile(token);
        }
        if (store.users.containsKey(newUsername)) {
            throw new BusinessException("用户名已存在");
        }

        UserRecord user = store.users.remove(oldUsername);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        matsimConfig.renameUserFolders(oldUsername, newUsername);
        long now = System.currentTimeMillis();
        user.username = newUsername;
        user.updatedAt = now;
        store.users.put(newUsername, user);
        for (SessionRecord item : store.sessions.values()) {
            if (oldUsername.equals(item.username)) {
                item.username = newUsername;
            }
        }
        saveStore(store);
        matsimConfig.init();
        return toAuthVO(token, session, user);
    }

    @Override
    public synchronized void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        AuthStore store = loadStore();
        store.sessions.remove(token);
        saveStore(store);
    }

    @Override
    public synchronized String resolveUsername(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        AuthStore store = loadStore();
        SessionRecord session = store.sessions.get(token);
        if (session == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (session.expiresAt < now || !store.users.containsKey(session.username)) {
            store.sessions.remove(token);
            saveStore(store);
            return null;
        }
        ensureUserFolder(session.username);
        return session.username;
    }

    private AuthVO createSession(AuthStore store, String username, long now) {
        cleanupExpiredSessions(store, now);
        String token = createToken();
        SessionRecord session = new SessionRecord();
        session.token = token;
        session.username = username;
        session.issuedAt = now;
        session.expiresAt = now + SESSION_TTL;
        store.sessions.put(token, session);
        UserRecord user = store.users.get(username);
        return toAuthVO(token, session, user);
    }

    private AuthVO toAuthVO(String token, SessionRecord session, UserRecord user) {
        return new AuthVO(token, session.username, session.expiresAt, user == null ? 0 : user.lastLoginAt);
    }

    private SessionRecord requireSession(AuthStore store, String token) {
        String username = resolveUsername(token);
        if (username == null) {
            throw new BusinessException("登录状态已过期，请重新登录");
        }
        SessionRecord session = store.sessions.get(token);
        if (session == null) {
            throw new BusinessException("登录状态已过期，请重新登录");
        }
        return session;
    }

    private void cleanupExpiredSessions(AuthStore store, long now) {
        Iterator<Map.Entry<String, SessionRecord>> iterator = store.sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt < now) {
                iterator.remove();
            }
        }
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            throw new BusinessException("请输入用户名");
        }
        String normalized = username.trim();
        if (!USERNAME_PATTERN.matcher(normalized).matches()
                || normalized.contains("..")
                || normalized.startsWith(".")
                || MatsimConfig.PUBLIC_SCOPE.equalsIgnoreCase(normalized)
                || "temp".equalsIgnoreCase(normalized)) {
            throw new BusinessException("用户名需为2-32位中文、字母、数字、点、短横线或下划线，且不能使用保留名称");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new BusinessException("密码长度需为6-64位");
        }
    }

    private void ensureUserFolder(String username) {
        matsimConfig.ensureUserFolders(username);
    }

    private String createToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String createPasswordHash(String password) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        byte[] hash = digest(salt, password);
        return Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || !storedHash.contains("$")) {
            return false;
        }
        String[] parts = storedHash.split("\\$", 2);
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        byte[] actual = digest(salt, password);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] digest(byte[] salt, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BusinessException("密码处理失败", e);
        }
    }

    private AuthStore loadStore() {
        Path path = storePath();
        try {
            if (!Files.exists(path)) {
                return new AuthStore();
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            AuthStore store = JSON.parseObject(text, AuthStore.class);
            return store == null ? new AuthStore() : store.normalize();
        } catch (IOException e) {
            throw new BusinessException("读取用户数据失败", e);
        }
    }

    private void saveStore(AuthStore store) {
        try {
            Files.createDirectories(dataRoot());
            Files.writeString(storePath(), JSON.toJSONString(store.normalize()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("保存用户数据失败", e);
        }
    }

    private Path dataRoot() {
        return Path.of(matsimConfig.getFolder()).toAbsolutePath().normalize();
    }

    private Path storePath() {
        return dataRoot().resolve(STORE_FILE);
    }

    public static class AuthStore {
        public Map<String, UserRecord> users = new LinkedHashMap<>();
        public Map<String, SessionRecord> sessions = new LinkedHashMap<>();

        public AuthStore normalize() {
            if (users == null) {
                users = new LinkedHashMap<>();
            }
            if (sessions == null) {
                sessions = new LinkedHashMap<>();
            }
            return this;
        }
    }

    public static class UserRecord {
        public String username;
        public String passwordHash;
        public long createdAt;
        public long updatedAt;
        public long lastLoginAt;
    }

    public static class SessionRecord {
        public String token;
        public String username;
        public long issuedAt;
        public long expiresAt;
    }
}
