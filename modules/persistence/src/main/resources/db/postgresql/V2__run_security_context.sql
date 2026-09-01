CREATE TABLE run_security_context (
  run_id VARCHAR(96) PRIMARY KEY,
  initiated_by VARCHAR(160) NOT NULL,
  CONSTRAINT fk_run_security_context_run FOREIGN KEY (run_id) REFERENCES task_runs(id)
);
