import {DatabaseSync} from 'node:sqlite';
import {createCipheriv,createDecipheriv,randomBytes} from 'node:crypto';
import {mkdirSync,chmodSync} from 'node:fs';
import {dirname} from 'node:path';
import {canonical,parse,requireThat,Held} from './contract.mjs';

/** Owns processing only. Coordinator owns case accumulation and daily delivery. */
export class ProcessingStore {
  constructor(path,key) {
    requireThat(Buffer.isBuffer(key)&&key.length===32,'PERSISTENCE_KEY_REQUIRED');this.key=Buffer.from(key);
    if(path!==':memory:'){mkdirSync(dirname(path),{recursive:true,mode:0o700});chmodSync(dirname(path),0o700);}
    this.db=new DatabaseSync(path);if(path!==':memory:')chmodSync(path,0o600);
    this.db.exec(`PRAGMA foreign_keys=ON; PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL;
      CREATE TABLE IF NOT EXISTS jobs(owner TEXT NOT NULL,job TEXT NOT NULL,request_sha TEXT NOT NULL,state TEXT NOT NULL,issue TEXT NOT NULL DEFAULT '',body TEXT NOT NULL,PRIMARY KEY(owner,job));
      CREATE TABLE IF NOT EXISTS stages(owner TEXT NOT NULL,job TEXT NOT NULL,stage TEXT NOT NULL,status TEXT NOT NULL,body TEXT,PRIMARY KEY(owner,job,stage));
      CREATE TABLE IF NOT EXISTS results(owner TEXT NOT NULL,job TEXT NOT NULL,digest TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(owner,job));
      CREATE TABLE IF NOT EXISTS verification(owner TEXT NOT NULL,job TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(owner,job));
      CREATE TRIGGER IF NOT EXISTS verification_no_update BEFORE UPDATE ON verification BEGIN SELECT RAISE(ABORT,'VERIFICATION_IMMUTABLE'); END;
      CREATE TRIGGER IF NOT EXISTS verification_no_delete BEFORE DELETE ON verification BEGIN SELECT RAISE(ABORT,'VERIFICATION_IMMUTABLE'); END;
      CREATE TABLE IF NOT EXISTS spend(owner TEXT NOT NULL,day TEXT NOT NULL,job TEXT NOT NULL,stage TEXT NOT NULL,PRIMARY KEY(owner,job,stage));
      CREATE TRIGGER IF NOT EXISTS results_no_update BEFORE UPDATE ON results BEGIN SELECT RAISE(ABORT,'RESULT_IMMUTABLE'); END;
      CREATE TRIGGER IF NOT EXISTS results_no_delete BEFORE DELETE ON results BEGIN SELECT RAISE(ABORT,'RESULT_IMMUTABLE'); END;
      CREATE TRIGGER IF NOT EXISTS completed_stage_no_update BEFORE UPDATE ON stages WHEN OLD.status='DONE' BEGIN SELECT RAISE(ABORT,'STAGE_IMMUTABLE'); END;
      CREATE TRIGGER IF NOT EXISTS enrollment_no_update BEFORE UPDATE OF body,request_sha ON jobs BEGIN SELECT RAISE(ABORT,'ENROLLMENT_IMMUTABLE'); END;`);
  }
  seal(value,aad) { const iv=randomBytes(12),c=createCipheriv('aes-256-gcm',this.key,iv);c.setAAD(Buffer.from(aad));const b=Buffer.concat([c.update(canonical(value),'utf8'),c.final()]);return Buffer.concat([iv,c.getAuthTag(),b]).toString('base64'); }
  open(raw,aad) { try{const b=Buffer.from(raw,'base64');requireThat(b.length>=28);const c=createDecipheriv('aes-256-gcm',this.key,b.subarray(0,12));c.setAAD(Buffer.from(aad));c.setAuthTag(b.subarray(12,28));return parse(Buffer.concat([c.update(b.subarray(28)),c.final()]),2*1048576);}catch{throw new Held('ENCRYPTED_STORE_REVIEW');} }
  tx(action){this.db.exec('BEGIN IMMEDIATE');try{const r=action();this.db.exec('COMMIT');return r;}catch(e){this.db.exec('ROLLBACK');throw e;}}
  job(owner,id){const r=this.db.prepare('SELECT * FROM jobs WHERE owner=? AND job=?').get(owner,id);return r&&{...r,enrollment:this.open(r.body,`job:${owner}:${id}`)};}
  enroll(owner,v){return this.tx(()=>{const id=v.request.jobId,old=this.job(owner,id);if(old){requireThat(old.request_sha===v.requestSha&&canonical(old.enrollment)===canonical(v.enrollment),'ENROLLMENT_CONFLICT');return {jobId:id,state:old.state,duplicate:true};}
    this.db.prepare("INSERT INTO jobs(owner,job,request_sha,state,body) VALUES(?,?,?,'QUEUED',?)").run(owner,id,v.requestSha,this.seal(v.enrollment,`job:${owner}:${id}`));return {jobId:id,state:'QUEUED',duplicate:false};});}
  recover(){this.tx(()=>{this.db.prepare("UPDATE stages SET status='UNKNOWN' WHERE status='CALLING'").run();this.db.prepare("UPDATE jobs SET state='HELD',issue='INTERRUPTED_STAGE_RECONCILE_REQUIRED' WHERE state='PROCESSING'").run();});}
  claim(owner){return this.tx(()=>{const r=this.db.prepare("SELECT job FROM jobs WHERE owner=? AND state='QUEUED' ORDER BY rowid LIMIT 1").get(owner);if(!r)return null;requireThat(this.db.prepare("UPDATE jobs SET state='PROCESSING' WHERE owner=? AND job=? AND state='QUEUED'").run(owner,r.job).changes===1,'JOB_CLAIM_LOST');return this.job(owner,r.job);});}
  hold(owner,id,code){this.db.prepare("UPDATE jobs SET state=CASE WHEN state='CANCELLED' THEN state ELSE 'HELD' END,issue=? WHERE owner=? AND job=? AND state!='RESULT_READY'").run(code,owner,id);}
  cancel(owner,id){this.db.prepare("UPDATE jobs SET state='CANCELLED',issue='LOCAL_STOP_INFLIGHT_NOT_RECALLED' WHERE owner=? AND job=?").run(owner,id);}
  stage(owner,id,stage){const r=this.db.prepare('SELECT * FROM stages WHERE owner=? AND job=? AND stage=?').get(owner,id,stage);return r&&{status:r.status,value:r.body?this.open(r.body,`stage:${owner}:${id}:${stage}`):null};}
  beginStage(owner,id,stage,day,limit){this.tx(()=>{requireThat(!this.stage(owner,id,stage),'PAID_STAGE_ALREADY_ATTEMPTED');requireThat(this.job(owner,id)?.state==='PROCESSING','JOB_NOT_CURRENT');const used=this.db.prepare('SELECT count(*) AS n FROM spend WHERE owner=? AND day=?').get(owner,day).n;requireThat(used<limit,'PAID_CALL_LIMIT');this.db.prepare('INSERT INTO spend VALUES(?,?,?,?)').run(owner,day,id,stage);this.db.prepare("INSERT INTO stages VALUES(?,?,?,'CALLING',NULL)").run(owner,id,stage);});}
  finishStage(owner,id,stage,value){requireThat(this.db.prepare("UPDATE stages SET status='DONE',body=? WHERE owner=? AND job=? AND stage=? AND status='CALLING'").run(this.seal(value,`stage:${owner}:${id}:${stage}`),owner,id,stage).changes===1,'STAGE_RECONCILE_REQUIRED');}
  failStage(owner,id,stage){this.db.prepare("UPDATE stages SET status='UNKNOWN' WHERE owner=? AND job=? AND stage=? AND status='CALLING'").run(owner,id,stage);}
  complete(owner,id,result,verification=null){this.tx(()=>{requireThat(this.job(owner,id)?.state==='PROCESSING','JOB_NOT_CURRENT');this.db.prepare('INSERT INTO results VALUES(?,?,?,?)').run(owner,id,result.resultDigest,this.seal(result,`result:${owner}:${id}`));if(verification)this.db.prepare('INSERT INTO verification VALUES(?,?,?)').run(owner,id,this.seal(verification,`verification:${owner}:${id}`));requireThat(this.db.prepare("UPDATE jobs SET state='RESULT_READY',issue='' WHERE owner=? AND job=? AND state='PROCESSING'").run(owner,id).changes===1,'JOB_COMMIT_LOST');});}
  result(owner,id){const r=this.db.prepare('SELECT * FROM results WHERE owner=? AND job=?').get(owner,id);return r?this.open(r.body,`result:${owner}:${id}`):null;}
  verification(owner,id){const r=this.db.prepare('SELECT body FROM verification WHERE owner=? AND job=?').get(owner,id);return r?this.open(r.body,`verification:${owner}:${id}`):null;}
  close(){this.db.close();this.key.fill(0);}
}
