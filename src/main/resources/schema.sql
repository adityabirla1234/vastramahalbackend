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
    deposit_payment_method VARCHAR(40), -- Cash|UPI|Card; how the security deposit was paid, entered at pickup.
                                         -- NULL = no deposit / older booking.
    advance_amount  DECIMAL(10,2)       NOT NULL DEFAULT 0,
    advance_payment_method VARCHAR(40), -- Cash|UPI|Card; how the advance was paid. NULL = no advance / older booking.
    balance_amount  DECIMAL(10,2)       NOT NULL DEFAULT 0,
    status          VARCHAR(20)         NOT NULL DEFAULT 'PENDING', -- PENDING|CONFIRMED|PICKED_UP|RETURNED|CANCELLED
    notes           TEXT,
    fitting_work    TEXT,               -- Alteration instructions for this item row. Only ever set AFTER the
                                         -- bill exists (Booking History "Edit" flow), never at creation. NULL = none.
    idempotency_key VARCHAR(80),
    created_by      BIGINT,
    group_id        VARCHAR(40),        -- Section 3.7 multi-item booking: every row created
                                         -- together in one New Booking session shares this
                                         -- value. NULL for a standalone single-item booking.
                                         -- Not a foreign key -- there is no separate booking
                                         -- group table, a "group" is just every row sharing this.
    bill_number     VARCHAR(40),        -- The shop's own "Bill No", typed in on the New Booking
                                         -- form. Same value on every row of a group booking.
                                         -- Optional, and NOT unique (human-entered reference).
    deposit_return_status VARCHAR(20),  -- RETURNED|NOT_RETURNED|DAMAGED, set only when this
                                         -- booking (or every row of its group) is finally marked
                                         -- RETURNED. Informational only -- never folded into
                                         -- rental_amount/balance_amount.
    deposit_return_reason  VARCHAR(500), -- Why the deposit was NOT handed back (required when staff answer "No" at
                                         -- return time). NULL otherwise.
    settlement_status      VARCHAR(10), -- SETTLED|DUE, set alongside deposit_return_status above.
    version         BIGINT              NOT NULL DEFAULT 0,
    created_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_booking_number (booking_number),
    UNIQUE KEY uq_idempotency_key (idempotency_key),
    CONSTRAINT fk_booking_item FOREIGN KEY (item_id) REFERENCES items(id),
    CONSTRAINT fk_booking_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT chk_booking_status CHECK (status IN ('PENDING','CONFIRMED','PICKED_UP','RETURNED','CANCELLED')),
    CONSTRAINT chk_booking_dates CHECK (return_date >= pickup_date),
    CONSTRAINT chk_booking_deposit_return_status CHECK (deposit_return_status IN ('RETURNED','NOT_RETURNED','DAMAGED')),
    CONSTRAINT chk_booking_settlement_status CHECK (settlement_status IN ('SETTLED','DUE'))
);

-- Critical index for the overlap/availability query (Section 10).
CREATE INDEX idx_booking_item_dates ON bookings (item_id, pickup_date, return_date);

-- Section 3.7 redesign, migrations for an already-deployed DB. Both are
-- safe to re-run alongside the CREATE TABLE statements above, same
-- ADD/DROP COLUMN IF [NOT] EXISTS pattern already used for owners.fcm_token.
ALTER TABLE items DROP COLUMN IF EXISTS deposit;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS group_id VARCHAR(40);
-- Plain CREATE INDEX (no IF NOT EXISTS -- unlike ADD/DROP COLUMN, this
-- MySQL version doesn't support that clause on CREATE INDEX). Skip/comment
-- this line out if re-running against a DB that already has the index.
CREATE INDEX idx_booking_group ON bookings (group_id);

-- "Bill No" on the New Booking form: migration for an already-deployed DB
-- (same ADD COLUMN IF NOT EXISTS pattern as above). Needed when
-- spring.jpa.hibernate.ddl-auto is `validate` (production posture) --
-- with `update` Hibernate adds the column itself on next startup.
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS bill_number VARCHAR(40);

-- "Fitting work" on each item of a bill in Booking History: migration for an
-- already-deployed DB (same ADD COLUMN IF NOT EXISTS pattern as above; with
-- ddl-auto=update Hibernate adds the column itself on next startup).
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS fitting_work TEXT;

-- Single "Mark picked up / returned" flow: the security deposit's payment
-- method (Cash/UPI/Card) and the reason given when it is NOT handed back.
-- Migration for an already-deployed DB, same ADD COLUMN IF NOT EXISTS pattern
-- as above; with ddl-auto=update Hibernate adds both columns itself on next
-- startup. Existing rows simply have NULL in both.
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS deposit_payment_method VARCHAR(40);
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS deposit_return_reason VARCHAR(500);

-- "Add accessory" on the New Booking form: the pant / jewellery / dupatta
-- that goes out alongside ONE booked item. Per booking row, not per bill --
-- a bill with four dresses has four independent accessory lists.
--
-- item_code/item_name/category are SNAPSHOTS copied off the inventory item
-- at booking time, not a live join (see BookingAccessory's class doc): an
-- old bill has to keep rendering what actually went out that day even after
-- the item is renamed, re-categorised or retired. item_id is kept purely so
-- the app can deep-link to the live product when it still exists, which is
-- also why its FK has no ON DELETE clause -- items are only ever
-- soft-deleted (ItemService.deleteItem), so the row never disappears.
--
-- The unique key is what makes the "same accessory ticked twice" case a
-- no-op rather than a duplicate bill line; BookingService.attachAccessories
-- already collapses them before they get here, this is the backstop.
CREATE TABLE IF NOT EXISTS booking_accessories (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id      BIGINT              NOT NULL,
    item_id         BIGINT              NOT NULL,
    item_code       VARCHAR(40)         NOT NULL,
    item_name       VARCHAR(150)        NOT NULL,
    category        VARCHAR(20)         NOT NULL,   -- PANT | JEWELLERY | DUPATTA
    created_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_booking_accessory (booking_id, item_id),
    CONSTRAINT fk_booking_accessory_booking FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_accessory_item FOREIGN KEY (item_id) REFERENCES items(id),
    CONSTRAINT chk_booking_accessory_category CHECK (category IN ('PANT','JEWELLERY','DUPATTA'))
);

CREATE TABLE IF NOT EXISTS payments (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id      BIGINT              NOT NULL,
    amount          DECIMAL(10,2)       NOT NULL,
    payment_date    DATE                NOT NULL,
    method          VARCHAR(40),
    notes           VARCHAR(300),
    -- Shared by every row written by ONE payment taken against a group
    -- booking; NULL for a payment against a standalone booking. Not a
    -- foreign key -- there is no group-payment table, exactly as there is
    -- no booking-group table (see bookings.group_id). Indexed because
    -- undoing a bill-level payment looks its rows up by this value.
    group_payment_ref VARCHAR(40),
    created_at      TIMESTAMP           DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_booking FOREIGN KEY (booking_id) REFERENCES bookings(id) ON DELETE CASCADE,
    KEY idx_payment_group_ref (group_payment_ref)
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

-- Idempotent offline creates: the app queues item/customer creates while
-- offline and replays them; this key (client-generated, stable across
-- retries) lets the server recognise a replay instead of making a duplicate.
-- Same ADD COLUMN IF NOT EXISTS pattern as above; with ddl-auto=update Hibernate
-- adds the column and its unique index itself on next startup. Existing rows
-- keep NULL, and MySQL allows any number of NULLs in a unique index.
ALTER TABLE items ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(80);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(80);
-- Plain CREATE UNIQUE INDEX (no IF NOT EXISTS on this MySQL version) -- skip
-- if the index already exists.
CREATE UNIQUE INDEX uq_item_idempotency_key ON items (idempotency_key);
CREATE UNIQUE INDEX uq_customer_idempotency_key ON customers (idempotency_key);
