CREATE INDEX vote_public_key ON vote (public_key);
CREATE INDEX vote_block_hash ON vote (block_hash);
CREATE INDEX weight_representative_public_key ON weight (representative_public_key);
CREATE INDEX receivable_receiver_public_key ON receivable (receiver_public_key);
