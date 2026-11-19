package com.forband.common;

import com.forband.auth.CurrentUser;
import com.forband.auth.CurrentUserHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 访问日志（docs/11：路径、状态码、耗时、操作人，30 天保留）。
 * 写专用 logger "ACCESS" → logs/access.log（logback-spring.xml 轮转）。
 * 只记录 /api/**；静态资源噪音不进访问日志。
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger accessLog = LoggerFactory.getLogger("ACCESS");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            CurrentUser user = CurrentUserHolder.get();
            String operator = user == null ? "-" : String.valueOf(user.memberId());
            accessLog.info("{} {} {} {} {}ms",
                    request.getRemoteAddr(),
                    operator,
                    request.getMethod(),
                    request.getRequestURI(),
                    System.currentTimeMillis() - start);
            CurrentUserHolder.clear();
        }
    }
}
