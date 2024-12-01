TRUNCATE TABLE unchecked_transaction;

ALTER TABLE unchecked_transaction
  ADD COLUMN timestamp TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL;
