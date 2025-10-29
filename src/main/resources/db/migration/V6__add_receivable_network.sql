ALTER TABLE receivable
  ADD COLUMN network ENUM('LOCAL','DEV','BETA','LIVE') NULL AFTER hash;

UPDATE receivable
SET network = (SELECT network FROM account LIMIT 1);

ALTER TABLE receivable
  MODIFY COLUMN network ENUM('LOCAL','DEV','BETA','LIVE') NOT NULL;

ALTER TABLE unchecked_transaction
  MODIFY COLUMN `timestamp` TIMESTAMP (3) DEFAULT CURRENT_TIMESTAMP (3) NOT NULL AFTER serialized;
