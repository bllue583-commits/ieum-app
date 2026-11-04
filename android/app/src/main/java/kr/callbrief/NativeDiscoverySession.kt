package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.JsonCanonical
import kr.co.callsummary.core.SqlCallRepository
import kr.co.ieum.discovery.*
import kr.co.ieum.discovery.DiscoveryTypes.*
import org.json.JSONObject
import java.io.InputStream

/** Code13 native bootstrap, immutable receipts and local encrypted intake; no transfer API. */
internal class NativeDiscoverySession(
    private val store:GeneralLocalStore,
    private val source:SelectedRecordingSource,
    private val selection:Selection,
    private val privacySnapshot:()->JSONObject,
    private val clock:Clock,
    private val now:()->Long={System.currentTimeMillis()},
    private val syntheticFixture:Boolean=false,
    config:Config=Config.conservative(),
    private val fault:FaultHook=FaultHook {},
    /** Recordings from before this are never looked at (the first pass reaches back to 10/1 only): the filename time
     * decides when the name has one, otherwise the modified time (design 3.2 #1, review pipeline #4). */
    private val minModifiedMs:Long=0L
) : AutoCloseable {
    /** The size line is the lower of [config] and what the local store's database accepts (an older store keeps 25 MB
     * until GeneralLocalStore widened it), so a file the store would refuse is held instead of failing over and over. */
    private val config:Config=config.withMaxBytes(store.audioByteLimit())
    /** True when the last listing kept only the newest recordings (heartbeat inventoryTruncated). */
    @Volatile var lastListingTruncated=false;private set
    private val queue=store.discoveryQueue(selection)
    private var lastDocuments=emptyMap<String,SourceDocument>()
    /** Inside [cycle] the folder is listed once and its pages come from this copy; a new round lists again only after the quiet time. */
    private class Listing(val documents:List<SourceDocument>,val complete:Boolean,val truncated:Boolean,val bootId:String,val readAt:Long)
    private var inCycle=false
    private var listing:Listing?=null
    private fun listing(fresh:Boolean):Listing {
        val cached=listing;val at=clock.now()
        if(inCycle&&cached!=null&&(!fresh||(cached.bootId==at.bootId&&at.elapsedMs-cached.readAt in 0 until config.quietMs)))return cached
        val inventory=source.inventory()
        // A source that does not drop old files itself (tests, older sources) is filtered here, before the page limits.
        val kept=inventory.documents.filter { !DiscoveryCutoff.before(it.name,it.modified,minModifiedMs) }
        return Listing(kept.sortedBy { it.documentId },inventory.complete,inventory.truncated,at.bootId,at.elapsedMs).also {
            lastDocuments=it.documents.associateBy { d->d.documentId };lastListingTruncated=it.truncated;if(inCycle)listing=it }
    }
    private fun skipped(d:SourceDocument)=DiscoveryCutoff.before(d.name,d.modified,minModifiedMs) || source.excluded(d)
    /** Inside one page's decisions the privacy settings are read and digested once (review pipeline #7). */
    private var pageDecisions=0;private var pageRevision:Pair<String,Long>?=null
    private fun document(m:Metadata):SourceDocument=lastDocuments[m.documentId] ?: store.discoveryDocument(selection,m)
    private val documents=object:Documents {
        override fun hasReadGrant(s:Selection):Boolean=s==selection && runCatching { source.checkSelected() }.isSuccess
        override fun listPage(s:Selection,cursor:String?,limit:Int):Page {
            check(s==selection) { "DISCOVERY_SCOPE_MISMATCH" };source.checkSelected()
            val inventory=listing(fresh=cursor==null)
            val sorted=inventory.documents.filter { cursor==null || it.documentId>cursor }
            val page=sorted.take(limit)
            return Page(page.map { metadata(it) },if(sorted.size>limit)page.last().documentId else null,inventory.complete)
        }
        override fun beginDecisions(s:Selection){if(pageDecisions++==0){pageRevision=null;source.beginBatch()}}
        override fun endDecisions(s:Selection){if(pageDecisions>0&&--pageDecisions==0){pageRevision=null;source.endBatch()}}
        override fun acceptPage(s:Selection,page:Page) {
            check(s==selection);source.checkSelected()
            val accepted=page.documents.map { m->checkNotNull(lastDocuments[m.documentId]).also{check(metadata(it)==m) { "SOURCE_METADATA_CHANGED" }} }
            store.rememberDiscoveryDocuments(selection,accepted.filter { !skipped(it) })
        }
        override fun stat(s:Selection,id:String):Metadata {
            check(s==selection);val d=lastDocuments[id] ?: store.discoveryDocumentById(selection,id,queue.snapshot())
            val current=source.stat(d);check(current.documentId==id && current.uri==d.uri) { "SOURCE_IDENTITY_CHANGED" };return metadata(current)
        }
        override fun open(s:Selection,id:String):InputStream {
            check(s==selection);val d=lastDocuments[id] ?: store.discoveryDocumentById(selection,id,queue.snapshot())
            // The source checks folder grant and contact rules once here; reads after that only watch the stop flag (RecordingDiscovery.read).
            return source.open(d)
        }
    }
    private fun metadata(d:SourceDocument)=Metadata(d.documentId,d.name,d.mime,
        d.size.takeIf { it>=0 },d.modified.takeIf { it>0 },d.partial || d.modified>now()-120000,d.virtual)
    private val policy=Policy { selected,m ->
        check(selected==selection);source.checkSelected();val d=document(m)
        val permission=if(skipped(d))Permission.EXCLUDED else try {source.checkReadable(d);Permission.CONFIRMED_ALLOWED}
            catch(error:Exception){source.checkSelected();Permission.UNCONFIRMED}
        val (fingerprint,revision)=policyRevision()
        val subject=if(permission==Permission.CONFIRMED_ALLOWED)JsonCanonical.digest(SqlCallRepository.map("root",selection.treeUri,"document",m.documentId,"privacy",fingerprint)) else null
        Decision(permission,revision,subject,syntheticFixture)
    }
    private fun policyRevision():Pair<String,Long> {
        if(pageDecisions>0)pageRevision?.let{return it}
        // Only privacy fields are inspected; account tokens and other settings are not copied.
        val state=privacySnapshot();val privacy=JSONObject()
        listOf("treeUri","personalPhoneDisabled","personalAliasDisabled","exclusions","identities","aliasMappings").forEach {key->
            if(state.has(key))privacy.put(key,state.get(key)) }
        val fingerprint=JsonCanonical.digest(SqlCallRepository.map("privacy",privacy.toString(),"selection",selection.revision))
        return (fingerprint to queue.policyRevision(fingerprint)).also { if(pageDecisions>0)pageRevision=it }
    }
    private val sink=object:Sink {
        override fun recover(selected:Selection,operationId:String):Receipt? {
            check(selected==selection);return store.recoverDiscoveryReceipt(selection,operationId)
        }
        override fun publish(frozen:Commit,plaintext:ByteArray,guard:Guard):Receipt {
            permit(frozen,guard);return store.collectDiscovery(frozen,plaintext,{ permit(frozen,guard) },fault)
        }
        override fun sameContent(frozen:Commit,guard:Guard):Receipt {
            permit(frozen,guard);return store.observeDiscoverySame(frozen,{permit(frozen,guard)},fault)
        }
        private fun permit(c:Commit,g:Guard) {
            check(source.collectionAccepted) { "PERSONAL_DATA_COLLECTION_NOT_ACCEPTED" }
            check(c.selection==selection && g.allowed()) { "DISCOVERY_INTAKE_LEASE_CHANGED" }
            source.checkSelected();source.checkReadable(document(c.metadata))
        }
    }
    val engine=RecordingDiscovery(queue,documents,policy,sink,selection,clock,config,fault,
        Bootstrap { selected,m,_ ->store.discoverySeed(selected,m) })
    /** [budgetMs]>0: keep going until the line is empty or the time is up; otherwise one bounded pass of [pages] and [jobs]. */
    fun cycle(pages:Int=4,jobs:Int=4,budgetMs:Long=0L):DiscoveryPump.Cycle {
        inCycle=true;listing=null
        try { return pass(pages,jobs,budgetMs) } finally { inCycle=false;listing=null }
    }
    private fun pass(pages:Int,jobs:Int,budgetMs:Long):DiscoveryPump.Cycle {
        if(!source.collectionAccepted) {
            val result=DiscoveryPump.Cycle();repeat(pages){result.observed+=engine.scanPage()};result.localOutcomes.add("PERSONAL_DATA_COLLECTION_NOT_ACCEPTED");return result
        }
        // First run after consent: create the on-device vault key once (no existing data to review).
        if(store.setupState()=="EMPTY") runCatching { store.initializeEmpty() }
        else if(store.setupState()=="PENDING") runCatching { store.recoverInitialization() }
        if(store.setupState()!="READY") {
            // Metadata queue can work without login or an initialized audio key. No auto key creation.
            val result=DiscoveryPump.Cycle();repeat(pages){result.observed+=engine.scanPage()};result.localOutcomes.add("AUDIO_STORE_INITIALIZATION_REQUIRED");return result
        }
        return if(budgetMs>0) DiscoveryPump(engine).runBudget(pages,budgetMs,config.quietMs) else DiscoveryPump(engine).run(pages,jobs)
    }
    /** Files the line has seen so far (for "was something new seen in this pass"). */
    fun knownFiles():Int=queue.snapshot().rows.size
    /** Recordings held because they are larger than the size line (heartbeat heldUnsupported, review pipeline #13). */
    fun heldTooLarge():Int=queue.snapshot().rows.values.count { it.status==Status.HELD_UNSUPPORTED && (it.metadata.bytes ?: 0L)>config.maxBytes }
    fun cancel(){engine.cancelRun();store.cancel()}
    override fun close(){engine.cancelRun();store.close()}
}
