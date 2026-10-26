package kr.callbrief

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kr.callbrief.core.AudioPolicy
import java.io.InputStream

/** Metadata supplied by a child listing of the selected tree, never by a filename-derived URI. */
data class SourceDocument(val documentId: String, val uri: String, val name: String,
                          val size: Long, val mime: String, val modified: Long,val partial:Boolean=false,val virtual:Boolean=false)
data class SourceInventory(val documents: List<SourceDocument>, val allDocumentIds: Set<String>, val complete: Boolean)

interface SelectedRecordingSource {
    val rootUri: String
    // Personal-data admission stays closed until device/signing/migration acceptance.
    val explicitlySyntheticScope: Boolean get() = false
    fun checkSelected()
    fun inventory(): SourceInventory
    fun stat(document: SourceDocument): SourceDocument
    fun excluded(document: SourceDocument): Boolean
    // Every production source must attest its pre-read identity/exclusion policy explicitly.
    fun checkReadable(document:SourceDocument):Unit = error("SOURCE_IDENTITY_POLICY_REQUIRED")
    fun open(document: SourceDocument): InputStream
}

/** Uses only an existing persisted READ grant. No grant creation, broad storage or deletion. */
class SafSelectedRecordingSource(private val context: Context, private val settings: SecureSettings,
                                 override val rootUri: String) : SelectedRecordingSource {
    private val resolver = context.contentResolver
    private val tree = Uri.parse(rootUri)
    override fun checkSelected() {
        check(settings.snapshot().optString("treeUri") == rootUri) { "SELECTED_FOLDER_CHANGED" }
        check(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) { "SELECTED_FOLDER_READ_GRANT_REQUIRED" }
        check(DocumentsContract.isTreeUri(tree)) { "SELECTED_TREE_REQUIRED" }
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
        queue.add(DocumentsContract.getTreeDocumentId(tree) to 0);var complete=true
        while(queue.isNotEmpty()) {
            checkSelected();val (parent,depth)=queue.removeFirst()
            val uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
            val cursor=resolver.query(uri,columns,null,null,null) ?: error("SELECTED_FOLDER_LIST_FAILED")
            cursor.use {
                // A loading child listing is partial even when no explicit size/depth limit is hit.
                complete = providerListingComplete(it) && complete
                while(it.moveToNext()) {
                    val d=row(it)
                    if(!ids.add(d.documentId)) { complete=false;continue }
                    if(ids.size>2000) { complete=false;queue.clear();break }
                    if(d.mime==DocumentsContract.Document.MIME_TYPE_DIR) {
                        if(depth<4) queue.add(d.documentId to depth+1) else complete=false
                    } else if(d.mime.startsWith("audio/") || AudioPolicy.mime(d.name)!=null) documents.add(d)
                }
                complete = providerListingComplete(it) && complete
            }
        }
        return SourceInventory(documents,ids,complete)
    }
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
        return LocalPrivacy.blocked(RecordingRef(Uri.parse(document.uri),document.name,document.size,document.mime,null,document.modified),settings.snapshot())
    }
    override fun open(document: SourceDocument): InputStream {
        checkReadable(document)
        return resolver.openInputStream(Uri.parse(document.uri)) ?: error("SOURCE_READ_FAILED")
    }
    internal fun openDeclaredSynthetic(document:SourceDocument):InputStream {
        checkSelected();check(!excluded(document)) { "EXCLUDED_BEFORE_READ" }
        return resolver.openInputStream(Uri.parse(document.uri)) ?: error("SOURCE_READ_FAILED")
    }
    override fun checkReadable(document:SourceDocument) {
        checkDocument(document)
        LocalPrivacy.requireCollectionAllowed(RecordingRef(Uri.parse(document.uri),document.name,document.size,document.mime,null,document.modified),settings.snapshot())
    }
}
