ALTER TABLE tasks ADD COLUMN conversation_id VARCHAR(96);

UPDATE tasks SET conversation_id = id WHERE conversation_id IS NULL;

ALTER TABLE tasks ALTER COLUMN conversation_id SET NOT NULL;

CREATE INDEX idx_tasks_conversation_id ON tasks(conversation_id);
