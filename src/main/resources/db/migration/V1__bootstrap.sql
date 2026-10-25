-- Flyway owns the schema. The booking model is introduced in stage 2.
CREATE TABLE schema_marker (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO schema_marker VALUES (1);
