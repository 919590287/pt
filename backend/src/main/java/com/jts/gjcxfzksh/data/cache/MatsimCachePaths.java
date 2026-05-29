package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.Scheme;

import java.nio.file.Path;

public final class MatsimCachePaths {

    private MatsimCachePaths() {
    }

    public static Path modelDir(MatsimData data) {
        return Path.of(data.getCacheFolder()).toAbsolutePath().normalize();
    }

    public static Path modelDir(Scheme scheme) {
        return Path.of(scheme.getCache()).toAbsolutePath().normalize();
    }

    public static Path versionDir(MatsimData data, String version) {
        return modelDir(data).resolve(version);
    }

    public static Path manifestPath(MatsimData data) {
        return modelDir(data).resolve("manifest.json");
    }

    public static Path manifestPath(Scheme scheme) {
        return modelDir(scheme).resolve("manifest.json");
    }
}
