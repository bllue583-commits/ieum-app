package kr.callbrief

import android.content.Context
import kr.callbrief.navermail.*

/** Host persistence consumes the existing MailIntake contract. It cannot authorize a mailbox read. */
internal class WorkflowNaverInbox(private val context:Context,private val namespace:String,
    private val recording:String?,private val ownerCurrent:()->Boolean):MailProposalInbox {
    override fun replace(batch:ScanBatch) {
        check(ownerCurrent()&&batch.account.ownerId==namespace) { "MAIL_OWNER_CHANGED" }
        RecordingWorkflowStore(context).use { it.storeNaverBatch(batch,recording,ownerCurrent) }
    }
    override fun purge(accountKey:String) {
        // Scope revocation retires candidates and fences approved analysis copies. Never deletes original mail.
        RecordingWorkflowStore(context).use { it.revokeNaverAccount(accountKey) }
    }
}
