ALTER TABLE transaction
  ADD COLUMN block_type ENUM ('OPEN','SEND','RECEIVE','CHANGE') NULL AFTER height;

UPDATE transaction
SET block_type =
  CASE ORD(SUBSTRING(serialized, 1, 1))
    WHEN 0 THEN 'OPEN'
    WHEN 1 THEN 'RECEIVE'
    WHEN 2 THEN 'SEND'
    WHEN 3 THEN 'CHANGE'
    ELSE NULL
    END
WHERE block_type IS NULL;
