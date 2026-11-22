-- Reserve part of the account-wide R2 free allowance for other buckets and transient data.
-- Backfill existing releases so future uploads cannot ignore already-accounted APKs.
CREATE TABLE IF NOT EXISTS r2_storage_budget (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  used_bytes INTEGER NOT NULL CHECK (used_bytes >= 0)
);

INSERT OR IGNORE INTO r2_storage_budget(id, used_bytes)
SELECT 1, COALESCE(SUM(size_bytes), 0) FROM releases;
