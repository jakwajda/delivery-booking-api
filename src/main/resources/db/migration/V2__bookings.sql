DROP TABLE schema_marker;
CREATE TABLE booking (
 id uuid PRIMARY KEY,
 idempotency_key varchar(128) NOT NULL UNIQUE,
 fingerprint varchar(64) NOT NULL,
 payload text NOT NULL,
 carrier varchar(16) NOT NULL CHECK (carrier IN ('ALPHA','BETA')),
 routing_version integer NOT NULL DEFAULT 1,
 status varchar(40) NOT NULL CHECK (status IN ('ACCEPTED','PROCESSING','RETRY_SCHEDULED','RECONCILING','BOOKED','REJECTED','FAILED','REQUIRES_RECONCILIATION')),
 tracking_number varchar(128),
 label_url text,
 error_code varchar(80),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK (status <> 'BOOKED' OR (tracking_number IS NOT NULL AND label_url IS NOT NULL))
);
CREATE TABLE dispatch_job (
 booking_id uuid PRIMARY KEY REFERENCES booking(id),
 action varchar(10) NOT NULL DEFAULT 'CREATE' CHECK (action IN ('CREATE','LOOKUP')),
 state varchar(10) NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','RUNNING','DONE')),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 lease_until timestamptz,
 lease_token uuid,
 attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
 create_attempts integer NOT NULL DEFAULT 0,
 lookup_attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX job_ready_idx ON dispatch_job(next_attempt_at) WHERE state = 'READY';
CREATE INDEX job_expired_idx ON dispatch_job(lease_until) WHERE state = 'RUNNING';
CREATE TABLE dispatch_attempt (
 id uuid PRIMARY KEY,
 booking_id uuid NOT NULL REFERENCES booking(id),
 action varchar(10) NOT NULL,
 attempt_number integer NOT NULL,
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 finished_at timestamptz,
 outcome varchar(80),
 UNIQUE (booking_id, attempt_number)
);
