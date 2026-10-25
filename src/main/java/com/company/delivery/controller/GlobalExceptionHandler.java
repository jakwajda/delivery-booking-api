package com.company.delivery.controller;

import com.company.delivery.exception.BookingProblem;

import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BookingProblem.class)
    ProblemDetail bookingProblem(BookingProblem error) {
        log.info(
                "Booking request rejected status={} code={}",
                error.getStatus().value(),
                error.getCode());
        var problem = ProblemDetail.forStatusAndDetail(error.getStatus(), error.getMessage());
        problem.setProperty("code", error.getCode());
        return problem;
    }

    @ExceptionHandler({
        TransientDataAccessException.class,
        RecoverableDataAccessException.class,
        DataAccessResourceFailureException.class
    })
    ProblemDetail unavailable(RuntimeException error) {
        log.error(
                "Booking storage access failed code={} exceptionType={}",
                "STORAGE_UNAVAILABLE",
                error.getClass().getSimpleName());
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE, "Storage is temporarily unavailable");
        problem.setProperty("code", "STORAGE_UNAVAILABLE");
        return problem;
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail storageError(RuntimeException error) {
        log.error(
                "Booking storage operation failed code={} exceptionType={}",
                "STORAGE_ERROR",
                error.getClass().getSimpleName());
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Storage operation failed");
        problem.setProperty("code", "STORAGE_ERROR");
        return problem;
    }

    @ExceptionHandler(CannotCreateTransactionException.class)
    ProblemDetail transactionStartError(CannotCreateTransactionException error) {
        if (error.contains(TransientDataAccessException.class)
                || error.contains(RecoverableDataAccessException.class)
                || error.contains(DataAccessResourceFailureException.class)
                || isUnavailableSqlCause(error.getMostSpecificCause())) {
            return unavailable(error);
        }
        return storageError(error);
    }

    private boolean isUnavailableSqlCause(Throwable cause) {
        if (!(cause instanceof SQLException sql)) {
            return false;
        }
        // SQLState class 08 denotes connection failures. Never inspect exception messages.
        return sql instanceof SQLTransientException
                || sql instanceof SQLNonTransientConnectionException
                || sql instanceof SQLRecoverableException
                || (sql.getSQLState() != null && sql.getSQLState().startsWith("08"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        var problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "Request fields are invalid");
        log.info("Booking request validation failed status={} code={}", 400, "INVALID_REQUEST");
        problem.setProperty("code", "INVALID_REQUEST");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }
}
