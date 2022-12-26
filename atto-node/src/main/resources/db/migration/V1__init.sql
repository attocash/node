CREATE TABLE account
(
    public_key                 VARBINARY(32) PRIMARY KEY,
    version                    SMALLINT UNSIGNED NOT NULl,
    height                     BIGINT UNSIGNED NOT NULL,
    balance                    BIGINT UNSIGNED NOT NULL,
    last_transaction_timestamp TIMESTAMP                           NOT NULL,
    last_transaction_hash      VARBINARY(32) NOT NULl,
    representative             VARBINARY(32) NOT NULL,

    persisted_at               TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at                 TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE transaction
(
    hash         VARBINARY(32) PRIMARY KEY,

    type         VARCHAR(7)                          NOT NULL,
    version      SMALLINT UNSIGNED                   NOT NULl,
    public_key   VARBINARY(32)                       NOT NULL,
    height       BIGINT UNSIGNED                     NOT NULL,
    balance      BIGINT UNSIGNED                     NOT NULL,
    timestamp    TIMESTAMP                           NOT NULL,
    block        VARBINARY(131)                      NOT NULL,

    signature    VARBINARY(64)                       NOT NULL,
    work         VARBINARY(32)                       NOT NULL,

    received_at  TIMESTAMP                           NOT NULL,
    persisted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE receivable
(
    hash                VARBINARY(32) PRIMARY KEY,
    receiver_public_key VARBINARY(32)                       NOT NULL,
    amount              BIGINT UNSIGNED                     NOT NULL,
    persisted_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE vote
(
    hash        VARBINARY(32)   NOT NULL,
    public_key  VARBINARY(32)   NOT NULL,
    timestamp   TIMESTAMP       NOT NULL,
    signature   VARBINARY(64) PRIMARY KEY,
    received_at BIGINT UNSIGNED NOT NULL,
    weight      BIGINT UNSIGNED NOT NULL
);