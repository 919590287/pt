package com.jts.gjcxfzksh.config;

import com.alibaba.fastjson2.JSON;
import com.jts.gjcxfzksh.data.entry.Scheme;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class MatsimConfig {

    @Getter
    @Value("${matsim.data}")
    private String folder;

    /**
     * 全部方案
     */
    @Getter
    private final LinkedHashMap<String, Scheme> schemes = new LinkedHashMap<>();

    /**
     * 初始化方案
     */
    @PostConstruct
    public synchronized void init() {
        schemes.clear();
        File root = new File(folder);
        File[] files = root.listFiles();
        if (files == null) {
            return;
        }
        for (File ff : files) { // 父文件夹，某区域建模
            if (!ff.isDirectory() || ff.getName().equals("temp")) {
                continue;
            }
            String ffName = ff.getName();
            List<Scheme> temp = new ArrayList<>();
            for (File data : Objects.requireNonNull(ff.listFiles())) { // 某区域方案
                Scheme scheme = new Scheme();
                String dataName = data.getName();
                if (dataName.equals("temp")) {
                    continue;
                }
                if (data.isDirectory()) {
                    String key = ffName + "/" + dataName;
                    scheme.setFolder(data.getAbsolutePath());
                    scheme.setInput(data.getAbsolutePath() + "/input");
                    scheme.setOutput(data.getAbsolutePath() + "/output");
                    if (!new File(scheme.getOutput()).exists()) { // 如果没有output说明不是完整方案
                        continue;
                    }
                    String desc_file = data.getAbsolutePath() + "/desc.json";
                    File desc = new File(desc_file);
                    Scheme.Desc d;
                    if (desc.exists()) {
                        try {
                            d = JSON.parseObject(new FileInputStream(desc), Scheme.Desc.class);
                        } catch (Exception e) {
                            log.warn("[{}]使用默认描述json", scheme.getName());
                            d = defual_desc();
                        }
                    } else {
                        d = defual_desc();
                    }
                    scheme.setDesc(d);
                    scheme.setName(key);
//                    JSONObject obj = FileUtil.readJonsFile(data.getAbsolutePath() + "/" + DESC_FILE);
//                    scheme.setDetail(obj.getString("detail")); // 从描述文件中读取
//                    scheme.setScale(obj.getDouble("scale") == null ? 1.0 : obj.getDouble("scale")); //
//                    scheme.setDefault(obj.getBooleanValue("default")); //
//                    if (dataName.equals(DefaultConfig.BASE)) {
//                        temp.addFirst(scheme);
//                    } else {
                    temp.add(scheme);
//                    }
                }
            }
            for (Scheme scheme : temp) {
                schemes.put(scheme.getName(), scheme);
            }
        }
        log.info("共找到{}个方案", schemes.size());
    }

    private static Scheme.Desc defual_desc() {
        Scheme.Desc d = new Scheme.Desc();
        d.set_default(false);
        d.setDetail("");
        d.setScale(1);
        d.setArea(1);
        return d;
    }

}
