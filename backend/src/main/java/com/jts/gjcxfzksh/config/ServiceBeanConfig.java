package com.jts.gjcxfzksh.config;

import com.jts.gjcxfzksh.api.service.SchemeService;
import com.jts.gjcxfzksh.api.service.impl.SchemeServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServiceBeanConfig {

    @Bean("schemeService")
    public SchemeService schemeService() {
        return new SchemeServiceImpl();
    }
}
