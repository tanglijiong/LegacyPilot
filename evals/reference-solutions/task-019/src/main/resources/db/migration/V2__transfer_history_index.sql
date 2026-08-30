CREATE INDEX idx_transfer_account_occurred_at
  ON transfer (account_id, occurred_at);
