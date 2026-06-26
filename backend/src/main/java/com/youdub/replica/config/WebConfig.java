package com.youdub.replica.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置。
 * 配置 CORS：允许的来源从 AppProperties.getCorsAllowOrigins() 获取，
 * 允许所有方法、所有 header。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties appProperties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = appProperties.getCorsAllowOrigins();
        registry.addMapping("/**")
                .allowedOrigins(origins.split(","))
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
