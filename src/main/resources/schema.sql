-- ============================================================================
-- Wedding Dress & Jewellery Rental Management - Schema
-- Matches Section 10 of the development plan.
-- Run once against the Aiven MySQL free-tier instance (or via Flyway/Liquibase
-- later if you want migrations instead of a raw schema.sql).
-- ============================================================================

CREATE TABLE IF NOT EXISTS owners (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(120)        NOT NULL,
    role            VARCHAR(20)         NOT NULL,   -- ADMIN | VIEWER
    device_token    VARCHAR(255),
    device_status   VARCHAR(20)         DEFAULT 'ACTIVE',
    fcm_token       VARCHAR(255),       -- current Firebase Cloud Messaging registration token, if any
    created_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_owner_role CHECK (role IN ('ADMIN', 'VIEWER'))
);

-- Existing deployments already have `owners` without fcm_token (this file
-- only runs CREATE TABLE IF NOT EXISTS on a fresh DB) -- MySQL 8.0.29+
-- (Aiven's default) supports IF NOT EXISTS on ADD COLUMN, so this is safe
-- to leave in place and re-run alongside the CREATE TABLE above.
ALTER TABLE owners ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(255);

CREATE TABLE IF NOT EXISTS items (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_code       VARCHAR(40)         NOT NULL,
    name            VARCHAR(150)        NOT NULL,
    category        VARCHAR(80),
    sub_category    VARCHAR(80),
    size            VARCHAR(30),
    color           VARCHAR(40),
    rental_price    DECIMAL(10,2)       NOT NULL DEFAULT 0,
    deposit         DECIMAL(10,2)       NOT NULL DEFAULT 0,
    description     TEXT,
    status          VARCHAR(20)         NOT NULL DEFAULT 'ACTIVE', -- ACTIVE|INACTIVE|MAINTENANCE|LOST|DAMAGED
    is_deleted      BOOLEAN             NOT NULL DEFAULT FALSE,    -- soft delete
    version         BIGINT              NOT NULL DEFAULT 0,        -- optimistic concurrency
    created_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_item_code (item_code),
    CONSTRAINT chk_item_status CHECK (status IN ('ACTIVE','INACTIVE','MAINTENANCE','LOST','DAMAGED'))
);

CREATE TABLE IF NOT EXISTS item_images (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id         BIGINT              NOT NULL,
    image_url       VARCHAR(500)        NOT NULL,   -- object storage URL/key, never binary in MySQL
    display_order   INT                 NOT NULL DEFAULT 0,
    is_primary      BOOLEAN             NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_image_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS customers (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(150)        NOT NULL,
    phone           VARCHAR(20)         NOT NULL,
    address         VARCHAR(300),
    notes           TEXT,
    is_deleted      BOOLEAN             NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_customer_phone (phone)
);

CREATE TABLE IF NOT EXISTS bookings (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_number  VARCHAR(40)         NOT NULL,
    item_id         BIGINT              NOT NULL,
    customer_id     BIGINT              NOT NULL,
    pickup_date     DATE                NOT NULL,
    event_date      DATE,
    return_date     DATE                NOT NULL,
    rental_amount   DECIMAL(10,2)       NOT NULL DEFAULT 0,
    deposit_amount  DECIMAL(10,2)       NOT NULL DEFAULT 0,
    advance_amount  DECIMAL(10,2)       NOT NULL DEFAULT 0,
    balance_amount  DECIMAL(10,2)       NOT NULL DEFAULT 0,
    status          VARCHAR(20)         NOT NULL DEFAULT 'PENDING', -- PENDING|CONFIRMED|PICKED_UP|RETURNED|CANCELLED
    notes           TEXT,
    idempotency_key VARCHAR(80),
    created_by      BIGINT,
    version         BIGINT              NOT NULL DEFAULT 0,
    created_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_booking_number (booking_number),
    UNIQUE KEY uq_idempotency_key (idempotency_key),
    CONSTRAINT fk_booking_item FOREIGN KEY (item_id) REFERENCES items(id),
    CONSTRAINT fk_booking_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT chk_booking_status CHECK (status IN ('PENDING','CONFIRMED','PICKED_UP','RETURNED','CANCELLED')),
    CONSTRAINT chk_booking_dates CHECK (return_date >= pickup_date)
);

-- Critical index for the overlap/availability query (Section 10).
CREATE INDEX idx_booking_item_dates ON bookings (item_id, pickup_date, return_date);

CREATE TABLE IF NOT EXISTS payments (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id      BIGINT              NOT NULL,
    amount          DECIMAL(10,2)       NOT NULL,
    payment_date    DATE                NOT NULL,
    method          VARCHAR(40),
    notes           VARCHAR(300),
    created_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_booking FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id        BIGINT,
    action          VARCHAR(60)         NOT NULL,   -- e.g. BOOKING_CANCELLED, ITEM_DELETED
    entity_type     VARCHAR(60)         NOT NULL,
    entity_id       BIGINT              NOT NULL,
    before_state    JSON,
    after_state     JSON,
    created_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sync_metadata (
    device_id       VARCHAR(80)         PRIMARY KEY,
    last_sync_at    TIMESTAMP,
    last_change_marker BIGINT           DEFAULT 0
);

-- Layer 3 of the plan: an external, out-of-database trail (Telegram
-- messages / Google Sheet rows) of every mutation, so the shop's data can
-- be reconstructed even if this Aiven instance is lost entirely. One row
-- per mutation, tracking each of the two delivery channels independently
-- since they're redundant WITH EACH OTHER, not with the row itself -- see
-- RedundantTrailEntry / RedundantTrailService. Only ever populated when at
-- least one channel is enabled (app.redundant-trail.{telegram,sheets}.enabled);
-- on the default zero-config posture this table stays empty.
CREATE TABLE IF NOT EXISTS redundant_trail_entries (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    action              VARCHAR(60)         NOT NULL,
    entity_type         VARCHAR(60)         NOT NULL,
    entity_id           BIGINT              NOT NULL,
    actor_id            BIGINT,
    before_state        JSON,
    after_state         JSON,
    telegram_status     VARCHAR(20)         NOT NULL DEFAULT 'DISABLED', -- PENDING|SENT|FAILED|DISABLED
    telegram_attempts   INT                 NOT NULL DEFAULT 0,
    telegram_last_error VARCHAR(500),
    sheets_status       VARCHAR(20)         NOT NULL DEFAULT 'DISABLED', -- PENDING|SENT|FAILED|DISABLED
    sheets_attempts     INT                 NOT NULL DEFAULT 0,
    sheets_last_error   VARCHAR(500),
    created_at          TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP           DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_trail_telegram_status CHECK (telegram_status IN ('PENDING','SENT','FAILED','DISABLED')),
    CONSTRAINT chk_trail_sheets_status CHECK (sheets_status IN ('PENDING','SENT','FAILED','DISABLED'))
);

-- Backs RedundantTrailEntryRepository.findIdsWithAnyPendingChannel (the
-- retry sweep's query) -- narrow enough to stay cheap even as this table
-- grows, since PENDING rows are always a small minority once delivery
-- is mostly keeping up.
CREATE INDEX idx_trail_telegram_status ON redundant_trail_entries (telegram_status);
CREATE INDEX idx_trail_sheets_status ON redundant_trail_entries (sheets_status);
