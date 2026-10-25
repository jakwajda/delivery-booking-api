package com.company.delivery.filter;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class ApiRequestLoggingFilterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiRequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> logs =
            new ListAppender<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    event.prepareForDeferredProcessing();
                    super.append(event);
                }
            };
    private final ApiRequestLoggingFilter filter = new ApiRequestLoggingFilter();

    @BeforeEach
    void capture() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
        logs.stop();
        MDC.clear();
    }

    @Test
    void correlatesRequestsAndLogsOnlySafeMetadata() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/bookings");
        request.setQueryString("secret=private-query");
        request.addHeader("Idempotency-Key", "private-key");
        request.addHeader("X-Request-ID", "untrusted-request-id");
        request.setContent("private-address".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    request.setAttribute(
                            HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/bookings");
                    assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-ID"));
                    response.setStatus(202);
                });
        assertThat(logs.list).hasSize(2);
        var incoming = logs.list.getFirst();
        assertThat(incoming.getFormattedMessage()).isEqualTo("Incoming API request method=POST");
        assertThat(incoming.getMDCPropertyMap().get("requestId"))
                .isEqualTo(response.getHeader("X-Request-ID"));
        var event = logs.list.getLast();
        assertThat(event.getMDCPropertyMap().get("requestId"))
                .isEqualTo(response.getHeader("X-Request-ID"));
        assertThat(response.getHeader("X-Request-ID")).isNotEqualTo("untrusted-request-id");
        assertThat(event.getFormattedMessage())
                .contains("status=202", "route=/api/v1/bookings", "durationMs=");
        assertThat(event.getFormattedMessage()).doesNotContain("private", "untrusted-request-id");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void reportsUnhandledFailureAndClearsRequestId() {
        MDC.put("requestId", "outer-context");
        var request = new MockHttpServletRequest("GET", "/api/v1/bookings/unknown");
        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request,
                                        new MockHttpServletResponse(),
                                        (req, res) -> {
                                            throw new ServletException("private exception text");
                                        }))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(logs.list.getLast().getFormattedMessage())
                .contains("status=500", "route=UNMAPPED");
        assertThat(logs.list.getLast().getFormattedMessage())
                .doesNotContain("private exception text");
    }

    @Test
    void unexpectedRouteAttributeDoesNotBreakTheRequest() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/bookings/unknown");
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) ->
                        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, 42));
        assertThat(logs.list.getLast().getFormattedMessage()).contains("route=UNMAPPED");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void doesNotLogHealthProbes() throws Exception {
        filter.doFilter(
                new MockHttpServletRequest("GET", "/actuator/health"),
                new MockHttpServletResponse(),
                (req, res) -> {});
        assertThat(logs.list).isEmpty();
    }
}
