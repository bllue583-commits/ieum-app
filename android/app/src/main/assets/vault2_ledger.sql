-- Additive ledger for a NEW encrypted store. Never applied to a v1 database.
CREATE TABLE IF NOT EXISTS vault2_initialization(
 owner_key TEXT PRIMARY KEY, initialization_id TEXT NOT NULL UNIQUE, key_alias TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN('PENDING','READY')));
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_initialization_identity BEFORE UPDATE ON vault2_initialization WHEN NEW.owner_key!=OLD.owner_key OR NEW.initialization_id!=OLD.initialization_id OR NEW.key_alias!=OLD.key_alias BEGIN SELECT RAISE(ABORT,'IMMUTABLE_INITIALIZATION_INTENT'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_initialization_no_delete BEFORE DELETE ON vault2_initialization BEGIN SELECT RAISE(ABORT,'IMMUTABLE_INITIALIZATION_INTENT'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_initialization_monotonic BEFORE UPDATE ON vault2_initialization WHEN OLD.state='READY' AND NEW.state!='READY' BEGIN SELECT RAISE(ABORT,'IMMUTABLE_INITIALIZATION_STATE'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_initialization_insert_guard BEFORE INSERT ON vault2_initialization WHEN EXISTS(SELECT 1 FROM vault2_initialization WHERE owner_key=NEW.owner_key OR initialization_id=NEW.initialization_id) BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM vault2_initialization WHERE owner_key=NEW.owner_key AND initialization_id=NEW.initialization_id AND key_alias=NEW.key_alias AND state=NEW.state) THEN RAISE(ABORT,'IMMUTABLE_INITIALIZATION_INTENT') END;
 SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TABLE IF NOT EXISTS vault2_refs(
 recording_id TEXT NOT NULL, source_version INTEGER NOT NULL,
 owner_key TEXT NOT NULL, plaintext_sha256 TEXT NOT NULL, plaintext_bytes INTEGER NOT NULL,
 key_alias TEXT NOT NULL, ciphertext_sha256 TEXT NOT NULL, storage_bytes INTEGER NOT NULL,
 PRIMARY KEY(recording_id,source_version), UNIQUE(ciphertext_sha256),
 FOREIGN KEY(recording_id,source_version) REFERENCES source_versions(recording_id,source_version),
 CHECK(plaintext_bytes>0 AND plaintext_bytes<=25000000),
 CHECK(storage_bytes>plaintext_bytes AND storage_bytes<=plaintext_bytes+1052),
 CHECK(length(plaintext_sha256)=64 AND plaintext_sha256 NOT GLOB '*[^0-9a-f]*'),
 CHECK(length(ciphertext_sha256)=64 AND ciphertext_sha256 NOT GLOB '*[^0-9a-f]*'));
-- statement-break
CREATE TABLE IF NOT EXISTS vault2_health(ciphertext_sha256 TEXT PRIMARY KEY REFERENCES vault2_refs(ciphertext_sha256), status TEXT NOT NULL, checked_at_ms INTEGER NOT NULL);
-- statement-break
CREATE TABLE IF NOT EXISTS vault2_pending(
 event_id TEXT PRIMARY KEY, owner_key TEXT NOT NULL, request_digest TEXT NOT NULL,
 metadata_json TEXT NOT NULL, fence_json TEXT NOT NULL,
 plaintext_sha256 TEXT NOT NULL, plaintext_bytes INTEGER NOT NULL, source_version INTEGER NOT NULL,
 key_alias TEXT NOT NULL, ciphertext_sha256 TEXT, storage_bytes INTEGER,
 completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1)));
-- statement-break
CREATE TABLE IF NOT EXISTS vault2_intake_receipts(
 event_id TEXT PRIMARY KEY REFERENCES receipts(event_id), owner_key TEXT NOT NULL,
 request_digest TEXT NOT NULL, recording_id TEXT NOT NULL, source_version INTEGER NOT NULL,
 ciphertext_sha256 TEXT NOT NULL REFERENCES vault2_refs(ciphertext_sha256));
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_ref_insert_binding BEFORE INSERT ON vault2_refs BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id WHERE r.recording_id=NEW.recording_id AND r.owner_key=NEW.owner_key AND v.source_version=NEW.source_version AND v.audio_sha256=NEW.plaintext_sha256 AND v.byte_count=NEW.plaintext_bytes) THEN RAISE(ABORT,'VAULT_SOURCE_BINDING_MISMATCH') END;
END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_ref_no_update BEFORE UPDATE ON vault2_refs BEGIN SELECT RAISE(ABORT,'IMMUTABLE_VAULT_REFERENCE'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_ref_no_delete BEFORE DELETE ON vault2_refs BEGIN SELECT RAISE(ABORT,'IMMUTABLE_VAULT_REFERENCE'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_ref_insert_guard BEFORE INSERT ON vault2_refs WHEN EXISTS(SELECT 1 FROM vault2_refs WHERE (recording_id=NEW.recording_id AND source_version=NEW.source_version) OR ciphertext_sha256=NEW.ciphertext_sha256) BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM vault2_refs WHERE recording_id=NEW.recording_id AND source_version=NEW.source_version AND owner_key=NEW.owner_key AND plaintext_sha256=NEW.plaintext_sha256 AND plaintext_bytes=NEW.plaintext_bytes AND key_alias=NEW.key_alias AND ciphertext_sha256=NEW.ciphertext_sha256 AND storage_bytes=NEW.storage_bytes) THEN RAISE(ABORT,'IMMUTABLE_VAULT_REFERENCE') END;
 SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_receipt_no_update BEFORE UPDATE ON vault2_intake_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_VAULT_RECEIPT'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_receipt_no_delete BEFORE DELETE ON vault2_intake_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_VAULT_RECEIPT'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_receipt_insert_guard BEFORE INSERT ON vault2_intake_receipts WHEN EXISTS(SELECT 1 FROM vault2_intake_receipts WHERE event_id=NEW.event_id) BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM vault2_intake_receipts WHERE event_id=NEW.event_id AND owner_key=NEW.owner_key AND request_digest=NEW.request_digest AND recording_id=NEW.recording_id AND source_version=NEW.source_version AND ciphertext_sha256=NEW.ciphertext_sha256) THEN RAISE(ABORT,'IMMUTABLE_VAULT_RECEIPT') END;
 SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_pending_frozen BEFORE UPDATE ON vault2_pending WHEN NEW.event_id!=OLD.event_id OR NEW.owner_key!=OLD.owner_key OR NEW.request_digest!=OLD.request_digest OR NEW.metadata_json!=OLD.metadata_json OR NEW.fence_json!=OLD.fence_json OR NEW.plaintext_sha256!=OLD.plaintext_sha256 OR NEW.plaintext_bytes!=OLD.plaintext_bytes OR NEW.source_version!=OLD.source_version OR NEW.key_alias!=OLD.key_alias BEGIN SELECT RAISE(ABORT,'IMMUTABLE_INTAKE_SNAPSHOT'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_pending_insert_guard BEFORE INSERT ON vault2_pending WHEN EXISTS(SELECT 1 FROM vault2_pending WHERE event_id=NEW.event_id) BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM vault2_pending WHERE event_id=NEW.event_id AND owner_key=NEW.owner_key AND request_digest=NEW.request_digest AND metadata_json=NEW.metadata_json AND fence_json=NEW.fence_json AND plaintext_sha256=NEW.plaintext_sha256 AND plaintext_bytes=NEW.plaintext_bytes AND source_version=NEW.source_version AND key_alias=NEW.key_alias AND ciphertext_sha256 IS NEW.ciphertext_sha256 AND storage_bytes IS NEW.storage_bytes AND completed=NEW.completed) THEN RAISE(ABORT,'IMMUTABLE_INTAKE_SNAPSHOT') END;
 SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TABLE IF NOT EXISTS vault2_dispatch_reservations(
 processing_id TEXT PRIMARY KEY, recording_id TEXT NOT NULL, owner_key TEXT NOT NULL,
 source_version INTEGER NOT NULL, lease_epoch INTEGER NOT NULL, visibility_revision INTEGER NOT NULL,
 FOREIGN KEY(recording_id,source_version) REFERENCES source_versions(recording_id,source_version));
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_dispatch_no_update BEFORE UPDATE ON vault2_dispatch_reservations BEGIN SELECT RAISE(ABORT,'IMMUTABLE_DISPATCH_RESERVATION'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_dispatch_no_delete BEFORE DELETE ON vault2_dispatch_reservations BEGIN SELECT RAISE(ABORT,'IMMUTABLE_DISPATCH_RESERVATION'); END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_dispatch_insert_guard BEFORE INSERT ON vault2_dispatch_reservations WHEN EXISTS(SELECT 1 FROM vault2_dispatch_reservations WHERE processing_id=NEW.processing_id) BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM vault2_dispatch_reservations WHERE processing_id=NEW.processing_id AND recording_id=NEW.recording_id AND owner_key=NEW.owner_key AND source_version=NEW.source_version AND lease_epoch=NEW.lease_epoch AND visibility_revision=NEW.visibility_revision) THEN RAISE(ABORT,'IMMUTABLE_DISPATCH_RESERVATION') END;
 SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_claim_guard BEFORE INSERT ON claim_commands BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM vault2_refs v JOIN vault2_health h ON h.ciphertext_sha256=v.ciphertext_sha256 WHERE v.recording_id=NEW.recording_id AND v.source_version=NEW.source_version AND v.plaintext_sha256=NEW.audio_sha256 AND h.status='VERIFIED') THEN RAISE(ABORT,'ENCRYPTED_SOURCE_NOT_VERIFIED') END;
END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_result_guard BEFORE INSERT ON result_commands BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM vault2_refs v JOIN vault2_health h ON h.ciphertext_sha256=v.ciphertext_sha256 WHERE v.recording_id=NEW.recording_id AND v.source_version=NEW.source_version AND v.plaintext_sha256=NEW.audio_sha256 AND h.status='VERIFIED') THEN RAISE(ABORT,'ENCRYPTED_SOURCE_NOT_VERIFIED') END;
END;
-- statement-break
CREATE TRIGGER IF NOT EXISTS vault2_accept_guard BEFORE INSERT ON accept_commands BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM summary_versions s JOIN vault2_refs v ON v.recording_id=s.recording_id AND v.source_version=s.source_version JOIN vault2_health h ON h.ciphertext_sha256=v.ciphertext_sha256 WHERE s.recording_id=NEW.recording_id AND s.summary_version=NEW.summary_version AND v.plaintext_sha256=s.audio_sha256 AND h.status='VERIFIED') THEN RAISE(ABORT,'ENCRYPTED_SOURCE_NOT_VERIFIED') END;
END;
