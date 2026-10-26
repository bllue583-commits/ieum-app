package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import org.json.JSONObject

/** Normal selected-folder intake -> encrypted native vault -> bound Drive request -> read-only inbox. */
internal class GoogleNativePipeline(context:Context,private val session:GoogleConnectionSession,
    private val settings:SecureSettings,private val selected:()->SelectedRecordingSource,
    transport:DriveTransport,private val postingCapability:(String)->Boolean={FrozenDotCapability(context).supports(it)},
    createNative:(Context)->GeneralLocalStore=::GeneralLocalStore) : AutoCloseable {
    private val owner=session.ownerBinding();private val epoch=session.epoch()
    private val scoped=AccountStorageContext(context,owner)
    private val native:GeneralLocalStore
    private val sources:GoogleNativeSources
    private val relay:GoogleRelayStore
    init {
        session.nativeSessionBinding();require(owner.isNotBlank())
        native=session.commit(epoch,owner) { createNative(scoped) }
        sources=GoogleNativeSources(scoped,session,settings,native,selected)
        try { relay=GoogleRelayStore(context,session,transport,sources,postingCapability) } catch(e:Exception) { sources.close();native.close();throw e }
    }
    fun setupState()=native.setupState()
    fun initializeEmpty()=session.commit(epoch,owner) { native.initializeEmpty() }
    fun importAndSubmit(document:SourceDocument,confirmedSyntheticCreator:Boolean):Pair<DriveRelay.Reply,JSONObject> {
        check(accountSupported()) { "DOT_PROFILE_ACCOUNT_UNSUPPORTED" }
        val source=selected();source.checkSelected();check(!source.excluded(document)) { "EXCLUDED_BEFORE_READ" }
        SyntheticApprovedSource.approve(settings,session,source,document,confirmedSyntheticCreator)
        val report=native.importSelected(SyntheticApprovedSource(source,document,session,settings))
        check(report.excluded==0 && report.held==0 && report.newVersions+report.sameContent==1) { "NATIVE_INTAKE_HELD" }
        val record=native.snapshot().let { a ->(0 until a.length()).map { a.getJSONObject(it) }.single { it.optString("actual_uri")==document.uri } }
        val reply=relay.submitNative(record.getString("file_id"))
        val binding=relay.nativeRegistration(reply.jobId)
        return reply to binding
    }
    fun submitExisting(recording:String,onReserved:(LocalJobStore.Job)->Unit={})=relay.submitNative(recording,onReserved)
    fun resume(jobId:String,onReserved:(LocalJobStore.Job)->Unit)=relay.resumeNative(jobId,onReserved)
    fun poll(jobId:String)=relay.pollReadOnly(jobId)
    fun pollObservation(jobId:String)=relay.pollObservation(jobId)
    fun retry(jobId:String,onReserved:(LocalJobStore.Job)->Unit={})=relay.retryFailed(jobId,onReserved)
    fun registration(jobId:String)=relay.nativeRegistration(jobId)
    fun driveAExport(jobId:String)=relay.driveAExport(jobId)
    fun driveAReopenedReceipt(jobId:String)=relay.driveAReopenedReceipt(jobId)
    fun driveAFrozenRequest(jobId:String)=relay.driveAFrozenRequest(jobId)
    fun receiveAutomationProposal(jobId:String,bytes:ByteArray,businessContextSha:String)=relay.receiveAutomationProposal(jobId,bytes,businessContextSha)
    fun accountSupported()=postingCapability(session.driveAccount())
    fun jobs()=relay.list()
    fun cancel()=native.cancel()
    override fun close() { relay.close();sources.close();native.close() }
}
