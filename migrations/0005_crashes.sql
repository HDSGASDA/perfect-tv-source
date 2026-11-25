-- Opt-in crash reports from managed apps (Playback > Send crash reports).
-- Exception type, this app's stack frames and a fingerprint; no message text, channel names or account data.
CREATE TABLE crashes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  received_at INTEGER NOT NULL,
  occurred_at INTEGER NOT NULL,
  version_code INTEGER NOT NULL,
  version_name TEXT NOT NULL,
  device TEXT NOT NULL,
  android INTEGER NOT NULL,
  type TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  thread TEXT NOT NULL,
  stack TEXT NOT NULL
);
CREATE INDEX crashes_fingerprint ON crashes(fingerprint);
CREATE INDEX crashes_received ON crashes(received_at);