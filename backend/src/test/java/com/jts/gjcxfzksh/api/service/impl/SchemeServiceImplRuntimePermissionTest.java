package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.entry.Scheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemeServiceImplRuntimePermissionTest {

    private final MatsimConfig config = new MatsimConfig();
    private final SchemeServiceImpl service = new SchemeServiceImpl();

    SchemeServiceImplRuntimePermissionTest() {
        service.matsimConfig = config;
    }

    @AfterEach
    void cleanup() {
        config.getSchemes().keySet().forEach(Datasource::remove);
    }

    @Test
    void ordinaryUserCannotGloballyUnloadPublicModelButCanUnloadOwnModel() {
        Scheme shared = scheme("area/public/v6", MatsimConfig.PUBLIC_SCOPE);
        Scheme owned = scheme("area/alice/private", "alice");
        config.getSchemes().put(shared.getName(), shared);
        config.getSchemes().put(owned.getName(), owned);

        assertFalse(service.unloadModel("alice", shared.getName()));
        assertTrue(service.unloadModel("alice", owned.getName()));
        assertFalse(service.unloadModel("bob", owned.getName()));
    }

    private static Scheme scheme(String name, String scope) {
        Scheme scheme = new Scheme();
        scheme.setName(name);
        scheme.setScope(scope);
        scheme.setDesc(new Scheme.Desc());
        return scheme;
    }
}
