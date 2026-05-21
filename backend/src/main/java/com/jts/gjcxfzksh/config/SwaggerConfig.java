package com.jts.gjcxfzksh.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * Swagger2的接口配置
 *
 * @author ruoyi
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public PropertyCustomizer jsonViewExampleCustomizer() {
        return new PropertyCustomizer() {
            @Override
            public Schema<?> customize(Schema property, AnnotatedType type) {
                // 获取父节点。如果是_Query视图则设置默认值为空，而不是{}。
                String name = type.getParent().getName();
                if(name != null) {
                    boolean queryView = name.endsWith("_Query");
                    if (queryView) {
                        type.getParent().setDefault(null);
//                    property.setExample("string");
                    }
                }
                return property;
            }
        };
    }

    /**
     * 创建API
     */
    @Bean
    public OpenAPI createRestApi() {
        return new OpenAPI()
                .info(new Info().title("多智能体公交出行仿真可视化平台")
                        .description("多智能体公交出行仿真可视化平台")
                        .version("v1.0"))
                .externalDocs(new ExternalDocumentation()
                        .description("")
                        .url(""));
    }

}
