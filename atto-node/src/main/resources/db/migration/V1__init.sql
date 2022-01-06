CREATE TABLE transactions
(
    hash              VARBINARY(32) PRIMARY KEY,
    status            VARCHAR(9) NOT NULl,

    type              VARCHAR(7) NOT NULL,
    version           SMALLINT UNSIGNED NOT NULl,
    publicKey         VARBINARY(32) NOT NULL,
    height            BIGINT UNSIGNED NOT NULL,
    previous          VARBINARY(32) NOT NULL,
    representative    VARBINARY(32) NOT NULL,
    link              VARBINARY(32) NOT NULL,
    balance           BIGINT UNSIGNED NOT NULL,
    amount            BIGINT UNSIGNED NOT NULL,
    timestamp         BIGINT UNSIGNED NOT NULL,

    signature         VARBINARY(64) NOT NULL,
    work              VARBINARY(32) NOT NULL,

    receivedTimestamp BIGINT UNSIGNED NOT NULL,

    createdAt         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updatedAt         TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE votes
(
    hash              VARBINARY(32) NOT NULL,
    publicKey         VARBINARY(32) NOT NULL,
    timestamp         BIGINT UNSIGNED NOT NULL,
    signature         VARBINARY(64) NOT NULL,
    receivedTimestamp BIGINT UNSIGNED NOT NULL,

    PRIMARY KEY (hash, publicKey)
);