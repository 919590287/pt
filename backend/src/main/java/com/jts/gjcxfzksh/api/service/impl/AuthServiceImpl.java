package com.jts.gjcxfzksh.api.service.impl;

import com.alibaba.fastjson2.JSON;
import com.jts.gjcxfzksh.api.model.vo.AuthVO;
import com.jts.gjcxfzksh.api.service.AuthService;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 用户/会话存储：启动时一次性加载进内存，读路径（每请求 resolveUsername）零磁盘 I/O、零全局锁；
 * 仅写操作（注册/登录/改名/登出）串行落盘（临时文件 + 原子替换）。
 * 注意：内存态为单一事实来源，文件仅做持久化，该方案仅适用于当前单实例部署架构。
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final long SESSION_TTL = 7L * 24 * 60 * 60 * 1000;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_.-]{2,32}$");
    private static final String STORE_FILE = ".gjcxfzksh-users.json";

    // PBKDF2 参数（OWASP 密码存储建议量级）；旧格式（单轮 SHA-256）登录成功后透明重哈希升级
    private static final String PBKDF2_PREFIX = "pbkdf2";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int PBKDF2_KEY_BITS = 256;

    // 登录失败限速：同一用户名窗口期内失败次数超限后暂时拒绝，防在线暴力破解
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOGIN_FAILURE_WINDOW_MS = 10 * 60 * 1000;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, FailureWindow> loginFailures = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, UserRecord> users = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    // 单一写入口：所有内存变更 + 落盘经由该锁，保证内存态与文件态一致
    private final Object writeLock = new Object();
    // 读路径上发现的过期会话只改内存并置脏，由定时任务统一落盘，避免请求内写文件
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    @Resource
    private MatsimConfig matsimConfig;

    @PostConstruct
    public void loadStoreIntoMemory() {
        try {
            Files.createDirectories(dataRoot());
        } catch (IOException e) {
            throw new BusinessException("初始化用户目录失败", e);
        }
        AuthStore store = readStoreFile();
        users.putAll(store.users);
        sessions.putAll(store.sessions);
        log.info("用户存储已加载进内存: users={}, sessions={}", users.size(), sessions.size());
    }

    @Override
    public AuthVO register(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        if (users.containsKey(normalizedUsername)) {
            throw new BusinessException("用户名已存在");
        }
        // PBKDF2 计算量大（百毫秒级），必须在 writeLock 外执行，否则并发请求会在锁上串行排队
        String passwordHash = createPasswordHash(password);
        synchronized (writeLock) {
            if (users.containsKey(normalizedUsername)) {
                throw new BusinessException("用户名已存在");
            }
            UserRecord user = new UserRecord();
            long now = System.currentTimeMillis();
            user.username = normalizedUsername;
            user.passwordHash = passwordHash;
            user.createdAt = now;
            user.updatedAt = now;
            user.lastLoginAt = now;
            users.put(normalizedUsername, user);
            ensureUserFolder(normalizedUsername);
            AuthVO auth = createSession(normalizedUsername, now);
            persist();
            return auth;
        }
    }

    @Override
    public AuthVO login(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        checkLoginRateLimit(normalizedUsername);
        // PBKDF2 校验在锁外完成；入锁后核对哈希未被并发修改，变了则放锁重新校验
        for (int attempt = 0; attempt < 3; attempt++) {
            UserRecord observed = users.get(normalizedUsername);
            String observedHash = observed == null ? null : observed.passwordHash;
            if (observed == null || !verifyPassword(password, observedHash)) {
                recordLoginFailure(normalizedUsername);
                throw new BusinessException("用户名或密码错误");
            }
            // 旧的单轮 SHA-256 哈希验证通过后透明升级为 PBKDF2，重哈希同样在锁外准备好
            String upgradedHash = isLegacyHash(observedHash) ? createPasswordHash(password) : null;
            synchronized (writeLock) {
                UserRecord user = users.get(normalizedUsername);
                if (user == null || !observedHash.equals(user.passwordHash)) {
                    continue;
                }
                loginFailures.remove(normalizedUsername);
                long now = System.currentTimeMillis();
                if (upgradedHash != null) {
                    user.passwordHash = upgradedHash;
                }
                user.lastLoginAt = now;
                user.updatedAt = now;
                ensureUserFolder(normalizedUsername);
                AuthVO auth = createSession(normalizedUsername, now);
                persist();
                return auth;
            }
        }
        throw new BusinessException("登录冲突，请重试");
    }

    @Override
    public AuthVO resetPassword(String username, String currentPassword, String newPassword) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(currentPassword);
        validatePassword(newPassword);
        checkLoginRateLimit(normalizedUsername);

        // 原密码校验与新密码 PBKDF2 均在锁外完成；入锁后再次核对哈希，避免与并发登录/改密竞争。
        for (int attempt = 0; attempt < 3; attempt++) {
            UserRecord observed = users.get(normalizedUsername);
            String observedHash = observed == null ? null : observed.passwordHash;
            if (observed == null || !verifyPassword(currentPassword, observedHash)) {
                recordLoginFailure(normalizedUsername);
                // 不区分用户不存在与密码错误，避免用户名枚举。
                throw new BusinessException("用户名或原密码错误");
            }
            String passwordHash = createPasswordHash(newPassword);
            synchronized (writeLock) {
                UserRecord user = users.get(normalizedUsername);
                if (user == null || !observedHash.equals(user.passwordHash)) {
                    continue;
                }
                loginFailures.remove(normalizedUsername);
                long now = System.currentTimeMillis();
                user.passwordHash = passwordHash;
                user.lastLoginAt = now;
                user.updatedAt = now;
                // 改密后撤销该账户的全部旧会话，防止泄漏 token 继续有效。
                sessions.values().removeIf(session -> normalizedUsername.equals(session.username));
                ensureUserFolder(normalizedUsername);
                AuthVO auth = createSession(normalizedUsername, now);
                persist();
                return auth;
            }
        }
        throw new BusinessException("密码修改冲突，请重试");
    }

    @Override
    public AuthVO profile(String token) {
        SessionRecord session = requireSession(token);
        UserRecord user = users.get(session.username);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return toAuthVO(token, session, user);
    }

    @Override
    public AuthVO rename(String token, String username) {
        String newUsername = normalizeUsername(username);
        synchronized (writeLock) {
            SessionRecord session = requireSession(token);
            String oldUsername = session.username;
            if (oldUsername.equals(newUsername)) {
                return profile(token);
            }
            if (users.containsKey(newUsername)) {
                throw new BusinessException("用户名已存在");
            }
            UserRecord user = users.remove(oldUsername);
            if (user == null) {
                throw new BusinessException("用户不存在");
            }
            matsimConfig.renameUserFolders(oldUsername, newUsername);
            long now = System.currentTimeMillis();
            user.username = newUsername;
            user.updatedAt = now;
            users.put(newUsername, user);
            for (SessionRecord item : sessions.values()) {
                if (oldUsername.equals(item.username)) {
                    item.username = newUsername;
                }
            }
            persist();
            matsimConfig.init();
            return toAuthVO(token, session, user);
        }
    }

    @Override
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        synchronized (writeLock) {
            if (sessions.remove(token) != null) {
                persist();
            }
        }
    }

    /**
     * 每请求热点路径：纯内存查询，无锁、无磁盘 I/O、无目录检查。
     */
    @Override
    public String resolveUsername(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        SessionRecord session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.expiresAt < System.currentTimeMillis() || !users.containsKey(session.username)) {
            sessions.remove(token);
            dirty.set(true);
            return null;
        }
        return session.username;
    }

    /**
     * 过期会话清理与脏数据落盘移出请求路径，由定时任务统一处理。
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000, initialDelay = 10 * 60 * 1000)
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        boolean removed = sessions.values().removeIf(session -> session.expiresAt < now);
        if (removed) {
            dirty.set(true);
        }
        flushIfDirty();
    }

    @PreDestroy
    public void flushIfDirty() {
        if (dirty.get()) {
            synchronized (writeLock) {
                if (dirty.get()) {
                    persist();
                }
            }
        }
    }

    private void checkLoginRateLimit(String username) {
        FailureWindow window = loginFailures.get(username);
        if (window == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - window.firstFailureAt > LOGIN_FAILURE_WINDOW_MS) {
            loginFailures.remove(username, window);
            return;
        }
        if (window.count.get() >= MAX_LOGIN_FAILURES) {
            throw new BusinessException("登录失败次数过多，请10分钟后再试");
        }
    }

    private void recordLoginFailure(String username) {
        long now = System.currentTimeMillis();
        FailureWindow window = loginFailures.compute(username, (key, existing) -> {
            if (existing == null || now - existing.firstFailureAt > LOGIN_FAILURE_WINDOW_MS) {
                return new FailureWindow(now);
            }
            return existing;
        });
        window.count.incrementAndGet();
    }

    private static class FailureWindow {
        final long firstFailureAt;
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

        FailureWindow(long firstFailureAt) {
            this.firstFailureAt = firstFailureAt;
        }
    }

    private AuthVO createSession(String username, long now) {
        sessions.values().removeIf(session -> session.expiresAt < now);
        String token = createToken();
        SessionRecord session = new SessionRecord();
        session.token = token;
        session.username = username;
        session.issuedAt = now;
        session.expiresAt = now + SESSION_TTL;
        sessions.put(token, session);
        UserRecord user = users.get(username);
        return toAuthVO(token, session, user);
    }

    private AuthVO toAuthVO(String token, SessionRecord session, UserRecord user) {
        return new AuthVO(token, session.username, session.expiresAt, user == null ? 0 : user.lastLoginAt);
    }

    private SessionRecord requireSession(String token) {
        String username = resolveUsername(token);
        if (username == null) {
            throw new BusinessException("登录状态已过期，请重新登录");
        }
        SessionRecord session = sessions.get(token);
        if (session == null) {
            throw new BusinessException("登录状态已过期，请重新登录");
        }
        return session;
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

    String createPasswordHash(String password) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        return PBKDF2_PREFIX
                + "$" + PBKDF2_ITERATIONS
                + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(hash);
    }

    boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || !storedHash.contains("$")) {
            return false;
        }
        if (!isLegacyHash(storedHash)) {
            String[] parts = storedHash.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                byte[] actual = pbkdf2(password, salt, iterations);
                return MessageDigest.isEqual(expected, actual);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        // 旧格式: base64(salt)$base64(sha256(salt||password))
        String[] parts = storedHash.split("\\$", 2);
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            byte[] actual = legacyDigest(salt, password);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean isLegacyHash(String storedHash) {
        return storedHash == null || !storedHash.startsWith(PBKDF2_PREFIX + "$");
    }

    private byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_KEY_BITS);
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new BusinessException("密码处理失败", e);
        }
    }

    private byte[] legacyDigest(byte[] salt, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BusinessException("密码处理失败", e);
        }
    }

    private AuthStore readStoreFile() {
        Path path = storePath();
        try {
            // 必须用 notExists 而不是 !exists：父目录不可进入时 exists 会返回 false 而不报错，
            // 于是"读不到用户表"被当成"首次运行"，静默起一个空用户表，
            // 接着第一次注册就把真实的用户表覆盖掉。notExists 在权限不明时返回 false，
            // 让下面的 readString 抛出 AccessDeniedException，问题当场暴露。
            if (Files.notExists(path)) {
                return new AuthStore();
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            AuthStore store = JSON.parseObject(text, AuthStore.class);
            return store == null ? new AuthStore() : store.normalize();
        } catch (IOException e) {
            throw new BusinessException("读取用户数据失败", e);
        }
    }

    /**
     * 写时快照 + 临时文件原子替换，避免写一半崩溃损坏存储文件。调用方需持有 writeLock。
     */
    private void persist() {
        AuthStore snapshot = new AuthStore();
        snapshot.users = new LinkedHashMap<>(users);
        snapshot.sessions = new LinkedHashMap<>(sessions);
        try {
            Files.createDirectories(dataRoot());
            Path target = storePath();
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, JSON.toJSONString(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // 部分文件系统（如 FAT 格式的 U 盘）不支持原子移动，降级为普通替换
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty.set(false);
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
        // login 在锁外读该字段做 PBKDF2 校验，volatile 保证读到锁内写入的最新值
        public volatile String passwordHash;
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
