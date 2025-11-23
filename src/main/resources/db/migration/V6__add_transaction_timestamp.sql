ALTER TABLE transaction
  ADD COLUMN timestamp TIMESTAMP(3) NULL after height;

UPDATE transaction t
  JOIN account_entry ae
ON t.hash = ae.hash
  SET t.timestamp = ae.timestamp
WHERE t.timestamp IS NULL;
