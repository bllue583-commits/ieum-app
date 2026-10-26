package kr.callbrief

import android.content.Context
import android.content.Intent
import android.os.Bundle
import kr.callbrief.navermail.android.NaverMailSettingsActivity
import kr.callbrief.navermail.MailProposalInbox

/** Independent NAVER policy remains false. Google only selects the local owner partition. */
internal class ScopedNaverMailSettingsActivity : NaverMailSettingsActivity() {
    private var owner=""
    private var epoch=-1L
    override fun onCreate(savedInstanceState:Bundle?) {
        runCatching { val session=GoogleConnectionSession.get(this);val v=session.dailyReportScope();epoch=v.epoch;owner=ownerNamespace(v.ownerId) }
        intent.putExtra(EXTRA_OWNER,owner);super.onCreate(savedInstanceState)
    }
    override fun createProposalInbox():MailProposalInbox {
        val session=GoogleConnectionSession.get(this);val selected=session.dailyReportScope()
        val context=if(selected.ownerId.isBlank())this else AccountStorageContext(this,selected.ownerId)
        return WorkflowNaverInbox(context,owner,intent.getStringExtra("workflow_recording_id"),{ ownerStillSelected() })
    }
    override fun ownerStillSelected():Boolean = runCatching {
        val session=GoogleConnectionSession.get(this)
        session.epoch()==epoch && ownerNamespace(session.ownerBinding())==owner
    }.getOrDefault(false)
    companion object {
        fun ownerNamespace(owner:String)=if(owner.isBlank())GeneralLocalStore.OWNER else "google_"+GoogleConnectionSession.scopeKey(owner)
        fun open(context:Context)=context.startActivity(Intent(context,ScopedNaverMailSettingsActivity::class.java))
    }
}
