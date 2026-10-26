package kr.callbrief

import kr.co.callsummary.core.JsonCanonical
import kr.co.callsummary.core.SqlCallRepository
import org.json.JSONObject

/** One user-selected synthetic document, through the same SAF/exclusion/encrypted intake path. */
internal class SyntheticApprovedSource(private val delegate:SelectedRecordingSource,private val selected:SourceDocument,
    private val session:GoogleConnectionSession,private val settings:SecureSettings) : SelectedRecordingSource {
    private val owner=session.ownerBinding();private val epoch=session.epoch()
    override val explicitlySyntheticScope=true
    override val rootUri=delegate.rootUri
    override fun checkSelected() { session.commit(epoch,owner) { delegate.checkSelected() } }
    override fun inventory():SourceInventory {
        checkSelected();val full=delegate.inventory();check(full.documents.count { it.documentId==selected.documentId && it==selected }==1) { "SELECTED_SYNTHETIC_DOCUMENT_CHANGED" }
        return SourceInventory(listOf(selected),full.allDocumentIds,full.complete)
    }
    override fun stat(document:SourceDocument)=delegate.stat(document)
    override fun excluded(document:SourceDocument)=delegate.excluded(document)
    override fun checkReadable(document:SourceDocument) {
        checkSelected();check(!delegate.excluded(document)) { "EXCLUDED_BEFORE_READ" }
        check(document==selected && delegate.stat(document)==selected) { "SELECTED_SYNTHETIC_DOCUMENT_CHANGED" }
        val approved=settings.snapshot().optJSONObject("nativeSyntheticApprovals")?.optJSONObject(approvalKey(rootUri,document))
        check(approved!=null && approved.optBoolean("syntheticCreatorConfirmed") && approved.optString("ownerId")==owner && approved.optLong("authEpoch",-1)==epoch) { "SYNTHETIC_CREATOR_CONFIRMATION_REQUIRED" }
    }
    override fun open(document:SourceDocument):java.io.InputStream {
        return session.commit(epoch,owner) {
        checkReadable(document)
        // The SAF delegate's normal counterpart gate deliberately stays strict for real calls.
        // This declared-synthetic scope uses its resolver after the explicit metadata approval.
        if(delegate is SafSelectedRecordingSource)delegate.openDeclaredSynthetic(document) else delegate.open(document)
        }
    }
    companion object {
        fun approvalKey(root:String,d:SourceDocument)=JsonCanonical.digest(SqlCallRepository.map("root",root,"document_id",d.documentId,"uri",d.uri,"name",d.name,"size",d.size,"mime",d.mime,"modified",d.modified))
        fun approve(settings:SecureSettings,session:GoogleConnectionSession,source:SelectedRecordingSource,d:SourceDocument,confirmed:Boolean) {
            require(confirmed) { "SYNTHETIC_CREATOR_CONFIRMATION_REQUIRED" }
            val v=session.view();session.commit(v.epoch,v.ownerId) {
                source.checkSelected();check(!source.excluded(d)) { "EXCLUDED_BEFORE_READ" };check(source.stat(d)==d)
                settings.update { state ->val a=state.optJSONObject("nativeSyntheticApprovals") ?: JSONObject()
                    a.put(approvalKey(source.rootUri,d),JSONObject().put("syntheticCreatorConfirmed",true).put("ownerId",v.ownerId).put("authEpoch",v.epoch))
                    state.put("nativeSyntheticApprovals",a)
                }
            }
        }
    }
}
