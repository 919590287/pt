package com.jts.gjcxfzksh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        // 离线服务器优先使用 classpath 中的本地 DTD，避免 MATSim 每次解析 XML 时尝试联网拉取 DTD 造成数秒卡顿/超时
        System.setProperty("matsim.preferLocalDtds", "true");
        SpringApplication.run(Application.class, args);
    }

}
