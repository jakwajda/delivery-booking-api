package com.company.delivery.exception;

import lombok.Getter;

import org.springframework.http.HttpStatus;

@Getter
public class BookingProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BookingProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
