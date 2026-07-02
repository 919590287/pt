package com.jts.gjcxfzksh.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级可观测性：为每个 /pt/** 请求生成 traceId（写入 MDC 与 X-Trace-Id 响应头），
 * 输出 uri/status/costMs 耗时日志，超过 {@link #SLOW_REQUEST_MS} 记 WARN。
 * 前端 request.js 读取 X-Trace-Id 后可与后端日志串联定位慢请求。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private static final long SLOW_REQUEST_MS = 1000;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只跟踪业务接口，静态资源与探测请求不产生日志噪音
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/pt/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = createTraceId();
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            if (costMs >= SLOW_REQUEST_MS) {
                log.warn("slow-request traceId={} method={} uri={} status={} costMs={}",
                        traceId, request.getMethod(), request.getRequestURI(), status, costMs);
            } else if (log.isInfoEnabled()) {
                log.info("request traceId={} method={} uri={} status={} costMs={}",
                        traceId, request.getMethod(), request.getRequestURI(), status, costMs);
            }
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private static String createTraceId() {
        // 16 位短 id 足够单机日志检索
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
