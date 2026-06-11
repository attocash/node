ALTER TABLE weight ADD PRIMARY KEY (representative_public_key);

ALTER TABLE weight ADD COLUMN last_vote_timestamp TIMESTAMP(6) NULL;
