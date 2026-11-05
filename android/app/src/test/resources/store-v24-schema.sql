-- v2.4 (versionCode 1033, commit 9668e98) store shape, generated from that commit's sources: call_core_schema.sql (25 MB observe
-- check), vault2_ledger.sql (25 MB vault2_refs check), GeneralLocalStore overlay, discovery and vault_pruned tables, ProtectedDiscoveryQueue,
-- CallTranscriptionStore and CallRecordStore (SCHEMA and the columns v2.4 added). Test fixture only (r12-4, DESIGN_V4 2.4-1).
-- Call-core internal SQLite schema v1. NOT a worker wire schema.
-- Split only on the statement-break marker, never on semicolons in triggers.
CREATE TABLE board_state(owner_key TEXT PRIMARY KEY, revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0));
-- statement-break
CREATE TABLE folder_grants(grant_id TEXT PRIMARY KEY, owner_key TEXT NOT NULL REFERENCES board_state(owner_key), root_uri TEXT NOT NULL, policy_revision INTEGER NOT NULL CHECK(policy_revision>0), active INTEGER NOT NULL CHECK(active IN(0,1)), selected_by_user INTEGER NOT NULL CHECK(selected_by_user=1));
-- statement-break
CREATE TABLE tasks(task_id TEXT PRIMARY KEY, owner_key TEXT NOT NULL REFERENCES board_state(owner_key), case_id TEXT NOT NULL, person_id TEXT, company_id TEXT, task_revision INTEGER NOT NULL DEFAULT 0, status TEXT CHECK(status IN('open','waiting','in_progress','needs_confirmation','done') OR status IS NULL), manual_locks INTEGER NOT NULL DEFAULT 0, assignee TEXT, due TEXT);
-- statement-break
CREATE TABLE content_blobs(audio_sha256 TEXT PRIMARY KEY CHECK(length(audio_sha256)=64 AND audio_sha256 NOT GLOB '*[^0-9a-f]*'), byte_count INTEGER NOT NULL CHECK(byte_count>0));
-- statement-break
CREATE TABLE recordings(recording_id TEXT PRIMARY KEY, owner_key TEXT NOT NULL REFERENCES board_state(owner_key), grant_id TEXT NOT NULL REFERENCES folder_grants(grant_id), document_id TEXT NOT NULL, display_name TEXT NOT NULL, mime_type TEXT NOT NULL, byte_count INTEGER NOT NULL, modified_at_ms INTEGER, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, source_seq INTEGER NOT NULL, source_version INTEGER NOT NULL, head_revision INTEGER NOT NULL, phone_group_ref TEXT, person_id TEXT, company_id TEXT, task_id TEXT REFERENCES tasks(task_id), original_started_at_ms INTEGER, original_day TEXT, user_revision INTEGER NOT NULL DEFAULT 0, lock_revision INTEGER NOT NULL DEFAULT 0, edited_lock INTEGER NOT NULL DEFAULT 0, completed_lock INTEGER NOT NULL DEFAULT 0, manual_text TEXT, manual_status TEXT CHECK(manual_status IN('open','waiting','in_progress','needs_confirmation','done') OR manual_status IS NULL), effective_summary_version INTEGER, presence TEXT NOT NULL DEFAULT 'PRESENT' CHECK(presence IN('PRESENT','MISSING')), UNIQUE(owner_key,grant_id,document_id), FOREIGN KEY(recording_id,effective_summary_version) REFERENCES summary_versions(recording_id,summary_version));
-- statement-break
CREATE TABLE source_versions(recording_id TEXT NOT NULL REFERENCES recordings(recording_id), source_version INTEGER NOT NULL CHECK(source_version>0), audio_sha256 TEXT NOT NULL REFERENCES content_blobs(audio_sha256), byte_count INTEGER NOT NULL, observed_at_ms INTEGER NOT NULL, observation_event_id TEXT NOT NULL UNIQUE, PRIMARY KEY(recording_id,source_version));
-- statement-break
CREATE TABLE processing(recording_id TEXT NOT NULL, source_version INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN('PENDING','TRANSCRIBING','REVIEW','FAILED','HELD_UNKNOWN_TIME','HELD_UNLINKED','RETIRED','WAITING_SOURCE')), attempts INTEGER NOT NULL DEFAULT 0, lease_epoch INTEGER NOT NULL DEFAULT 0, processing_id TEXT UNIQUE, lease_until_ms INTEGER, transcriber_version TEXT, cached_transcript_id TEXT, base_user_revision INTEGER, base_lock_revision INTEGER, base_task_revision INTEGER, base_board_revision INTEGER, base_summary_version INTEGER, base_task_id TEXT, base_case_id TEXT, base_person_id TEXT, base_company_id TEXT, base_original_started_at_ms INTEGER, error_code TEXT, PRIMARY KEY(recording_id,source_version), FOREIGN KEY(recording_id,source_version) REFERENCES source_versions(recording_id,source_version));
-- statement-break
CREATE TABLE transcript_versions(transcript_id TEXT PRIMARY KEY, audio_sha256 TEXT NOT NULL REFERENCES content_blobs(audio_sha256), transcriber_version TEXT NOT NULL, text_sha256 TEXT NOT NULL CHECK(length(text_sha256)=64 AND text_sha256 NOT GLOB '*[^0-9a-f]*'), raw_text TEXT NOT NULL CHECK(length(raw_text)>0 AND length(raw_text)<=20000), quality_state TEXT NOT NULL CHECK(quality_state IN('raw_review_required','quality_blocked')), created_at_ms INTEGER NOT NULL, UNIQUE(audio_sha256,transcriber_version,text_sha256));
-- statement-break
CREATE TABLE source_transcripts(recording_id TEXT NOT NULL, source_version INTEGER NOT NULL, transcript_id TEXT NOT NULL REFERENCES transcript_versions(transcript_id), PRIMARY KEY(recording_id,source_version,transcript_id), FOREIGN KEY(recording_id,source_version) REFERENCES source_versions(recording_id,source_version));
-- statement-break
CREATE TABLE summary_versions(recording_id TEXT NOT NULL, summary_version INTEGER NOT NULL CHECK(summary_version>0), source_version INTEGER NOT NULL, audio_sha256 TEXT NOT NULL, transcript_id TEXT NOT NULL REFERENCES transcript_versions(transcript_id), text_sha256 TEXT NOT NULL, summary_sha256 TEXT NOT NULL CHECK(length(summary_sha256)=64 AND summary_sha256 NOT GLOB '*[^0-9a-f]*'), summary_text TEXT NOT NULL CHECK(length(summary_text)>0 AND length(summary_text)<=3000), processing_id TEXT NOT NULL UNIQUE, lease_epoch INTEGER NOT NULL, producer_version TEXT NOT NULL, source_pack_sha256 TEXT NOT NULL, source_pack_json TEXT NOT NULL, candidate_v1_json TEXT NOT NULL, change_diff_json TEXT NOT NULL, review_reasons_json TEXT NOT NULL, repository_review_code TEXT NOT NULL, base_user_revision INTEGER NOT NULL, base_lock_revision INTEGER NOT NULL, base_task_revision INTEGER NOT NULL, base_board_revision INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, PRIMARY KEY(recording_id,summary_version), FOREIGN KEY(recording_id,source_version) REFERENCES source_versions(recording_id,source_version));
-- statement-break
CREATE TABLE content_leases(audio_sha256 TEXT NOT NULL, transcriber_version TEXT NOT NULL, recording_id TEXT NOT NULL, source_version INTEGER NOT NULL, processing_id TEXT NOT NULL, lease_until_ms INTEGER NOT NULL, PRIMARY KEY(audio_sha256,transcriber_version));
-- statement-break
CREATE TABLE canonical_storage(audio_sha256 TEXT PRIMARY KEY REFERENCES content_blobs(audio_sha256),storage_ref TEXT NOT NULL UNIQUE,byte_count INTEGER NOT NULL,available INTEGER NOT NULL CHECK(available IN(0,1)));
-- statement-break
CREATE TABLE manual_history(event_id TEXT PRIMARY KEY, recording_id TEXT NOT NULL REFERENCES recordings(recording_id), user_revision INTEGER NOT NULL, before_text TEXT, after_text TEXT, before_status TEXT, after_status TEXT, restored_summary_version INTEGER, created_at_ms INTEGER NOT NULL);
-- statement-break
CREATE TABLE task_history(event_id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(task_id), task_revision INTEGER NOT NULL, before_status TEXT, after_status TEXT, created_at_ms INTEGER NOT NULL);
-- statement-break
CREATE TABLE receipts(event_id TEXT PRIMARY KEY, operation TEXT NOT NULL, request_digest TEXT NOT NULL, recording_id TEXT, source_version INTEGER, head_revision INTEGER, audio_sha256 TEXT, summary_version INTEGER, lease_epoch INTEGER, processing_id TEXT, outcome TEXT NOT NULL, created_at_ms INTEGER NOT NULL);
-- statement-break
CREATE TABLE request_bindings(event_id TEXT PRIMARY KEY REFERENCES receipts(event_id),operation TEXT NOT NULL,request_json TEXT NOT NULL);
-- statement-break
CREATE VIEW recording_list AS SELECT r.recording_id,r.owner_key,r.phone_group_ref,r.person_id,r.company_id,r.task_id,r.original_started_at_ms,r.original_day,r.first_seen_ms,r.last_seen_ms,r.source_seq,r.source_version,r.head_revision,r.user_revision,r.lock_revision,r.edited_lock,r.completed_lock,r.presence,p.state,p.attempts,p.error_code,v.audio_sha256,r.effective_summary_version,CASE WHEN r.effective_summary_version IS NOT NULL AND s.source_version!=r.source_version THEN 1 ELSE 0 END AS effective_summary_stale FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version JOIN processing p ON p.recording_id=r.recording_id AND p.source_version=r.source_version LEFT JOIN summary_versions s ON s.recording_id=r.recording_id AND s.summary_version=r.effective_summary_version;
-- statement-break
CREATE TABLE observe_commands(event_id TEXT PRIMARY KEY, request_digest TEXT NOT NULL, recording_id TEXT NOT NULL, owner_key TEXT NOT NULL, grant_id TEXT NOT NULL, policy_revision INTEGER NOT NULL, document_id TEXT NOT NULL, display_name TEXT NOT NULL, mime_type TEXT NOT NULL, byte_count INTEGER NOT NULL, modified_at_ms INTEGER, source_seq INTEGER NOT NULL, phone_group_ref TEXT, person_id TEXT, company_id TEXT, task_id TEXT, original_started_at_ms INTEGER, audio_sha256 TEXT NOT NULL, now_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER observe_execute AFTER INSERT ON observe_commands BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM folder_grants WHERE grant_id=NEW.grant_id AND owner_key=NEW.owner_key AND active=1 AND selected_by_user=1 AND policy_revision=NEW.policy_revision) THEN RAISE(ABORT,'FOLDER_POLICY_CHANGED') END;
  SELECT CASE WHEN NEW.source_seq<1 OR NEW.byte_count<1 OR NEW.byte_count>25000000 THEN RAISE(ABORT,'INVALID_OBSERVATION') END;
  SELECT CASE WHEN NEW.task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM tasks WHERE task_id=NEW.task_id AND owner_key=NEW.owner_key AND person_id IS NEW.person_id AND company_id IS NEW.company_id) THEN RAISE(ABORT,'TASK_LINK_NOT_CONFIRMED') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM recordings WHERE recording_id=NEW.recording_id AND (owner_key!=NEW.owner_key OR grant_id!=NEW.grant_id OR document_id!=NEW.document_id)) OR EXISTS(SELECT 1 FROM recordings WHERE owner_key=NEW.owner_key AND grant_id=NEW.grant_id AND document_id=NEW.document_id AND recording_id!=NEW.recording_id) THEN RAISE(ABORT,'DOCUMENT_IDENTITY_CONFLICT') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM recordings WHERE recording_id=NEW.recording_id AND source_seq>=NEW.source_seq) THEN RAISE(ABORT,'STALE_OBSERVATION') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM recordings WHERE recording_id=NEW.recording_id AND (phone_group_ref IS NOT NEW.phone_group_ref OR person_id IS NOT NEW.person_id OR company_id IS NOT NEW.company_id OR task_id IS NOT NEW.task_id OR original_started_at_ms IS NOT NEW.original_started_at_ms)) THEN RAISE(ABORT,'METADATA_CONFIRMATION_REQUIRED') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM content_blobs WHERE audio_sha256=NEW.audio_sha256 AND byte_count!=NEW.byte_count) THEN RAISE(ABORT,'CONTENT_SIZE_CONFLICT') END;
  INSERT OR IGNORE INTO content_blobs VALUES(NEW.audio_sha256,NEW.byte_count);
  INSERT OR IGNORE INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,modified_at_ms,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,phone_group_ref,person_id,company_id,task_id,original_started_at_ms,original_day) VALUES(NEW.recording_id,NEW.owner_key,NEW.grant_id,NEW.document_id,NEW.display_name,NEW.mime_type,NEW.byte_count,NEW.modified_at_ms,NEW.now_ms,NEW.now_ms,0,0,0,NEW.phone_group_ref,NEW.person_id,NEW.company_id,NEW.task_id,NEW.original_started_at_ms,date(NEW.original_started_at_ms/1000,'unixepoch','+9 hours'));
  UPDATE processing SET state='RETIRED',lease_until_ms=NULL WHERE recording_id=NEW.recording_id AND source_version=(SELECT source_version FROM recordings WHERE recording_id=NEW.recording_id) AND state!='REVIEW' AND NOT EXISTS(SELECT 1 FROM source_versions WHERE recording_id=NEW.recording_id AND source_version=(SELECT source_version FROM recordings WHERE recording_id=NEW.recording_id) AND audio_sha256=NEW.audio_sha256);
  DELETE FROM content_leases WHERE recording_id=NEW.recording_id AND NOT EXISTS(SELECT 1 FROM source_versions WHERE recording_id=NEW.recording_id AND source_version=(SELECT source_version FROM recordings WHERE recording_id=NEW.recording_id) AND audio_sha256=NEW.audio_sha256);
  UPDATE recordings SET source_version=source_version+1 WHERE recording_id=NEW.recording_id AND NOT EXISTS(SELECT 1 FROM source_versions WHERE recording_id=NEW.recording_id AND source_version=recordings.source_version AND audio_sha256=NEW.audio_sha256);
  INSERT OR IGNORE INTO source_versions SELECT recording_id,source_version,NEW.audio_sha256,NEW.byte_count,NEW.now_ms,NEW.event_id FROM recordings WHERE recording_id=NEW.recording_id;
  INSERT OR IGNORE INTO processing(recording_id,source_version,state) SELECT recording_id,source_version,CASE WHEN original_started_at_ms IS NULL THEN 'HELD_UNKNOWN_TIME' WHEN task_id IS NULL THEN 'HELD_UNLINKED' ELSE 'PENDING' END FROM recordings WHERE recording_id=NEW.recording_id;
  UPDATE recordings SET display_name=NEW.display_name,mime_type=NEW.mime_type,byte_count=NEW.byte_count,modified_at_ms=NEW.modified_at_ms,last_seen_ms=NEW.now_ms,source_seq=NEW.source_seq,head_revision=head_revision+1,presence='PRESENT' WHERE recording_id=NEW.recording_id;
  INSERT INTO receipts SELECT NEW.event_id,'OBSERVE',NEW.request_digest,recording_id,source_version,head_revision,NEW.audio_sha256,NULL,NULL,NULL,CASE WHEN (SELECT observation_event_id FROM source_versions WHERE recording_id=NEW.recording_id AND source_version=r.source_version)=NEW.event_id THEN 'NEW_CONTENT_VERSION' ELSE 'SAME_CONTENT' END,NEW.now_ms FROM recordings r WHERE recording_id=NEW.recording_id;
  DELETE FROM observe_commands WHERE event_id=NEW.event_id;
END;
-- statement-break
CREATE TABLE claim_commands(event_id TEXT PRIMARY KEY,request_digest TEXT NOT NULL,recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha256 TEXT NOT NULL,processing_id TEXT NOT NULL,transcriber_version TEXT NOT NULL,now_ms INTEGER NOT NULL,lease_until_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER claim_execute AFTER INSERT ON claim_commands BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version JOIN folder_grants g ON g.grant_id=r.grant_id WHERE r.recording_id=NEW.recording_id AND r.source_version=NEW.source_version AND v.audio_sha256=NEW.audio_sha256 AND (g.active=1 OR EXISTS(SELECT 1 FROM canonical_storage c WHERE c.audio_sha256=v.audio_sha256 AND c.available=1))) THEN RAISE(ABORT,'STALE_SOURCE_OR_POLICY') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM recordings WHERE recording_id=NEW.recording_id AND NOT EXISTS(SELECT 1 FROM source_versions v WHERE v.recording_id=NEW.recording_id AND v.source_version=recordings.source_version AND (EXISTS(SELECT 1 FROM canonical_storage c WHERE c.audio_sha256=v.audio_sha256 AND c.available=1) OR (presence='PRESENT' AND NOT EXISTS(SELECT 1 FROM canonical_storage c WHERE c.audio_sha256=v.audio_sha256))))) THEN RAISE(ABORT,'SOURCE_UNAVAILABLE') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM recordings WHERE recording_id=NEW.recording_id AND original_started_at_ms IS NULL) THEN RAISE(ABORT,'ORIGINAL_TIMESTAMP_UNKNOWN') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM recordings WHERE recording_id=NEW.recording_id AND task_id IS NULL) THEN RAISE(ABORT,'UNLINKED_TASK') END;
  SELECT CASE WHEN NEW.lease_until_ms<=NEW.now_ms OR NEW.lease_until_ms>NEW.now_ms+300000 THEN RAISE(ABORT,'INVALID_LEASE') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM processing WHERE recording_id=NEW.recording_id AND source_version=NEW.source_version AND (attempts>=3 OR state IN('REVIEW','RETIRED'))) THEN RAISE(ABORT,'NOT_RETRYABLE') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM processing WHERE recording_id=NEW.recording_id AND source_version=NEW.source_version AND state='TRANSCRIBING' AND lease_until_ms>NEW.now_ms) THEN RAISE(ABORT,'LEASE_BUSY') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM processing WHERE processing_id=NEW.processing_id) OR EXISTS(SELECT 1 FROM summary_versions WHERE processing_id=NEW.processing_id) OR EXISTS(SELECT 1 FROM receipts WHERE operation='CLAIM' AND processing_id=NEW.processing_id) THEN RAISE(ABORT,'PROCESSING_ID_REUSED') END;
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM transcript_versions WHERE audio_sha256=NEW.audio_sha256 AND transcriber_version=NEW.transcriber_version AND quality_state!='quality_blocked') AND EXISTS(SELECT 1 FROM content_leases WHERE audio_sha256=NEW.audio_sha256 AND transcriber_version=NEW.transcriber_version AND lease_until_ms>NEW.now_ms) THEN RAISE(ABORT,'CONTENT_TRANSCRIPTION_BUSY') END;
  UPDATE processing SET state='TRANSCRIBING',attempts=attempts+1,lease_epoch=lease_epoch+1,processing_id=NEW.processing_id,lease_until_ms=NEW.lease_until_ms,transcriber_version=NEW.transcriber_version,cached_transcript_id=(SELECT transcript_id FROM transcript_versions WHERE audio_sha256=NEW.audio_sha256 AND transcriber_version=NEW.transcriber_version AND quality_state!='quality_blocked' ORDER BY created_at_ms,transcript_id LIMIT 1),base_user_revision=(SELECT user_revision FROM recordings WHERE recording_id=NEW.recording_id),base_lock_revision=(SELECT lock_revision FROM recordings WHERE recording_id=NEW.recording_id),base_task_revision=(SELECT t.task_revision FROM tasks t JOIN recordings r ON r.task_id=t.task_id WHERE r.recording_id=NEW.recording_id),base_board_revision=(SELECT b.revision FROM board_state b JOIN recordings r ON r.owner_key=b.owner_key WHERE r.recording_id=NEW.recording_id),base_summary_version=(SELECT coalesce(max(summary_version),0) FROM summary_versions WHERE recording_id=NEW.recording_id),error_code=NULL ,base_task_id=(SELECT task_id FROM recordings WHERE recording_id=NEW.recording_id),base_case_id=(SELECT t.case_id FROM tasks t JOIN recordings r ON r.task_id=t.task_id WHERE r.recording_id=NEW.recording_id),base_person_id=(SELECT person_id FROM recordings WHERE recording_id=NEW.recording_id),base_company_id=(SELECT company_id FROM recordings WHERE recording_id=NEW.recording_id),base_original_started_at_ms=(SELECT original_started_at_ms FROM recordings WHERE recording_id=NEW.recording_id) WHERE recording_id=NEW.recording_id AND source_version=NEW.source_version;
  DELETE FROM content_leases WHERE audio_sha256=NEW.audio_sha256 AND transcriber_version=NEW.transcriber_version AND lease_until_ms<=NEW.now_ms;
  INSERT INTO content_leases SELECT NEW.audio_sha256,NEW.transcriber_version,NEW.recording_id,NEW.source_version,NEW.processing_id,NEW.lease_until_ms WHERE NOT EXISTS(SELECT 1 FROM transcript_versions WHERE audio_sha256=NEW.audio_sha256 AND transcriber_version=NEW.transcriber_version AND quality_state!='quality_blocked');
  INSERT INTO receipts SELECT NEW.event_id,'CLAIM',NEW.request_digest,NEW.recording_id,NEW.source_version,(SELECT head_revision FROM recordings WHERE recording_id=NEW.recording_id),NEW.audio_sha256,base_summary_version,lease_epoch,NEW.processing_id,CASE WHEN cached_transcript_id IS NULL THEN 'CLAIMED_AUDIO' ELSE 'CLAIMED_CACHED_TRANSCRIPT' END,NEW.now_ms FROM processing WHERE recording_id=NEW.recording_id AND source_version=NEW.source_version;
  DELETE FROM claim_commands WHERE event_id=NEW.event_id;
END;
-- statement-break
CREATE TABLE result_commands(event_id TEXT PRIMARY KEY,request_digest TEXT NOT NULL,recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha256 TEXT NOT NULL,processing_id TEXT NOT NULL,lease_epoch INTEGER NOT NULL,base_user_revision INTEGER NOT NULL,base_lock_revision INTEGER NOT NULL,base_task_revision INTEGER NOT NULL,base_board_revision INTEGER NOT NULL,base_summary_version INTEGER NOT NULL,transcriber_version TEXT NOT NULL,transcript_id TEXT NOT NULL,text_sha256 TEXT NOT NULL,raw_text TEXT NOT NULL,quality_state TEXT NOT NULL,summary_sha256 TEXT NOT NULL,summary_text TEXT NOT NULL,producer_version TEXT NOT NULL,source_pack_sha256 TEXT NOT NULL,source_pack_json TEXT NOT NULL,candidate_v1_json TEXT NOT NULL,change_diff_json TEXT NOT NULL,review_reasons_json TEXT NOT NULL,now_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER result_execute AFTER INSERT ON result_commands BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version JOIN folder_grants g ON g.grant_id=r.grant_id WHERE r.recording_id=NEW.recording_id AND r.source_version=NEW.source_version AND v.audio_sha256=NEW.audio_sha256 AND (g.active=1 OR EXISTS(SELECT 1 FROM canonical_storage c WHERE c.audio_sha256=v.audio_sha256 AND c.available=1))) THEN RAISE(ABORT,'STALE_SOURCE_OR_POLICY') END;
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version JOIN folder_grants g ON g.grant_id=r.grant_id WHERE r.recording_id=NEW.recording_id AND (EXISTS(SELECT 1 FROM canonical_storage c WHERE c.audio_sha256=v.audio_sha256 AND c.available=1) OR (r.presence='PRESENT' AND g.active=1 AND NOT EXISTS(SELECT 1 FROM canonical_storage c WHERE c.audio_sha256=v.audio_sha256)))) THEN RAISE(ABORT,'SOURCE_UNAVAILABLE') END;
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM processing WHERE recording_id=NEW.recording_id AND source_version=NEW.source_version AND state='TRANSCRIBING' AND processing_id=NEW.processing_id AND lease_epoch=NEW.lease_epoch AND lease_until_ms>NEW.now_ms) THEN RAISE(ABORT,'STALE_LEASE') END;
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM processing WHERE recording_id=NEW.recording_id AND source_version=NEW.source_version AND base_user_revision=NEW.base_user_revision AND base_lock_revision=NEW.base_lock_revision AND base_task_revision=NEW.base_task_revision AND base_board_revision=NEW.base_board_revision AND base_summary_version=NEW.base_summary_version AND transcriber_version=NEW.transcriber_version) THEN RAISE(ABORT,'CLAIM_BASE_MISMATCH') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM processing p JOIN transcript_versions t ON t.transcript_id=p.cached_transcript_id WHERE p.recording_id=NEW.recording_id AND p.source_version=NEW.source_version AND (t.transcript_id!=NEW.transcript_id OR t.text_sha256!=NEW.text_sha256 OR t.raw_text!=NEW.raw_text OR t.quality_state!=NEW.quality_state)) THEN RAISE(ABORT,'CACHED_TRANSCRIPT_MISMATCH') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM transcript_versions WHERE transcript_id=NEW.transcript_id AND (audio_sha256!=NEW.audio_sha256 OR transcriber_version!=NEW.transcriber_version OR text_sha256!=NEW.text_sha256 OR raw_text!=NEW.raw_text OR quality_state!=NEW.quality_state)) THEN RAISE(ABORT,'TRANSCRIPT_IDENTITY_CONFLICT') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM transcript_versions WHERE audio_sha256=NEW.audio_sha256 AND transcriber_version=NEW.transcriber_version AND text_sha256=NEW.text_sha256 AND (transcript_id!=NEW.transcript_id OR raw_text!=NEW.raw_text OR quality_state!=NEW.quality_state)) THEN RAISE(ABORT,'TRANSCRIPT_CACHE_CONFLICT') END;
  INSERT OR IGNORE INTO transcript_versions VALUES(NEW.transcript_id,NEW.audio_sha256,NEW.transcriber_version,NEW.text_sha256,NEW.raw_text,NEW.quality_state,NEW.now_ms);
  INSERT OR IGNORE INTO source_transcripts VALUES(NEW.recording_id,NEW.source_version,NEW.transcript_id);
  INSERT INTO summary_versions SELECT NEW.recording_id,NEW.base_summary_version+1,NEW.source_version,NEW.audio_sha256,NEW.transcript_id,NEW.text_sha256,NEW.summary_sha256,NEW.summary_text,NEW.processing_id,NEW.lease_epoch,NEW.producer_version,NEW.source_pack_sha256,NEW.source_pack_json,NEW.candidate_v1_json,NEW.change_diff_json,NEW.review_reasons_json,CASE WHEN r.user_revision!=NEW.base_user_revision OR r.lock_revision!=NEW.base_lock_revision OR r.user_revision>0 OR r.edited_lock=1 OR r.completed_lock=1 OR r.manual_status='done' THEN 'USER_STATE_PRESERVED' WHEN t.task_revision IS NULL OR t.task_revision!=NEW.base_task_revision OR t.status='done' OR t.manual_locks!=0 THEN 'TASK_STATE_PRESERVED' WHEN b.revision!=NEW.base_board_revision THEN 'BOARD_REVISION_CHANGED' WHEN NEW.quality_state='quality_blocked' THEN 'STT_QUALITY_BLOCKED' ELSE 'SEMANTIC_REVIEW_REQUIRED' END,NEW.base_user_revision,NEW.base_lock_revision,NEW.base_task_revision,NEW.base_board_revision,NEW.now_ms FROM recordings r LEFT JOIN tasks t ON t.task_id=r.task_id JOIN board_state b ON b.owner_key=r.owner_key WHERE r.recording_id=NEW.recording_id;
  UPDATE processing SET state='REVIEW',lease_until_ms=NULL,error_code=NULL WHERE recording_id=NEW.recording_id AND source_version=NEW.source_version;
  DELETE FROM content_leases WHERE processing_id=NEW.processing_id;
  INSERT INTO receipts SELECT NEW.event_id,'RESULT',NEW.request_digest,NEW.recording_id,NEW.source_version,(SELECT head_revision FROM recordings WHERE recording_id=NEW.recording_id),NEW.audio_sha256,NEW.base_summary_version+1,NEW.lease_epoch,NEW.processing_id,'REVIEW_REQUIRED',NEW.now_ms;
  DELETE FROM result_commands WHERE event_id=NEW.event_id;
END;
-- statement-break
CREATE TABLE fail_commands(event_id TEXT PRIMARY KEY,request_digest TEXT NOT NULL,recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,processing_id TEXT NOT NULL,lease_epoch INTEGER NOT NULL,error_code TEXT NOT NULL CHECK(error_code IN('TRANSPORT_DISCONNECTED','ENGINE_UNAVAILABLE','INVALID_WORKER_RESULT','SOURCE_READ_FAILED','TIMEOUT','SYNTHETIC_FAILURE')),now_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER fail_execute AFTER INSERT ON fail_commands BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings r JOIN processing p ON p.recording_id=r.recording_id AND p.source_version=r.source_version WHERE r.recording_id=NEW.recording_id AND r.source_version=NEW.source_version AND p.state='TRANSCRIBING' AND p.processing_id=NEW.processing_id AND p.lease_epoch=NEW.lease_epoch AND p.lease_until_ms>NEW.now_ms) THEN RAISE(ABORT,'STALE_LEASE') END;
  UPDATE processing SET state='FAILED',error_code=NEW.error_code,lease_until_ms=NULL WHERE recording_id=NEW.recording_id AND source_version=NEW.source_version;
  DELETE FROM content_leases WHERE processing_id=NEW.processing_id;
  INSERT INTO receipts SELECT NEW.event_id,'FAIL',NEW.request_digest,NEW.recording_id,NEW.source_version,head_revision,NULL,NULL,NEW.lease_epoch,NEW.processing_id,'FAILED',NEW.now_ms FROM recordings WHERE recording_id=NEW.recording_id;
  DELETE FROM fail_commands WHERE event_id=NEW.event_id;
END;
-- statement-break
CREATE TABLE edit_commands(event_id TEXT PRIMARY KEY,request_digest TEXT NOT NULL,recording_id TEXT NOT NULL,expected_user_revision INTEGER NOT NULL,expected_lock_revision INTEGER NOT NULL,edit_text INTEGER NOT NULL CHECK(edit_text IN(0,1)),new_text TEXT,edit_status INTEGER NOT NULL CHECK(edit_status IN(0,1)),new_status TEXT CHECK(new_status IN('open','waiting','in_progress','needs_confirmation','done') OR new_status IS NULL),now_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER edit_execute AFTER INSERT ON edit_commands BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings WHERE recording_id=NEW.recording_id AND user_revision=NEW.expected_user_revision AND lock_revision=NEW.expected_lock_revision) THEN RAISE(ABORT,'USER_REVISION_CONFLICT') END;
  SELECT CASE WHEN (NEW.edit_text=0 AND NEW.edit_status=0) OR (NEW.edit_text=1 AND (NEW.new_text IS NULL OR length(NEW.new_text)>20000)) THEN RAISE(ABORT,'INVALID_MANUAL_EDIT') END;
  INSERT INTO manual_history SELECT NEW.event_id,recording_id,user_revision+1,coalesce(manual_text,(SELECT summary_text FROM summary_versions WHERE recording_id=NEW.recording_id AND summary_version=r.effective_summary_version)),CASE WHEN NEW.edit_text=1 THEN NEW.new_text ELSE manual_text END,manual_status,CASE WHEN NEW.edit_status=1 THEN NEW.new_status ELSE manual_status END,NULL,NEW.now_ms FROM recordings r WHERE recording_id=NEW.recording_id;
  UPDATE recordings SET user_revision=user_revision+1,lock_revision=lock_revision+CASE WHEN (NEW.edit_text=1 AND edited_lock=0) OR (NEW.edit_status=1 AND completed_lock!=CASE WHEN NEW.new_status='done' THEN 1 ELSE 0 END) THEN 1 ELSE 0 END,edited_lock=CASE WHEN NEW.edit_text=1 THEN 1 ELSE edited_lock END,completed_lock=CASE WHEN NEW.edit_status=1 THEN CASE WHEN NEW.new_status='done' THEN 1 ELSE 0 END ELSE completed_lock END,manual_text=CASE WHEN NEW.edit_text=1 THEN NEW.new_text ELSE manual_text END,manual_status=CASE WHEN NEW.edit_status=1 THEN NEW.new_status ELSE manual_status END WHERE recording_id=NEW.recording_id;
  INSERT INTO receipts SELECT NEW.event_id,'EDIT',NEW.request_digest,recording_id,source_version,head_revision,NULL,effective_summary_version,NULL,NULL,'USER_EDITED',NEW.now_ms FROM recordings WHERE recording_id=NEW.recording_id;
  DELETE FROM edit_commands WHERE event_id=NEW.event_id;
END;
-- statement-break
CREATE TABLE unlock_commands(event_id TEXT PRIMARY KEY,request_digest TEXT NOT NULL,recording_id TEXT NOT NULL,expected_user_revision INTEGER NOT NULL,expected_lock_revision INTEGER NOT NULL,clear_edited INTEGER NOT NULL CHECK(clear_edited IN(0,1)),clear_completed INTEGER NOT NULL CHECK(clear_completed IN(0,1)),now_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER unlock_execute AFTER INSERT ON unlock_commands BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings WHERE recording_id=NEW.recording_id AND user_revision=NEW.expected_user_revision AND lock_revision=NEW.expected_lock_revision) THEN RAISE(ABORT,'USER_REVISION_CONFLICT') END;
  UPDATE recordings SET lock_revision=lock_revision+CASE WHEN (NEW.clear_edited=1 AND edited_lock=1) OR (NEW.clear_completed=1 AND completed_lock=1) THEN 1 ELSE 0 END,edited_lock=CASE WHEN NEW.clear_edited=1 THEN 0 ELSE edited_lock END,completed_lock=CASE WHEN NEW.clear_completed=1 THEN 0 ELSE completed_lock END WHERE recording_id=NEW.recording_id;
  INSERT INTO receipts SELECT NEW.event_id,'UNLOCK',NEW.request_digest,recording_id,source_version,head_revision,NULL,effective_summary_version,NULL,NULL,'UNLOCKED_MANUAL_VALUE_PRESERVED',NEW.now_ms FROM recordings WHERE recording_id=NEW.recording_id;
  DELETE FROM unlock_commands WHERE event_id=NEW.event_id;
END;
-- statement-break
CREATE TABLE accept_commands(event_id TEXT PRIMARY KEY,request_digest TEXT NOT NULL,recording_id TEXT NOT NULL,summary_version INTEGER NOT NULL,expected_user_revision INTEGER NOT NULL,expected_lock_revision INTEGER NOT NULL,expected_task_revision INTEGER NOT NULL,expected_board_revision INTEGER NOT NULL,semantic_review_confirmed INTEGER NOT NULL CHECK(semantic_review_confirmed IN(0,1)),restore_historical INTEGER NOT NULL CHECK(restore_historical IN(0,1)),now_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER accept_execute AFTER INSERT ON accept_commands BEGIN
  SELECT CASE WHEN NEW.semantic_review_confirmed!=1 THEN RAISE(ABORT,'SEMANTIC_REVIEW_REQUIRED') END;
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings r JOIN tasks t ON t.task_id=r.task_id JOIN board_state b ON b.owner_key=r.owner_key WHERE r.recording_id=NEW.recording_id AND r.user_revision=NEW.expected_user_revision AND r.lock_revision=NEW.expected_lock_revision AND t.task_revision=NEW.expected_task_revision AND b.revision=NEW.expected_board_revision) THEN RAISE(ABORT,'ACCEPT_REVISION_CONFLICT') END;
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM summary_versions s JOIN recordings r ON r.recording_id=s.recording_id JOIN transcript_versions t ON t.transcript_id=s.transcript_id WHERE s.recording_id=NEW.recording_id AND s.summary_version=NEW.summary_version AND (NEW.restore_historical=1 OR s.source_version=r.source_version) AND t.quality_state!='quality_blocked') THEN RAISE(ABORT,'SUMMARY_NOT_ACCEPTABLE') END;
  INSERT INTO manual_history SELECT NEW.event_id,r.recording_id,r.user_revision+1,coalesce(r.manual_text,old.summary_text),s.summary_text,r.manual_status,r.manual_status,CASE WHEN NEW.restore_historical=1 THEN NEW.summary_version ELSE NULL END,NEW.now_ms FROM recordings r JOIN summary_versions s ON s.recording_id=r.recording_id AND s.summary_version=NEW.summary_version LEFT JOIN summary_versions old ON old.recording_id=r.recording_id AND old.summary_version=r.effective_summary_version WHERE r.recording_id=NEW.recording_id;
  UPDATE recordings SET effective_summary_version=NEW.summary_version,manual_text=NULL,user_revision=user_revision+1,lock_revision=lock_revision+CASE WHEN edited_lock=0 THEN 1 ELSE 0 END,edited_lock=1 WHERE recording_id=NEW.recording_id;
  INSERT INTO receipts SELECT NEW.event_id,'ACCEPT',NEW.request_digest,recording_id,source_version,head_revision,NULL,NEW.summary_version,NULL,NULL,CASE WHEN NEW.restore_historical=1 THEN 'HISTORICAL_SUMMARY_RESTORED' ELSE 'SUMMARY_ACCEPTED_BY_USER' END,NEW.now_ms FROM recordings WHERE recording_id=NEW.recording_id;
  DELETE FROM accept_commands WHERE event_id=NEW.event_id;
END;
-- statement-break
CREATE TABLE task_edit_commands(event_id TEXT PRIMARY KEY,request_digest TEXT NOT NULL,task_id TEXT NOT NULL,expected_task_revision INTEGER NOT NULL,expected_board_revision INTEGER NOT NULL,new_status TEXT CHECK(new_status IN('open','waiting','in_progress','needs_confirmation','done') OR new_status IS NULL),now_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER task_edit_execute AFTER INSERT ON task_edit_commands BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM tasks t JOIN board_state b ON b.owner_key=t.owner_key WHERE t.task_id=NEW.task_id AND t.task_revision=NEW.expected_task_revision AND b.revision=NEW.expected_board_revision) THEN RAISE(ABORT,'TASK_REVISION_CONFLICT') END;
  INSERT INTO task_history SELECT NEW.event_id,task_id,task_revision+1,status,NEW.new_status,NEW.now_ms FROM tasks WHERE task_id=NEW.task_id;
  UPDATE tasks SET status=NEW.new_status,task_revision=task_revision+1,manual_locks=1 WHERE task_id=NEW.task_id;
  UPDATE board_state SET revision=revision+1 WHERE owner_key=(SELECT owner_key FROM tasks WHERE task_id=NEW.task_id);
  INSERT INTO receipts VALUES(NEW.event_id,'TASK_EDIT',NEW.request_digest,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'TASK_EDITED_BY_USER',NEW.now_ms);
  DELETE FROM task_edit_commands WHERE event_id=NEW.event_id;
END;
-- statement-break
CREATE TABLE confirm_commands(event_id TEXT PRIMARY KEY,request_digest TEXT NOT NULL,recording_id TEXT NOT NULL,expected_user_revision INTEGER NOT NULL,expected_lock_revision INTEGER NOT NULL,person_id TEXT,company_id TEXT,task_id TEXT,original_started_at_ms INTEGER,now_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER confirm_execute AFTER INSERT ON confirm_commands BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings WHERE recording_id=NEW.recording_id AND user_revision=NEW.expected_user_revision AND lock_revision=NEW.expected_lock_revision) THEN RAISE(ABORT,'USER_REVISION_CONFLICT') END;
  SELECT CASE WHEN NEW.task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM tasks t JOIN recordings r ON r.owner_key=t.owner_key WHERE r.recording_id=NEW.recording_id AND t.task_id=NEW.task_id AND t.person_id IS NEW.person_id AND t.company_id IS NEW.company_id) THEN RAISE(ABORT,'TASK_LINK_NOT_CONFIRMED') END;
  UPDATE recordings SET person_id=NEW.person_id,company_id=NEW.company_id,task_id=NEW.task_id,original_started_at_ms=NEW.original_started_at_ms,original_day=date(NEW.original_started_at_ms/1000,'unixepoch','+9 hours'),user_revision=user_revision+1,head_revision=head_revision+1 WHERE recording_id=NEW.recording_id;
  UPDATE processing SET state=CASE WHEN (SELECT original_started_at_ms FROM recordings WHERE recording_id=NEW.recording_id) IS NULL THEN 'HELD_UNKNOWN_TIME' WHEN NEW.task_id IS NULL THEN 'HELD_UNLINKED' ELSE 'PENDING' END WHERE recording_id=NEW.recording_id AND source_version=(SELECT source_version FROM recordings WHERE recording_id=NEW.recording_id) AND state IN('HELD_UNKNOWN_TIME','HELD_UNLINKED','PENDING');
  INSERT INTO receipts SELECT NEW.event_id,'CONFIRM',NEW.request_digest,recording_id,source_version,head_revision,NULL,NULL,NULL,NULL,'METADATA_CONFIRMED_BY_USER',NEW.now_ms FROM recordings WHERE recording_id=NEW.recording_id;
  DELETE FROM confirm_commands WHERE event_id=NEW.event_id;
END;
-- statement-break
CREATE TABLE seal_commands(event_id TEXT PRIMARY KEY,request_digest TEXT NOT NULL,recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha256 TEXT NOT NULL,storage_ref TEXT NOT NULL,byte_count INTEGER NOT NULL,now_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER seal_execute AFTER INSERT ON seal_commands BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version WHERE r.recording_id=NEW.recording_id AND r.source_version=NEW.source_version AND v.audio_sha256=NEW.audio_sha256 AND v.byte_count=NEW.byte_count) THEN RAISE(ABORT,'SEALED_SOURCE_MISMATCH') END;
  SELECT CASE WHEN EXISTS(SELECT 1 FROM canonical_storage WHERE audio_sha256=NEW.audio_sha256 AND (storage_ref!=NEW.storage_ref OR byte_count!=NEW.byte_count)) THEN RAISE(ABORT,'STORAGE_REF_CONFLICT') END;
  INSERT OR IGNORE INTO canonical_storage VALUES(NEW.audio_sha256,NEW.storage_ref,NEW.byte_count,1);
  UPDATE canonical_storage SET available=1 WHERE audio_sha256=NEW.audio_sha256;
  UPDATE processing SET state=CASE WHEN (SELECT original_started_at_ms FROM recordings WHERE recording_id=NEW.recording_id) IS NULL THEN 'HELD_UNKNOWN_TIME' WHEN (SELECT task_id FROM recordings WHERE recording_id=NEW.recording_id) IS NULL THEN 'HELD_UNLINKED' ELSE 'PENDING' END,lease_until_ms=NULL WHERE recording_id=NEW.recording_id AND source_version=NEW.source_version AND state='WAITING_SOURCE';
  INSERT INTO receipts SELECT NEW.event_id,'SEAL',NEW.request_digest,recording_id,source_version,head_revision,NEW.audio_sha256,NULL,NULL,NULL,'CANONICAL_REFERENCE_VERIFIED_LOCALLY',NEW.now_ms FROM recordings WHERE recording_id=NEW.recording_id;
  DELETE FROM seal_commands WHERE event_id=NEW.event_id;
END;
-- statement-break
CREATE TABLE availability_commands(event_id TEXT PRIMARY KEY,request_digest TEXT NOT NULL,recording_id TEXT NOT NULL,expected_source_version INTEGER NOT NULL,target TEXT NOT NULL CHECK(target IN('ALIAS','CANONICAL')),available INTEGER NOT NULL CHECK(available IN(0,1)),scan_complete INTEGER NOT NULL CHECK(scan_complete IN(0,1)),now_ms INTEGER NOT NULL);
-- statement-break
CREATE TRIGGER availability_execute AFTER INSERT ON availability_commands BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM recordings WHERE recording_id=NEW.recording_id AND source_version=NEW.expected_source_version) THEN RAISE(ABORT,'STALE_SOURCE') END;
  UPDATE recordings SET presence=CASE WHEN NEW.available=1 THEN 'PRESENT' ELSE 'MISSING' END,head_revision=head_revision+1 WHERE recording_id=NEW.recording_id AND NEW.target='ALIAS' AND NEW.scan_complete=1;
  UPDATE canonical_storage SET available=NEW.available WHERE audio_sha256=(SELECT audio_sha256 FROM source_versions WHERE recording_id=NEW.recording_id AND source_version=NEW.expected_source_version) AND NEW.target='CANONICAL' AND NEW.scan_complete=1;
  UPDATE processing SET state='WAITING_SOURCE',lease_until_ms=NULL WHERE recording_id=NEW.recording_id AND source_version=NEW.expected_source_version AND state IN('PENDING','TRANSCRIBING','FAILED') AND NEW.scan_complete=1 AND NOT EXISTS(SELECT 1 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version JOIN folder_grants g ON g.grant_id=r.grant_id WHERE r.recording_id=NEW.recording_id AND (EXISTS(SELECT 1 FROM canonical_storage c WHERE c.audio_sha256=v.audio_sha256 AND c.available=1) OR (r.presence='PRESENT' AND g.active=1 AND NOT EXISTS(SELECT 1 FROM canonical_storage c WHERE c.audio_sha256=v.audio_sha256))));
  DELETE FROM content_leases WHERE recording_id=NEW.recording_id AND EXISTS(SELECT 1 FROM processing WHERE recording_id=NEW.recording_id AND source_version=NEW.expected_source_version AND state='WAITING_SOURCE');
  UPDATE processing SET state=CASE WHEN (SELECT original_started_at_ms FROM recordings WHERE recording_id=NEW.recording_id) IS NULL THEN 'HELD_UNKNOWN_TIME' WHEN (SELECT task_id FROM recordings WHERE recording_id=NEW.recording_id) IS NULL THEN 'HELD_UNLINKED' ELSE 'PENDING' END WHERE recording_id=NEW.recording_id AND source_version=NEW.expected_source_version AND state='WAITING_SOURCE' AND NEW.scan_complete=1 AND EXISTS(SELECT 1 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version JOIN folder_grants g ON g.grant_id=r.grant_id WHERE r.recording_id=NEW.recording_id AND (EXISTS(SELECT 1 FROM canonical_storage c WHERE c.audio_sha256=v.audio_sha256 AND c.available=1) OR (r.presence='PRESENT' AND g.active=1 AND NOT EXISTS(SELECT 1 FROM canonical_storage c WHERE c.audio_sha256=v.audio_sha256))));
  INSERT INTO receipts SELECT NEW.event_id,'AVAILABILITY',NEW.request_digest,recording_id,source_version,head_revision,NULL,effective_summary_version,NULL,NULL,CASE WHEN NEW.scan_complete=0 THEN 'INCOMPLETE_INVENTORY_IGNORED' ELSE 'AVAILABILITY_UPDATED' END,NEW.now_ms FROM recordings WHERE recording_id=NEW.recording_id;
  DELETE FROM availability_commands WHERE event_id=NEW.event_id;
END;
-- generated guards
-- statement-break
CREATE TRIGGER observe_replay BEFORE INSERT ON observe_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='OBSERVE' OR request_digest!=NEW.request_digest OR r.audio_sha256!=NEW.audio_sha256)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER claim_replay BEFORE INSERT ON claim_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='CLAIM' OR request_digest!=NEW.request_digest)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER result_replay BEFORE INSERT ON result_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='RESULT' OR request_digest!=NEW.request_digest)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER fail_replay BEFORE INSERT ON fail_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='FAIL' OR request_digest!=NEW.request_digest)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER edit_replay BEFORE INSERT ON edit_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='EDIT' OR request_digest!=NEW.request_digest)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER unlock_replay BEFORE INSERT ON unlock_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='UNLOCK' OR request_digest!=NEW.request_digest)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER accept_replay BEFORE INSERT ON accept_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='ACCEPT' OR request_digest!=NEW.request_digest)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER task_edit_replay BEFORE INSERT ON task_edit_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='TASK_EDIT' OR request_digest!=NEW.request_digest)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER confirm_replay BEFORE INSERT ON confirm_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='CONFIRM' OR request_digest!=NEW.request_digest)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER seal_replay BEFORE INSERT ON seal_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='SEAL' OR request_digest!=NEW.request_digest)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER availability_replay BEFORE INSERT ON availability_commands
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts r WHERE event_id=NEW.event_id AND (operation!='AVAILABILITY' OR request_digest!=NEW.request_digest)) THEN RAISE(ABORT,'IDEMPOTENCY_CONFLICT') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER source_versions_immutable_update BEFORE UPDATE ON source_versions BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER source_versions_immutable_delete BEFORE DELETE ON source_versions BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER content_blobs_immutable_update BEFORE UPDATE ON content_blobs BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER content_blobs_immutable_delete BEFORE DELETE ON content_blobs BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER transcript_versions_immutable_update BEFORE UPDATE ON transcript_versions BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER transcript_versions_immutable_delete BEFORE DELETE ON transcript_versions BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER source_transcripts_immutable_update BEFORE UPDATE ON source_transcripts BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER source_transcripts_immutable_delete BEFORE DELETE ON source_transcripts BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER summary_versions_immutable_update BEFORE UPDATE ON summary_versions BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER summary_versions_immutable_delete BEFORE DELETE ON summary_versions BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER receipts_immutable_update BEFORE UPDATE ON receipts BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER receipts_immutable_delete BEFORE DELETE ON receipts BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER request_bindings_immutable_update BEFORE UPDATE ON request_bindings BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER request_bindings_immutable_delete BEFORE DELETE ON request_bindings BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER manual_history_immutable_update BEFORE UPDATE ON manual_history BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER manual_history_immutable_delete BEFORE DELETE ON manual_history BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER task_history_immutable_update BEFORE UPDATE ON task_history BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
CREATE TRIGGER task_history_immutable_delete BEFORE DELETE ON task_history BEGIN
  SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');
END;
-- statement-break
-- Protect REPLACE at both transcript keys even when recursive_triggers is OFF.
-- An identical cached transcript may arrive with a later observation time: retain the original
-- row by IGNORE instead of replacing its immutable created_at_ms.
CREATE TRIGGER transcript_versions_immutable_insert BEFORE INSERT ON transcript_versions
WHEN EXISTS(SELECT 1 FROM transcript_versions WHERE transcript_id=NEW.transcript_id OR
  (audio_sha256=NEW.audio_sha256 AND transcriber_version=NEW.transcriber_version AND text_sha256=NEW.text_sha256)) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM transcript_versions WHERE
    (transcript_id=NEW.transcript_id AND (audio_sha256 IS NOT NEW.audio_sha256 OR transcriber_version IS NOT NEW.transcriber_version
      OR text_sha256 IS NOT NEW.text_sha256 OR raw_text IS NOT NEW.raw_text OR quality_state IS NOT NEW.quality_state)) OR
    (audio_sha256=NEW.audio_sha256 AND transcriber_version=NEW.transcriber_version AND text_sha256=NEW.text_sha256 AND transcript_id IS NOT NEW.transcript_id))
    THEN RAISE(ABORT,'IMMUTABLE_HISTORY') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
CREATE TRIGGER receipts_immutable_insert BEFORE INSERT ON receipts
WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id) BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM receipts WHERE event_id=NEW.event_id AND
    (operation IS NOT NEW.operation OR request_digest IS NOT NEW.request_digest OR recording_id IS NOT NEW.recording_id
      OR source_version IS NOT NEW.source_version OR head_revision IS NOT NEW.head_revision OR audio_sha256 IS NOT NEW.audio_sha256
      OR summary_version IS NOT NEW.summary_version OR lease_epoch IS NOT NEW.lease_epoch OR processing_id IS NOT NEW.processing_id
      OR outcome IS NOT NEW.outcome OR created_at_ms IS NOT NEW.created_at_ms)) THEN RAISE(ABORT,'IMMUTABLE_HISTORY') END;
  SELECT RAISE(IGNORE);
END;
-- statement-break
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
-- statement-break
CREATE TABLE IF NOT EXISTS local_files(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),root_uri TEXT NOT NULL,actual_document_id TEXT NOT NULL,actual_uri TEXT NOT NULL,source_signature TEXT NOT NULL,plaintext_sha256 TEXT NOT NULL,filename_time_candidate_ms INTEGER,UNIQUE(root_uri,actual_document_id))
-- statement-break
CREATE TABLE IF NOT EXISTS local_visibility(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN(0,1)),revision INTEGER NOT NULL DEFAULT 0)
-- statement-break
CREATE TABLE IF NOT EXISTS local_visibility_history(event_id TEXT PRIMARY KEY,recording_id TEXT NOT NULL,archived INTEGER NOT NULL,revision INTEGER NOT NULL,at_ms INTEGER NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS local_intakes(event_id TEXT PRIMARY KEY,root_uri TEXT NOT NULL,actual_document_id TEXT NOT NULL,source_signature TEXT NOT NULL,plaintext_sha256 TEXT NOT NULL,metadata_json TEXT NOT NULL,completed INTEGER NOT NULL DEFAULT 0 CHECK(completed IN(0,1)))
-- statement-break
CREATE TABLE IF NOT EXISTS discovery_documents(root TEXT NOT NULL,document_id TEXT NOT NULL,uri TEXT NOT NULL,name TEXT NOT NULL,size INTEGER NOT NULL,mime TEXT NOT NULL,modified INTEGER NOT NULL,partial INTEGER NOT NULL,virtual INTEGER NOT NULL,PRIMARY KEY(root,document_id))
-- statement-break
CREATE TABLE IF NOT EXISTS discovery_intake_requests(operation_id TEXT PRIMARY KEY,owner_key TEXT NOT NULL,commit_payload TEXT NOT NULL,metadata_json TEXT NOT NULL,request_digest TEXT NOT NULL,root TEXT NOT NULL,document_id TEXT NOT NULL)
-- statement-break
CREATE TRIGGER IF NOT EXISTS discovery_request_update BEFORE UPDATE ON discovery_intake_requests BEGIN SELECT RAISE(ABORT,'IMMUTABLE_DISCOVERY_REQUEST');END
-- statement-break
CREATE TRIGGER IF NOT EXISTS discovery_request_delete BEFORE DELETE ON discovery_intake_requests BEGIN SELECT RAISE(ABORT,'IMMUTABLE_DISCOVERY_REQUEST');END
-- statement-break
CREATE TABLE IF NOT EXISTS vault_pruned(ciphertext_sha256 TEXT PRIMARY KEY,at_ms INTEGER NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS discovery_queue_roots(scope TEXT PRIMARY KEY,header TEXT NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS discovery_queue_rows(scope TEXT NOT NULL,document_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(scope,document_id))
-- statement-break
CREATE TABLE IF NOT EXISTS discovery_policy_revisions(scope TEXT PRIMARY KEY,digest TEXT NOT NULL,revision INTEGER NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS call_transcription_parts(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,chunk INTEGER NOT NULL,segments_json TEXT NOT NULL,chunk_ms INTEGER,PRIMARY KEY(recording_id,source_version,audio_sha,chunk))
-- statement-break
CREATE TABLE IF NOT EXISTS call_transcription_stalls(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL,audio_sha TEXT NOT NULL,chunk INTEGER NOT NULL,stops INTEGER NOT NULL,PRIMARY KEY(recording_id,source_version,audio_sha,chunk))
-- statement-break
CREATE TABLE IF NOT EXISTS call_transcription_jobs(recording_id TEXT NOT NULL,source_version INTEGER NOT NULL, audio_sha TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN('QUEUED','RUNNING','DONE','FAILED','SKIPPED')), reason TEXT NOT NULL DEFAULT '',attempts INTEGER NOT NULL DEFAULT 0,updated_ms INTEGER NOT NULL, PRIMARY KEY(recording_id,source_version,audio_sha))
-- statement-break
CREATE TABLE IF NOT EXISTS workflow_links(recording_id TEXT PRIMARY KEY REFERENCES recordings(recording_id),revision INTEGER NOT NULL,person_id TEXT,company_id TEXT,case_id TEXT NOT NULL,task_id TEXT NOT NULL,phone_normalized TEXT NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS workflow_items(id TEXT PRIMARY KEY,recording_id TEXT NOT NULL REFERENCES recordings(recording_id),case_id TEXT,kind TEXT NOT NULL CHECK(kind IN ('TODO','DOCUMENT')),title TEXT NOT NULL,edition TEXT NOT NULL,due_text TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,source_key TEXT,evidence_quote TEXT NOT NULL,created_ms INTEGER NOT NULL,done_ms INTEGER,ai_note TEXT)
-- statement-break
CREATE TABLE IF NOT EXISTS workflow_item_history(event_id TEXT PRIMARY KEY,item_id TEXT NOT NULL,revision INTEGER NOT NULL,before_json TEXT NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS call_records(record_id TEXT PRIMARY KEY,recording_id TEXT NOT NULL,started_ms INTEGER,duration_ms INTEGER NOT NULL,counterpart TEXT,counterpart_key TEXT,transcript TEXT NOT NULL,segments_json TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('TRANSCRIBED','ANALYZED','FAILED','WAITING_CONSENT')),summary TEXT,analysis_json TEXT,error TEXT,created_ms INTEGER NOT NULL,analyzed_ms INTEGER)
-- statement-break
CREATE TABLE IF NOT EXISTS call_task_bindings(item_id TEXT PRIMARY KEY,record_id TEXT NOT NULL REFERENCES call_records(record_id),last_revision INTEGER NOT NULL,created_ms INTEGER NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS call_inbox_sent(record_id TEXT PRIMARY KEY REFERENCES call_records(record_id),sent_ms INTEGER NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS app_migration(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS site_memo(id INTEGER PRIMARY KEY CHECK(id=1),text TEXT NOT NULL,updated_ms INTEGER NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS companies(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE,trade TEXT,memo TEXT NOT NULL DEFAULT '',updated_ms INTEGER NOT NULL,name_key TEXT)
-- statement-break
CREATE TABLE IF NOT EXISTS people(id TEXT PRIMARY KEY,name TEXT NOT NULL,phone_key TEXT UNIQUE,company_id TEXT,role TEXT,is_staff INTEGER NOT NULL DEFAULT 0,updated_ms INTEGER NOT NULL,name_key TEXT)
-- statement-break
CREATE TABLE IF NOT EXISTS call_links(record_id TEXT PRIMARY KEY,person_id TEXT,company_id TEXT)
-- statement-break
CREATE TABLE IF NOT EXISTS task_companies(item_id TEXT PRIMARY KEY,company_id TEXT NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS person_aliases(name_key TEXT PRIMARY KEY,person_id TEXT NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS company_aliases(name_key TEXT PRIMARY KEY,company_id TEXT NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS people_phones(phone_key TEXT NOT NULL,person_id TEXT NOT NULL,PRIMARY KEY(phone_key,person_id))
-- statement-break
CREATE TABLE IF NOT EXISTS call_log(log_id INTEGER PRIMARY KEY,number_key TEXT,name TEXT,start_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,type INTEGER NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS call_record_log(record_id TEXT PRIMARY KEY,log_id INTEGER NOT NULL UNIQUE,direction TEXT)
-- statement-break
CREATE INDEX IF NOT EXISTS call_log_start ON call_log(start_ms)
-- statement-break
CREATE TABLE IF NOT EXISTS ledger_edits(id TEXT PRIMARY KEY,json TEXT NOT NULL,created_ms INTEGER NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS issues(id TEXT PRIMARY KEY,ord INTEGER NOT NULL,title TEXT NOT NULL,status TEXT NOT NULL,companies TEXT NOT NULL,updated_ms INTEGER NOT NULL,record_keys TEXT NOT NULL DEFAULT '[]')
-- statement-break
CREATE TABLE IF NOT EXISTS personal_numbers(phone_key TEXT PRIMARY KEY,decided_ms INTEGER NOT NULL)
-- statement-break
CREATE TABLE IF NOT EXISTS personal_streaks(phone_key TEXT PRIMARY KEY,streak INTEGER NOT NULL,updated_ms INTEGER NOT NULL)
-- statement-break
ALTER TABLE people ADD COLUMN ledger_id TEXT
-- statement-break
ALTER TABLE people ADD COLUMN name_locked INTEGER NOT NULL DEFAULT 0
-- statement-break
ALTER TABLE workflow_items ADD COLUMN source TEXT
-- statement-break
ALTER TABLE workflow_items ADD COLUMN tentative INTEGER NOT NULL DEFAULT 0
-- statement-break
ALTER TABLE workflow_items ADD COLUMN last_call_ms INTEGER
-- statement-break
ALTER TABLE call_inbox_sent ADD COLUMN result_ms INTEGER
-- statement-break
CREATE UNIQUE INDEX IF NOT EXISTS people_ledger_id ON people(ledger_id)
-- statement-break
CREATE INDEX IF NOT EXISTS people_name_key ON people(name_key)
-- statement-break
CREATE INDEX IF NOT EXISTS companies_name_key ON companies(name_key)
