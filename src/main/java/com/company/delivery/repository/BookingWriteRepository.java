package com.company.delivery.repository;

import com.company.delivery.model.Carrier;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class BookingWriteRepository {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertWithJobIfAbsent(
            UUID id, String key, String fingerprint, String payload, Carrier carrier) {
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO booking(id, idempotency_key, fingerprint, payload, carrier, status)
                        VALUES (?, ?, ?, ?, ?, 'ACCEPTED') ON CONFLICT (idempotency_key) DO NOTHING
                        """,
                        id,
                        key,
                        fingerprint,
                        payload,
                        carrier.name());
        if (inserted == 0) {
            return false;
        }
        jdbc.update("INSERT INTO dispatch_job(booking_id) VALUES (?)", id);
        return true;
    }
}
