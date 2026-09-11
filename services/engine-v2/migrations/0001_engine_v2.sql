CREATE TABLE IF NOT EXISTS engine_v2_domains (
    name TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS engine_v2_mailboxes (
    address TEXT PRIMARY KEY,
    domain TEXT NOT NULL REFERENCES engine_v2_domains(name) ON DELETE RESTRICT,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    quota_bytes BIGINT NOT NULL CHECK (quota_bytes >= 0),
    suspended BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS engine_v2_aliases (
    alias TEXT PRIMARY KEY,
    destination TEXT NOT NULL REFERENCES engine_v2_mailboxes(address) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS engine_v2_messages (
    id UUID PRIMARY KEY,
    uid BIGSERIAL UNIQUE NOT NULL,
    mailbox_address TEXT NOT NULL REFERENCES engine_v2_mailboxes(address) ON DELETE CASCADE,
    folder TEXT NOT NULL DEFAULT 'INBOX',
    sender TEXT NOT NULL,
    recipients TEXT[] NOT NULL DEFAULT '{}',
    cc TEXT[] NOT NULL DEFAULT '{}',
    subject TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL DEFAULT '',
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    seen BOOLEAN NOT NULL DEFAULT FALSE,
    size_bytes BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS engine_v2_messages_mailbox_uid_idx
    ON engine_v2_messages(mailbox_address, folder, uid DESC);

CREATE TABLE IF NOT EXISTS engine_v2_outgoing_queue (
    id UUID PRIMARY KEY,
    sender TEXT NOT NULL,
    recipients TEXT[] NOT NULL,
    cc TEXT[] NOT NULL DEFAULT '{}',
    subject TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL DEFAULT '',
    attempts INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL DEFAULT 'QUEUED',
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS engine_v2_queue_due_idx
    ON engine_v2_outgoing_queue(state, next_attempt_at);
