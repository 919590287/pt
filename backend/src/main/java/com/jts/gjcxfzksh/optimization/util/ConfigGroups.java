package com.jts.gjcxfzksh.optimization.util;

import lombok.extern.slf4j.Slf4j;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;

import java.util.Map;

/**
 * config 派生工具：scoring 参数集克隆（边界方式锁定的派生子人群）与 outside 活动参数注入。
 */
@Slf4j
public final class ConfigGroups {

    /** 边界方式锁定的派生子人群后缀 */
    public static final String LOCK_SUFFIX = "__bLock";
    /** 默认（空）子人群对应的锁定子人群名 */
    public static final String LOCK_DEFAULT = "bLock";
    public static final String OUTSIDE_ACT_TYPE = "outside";
    /** MATSim 子人群显式命名：无属性(null) 统一规范化为 "default" */
    public static final String DEFAULT_SUBPOP = "default";

    private ConfigGroups() {
    }

    public static String lockName(String origSubpopulation) {
        if (origSubpopulation == null || origSubpopulation.isBlank()) {
            return LOCK_DEFAULT;
        }
        return origSubpopulation + LOCK_SUFFIX;
    }

    /**
     * 通用 ConfigGroup 深拷贝：直接参数 + 递归 parameterSet。
     * skipParams 用于跳过目标已固定的参数（如 subpopulation）。
     */
    public static void copyInto(ConfigGroup source, ConfigGroup target, String... skipParams) {
        for (Map.Entry<String, String> entry : source.getParams().entrySet()) {
            boolean skip = false;
            for (String s : skipParams) {
                if (s.equals(entry.getKey())) {
                    skip = true;
                    break;
                }
            }
            if (skip || entry.getValue() == null) {
                continue;
            }
            try {
                target.addParam(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("配置参数拷贝失败 {}={}: {}", entry.getKey(), entry.getValue(), e.getMessage());
            }
        }
        source.getParameterSets().forEach((type, sets) -> {
            for (ConfigGroup set : sets) {
                ConfigGroup copy = target.createParameterSet(type);
                copyInto(set, copy);
                target.addParameterSet(copy);
            }
        });
    }

    /**
     * 为锁定子人群克隆原子人群的 scoring 参数集（评分行为一致），并配置仅
     * ChangeExpBeta + ReRoute 的 replanning（无方式创新、无时间突变）。
     */
    public static void addLockSubpopulation(Config config, String origSubpopulation) {
        String lock = lockName(origSubpopulation);
        ScoringConfigGroup scoring = config.scoring();
        if (!scoring.getScoringParametersPerSubpopulation().containsKey(lock)) {
            ScoringConfigGroup.ScoringParameterSet origSet = null;
            if (origSubpopulation == null || origSubpopulation.isBlank()) {
                // 默认集可能仍叫 null，也可能已被规范化为 "default"
                origSet = scoring.getScoringParametersPerSubpopulation().values().stream()
                        .filter(s -> s.getSubpopulation() == null || DEFAULT_SUBPOP.equals(s.getSubpopulation()))
                        .findFirst().orElse(scoring.getScoringParameters(null));
            } else {
                origSet = scoring.getScoringParameters(origSubpopulation);
            }
            // 不能用 getOrCreateScoringParameters：它先 add（此时 subpopulation 仍为 null）再改名，
            // add 阶段会把默认(null)子人群的参数集顶掉。必须先拷参数、先设名、最后 add。
            ScoringConfigGroup.ScoringParameterSet lockSet = (ScoringConfigGroup.ScoringParameterSet)
                    scoring.createParameterSet(ScoringConfigGroup.ScoringParameterSet.SET_TYPE);
            if (origSet != null) {
                copyInto(origSet, lockSet, "subpopulation");
            }
            lockSet.setSubpopulation(lock);
            scoring.addParameterSet(lockSet);
            if (origSet != null && scoring.getScoringParametersPerSubpopulation().values().stream()
                    .noneMatch(s -> s.getSubpopulation() == null || "default".equals(s.getSubpopulation()))
                    && (origSubpopulation == null || origSubpopulation.isBlank())) {
                // 兜底：若默认参数集仍被顶掉，恢复一份
                ScoringConfigGroup.ScoringParameterSet restored = (ScoringConfigGroup.ScoringParameterSet)
                        scoring.createParameterSet(ScoringConfigGroup.ScoringParameterSet.SET_TYPE);
                copyInto(origSet, restored, "subpopulation");
                scoring.addParameterSet(restored);
            }
        }

        ReplanningConfigGroup replanning = config.replanning();
        boolean exists = replanning.getStrategySettings().stream()
                .anyMatch(s -> lock.equals(s.getSubpopulation()));
        if (!exists) {
            ReplanningConfigGroup.StrategySettings selector = new ReplanningConfigGroup.StrategySettings();
            selector.setStrategyName("ChangeExpBeta");
            selector.setWeight(0.85);
            selector.setSubpopulation(lock);
            replanning.addStrategySettings(selector);

            ReplanningConfigGroup.StrategySettings reroute = new ReplanningConfigGroup.StrategySettings();
            reroute.setStrategyName("ReRoute");
            reroute.setWeight(0.15);
            reroute.setSubpopulation(lock);
            replanning.addStrategySettings(reroute);
        }
    }

    /**
     * 子人群显式化：MATSim 运行时按属性值精确匹配（null 不会自动映射到 "default"）。
     * 切分模型引入锁定子人群后，把 scoring 默认集(null) 与 replanning 无子人群策略
     * 统一规范为 "default"，plans 侧由切分服务给无属性 person 补 subpopulation=default。
     */
    public static void normalizeDefaultSubpopulation(Config config) {
        for (ScoringConfigGroup.ScoringParameterSet set : config.scoring().getScoringParametersPerSubpopulation().values()) {
            if (set.getSubpopulation() == null) {
                set.setSubpopulation(DEFAULT_SUBPOP);
            }
        }
        for (ReplanningConfigGroup.StrategySettings s : config.replanning().getStrategySettings()) {
            if (s.getSubpopulation() == null || s.getSubpopulation().isBlank()) {
                s.setSubpopulation(DEFAULT_SUBPOP);
            }
        }
    }

    /**
     * 给所有 scoring 参数集补 outside 虚拟活动参数（不参与打分）。
     */
    public static void ensureOutsideActivityParams(Config config) {
        config.scoring().getScoringParametersPerSubpopulation().values().forEach(set -> {
            if (set.getActivityParamsPerType().containsKey(OUTSIDE_ACT_TYPE)) {
                return;
            }
            ScoringConfigGroup.ActivityParams outside = new ScoringConfigGroup.ActivityParams(OUTSIDE_ACT_TYPE);
            outside.setTypicalDuration(12 * 3600);
            outside.setScoringThisActivityAtAll(false);
            set.addActivityParams(outside);
        });
        // 顶层（无子人群参数集时的兜底）
        if (config.scoring().getScoringParametersPerSubpopulation().isEmpty()
                && config.scoring().getActivityParams(OUTSIDE_ACT_TYPE) == null) {
            ScoringConfigGroup.ActivityParams outside = new ScoringConfigGroup.ActivityParams(OUTSIDE_ACT_TYPE);
            outside.setTypicalDuration(12 * 3600);
            outside.setScoringThisActivityAtAll(false);
            config.scoring().addActivityParams(outside);
        }
    }
}
