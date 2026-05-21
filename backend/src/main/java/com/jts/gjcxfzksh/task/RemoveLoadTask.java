package com.jts.gjcxfzksh.task;


import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.Database;
import com.jts.gjcxfzksh.data.entry.Scheme;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 移除加载方案定时任务
 */
@Slf4j
@Component
public class RemoveLoadTask {

    //    private static final Long DAY_OF_MILLISECOND = 60 * 1000L;
    private static final Long DAY_OF_MILLISECOND = 24 * 60 * 60 * 1000L;
    @Resource
    private MatsimConfig config;


    //    @Scheduled(cron = "0/1 * * * * ?")    // 每钟秒执行
    @Scheduled(cron = "0 0 0/1 * * ?")      // 每分小时执行
    public void task() {
        Map<String, Scheme> schemeMap = config.getSchemes();
        List<String> _default = config.getSchemes().values().stream().filter(scheme -> scheme.getDesc().get_default()).map(Scheme::getName).toList();

        log.info("执行定时清理任务...");
        List<String> list = new ArrayList<>();
        for (String name : schemeMap.keySet()) {
            if (Datasource.loadStatus(name)) {
                Database database = Datasource.data(name);
                MatsimData matsim_data = database.matsim_data();
                long currentTimeMillis = System.currentTimeMillis();
                if (matsim_data.getLastRequestTime() != 0 && matsim_data.getLastRequestTime() + DAY_OF_MILLISECOND < currentTimeMillis) { // 超过一天没有使用
                    list.add(name);
                }
            }
        }

        list.removeAll(_default);
        for (String name : list) {
            Datasource.remove(name);
            log.warn("[{}]已超过24小时未使用已被释放...", name);
        }

    }

}
