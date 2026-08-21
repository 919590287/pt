package com.jts.gjcxfzksh.cachebuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.Application;
import com.jts.gjcxfzksh.data.cache.ModelCacheManager;
import com.jts.gjcxfzksh.data.cache.ModelCacheStatus;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * 一次性缓存构建 JVM。它复用生产缓存实现，但不启动 Undertow；构建完成后进程退出，
 * MATSim Scenario、解析队列和临时字典占用的全部堆/原生内存由操作系统一次性回收。
 */
public final class CacheBuilderMain {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CacheBuilderMain() {
    }

    public static void main(String[] args) {
        int code = run(args);
        System.exit(code);
    }

    static int run(String[] args) {
        if (args == null || args.length < 2) {
            System.err.println("usage: CacheBuilderMain <modelName> <statusFile> [spring options]");
            return 2;
        }
        String modelName = args[0];
        Path statusFile = Path.of(args[1]).toAbsolutePath().normalize();
        String[] springArgs = Arrays.copyOfRange(args, 2, args.length);
        System.setProperty("matsim.preferLocalDtds", "true");

        ConfigurableApplicationContext context = null;
        try {
            SpringApplication application = new SpringApplication(Application.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setLazyInitialization(true);
            application.setLogStartupInfo(true);
            context = application.run(springArgs);
            return context.getBean(ModelCacheManager.class)
                    .runBuilderInCurrentProcess(modelName, statusFile);
        } catch (Throwable error) {
            writeStartupFailure(statusFile, error);
            error.printStackTrace(System.err);
            return 3;
        } finally {
            if (context != null) context.close();
        }
    }

    private static void writeStartupFailure(Path statusFile, Throwable error) {
        try {
            Files.createDirectories(statusFile.getParent());
            ModelCacheStatus status = ModelCacheStatus.missing(statusFile.getParent().toString());
            status.setStatus("failed");
            status.setMessage(error.getMessage() == null ? "缓存构建进程启动失败" : error.getMessage());
            status.setProgressMessage(status.getMessage());
            status.setFinishedAt(System.currentTimeMillis());
            Path temporary = statusFile.resolveSibling(statusFile.getFileName() + ".startup.tmp");
            JSON.writeValue(temporary.toFile(), status);
            try {
                Files.move(temporary, statusFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception noAtomicMove) {
                Files.move(temporary, statusFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        }
    }
}
