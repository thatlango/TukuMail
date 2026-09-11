ALTER TABLE engine_v2_messages
    ADD COLUMN IF NOT EXISTS raw_message BYTEA;

ALTER TABLE engine_v2_outgoing_queue
    ADD COLUMN IF NOT EXISTS raw_message BYTEA;

CREATE INDEX IF NOT EXISTS engine_v2_messages_received_idx
    ON engine_v2_messages(mailbox_address, received_at DESC);
