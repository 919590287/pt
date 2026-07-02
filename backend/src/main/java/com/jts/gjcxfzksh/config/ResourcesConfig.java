package com.jts.gjcxfzksh.config;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 通用配置
 *
 * @author ruoyi
 */
@Configuration
public class ResourcesConfig implements WebMvcConfigurer {

    @Resource
    private AuthInterceptor authInterceptor;

    /**
     * 允许的跨域来源（逗号分隔的 origin pattern），从 CORS_ALLOWED_ORIGINS 环境变量注入。
     * 未配置时使用默认白名单：本机 + 常见内网网段，公网任意源不再放行。
     */
    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    /**
     * 跨域配置。
     * 安全约束：allowCredentials(true) 时禁止使用 "*" 通配 origin
     * （通配 pattern 会回显任意请求 Origin，属高危组合），必须走白名单。
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            for (String origin : allowedOrigins.split(",")) {
                if (!origin.isBlank()) {
                    config.addAllowedOriginPattern(origin.trim());
                }
            }
        } else {
            // 默认白名单：开发环境（localhost 任意端口）与内网部署（10.*/192.168.* 网段）。
            // 172.16-31.* 等其他网段或域名部署请通过 CORS_ALLOWED_ORIGINS 配置。
            config.addAllowedOriginPattern("http://localhost:[*]");
            config.addAllowedOriginPattern("https://localhost:[*]");
            config.addAllowedOriginPattern("http://127.0.0.1:[*]");
            config.addAllowedOriginPattern("http://192.168.*:[*]");
            config.addAllowedOriginPattern("http://10.*:[*]");
        }
        // 设置访问源请求头
        config.addAllowedHeader("*");
        // 设置访问源请求方法
        config.addAllowedMethod("*");
        // 暴露 traceId 响应头供前端串联排查
        config.addExposedHeader(RequestTraceFilter.TRACE_ID_HEADER);
        // 有效期 1800秒
        config.setMaxAge(1800L);
        // 添加映射路径，拦截一切请求
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        // 返回新的CorsFilter
        return new CorsFilter(source);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/pt/**")
                .excludePathPatterns("/pt/auth/**");
    }
}
