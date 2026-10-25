package com.company.delivery.controller;

import com.company.delivery.dto.BookingRequest;
import com.company.delivery.dto.BookingView;
import com.company.delivery.service.BookingService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "delivery.api.enabled", havingValue = "true", matchIfMissing = true)
public class BookingController {

    private final BookingService service;

    @PostMapping
    public ResponseEntity<BookingView> submit(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody BookingRequest request) {
        BookingView booking = service.submit(key, request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/bookings/" + booking.id()))
                .body(booking);
    }

    @GetMapping("/{id}")
    public BookingView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/result")
    public BookingView.Result result(@PathVariable UUID id) {
        return service.result(id);
    }
}
