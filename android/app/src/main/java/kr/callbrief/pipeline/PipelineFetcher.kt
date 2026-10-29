package kr.callbrief.pipeline

/** One bounded pass over this device's outbox in the inbox repository (docs/contracts layout).
 * Only new files (path + bytes digest) are opened. Every document is opened with the device
 * encryption key and the aad implied by its file name, verified with the compiled-in server
 * signing key, validated against the locally kept C1, and only then applied. A rejected file
 * changes nothing except the fetch log. Re-fetching never re-applies. */
internal class PipelineFetcher(private val store:PipelineApplyStore,private val inbox:PipelineInbox?,private val keys:PipelineDeviceKeys?,
    private val server:PipelineServerKeys?,private val index:PipelineTranscriptIndex?,private val applier:PipelineApplier,
    private val onNewReport:(Map<String,Any?>)->Unit={},private val maxFiles:Int=200) {
    data class Result(val status:String,val applied:Int=0,val reports:Int=0,val rejected:Int=0,val skipped:Int=0,val newReportHeads:List<String> = emptyList())
    private val analysisName=Regex("([0-9a-f]{64})-r([1-9][0-9]{0,15})\\.sealed\\.json")
    private val reportName=Regex("(\\d{4}-\\d{2}-\\d{2})-r([1-9][0-9]{0,15})\\.sealed\\.json")

    fun run():Result {
        if(server==null)return Result("SERVER_KEYS_MISSING")
        if(inbox==null)return Result("INBOX_TOKEN_MISSING")
        if(keys==null || index==null)return Result("DEVICE_NOT_READY")
        val device=keys.deviceId
        val analyses=try { inbox.list(PipelineContract.Paths.analysisDir(device)) } catch(_:Exception) { return Result("INBOX_UNAVAILABLE") }
        val reports=try { inbox.list(PipelineContract.Paths.reportDir(device)) } catch(_:Exception) { return Result("INBOX_UNAVAILABLE") }
        var applied=0;var stored=0;var rejected=0;var skipped=0;var budget=maxFiles;val heads=mutableListOf<String>()
        for(name in analyses.sorted()) {
            val m=analysisName.matchEntire(name) ?: continue
            val path=PipelineContract.Paths.analysisDir(device)+"/"+name
            if(store.fetchedPath(path)) { skipped++;continue }
            if(budget--<=0)break
            val bytes=(try { inbox.get(path) } catch(_:Exception) { return Result("INBOX_UNAVAILABLE",applied,stored,rejected,skipped,heads) }) ?: continue
            val digest=PipelineContract.sha(bytes)
            val transcriptId=m.groupValues[1];val revision=m.groupValues[2].toLong()
            val outcome=try {
                val doc=PipelineContract.openFrom(PipelineContract.parse(bytes),keys.encryptionPrivate(),keys.encryptionKid,"c2:"+PipelineContract.analysisIdOf(transcriptId,revision)) { PipelineContract.validateAnalysis(it) }
                PipelineContract.verifySignature(doc,server.signing)
                PipelineContract.require(doc["deviceId"]==device,"DEVICE_MISMATCH")
                PipelineContract.require(doc["transcriptId"]==transcriptId && doc["revision"]==revision,"PATH_BINDING_MISMATCH")
                val c1=index.transcript(transcriptId) ?: throw PipelineContractException("UNKNOWN_TRANSCRIPT")
                PipelineContract.validateAnalysis(doc,c1)
                val recording=index.recordingFor(transcriptId) ?: throw PipelineContractException("UNKNOWN_TRANSCRIPT")
                val o=applier.apply(doc,c1,recording);if(o.applied>0)applied++
                if(o.state=="REJECTED")rejected++
                "ANALYSIS_"+o.state to o.reason
            } catch(e:PipelineContractException) { rejected++;"REJECTED" to e.code }
            catch(_:Exception) { null } // Local store/transient failure: not logged, retried on the next pass.
            if(outcome!=null)store.logFetch(path,digest,outcome.first,outcome.second)
        }
        for(name in reports.sorted()) {
            val m=reportName.matchEntire(name) ?: continue
            val path=PipelineContract.Paths.reportDir(device)+"/"+name
            if(store.fetchedPath(path)) { skipped++;continue }
            if(budget--<=0)break
            val bytes=(try { inbox.get(path) } catch(_:Exception) { return Result("INBOX_UNAVAILABLE",applied,stored,rejected,skipped,heads) }) ?: continue
            val digest=PipelineContract.sha(bytes)
            val outcome=try {
                val date=PipelineContract.isoDate(m.groupValues[1]);val revision=m.groupValues[2].toLong()
                val doc=PipelineContract.openFrom(PipelineContract.parse(bytes),keys.encryptionPrivate(),keys.encryptionKid,"c3:"+PipelineContract.reportIdOf(device,date,revision)) { PipelineContract.validateReport(it) }
                PipelineContract.verifySignature(doc,server.signing)
                PipelineContract.require(doc["deviceId"]==device,"DEVICE_MISMATCH")
                val head=store.tx { store.storeReport(doc) };stored++
                if(head) { heads+=date;runCatching { onNewReport(doc) } }
                "REPORT_STORED" to if(head)"NEW_HEAD" else "OLDER_REVISION"
            } catch(e:PipelineContractException) { rejected++;"REJECTED" to e.code }
            store.logFetch(path,digest,outcome.first,outcome.second)
        }
        return Result("OK",applied,stored,rejected,skipped,heads)
    }
}
