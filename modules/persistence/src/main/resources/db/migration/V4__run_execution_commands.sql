CREATE TABLE run_execution_commands (
  id VARCHAR(96) PRIMARY KEY,
  run_id VARCHAR(96) NOT NULL,
  operation VARCHAR(24) NOT NULL,
  accepted_run_version BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  actor VARCHAR(256) NOT NULL,
  correlation_id VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  attempts INTEGER NOT NULL,
  owner VARCHAR(128),
  lease_expires_at TIMESTAMP,
  error_text VARCHAR(2048) NOT NULL,
  requested_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_execution_command_run FOREIGN KEY (run_id) REFERENCES task_runs(id),
  CONSTRAINT uq_execution_command_key UNIQUE (run_id, idempotency_key),
  CONSTRAINT uq_execution_command_version UNIQUE (run_id, operation, accepted_run_version)
);

CREATE INDEX idx_execution_command_claim
  ON run_execution_commands(status, lease_expires_at, requested_at);
