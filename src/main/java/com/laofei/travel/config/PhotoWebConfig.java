package com.laofei.travel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 照片存储配置：
 * 1. 暴露 photoDir（Path）Bean，PhotoController 用它落盘文件；
 * 2. 把磁盘目录映射为 /photos/** 静态资源（游客可匿名访问，AuthFilter 已放行该前缀）。
 * 目录由环境变量 PHOTO_DIR 控制，默认 ./photos（相对工作目录）。
 */
@Configuration
public class PhotoWebConfig implements WebMvcConfigurer {

    @Value("${app.photo-dir:./photos}")
    private String photoDir;

    /** 绝对化 + 规范化后的照片目录，供上传 / 删除使用 */
    @Bean
    public Path photoDirPath() {
        return Paths.get(photoDir).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(photoDir).toAbsolutePath().normalize();
        registry.addResourceHandler("/photos/**")
                .addResourceLocations("file:" + dir + "/")
                .setCachePeriod(3600);
    }
}
