package com.jts.gjcxfzksh.optimization.runner;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * MATSim 场景运行入口（独立子进程，由 RunJobManager 启动）。
 * 用法: MatsimScenarioRunner <config.xml> <outputDir>
 *
 * 以独立 JVM 运行，避免仿真的内存/CPU 负载影响 API 服务；
 * fat-jar 部署时通过 Spring Boot PropertiesLauncher 调起（-Dloader.main=本类）。
 */
public final class MatsimScenarioRunner {

    private MatsimScenarioRunner() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("用法: MatsimScenarioRunner <config.xml> <outputDir>");
            System.exit(2);
        }
        System.setProperty("matsim.preferLocalDtds", "true");
        String configPath = args[0];
        String outputDir = args[1];
        try {
            Config config = ConfigUtils.loadConfig(configPath);
            config.controller().setOutputDirectory(outputDir);
            config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
            config.controller().setRunId(null);
            Scenario scenario = ScenarioUtils.loadScenario(config);
            Controler controler = new Controler(scenario);
            controler.run();
            System.out.println("MATSIM_RUN_DONE");
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.err.println("MATSIM_RUN_FAILED: " + e.getMessage());
            System.exit(1);
        }
    }
}
