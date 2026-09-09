ALTER TABLE projects ADD COLUMN removed_at TIMESTAMP NULL;

CREATE INDEX idx_projects_removed_at ON projects(removed_at);
