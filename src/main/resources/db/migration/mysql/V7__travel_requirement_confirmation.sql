ALTER TABLE travel_requirement_drafts ADD COLUMN confirmation_idempotency_key VARCHAR(128) NULL;
ALTER TABLE travel_requirement_drafts ADD COLUMN confirmation_snapshot_json MEDIUMTEXT NULL;
ALTER TABLE travel_requirement_drafts ADD COLUMN timezone VARCHAR(64) NULL;
ALTER TABLE travel_requirement_drafts ADD COLUMN answers_json MEDIUMTEXT NULL;
CREATE UNIQUE INDEX uk_travel_requirement_confirmation ON travel_requirement_drafts(user_id, confirmation_idempotency_key);
