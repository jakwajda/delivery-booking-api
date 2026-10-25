package com.company.delivery.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.company.delivery.service.BookingService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

class GlobalExceptionHandlerTest {

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final String PRIVATE_MESSAGE = "PRIVATE-ADDRESS PRIVATE-KEY";
    private static final String PRIVATE_SQL = "SELECT * FROM private_customer_data";

    private final BookingService service = mock(BookingService.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        logs.start();
        logger.addAppender(logs);
        mvc = MockMvcBuilders.standaloneSetup(new BookingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @ParameterizedTest
    @MethodSource("unavailableFailures")
    void availabilityAndTransientFailuresReturn503(RuntimeException error) throws Exception {
        when(service.get(BOOKING_ID)).thenThrow(error);

        var response = mvc.perform(get("/api/v1/bookings/{id}", BOOKING_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("Storage is temporarily unavailable"))
                .andReturn().getResponse().getContentAsString();

        assertSafeDiagnostics(response, error, "STORAGE_UNAVAILABLE");
    }

    @ParameterizedTest
    @MethodSource("internalFailures")
    void integrityAndProgrammingFailuresReturn500(RuntimeException error) throws Exception {
        when(service.get(BOOKING_ID)).thenThrow(error);

        var response = mvc.perform(get("/api/v1/bookings/{id}", BOOKING_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("STORAGE_ERROR"))
                .andExpect(jsonPath("$.detail").value("Storage operation failed"))
                .andReturn().getResponse().getContentAsString();

        assertSafeDiagnostics(response, error, "STORAGE_ERROR");
    }

    private void assertSafeDiagnostics(String response, RuntimeException error, String code) {
        assertThat(response).doesNotContain("PRIVATE-ADDRESS", "PRIVATE-KEY", "PRIVATE-CAUSE", PRIVATE_SQL);
        assertThat(logs.list).hasSize(1);
        var event = logs.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .contains("code=" + code, "exceptionType=" + error.getClass().getSimpleName())
                .doesNotContain("PRIVATE-ADDRESS", "PRIVATE-KEY", "PRIVATE-CAUSE", PRIVATE_SQL);
        assertThat(event.getThrowableProxy()).isNull();
    }

    private static Stream<RuntimeException> unavailableFailures() {
        return Stream.of(
                new CannotGetJdbcConnectionException(PRIVATE_MESSAGE, privateCause()),
                new DataAccessResourceFailureException(PRIVATE_MESSAGE, privateCause()),
                new QueryTimeoutException(PRIVATE_MESSAGE, privateCause()),
                new CannotAcquireLockException(PRIVATE_MESSAGE, privateCause()),
                new RecoverableDataAccessException(PRIVATE_MESSAGE, privateCause()),
                new CannotCreateTransactionException(
                        PRIVATE_MESSAGE, new SQLException("PRIVATE-CAUSE", "08006")));
    }

    private static Stream<RuntimeException> internalFailures() {
        return Stream.of(
                new DataIntegrityViolationException(PRIVATE_MESSAGE, privateCause()),
                new BadSqlGrammarException(PRIVATE_MESSAGE, PRIVATE_SQL, privateCause()),
                new InvalidDataAccessApiUsageException(PRIVATE_MESSAGE, privateCause()),
                new UncategorizedSQLException(PRIVATE_MESSAGE, PRIVATE_SQL, privateCause()),
                new CannotCreateTransactionException(
                        PRIVATE_MESSAGE, new SQLException("PRIVATE-CAUSE", "23505")),
                new CannotCreateTransactionException(
                        PRIVATE_MESSAGE, new IllegalStateException("PRIVATE-CAUSE")));
    }

    private static SQLException privateCause() {
        return new SQLException("PRIVATE-CAUSE");
    }
}
