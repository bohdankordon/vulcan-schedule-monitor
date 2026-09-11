ALTER TABLE telegram_identity
    ADD COLUMN language_code VARCHAR(2) NOT NULL DEFAULT 'en';

ALTER TABLE telegram_identity
    ADD CONSTRAINT ck_telegram_identity_language_code CHECK (
        language_code IN ('en', 'ru', 'uk', 'pl')
    );
