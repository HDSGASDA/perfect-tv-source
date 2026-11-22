-- Opt-in playback diagnostics from managed apps (Playback › Send playback diagnostics).
-- Anonymous per-channel totals plus the box model; no account data or identifiers.
CREATE TABLE diagnostics (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  received_at INTEGER NOT NULL,
  version_code INTEGER NOT NULL,
  version_name TEXT NOT NULL,
  device TEXT NOT NULL,
  android INTEGER NOT NULL,
  channels_json TEXT NOT NULL
);
CREATE INDEX diagnostics_received ON diagnostics(received_at);