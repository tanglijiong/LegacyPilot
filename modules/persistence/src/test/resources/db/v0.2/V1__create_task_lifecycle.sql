CREATE TABLE projects (
  id VARCHAR(96) PRIMARY KEY,
  source VARCHAR(2048) NOT NULL,
  repository_path VARCHAR(4096) NOT NULL,
  base_revision VARCHAR(64) NOT NULL,
  registered_at TIMESTAMP NOT NULL
);

CREATE TABLE tasks (
  id VARCHAR(96) PRIMARY KEY,
  project_id VARCHAR(96) NOT NULL,
  requirement_text CLOB NOT NULL,
  acceptance_criteria CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_tasks_project FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE task_runs (
  id VARCHAR(96) PRIMARY KEY,
  task_id VARCHAR(96) NOT NULL,
  status VARCHAR(48) NOT NULL,
  version BIGINT NOT NULL,
  workspace_id VARCHAR(96),
  recovery_target VARCHAR(48),
  terminal_reason VARCHAR(2048),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_task_runs_task FOREIGN KEY (task_id) REFERENCES tasks(id)
);

CREATE TABLE run_transitions (
  run_id VARCHAR(96) NOT NULL,
  sequence_number INTEGER NOT NULL,
  source_status VARCHAR(48) NOT NULL,
  target_status VARCHAR(48) NOT NULL,
  occurred_at TIMESTAMP NOT NULL,
  reason VARCHAR(2048) NOT NULL,
  PRIMARY KEY (run_id, sequence_number),
  CONSTRAINT fk_run_transitions_run FOREIGN KEY (run_id) REFERENCES task_runs(id)
);

CREATE INDEX idx_tasks_project_id ON tasks(project_id);
CREATE INDEX idx_task_runs_task_id ON task_runs(task_id);
