-- Core-Banking: esquema inicial (docs/05 §1.1)

CREATE TABLE accounts (
    id             UUID          PRIMARY KEY,
    user_id        VARCHAR(64),
    type           VARCHAR(10)   NOT NULL CHECK (type IN ('USER', 'SYSTEM')),
    alias          VARCHAR(30)   NOT NULL UNIQUE CHECK (alias ~ '^[a-z0-9._-]{3,30}$'),
    alias_changed  BOOLEAN       NOT NULL DEFAULT false,
    account_number VARCHAR(255)  NOT NULL,
    currency       CHAR(3)       NOT NULL DEFAULT 'ARS' CHECK (currency = 'ARS'),
    balance        NUMERIC(19,4) NOT NULL DEFAULT 0,
    status         VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT accounts_user_balance_non_negative CHECK (type = 'SYSTEM' OR balance >= 0),
    CONSTRAINT accounts_user_has_owner CHECK (type = 'SYSTEM' OR user_id IS NOT NULL)
);

-- Una cuenta por usuario
CREATE UNIQUE INDEX accounts_one_per_user ON accounts (user_id) WHERE type = 'USER';

CREATE TABLE ledger_entries (
    id                 UUID          PRIMARY KEY,
    account_id         UUID          NOT NULL REFERENCES accounts (id),
    reference_type     VARCHAR(10)   NOT NULL CHECK (reference_type IN ('TRANSFER', 'DEPOSIT')),
    reference_id       UUID          NOT NULL,
    entry_type         VARCHAR(10)   NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT', 'REVERSAL', 'DEPOSIT')),
    amount             NUMERIC(19,4) NOT NULL CHECK (amount <> 0),
    balance_after      NUMERIC(19,4) NOT NULL,
    counterparty_alias VARCHAR(30),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_unique_movement UNIQUE (account_id, reference_id, entry_type),
    CONSTRAINT ledger_entries_sign CHECK (
        (entry_type = 'DEBIT' AND amount < 0)
        OR (entry_type IN ('CREDIT', 'REVERSAL') AND amount > 0)
        OR entry_type = 'DEPOSIT'
    )
);

CREATE INDEX ledger_entries_account_created ON ledger_entries (account_id, created_at DESC);

-- Los asientos son inmutables: nunca UPDATE/DELETE
CREATE FUNCTION prevent_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is immutable (% not allowed)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();

CREATE TABLE transfer_operations (
    id                UUID          PRIMARY KEY,
    transfer_id       UUID          NOT NULL,
    step              VARCHAR(10)   NOT NULL CHECK (step IN ('DEBIT', 'CREDIT', 'REFUND')),
    outcome           VARCHAR(10)   NOT NULL CHECK (outcome IN ('SUCCESS', 'REJECTED')),
    amount            NUMERIC(19,4) NOT NULL,
    from_account_id   UUID          NOT NULL,
    to_account_id     UUID          NOT NULL,
    reply_routing_key VARCHAR(64)   NOT NULL,
    reply_type        VARCHAR(32)   NOT NULL,
    reply_payload     JSONB         NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT transfer_operations_unique_step UNIQUE (transfer_id, step)
);

CREATE TABLE deposits (
    id              UUID          PRIMARY KEY,
    account_id      UUID          NOT NULL REFERENCES accounts (id),
    requested_by    VARCHAR(64)   NOT NULL,
    source          VARCHAR(12)   NOT NULL CHECK (source IN ('DEMO_FAUCET', 'ADMIN')),
    idempotency_key VARCHAR(64)   NOT NULL,
    request_hash    CHAR(64)      NOT NULL,
    amount          NUMERIC(19,4) NOT NULL CHECK (amount > 0 AND amount <= 1000000),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT deposits_unique_key UNIQUE (requested_by, idempotency_key)
);

CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY,
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(40)  NOT NULL,
    routing_key  VARCHAR(64)  NOT NULL,
    payload      JSONB        NOT NULL,
    status       VARCHAR(8)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT')),
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_events_status_created ON outbox_events (status, created_at);

CREATE TABLE processed_messages (
    message_id   UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id            UUID         PRIMARY KEY,
    actor_user_id VARCHAR(64)  NOT NULL,
    action        VARCHAR(40)  NOT NULL,
    target_type   VARCHAR(20)  NOT NULL,
    target_id     VARCHAR(64)  NOT NULL,
    details       JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Cuenta de sistema TREASURY (contrapartida de los depósitos; puede quedar en negativo).
-- account_number sin cifrar a propósito: no es un dato de usuario y el converter lo deja pasar.
INSERT INTO accounts (id, user_id, type, alias, account_number, balance)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM', 'treasury', 'SYSTEM-TREASURY', 0);
