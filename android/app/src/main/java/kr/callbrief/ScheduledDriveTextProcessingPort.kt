package kr.callbrief

import android.content.Context
import android.os.Looper
import kr.callagent.drive.*
import java.time.Instant

/** Exact-ID reader for actual scheduled dot text results. Own namespace; never native-audio completion. */
internal data class ScheduledDriveTextPins(val job:String,val folder:String,val input:String,val result:String,
    val inputSha:String,val resultSha:String,val notBefore:Instant)
internal class ScheduledDriveTextProcessingPort(private val session:GoogleConnectionSession,
    private val transport:DriveTransport=DriveRestTransport(session,GoogleDriveHttp(session)),private val clock:()->Instant=Instant::now) {
    data class Observation(val proposal:ScheduledTextProofCodec.Proposal,val inputVersion:String,val resultVersion:String) {
        val readbackVerified=true;val nativeAudioRequestCompleted=false;val canonicalWriteAllowed=false
        val sttExecuted=false;val kakaoSent=false;val schedulerTriggerIndependentlyVerified=false
    }
    private fun metadata(id:String,parent:String?,mime:String,epoch:Long,owner:String):DriveTransport.Metadata {
        session.requireEpoch(epoch);check(session.state()==AuthorizationPort.State.ACTIVE) { "GOOGLE_DRIVE_AUTH_REQUIRED" }
        val m=transport.metadata(id)
        check(m.id==id && m.mimeType==mime && m.ownerBinding==owner && m.privateToOwner && !m.trashed && m.isAppAuthorized && (parent==null || m.parentId==parent)) { "SCHEDULED_DRIVE_PRIVATE_APP_FILE_ACCESS_REQUIRED" }
        return m
    }
    private fun read(id:String,parent:String,epoch:Long,owner:String):Pair<ByteArray,String> {
        val before=metadata(id,parent,DriveTransport.JSON_MIME,epoch,owner);check(before.size in 1..65536) { "SCHEDULED_PROOF_SIZE" }
        val bytes=transport.download(id,65536).bytes;val after=metadata(id,parent,DriveTransport.JSON_MIME,epoch,owner)
        check(before.version==after.version && before.size==after.size && after.size==bytes.size.toLong()) { "SCHEDULED_PROOF_CHANGED_DURING_READ" };return bytes to after.version
    }
    fun read(pins:ScheduledDriveTextPins):Observation {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "SCHEDULED_PROOF_IO_THREAD_REQUIRED" }
        val owner=session.ownerBinding();val epoch=session.epoch();session.nativeSessionBinding()
        metadata(pins.folder,null,DriveTransport.FOLDER_MIME,epoch,owner)
        val input=read(pins.input,pins.folder,epoch,owner);val result=read(pins.result,pins.folder,epoch,owner)
        // Cross-file read is bounded but never called a remote atomic CAS.
        check(metadata(pins.input,pins.folder,DriveTransport.JSON_MIME,epoch,owner).version==input.second && metadata(pins.result,pins.folder,DriveTransport.JSON_MIME,epoch,owner).version==result.second) { "SCHEDULED_PROOF_CHANGED_DURING_READ" }
        val proposal=ScheduledTextProofCodec().validate(input.first,result.first,pins.job,pins.input,pins.inputSha,pins.resultSha,pins.notBefore,clock())
        return session.commit(epoch,owner) { Observation(proposal,input.second,result.second) }
    }
}
