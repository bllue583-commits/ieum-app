import {exact,text,identifier,hash,sha,canonical,requireThat} from './contract.mjs';

/** Minimal scheduled dot proof. No Android, audio, existing Drive result, or Kakao binding. */
export const scheduledFixture={schema:'ieum.scheduled-text-fixture.v1',synthetic:true,sample_id:'synthetic-call-a',
  text:'[합성 대화] 가상 샘플 배송은 13일로 조정할 수 있습니다. 최종 일정은 다시 확인해서 알려드리겠습니다.',
  source_scope:'FIXED_SYNTHETIC_TEXT_ONLY'};
export function validateScheduledResult(x,{proofRequestId,sourceFileId,sourceSha}) {
  exact(x,['schema','synthetic','proof_request_id','source_file_id','source_sha256','execution_context','summary','tasks','applied','canonical_write_allowed','stt_executed','android_upload_verified','kakao_sent']);
  requireThat(x.schema==='ieum.scheduled-text-result.v1'&&x.synthetic===true&&x.proof_request_id===proofRequestId&&x.source_file_id===sourceFileId&&x.source_sha256===sourceSha,'SCHEDULED_PROOF_BINDING');
  identifier(proofRequestId);identifier(sourceFileId);hash(sourceSha);requireThat(x.execution_context==='SCHEDULED_DOT_TEXT_ONLY','SCHEDULED_CONTEXT_REQUIRED');text(x.summary,1200);
  requireThat(['applied','canonical_write_allowed','stt_executed','android_upload_verified','kakao_sent'].every(k=>x[k]===false),'SCHEDULED_TRUTH_FLAGS');
  requireThat(Array.isArray(x.tasks)&&x.tasks.length<=10,'SCHEDULED_TASK_COUNT');
  const seen=new Set();for(const t of x.tasks) {
    exact(t,['title','owner','due','status','evidence']);text(t.title,160);text(t.owner,80);
    requireThat(t.due===null||(typeof t.due==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(t.due)&&new Date(t.due+'T00:00:00Z').toISOString().slice(0,10)===t.due),'SCHEDULED_DUE');
    requireThat(['open','in_progress','waiting','needs_confirmation'].includes(t.status),'SCHEDULED_DONE_NOT_ALLOWED');exact(t.evidence,['sample_id','quote']);
    requireThat(t.evidence.sample_id===scheduledFixture.sample_id&&typeof t.evidence.quote==='string'&&t.evidence.quote.length>0&&scheduledFixture.text.includes(t.evidence.quote),'SCHEDULED_QUOTE');
    const key=sha(canonical([t.title.normalize('NFKC').toLowerCase().trim(),t.owner.normalize('NFKC').toLowerCase().trim(),t.due]));requireThat(!seen.has(key),'SCHEDULED_DUPLICATE');seen.add(key);
  }
  return {contentValidated:true,sourceSha,proofRequestId,automaticInvocationIndependentlyVerified:false,resultWriteIndependentlyVerified:false,androidUploadVerified:false,sttExecuted:false,kakaoSent:false};
}
