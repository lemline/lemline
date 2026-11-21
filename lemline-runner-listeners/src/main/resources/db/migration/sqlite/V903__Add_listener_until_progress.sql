-- Durable progress for complete until event consumption strategies.
ALTER TABLE lemline_listeners ADD COLUMN until_progress TEXT;
