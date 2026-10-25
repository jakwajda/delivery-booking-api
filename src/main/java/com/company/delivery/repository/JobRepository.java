package com.company.delivery.repository;

import com.company.delivery.config.WorkerProperties;
import com.company.delivery.dto.BookingRequest;
import com.company.delivery.model.Carrier;
import com.company.delivery.model.ClaimedJob;
import com.company.delivery.model.Decision;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

@Repository
@RequiredArgsConstructor
@Slf4j
public class JobRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WorkerProperties properties;
    private final Validator validator;

    @Transactional
    public Optional<ClaimedJob> claim(Carrier carrier) {
        var candidates =
                jdbc.query(
                        """
                        SELECT j.*, b.payload, b.created_at, clock_timestamp() AS db_now
                        FROM dispatch_job j JOIN booking b ON b.id = j.booking_id
                        WHERE b.carrier = ? AND (
                          (j.state = 'READY' AND j.next_attempt_at <= clock_timestamp())
                          OR (j.state = 'RUNNING' AND j.lease_until <= clock_timestamp()))
                        ORDER BY j.next_attempt_at, j.booking_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                        """,
                        (rs, __) ->
                                new Candidate(
                                        rs.getObject("booking_id", UUID.class),
                                        rs.getString("payload"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getTimestamp("db_now").toInstant(),
                                        rs.getString("state").equals("RUNNING"),
                                        ClaimedJob.Action.valueOf(rs.getString("action")),
                                        rs.getInt("attempts"),
                                        rs.getInt("create_attempts"),
                                        rs.getInt("lookup_attempts"),
                                        rs.getBoolean("uncertain")),
                        carrier.name());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Candidate candidate = candidates.getFirst();
        Optional<BookingRequest> request = deserializeRequest(candidate.payload());
        if (request.isEmpty() || !validator.validate(request.get()).isEmpty()) {
            quarantine(candidate, carrier);
            return Optional.empty();
        }
        var action = candidate.expired() ? ClaimedJob.Action.LOOKUP : candidate.action();
        var token = UUID.randomUUID();
        if (candidate.expired()) {
            jdbc.update(
                    """
                    UPDATE dispatch_attempt SET finished_at = clock_timestamp(), outcome = 'LEASE_EXPIRED'
                    WHERE booking_id = ? AND finished_at IS NULL
                    """,
                    candidate.id());
        }
        jdbc.update(
                """
                UPDATE dispatch_job SET state = 'RUNNING', action = ?, lease_token = ?,
                  lease_until = clock_timestamp() + (? * interval '1 millisecond'),
                  attempts = attempts + 1, uncertain = uncertain OR ?,
                  create_attempts = create_attempts + ?, lookup_attempts = lookup_attempts + ?
                WHERE booking_id = ?
                """,
                action.name(),
                token,
                properties.lease().toMillis(),
                candidate.expired(),
                action == ClaimedJob.Action.CREATE ? 1 : 0,
                action == ClaimedJob.Action.LOOKUP ? 1 : 0,
                candidate.id());
        jdbc.update(
                """
                UPDATE booking SET status = ?, updated_at = clock_timestamp() WHERE id = ?
                """,
                action == ClaimedJob.Action.CREATE ? "PROCESSING" : "RECONCILING",
                candidate.id());
        jdbc.update(
                """
                INSERT INTO dispatch_attempt(id, booking_id, action, attempt_number) VALUES (?, ?, ?, ?)
                """,
                token,
                candidate.id(),
                action.name(),
                candidate.attempts() + 1);
        return Optional.of(
                new ClaimedJob(
                        candidate.id(),
                        token,
                        action,
                        carrier,
                        request.get(),
                        candidate.createdAt(),
                        candidate.now(),
                        (action == ClaimedJob.Action.CREATE
                                        ? candidate.creates()
                                        : candidate.lookups())
                                + 1,
                        candidate.expired() || candidate.uncertain()));
    }

    private Optional<BookingRequest> deserializeRequest(String payload) {
        try {
            return Optional.ofNullable(json.readValue(payload, BookingRequest.class));
        } catch (JacksonException invalidPayload) {
            return Optional.empty();
        }
    }

    private void quarantine(Candidate candidate, Carrier carrier) {
        // An expired owner may already have created a shipment. Invalid data must not
        // turn that uncertainty into a definitive failure or allow another CREATE.
        boolean uncertain =
                candidate.expired()
                        || candidate.uncertain()
                        || candidate.action() == ClaimedJob.Action.LOOKUP;
        String status = uncertain ? "REQUIRES_RECONCILIATION" : "FAILED";
        jdbc.update(
                """
                UPDATE booking SET status = ?, error_code = 'INVALID_STORED_PAYLOAD',
                  updated_at = clock_timestamp() WHERE id = ?
                """,
                status,
                candidate.id());
        jdbc.update(
                """
                UPDATE dispatch_job SET state = 'DONE', lease_token = NULL, lease_until = NULL,
                  uncertain = ? WHERE booking_id = ?
                """,
                uncertain,
                candidate.id());
        jdbc.update(
                """
                UPDATE dispatch_attempt SET finished_at = clock_timestamp(),
                  outcome = 'INVALID_STORED_PAYLOAD'
                WHERE booking_id = ? AND finished_at IS NULL
                """,
                candidate.id());
        log.warn(
                "Dispatch job quarantined bookingId={} carrier={} status={} code={}",
                candidate.id(),
                carrier,
                status,
                "INVALID_STORED_PAYLOAD");
    }

    @Transactional
    public boolean complete(ClaimedJob job, Decision decision) {
        // Lock ownership before touching booking. A stale worker cannot modify any business state.
        var owners =
                jdbc.queryForList(
                        """
                        SELECT booking_id FROM dispatch_job
                        WHERE booking_id = ? AND lease_token = ? AND state = 'RUNNING' FOR UPDATE
                        """,
                        UUID.class,
                        job.bookingId(),
                        job.token());
        if (owners.isEmpty()) {
            return false;
        }
        var outcome = decision.outcome();
        jdbc.update(
                """
                UPDATE booking SET status = ?, tracking_number = ?, label_url = ?, error_code = ?,
                  updated_at = clock_timestamp() WHERE id = ?
                """,
                decision.status().name(),
                outcome.trackingNumber(),
                outcome.labelUrl(),
                outcome.code(),
                job.bookingId());
        jdbc.update(
                """
                UPDATE dispatch_job SET state = ?, action = ?, lease_token = NULL, lease_until = NULL,
                  next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'), uncertain = uncertain OR ?
                WHERE booking_id = ?
                """,
                decision.nextAction() == null ? "DONE" : "READY",
                decision.nextAction() == null ? job.action().name() : decision.nextAction().name(),
                decision.delay().toMillis(),
                outcome.kind() == com.company.delivery.client.CarrierOutcome.Kind.UNKNOWN,
                job.bookingId());
        jdbc.update(
                """
                UPDATE dispatch_attempt SET finished_at = clock_timestamp(), outcome = ? WHERE id = ?
                """,
                outcome.code() == null ? outcome.kind().name() : outcome.code(),
                job.token());
        return true;
    }

    private record Candidate(
            UUID id,
            String payload,
            Instant createdAt,
            Instant now,
            boolean expired,
            ClaimedJob.Action action,
            int attempts,
            int creates,
            int lookups,
            boolean uncertain) {}
}
