CREATE TABLE weight_rebuilt
(
  representative_algorithm  ENUM ('V1')   NOT NULL,
  representative_public_key VARBINARY(32) NOT NULL,
  weight                    BIGINT UNSIGNED,
  last_vote_timestamp       DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
  PRIMARY KEY (representative_public_key)
);

CREATE INDEX account_representative_public_key ON account (representative_public_key);

INSERT INTO weight_rebuilt (representative_algorithm, representative_public_key, weight, last_vote_timestamp)
SELECT
  a.representative_algorithm,
  a.representative_public_key,
  CAST(SUM(a.balance) AS UNSIGNED) AS weight,
  COALESCE(v.last_vote_timestamp, '1970-01-01 00:00:00.000000') AS last_vote_timestamp
FROM account a
LEFT JOIN (
  SELECT public_key, MAX(received_at) AS last_vote_timestamp
  FROM vote
  GROUP BY public_key
) v ON v.public_key = a.representative_public_key
GROUP BY a.representative_algorithm, a.representative_public_key, v.last_vote_timestamp
HAVING SUM(a.balance) > 0;

DROP TABLE weight;

RENAME TABLE weight_rebuilt TO weight;
