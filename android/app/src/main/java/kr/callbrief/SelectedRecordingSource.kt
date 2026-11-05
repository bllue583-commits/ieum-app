package kr.callbrief

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kr.callbrief.core.AudioPolicy
import org.json.JSONObject
import java.io.InputStream

/** Metadata supplied by a child listing of the selected tree, never by a filename-derived URI. */
data class SourceDocument(val documentId: String, val uri: String, val name: String,
                          val size: Long, val mime: String, val modified: Long,val partial:Boolean=false,val virtual:Boolean=false)
/** [truncated]: more recordings from 10/1 on than the listing keeps (heartbeat inventoryTruncated); the newest were kept. */
data class SourceInventory(val documents: List<SourceDocument>, val allDocumentIds: Set<String>, val complete: Boolean,
                           val truncated: Boolean = false)

interface SelectedRecordingSource {
    val rootUri: String
    /** Public synthetic fixtures (tests). */
    val explicitlySyntheticScope: Boolean get() = false
    /** Real recordings may be read only after the user turned on "정리 동의" for this folder. */
    val collectionAccepted: Boolean get() = explicitlySyntheticScope
    fun checkSelected()
    fun inventory(): SourceInventory
    fun stat(document: SourceDocument): SourceDocument
    fun excluded(document: SourceDocument): Boolean
    // Every production source must attest its pre-read identity/exclusion policy explicitly.
    fun checkReadable(document:SourceDocument):Unit = error("SOURCE_IDENTITY_POLICY_REQUIRED")
    fun open(document: SourceDocument): InputStream
    /** Between [beginBatch] and [endBatch] (one metadata page) the source may read its settings once instead of per file. */
    fun beginBatch() {}
    fun endBatch() {}
}

/** Uses only an existing persisted READ grant. No grant creation, broad storage or deletion.
 * [cutoffMs]: recordings from before this (filename time first, else modified time) are dropped while the folder is
 * listed and never counted (design 3.2 #2). [keepLimit] applies to the recordings that are kept. */
class SafSelectedRecordingSource(private val context: Context, private val settings: SecureSettings,
                                 override val rootUri: String, private val cutoffMs: Long = 0L,
                                 private val keepLimit: Int = KEEP_LIMIT) : SelectedRecordingSource {
    companion object {
        /** Recordings kept from one listing (the old limit counted every row, old files and folders included). */
        const val KEEP_LIMIT=2000
        /** Rows looked at in one listing at most, old ones included, so a huge folder cannot keep a pass busy. */
        const val SCAN_LIMIT=50_000
    }
    private val resolver = context.contentResolver
    // One page of decisions reads the settings once (review pipeline #7: 2 to 4 Keystore decrypts per file before).
    private var batchDepth=0;private var batchState:JSONObject?=null;private var batchSelected=false
    override fun beginBatch() { if(batchDepth++==0){batchState=null;batchSelected=false} }
    override fun endBatch() { if(batchDepth>0 && --batchDepth==0){batchState=null;batchSelected=false} }
    private fun state():JSONObject = if(batchDepth>0) batchState ?: settings.snapshot().also { batchState=it } else settings.snapshot()
    override val collectionAccepted: Boolean get() = runCatching { state().let {
        it.optBoolean(kr.callbrief.pipeline.PipelineSettingsKeys.CONSENT_GRANTED,false) && it.optString("treeUri")==rootUri } }.getOrDefault(false)
    private val tree = Uri.parse(rootUri)
    override fun checkSelected() {
        if(batchDepth>0 && batchSelected) return
        check(state().optString("treeUri") == rootUri) { "SELECTED_FOLDER_CHANGED" }
        check(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) { "SELECTED_FOLDER_READ_GRANT_REQUIRED" }
        check(DocumentsContract.isTreeUri(tree)) { "SELECTED_TREE_REQUIRED" }
        if(batchDepth>0) batchSelected=true
    }
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_FLAGS)
    private fun row(cursor: android.database.Cursor): SourceDocument {
        val id = cursor.getString(0)
        return SourceDocument(id, DocumentsContract.buildDocumentUriUsingTree(tree,id).toString(), cursor.getString(1).orEmpty(),
            if(cursor.isNull(3)) -1 else cursor.getLong(3),cursor.getString(2).orEmpty(),if(cursor.isNull(4)) -1 else cursor.getLong(4),
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS).let { it<0 || cursor.isNull(it) || (cursor.getInt(it) and 8192)!=0 },
            cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS).let { it>=0 && !cursor.isNull(it) && (cursor.getInt(it) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT)!=0 })
    }
    override fun inventory(): SourceInventory {
        checkSelected()
        val documents=mutableListOf<SourceDocument>();val ids=mutableSetOf<String>();val queue=ArrayDeque<Pair<String,Int>>()
        queue.add(DocumentsContract.getTreeDocumentId(tree) to 0);var complete=true;var truncated=false;var scanned=0
        // When more recordings than the limit are left, the newest are kept so a call that just ended is always listed.
        val newest=java.util.PriorityQueue<SourceDocument>(compareBy<SourceDocument>({ it.order() },{ it.documentId }))
        outer@ while(queue.isNotEmpty()) {
            checkSelected();val (parent,depth)=queue.removeFirst()
            val uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
            val cursor=resolver.query(uri,columns,null,null,null) ?: error("SELECTED_FOLDER_LIST_FAILED")
            cursor.use {
                // A loading child listing is partial even when no explicit size/depth limit is hit.
                complete = providerListingComplete(it) && complete
                while(it.moveToNext()) {
                    if(++scanned>SCAN_LIMIT) { complete=false;truncated=true;queue.clear();break }
                    val d=row(it)
                    if(!ids.add(d.documentId)) { complete=false;continue }
                    if(d.mime==DocumentsContract.Document.MIME_TYPE_DIR) {
                        if(depth<4) queue.add(d.documentId to depth+1) else complete=false
                    } else if(d.mime.startsWith("audio/") || AudioPolicy.mime(d.name)!=null) {
                        if(DiscoveryCutoff.before(d.name,d.modified,cutoffMs)) continue // dropped, not counted
                        newest.add(d)
                        if(newest.size>keepLimit) { newest.poll();complete=false;truncated=true }
                    }
                }
                complete = providerListingComplete(it) && complete
            }
        }
        documents.addAll(newest)
        return SourceInventory(documents,ids,complete,truncated)
    }
    private fun SourceDocument.order():Long = DiscoveryCutoff.startedAtMs(name) ?: modified
    private fun providerListingComplete(cursor: android.database.Cursor): Boolean {
        val extras = cursor.extras
        // Provider error strings may contain private source details; expose only a typed failure.
        check(extras?.containsKey(DocumentsContract.EXTRA_ERROR) != true) { "PROVIDER_LIST_FAILED" }
        return extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) != true
    }
    private fun checkDocument(document:SourceDocument) {
        checkSelected()
        check(Uri.parse(document.uri)==DocumentsContract.buildDocumentUriUsingTree(tree,document.documentId)) { "SOURCE_URI_BINDING_MISMATCH" }
        if(android.os.Build.VERSION.SDK_INT<29)error("SOURCE_TREE_MEMBERSHIP_REQUIRES_API29")
        val root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        check(DocumentsContract.isChildDocument(resolver,root,Uri.parse(document.uri))) { "SOURCE_OUTSIDE_SELECTED_TREE" }
    }
    override fun stat(document: SourceDocument): SourceDocument {
        checkDocument(document)
        val cursor=resolver.query(Uri.parse(document.uri),columns,null,null,null) ?: error("SOURCE_METADATA_UNAVAILABLE")
        return cursor.use { check(it.moveToFirst()) { "SOURCE_METADATA_UNAVAILABLE" };row(it) }
    }
    override fun excluded(document: SourceDocument): Boolean {
        checkSelected()
        // Metadata-only filter, including existing rules; no identity assignment or settings update.
        return LocalPrivacy.blocked(RecordingRef(Uri.parse(document.uri),document.name,document.size,document.mime,null,document.modified),state())
    }
    override fun open(document: SourceDocument): InputStream {
        checkReadable(document)
        return resolver.openInputStream(Uri.parse(document.uri)) ?: error("SOURCE_READ_FAILED")
    }
    override fun checkReadable(document:SourceDocument) {
        checkDocument(document)
        LocalPrivacy.requireCollectionAllowed(RecordingRef(Uri.parse(document.uri),document.name,document.size,document.mime,null,document.modified),state())
    }
}
