CREATE TABLE stale_vote_block
(
  block_hash VARBINARY(32) PRIMARY KEY,
  created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL
);

CREATE INDEX stale_vote_block_created_at ON stale_vote_block (created_at);
