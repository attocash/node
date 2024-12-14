CREATE TABLE weight
(
  representative_algorithm  ENUM ('V1')                                                              NOT NULL,
  representative_public_key VARBINARY(32)                                                            NOT NULL,
  weight                    BIGINT UNSIGNED
);


ALTER TABLE vote DROP COLUMN weight;
