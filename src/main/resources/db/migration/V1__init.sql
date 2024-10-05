CREATE TABLE account
(
  public_key                 VARBINARY(32) PRIMARY KEY,
  network                    ENUM ('LOCAL','DEV','BETA','LIVE')                                       NOT NULL,
  version                    SMALLINT UNSIGNED                                                        NOT NULl,
  algorithm                  ENUM ('V1')                                                              NOT NULL,
  height                     BIGINT UNSIGNED                                                          NOT NULL,
  balance                    BIGINT UNSIGNED                                                          NOT NULL,
  last_transaction_timestamp TIMESTAMP(3)                              NOT NULL,
  last_transaction_hash      VARBINARY(32)                                                            NOT NULl,
  representative_algorithm   ENUM ('V1')                                                              NOT NULL,
  representative_public_key  VARBINARY(32)                                                            NOT NULL,

  persisted_at               TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
  updated_at                 TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) NOT NULL
);

CREATE TABLE transaction
(
  hash         VARBINARY(32) PRIMARY KEY,

  public_key   VARBINARY(32)                              NOT NULL,
  height       BIGINT UNSIGNED                            NOT NULL,

  serialized   VARBINARY(206)                             NOT NULL,

  received_at  TIMESTAMP(3)                              NOT NULL,
  persisted_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL
);

CREATE UNIQUE INDEX transaction_public_key_height ON transaction (public_key, height);

CREATE TABLE receivable
(
  hash                VARBINARY(32) PRIMARY KEY,
  version             SMALLINT UNSIGNED                         NOT NULl,
  algorithm           ENUM ('V1')                               NOT NULL,
  timestamp           TIMESTAMP(3)                              NOT NULL,
  receiver_algorithm  ENUM ('V1')                               NOT NULL,
  receiver_public_key VARBINARY(32)                             NOT NULL,
  amount              BIGINT UNSIGNED                           NOT NULL,
  persisted_at        TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL
);

CREATE TABLE vote
(
  hash         VARBINARY(32)                             NOT NULL,
  algorithm    ENUM ('V1')                               NOT NULL,
  public_key   VARBINARY(32)                             NOT NULL,
  timestamp    BIGINT                                    NOT NULL,
  signature    VARBINARY(64) PRIMARY KEY,
  weight       BIGINT UNSIGNED                           NOT NULL,

  received_at  TIMESTAMP(3)                              NOT NULL,
  persisted_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL
);


CREATE TABLE unchecked_transaction
(
  hash         VARBINARY(32) PRIMARY KEY,

  height       BIGINT UNSIGNED                           NOT NULL,
  public_key   VARBINARY(32)                             NOT NULL,
  previous     VARBINARY(32),

  serialized   VARBINARY(206)                            NOT NULL,

  received_at  TIMESTAMP(3)                              NOT NULL,
  persisted_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL
);

CREATE UNIQUE INDEX unchecked_transaction_public_key_height ON unchecked_transaction (public_key, height);
