package com.forband.auth;

import com.forband.common.AccessLogFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public WebMvcConfig(AuthInterceptor authInterceptor,
                        CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.authInterceptor = authInterceptor;
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // "/api/files/*/view"（单段通配）：带票据的**文件取用**端点，给**手机浏览器**用——
                // 它做不了"导航/下载也带 Authorization 头"，只能靠 URL 里的票据（FileTicketService.verify
                // 全权把关，无票/伪造/跨文件/过期一律 401）。**不**匹配 /api/files/{id} 与 /api/files，
                // 那两条仍需头部鉴权。详见 docs/08 §8.5。
                .excludePathPatterns("/api/auth/login", "/api/health", "/api/files/*/view");
    }

    /**
     * PWA 缓存头（docs/19 §19.8）：SW / manifest / HTML 必须 no-cache，静态资源也不加长缓存。
     * <p>
     * ⚠️ 本站文件名没有内容指纹（永远叫 /band.css、/band-core.js），SW 用 stale-while-revalidate
     * 先给缓存再后台拉新；若 HTTP 层给 30 天长缓存，后台拉到的仍是旧文件，SW 就永远更新不了。
     * 真正的缓存层是 SW 的 Cache Storage，不是浏览器 HTTP 缓存。
     * <p>
     * ⚠️ 根级用 /** 兜底：实测 `/*` 在 Spring 的静态资源 handler 里拿不到响应头（根级页面会漏），
     * `/**` 能覆盖全部静态文件；目录模式比它更具体，会优先命中下面那几条。
     * 目录模式的 addResourceLocations 要指到该目录（捕获路径不含前缀）。
     * `/api/**` 由控制器处理，不经过这里，继续保持不缓存。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl noCache = CacheControl.noCache();
        // 根级文件（*.html / *.js / *.css / manifest.json / service-worker.js）走这条。
        // 目录模式（/icons/** 等）比它更具体，会优先命中下面那几条。
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(noCache);
        registry.addResourceHandler("/icons/**")
                .addResourceLocations("classpath:/static/icons/")
                .setCacheControl(noCache);
        registry.addResourceHandler("/fonts/**")
                .addResourceLocations("classpath:/static/fonts/")
                .setCacheControl(noCache);
        registry.addResourceHandler("/vendor/**")
                .addResourceLocations("classpath:/static/vendor/")
                .setCacheControl(noCache);
        registry.addResourceHandler("/tuner/**")
                .addResourceLocations("classpath:/static/tuner/")
                .setCacheControl(noCache);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilter() {
        FilterRegistrationBean<AccessLogFilter> registration =
                new FilterRegistrationBean<>(new AccessLogFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
