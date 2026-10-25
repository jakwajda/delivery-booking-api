package com.company.delivery.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ApiRequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        boolean failed = false;
        MDC.put("requestId", requestId);
        try {
            response.setHeader("X-Request-ID", requestId);
            // The DispatcherServlet has not resolved the route at this point.
            log.info("Incoming API request method={}", request.getMethod());
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            try {
                log.info(
                        "API request completed method={} route={} status={} durationMs={}",
                        request.getMethod(),
                        getRoute(request),
                        failed ? 500 : response.getStatus(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            } finally {
                MDC.remove("requestId");
            }
        }
    }

    private static String getRoute(HttpServletRequest request) {
        // Log a server-defined route, never raw URLs, query strings, keys or request bodies.
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return route instanceof String pattern ? pattern : "UNMAPPED";
    }
}
