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
    private val config:Config=Config.conservative(),
    private val fault:FaultHook=FaultHook {}
) : AutoCloseable {
    private val queue=store.discoveryQueue(selection)
    private var lastDocuments=emptyMap<String,SourceDocument>()
    private fun document(m:Metadata):SourceDocument=lastDocuments[m.documentId] ?: store.discoveryDocument(selection,m)
    private val documents=object:Documents {
        override fun hasReadGrant(s:Selection):Boolean=s==selection && runCatching { source.checkSelected() }.isSuccess
        override fun listPage(s:Selection,cursor:String?,limit:Int):Page {
            check(s==selection) { "DISCOVERY_SCOPE_MISMATCH" };source.checkSelected()
            val inventory=source.inventory();lastDocuments=inventory.documents.associateBy { it.documentId }
            val sorted=inventory.documents.sortedBy { it.documentId }.filter { cursor==null || it.documentId>cursor }
            val page=sorted.take(limit)
            return Page(page.map { metadata(it) },if(sorted.size>limit)page.last().documentId else null,inventory.complete)
        }
        override fun acceptPage(s:Selection,page:Page) {
            check(s==selection);source.checkSelected()
            val accepted=page.documents.map { m->checkNotNull(lastDocuments[m.documentId]).also{check(metadata(it)==m) { "SOURCE_METADATA_CHANGED" }} }
            store.rememberDiscoveryDocuments(selection,accepted.filter { !source.excluded(it) })
        }
        override fun stat(s:Selection,id:String):Metadata {
            check(s==selection);val d=lastDocuments[id] ?: store.discoveryDocumentById(selection,id,queue.snapshot())
            val current=source.stat(d);check(current.documentId==id && current.uri==d.uri) { "SOURCE_IDENTITY_CHANGED" };return metadata(current)
        }
        override fun open(s:Selection,id:String):InputStream {
            check(s==selection);val d=lastDocuments[id] ?: store.discoveryDocumentById(selection,id,queue.snapshot())
            source.checkSelected();source.checkReadable(d)
            return object:java.io.FilterInputStream(source.open(d)) {
                private fun permit(){source.checkSelected();source.checkReadable(d)}
                override fun read():Int {permit();return `in`.read()}
                override fun read(b:ByteArray,o:Int,n:Int):Int {permit();return `in`.read(b,o,n)}
            }
        }
    }
    private fun metadata(d:SourceDocument)=Metadata(d.documentId,d.name,d.mime,
        d.size.takeIf { it>=0 },d.modified.takeIf { it>0 },d.partial || d.modified>now()-120000,d.virtual)
    private val policy=Policy { selected,m ->
        check(selected==selection);source.checkSelected();val d=document(m)
        val permission=if(source.excluded(d))Permission.EXCLUDED else try {source.checkReadable(d);Permission.CONFIRMED_ALLOWED}
            catch(error:Exception){source.checkSelected();Permission.UNCONFIRMED}
        // Only privacy fields are inspected; account tokens and other settings are not copied.
        val state=privacySnapshot();val privacy=JSONObject()
        listOf("treeUri","personalPhoneDisabled","personalAliasDisabled","exclusions","identities","aliasMappings").forEach {key->
            if(state.has(key))privacy.put(key,state.get(key)) }
        val fingerprint=JsonCanonical.digest(SqlCallRepository.map("privacy",privacy.toString(),"selection",selection.revision))
        val revision=queue.policyRevision(fingerprint)
        val subject=if(permission==Permission.CONFIRMED_ALLOWED)JsonCanonical.digest(SqlCallRepository.map("root",selection.treeUri,"document",m.documentId,"privacy",fingerprint)) else null
        Decision(permission,revision,subject,syntheticFixture)
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
    fun cycle(pages:Int=4,jobs:Int=4):DiscoveryPump.Cycle {
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
        return DiscoveryPump(engine).run(pages,jobs)
    }
    fun cancel(){engine.cancelRun();store.cancel()}
    override fun close(){engine.cancelRun();store.close()}
}
