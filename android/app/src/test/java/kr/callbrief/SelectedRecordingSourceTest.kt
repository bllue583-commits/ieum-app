package kr.callbrief

import android.content.*
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kr.callbrief.core.PrivacyRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Host ContentResolver/URI/metadata route with a generated test ContentProvider and shadow grant.
 * Does NOT prove a real DocumentsUI choice, OEM provider or system permission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class])
class SelectedRecordingSourceTest : ProtectedTextHostTestBase() {
    class Provider:ContentProvider() {
        val documents=linkedMapOf<String,SourceDocument>()
        var opens=0;var queryFailure=false
        var membershipAllowed=true;var membershipResponse=true;var membershipCalls=0
        override fun onCreate()=true
        override fun call(method:String,arg:String?,extras:android.os.Bundle?):android.os.Bundle? {
            if(method=="android:isChildDocument")membershipCalls++
            return if(membershipResponse)SyntheticTreeMembershipSupport.response(method,extras,documents.keys,membershipAllowed) else null
        }

        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):android.database.Cursor? {
            if(queryFailure) throw SecurityException("GENERATED_PROVIDER_DENIED")
            val columns=projection!!.map { it }.toTypedArray();val c=MatrixCursor(columns)
            val selected=if(uri.pathSegments.contains("children")) documents.values else listOfNotNull(documents[DocumentsContract.getDocumentId(uri)])
            selected.forEach { d ->c.addRow(columns.map { name->when(name) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID->d.documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME->d.name
                DocumentsContract.Document.COLUMN_MIME_TYPE->d.mime
                DocumentsContract.Document.COLUMN_SIZE->d.size
                DocumentsContract.Document.COLUMN_LAST_MODIFIED->d.modified
                else->null
            } }.toTypedArray()) };return c
        }
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            check(mode=="r");opens++
            val file=File(context!!.cacheDir,"generated-provider-input.wav")
            file.writeBytes(GeneralLocalStoreTest.wav())
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri:Uri)="audio/wav"
        override fun insert(uri:Uri,values:ContentValues?):Uri?=error("READ_ONLY_TEST_PROVIDER")
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=error("READ_ONLY_TEST_PROVIDER")
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=error("READ_ONLY_TEST_PROVIDER")
    }
    private lateinit var context:Context
    private lateinit var settings:SecureSettings
    private lateinit var provider:Provider
    private val tree=DocumentsContract.buildTreeDocumentUri("synthetic.callbrief.test","root")
    @Before fun fresh() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).edit().clear().commit()
        val testKey=SecretKeySpec(ByteArray(32) { 8 },"AES")
        settings=SecureSettings(context,object:SecureSettings.KeyAccess {
            override fun existing():SecretKey=testKey
            override fun createForFirstEmptyStore():SecretKey=error("TEST_NO_KEY_GENERATION")
        })
        settings.update { it.put("treeUri",tree.toString()) }
        context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        provider=Provider();provider.attachInfo(context,ProviderInfo().apply { authority="synthetic.callbrief.test";grantUriPermissions=true })
        ShadowContentResolver.registerProviderInternal("synthetic.callbrief.test",provider)
        provider.documents["one"]=SourceDocument("one",DocumentsContract.buildDocumentUriUsingTree(tree,"one").toString(),"통화 녹음_합성기관 01000002222_20261010_100000.wav",52,"audio/wav",GeneralLocalStoreTest.NOW-600000)
    }
    private fun source()=SafSelectedRecordingSource(context,settings,tree.toString())
    @Test fun metadataEnumerationAndTwoReadsUseSelectedTreeUris() {
        val s=source();val inv=s.inventory();assertTrue(inv.complete);assertEquals(setOf("one"),inv.allDocumentIds)
        val d=inv.documents.single();assertEquals(d,s.stat(d));assertEquals(0,provider.opens)
        assertArrayEquals(GeneralLocalStoreTest.wav(),s.open(d).use { it.readBytes() });assertEquals(1,provider.opens)
    }
    @Test fun excludedNameIsBlockedBeforeResolverOpen() {
        settings.update { it.put("exclusions",JSONArray().put(JSONObject().put("type","alias").put("hash",PrivacyRules.hash("테스트제외")).put("length",5))) }
        provider.documents["one"]=provider.documents.getValue("one").copy(name="통화 녹음_테스트제외_20261010_100000.wav")
        val s=source();val d=s.inventory().documents.single();assertTrue(s.excluded(d))
        try { s.open(d);fail() } catch(_:IllegalStateException) {}
        assertEquals(0,provider.opens)
    }
    @Test fun releasedShadowGrantFailsClosedWithoutOpen() {
        context.contentResolver.releasePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { source().inventory();fail() } catch(_:IllegalStateException) {}
        assertEquals(0,provider.opens)
    }
    @Test fun changedSelectionAndProviderDenialDoNotBecomeEmptySuccess() {
        val s=source();settings.update { it.put("treeUri","content://different/tree/root") }
        try { s.inventory();fail() } catch(_:IllegalStateException) {}
        settings.update { it.put("treeUri",tree.toString()) };provider.queryFailure=true
        try { s.inventory();fail() } catch(_:SecurityException) {}
        assertEquals(0,provider.opens)
    }
    @Test fun C01ProviderRejectsTreeMembershipBeforeStatAndRead() {
        val s=source();val d=s.inventory().documents.single();provider.membershipAllowed=false
        assertThrows(IllegalStateException::class.java) { s.stat(d) }
        assertThrows(IllegalStateException::class.java) { s.open(d) }
        assertEquals(0,provider.opens)
    }
    @Test fun C02MissingMembershipResponseCannotAuthorizeRead() {
        val s=source();val d=s.inventory().documents.single();provider.membershipResponse=false
        assertTrue(runCatching { s.open(d) }.isFailure)
        assertEquals(0,provider.opens)
    }
    @Test fun C03ForeignUriFailsBindingBeforeMembershipRequest() {
        val s=source();val d=s.inventory().documents.single().copy(uri="content://foreign.synthetic/tree/root/document/one")
        assertThrows(IllegalStateException::class.java) { s.open(d) }
        assertEquals(0,provider.opens);assertEquals(0,provider.membershipCalls)
    }
    @Test fun C04ForgedUnknownChildCannotReadGeneratedFile() {
        val s=source();val d=s.inventory().documents.single().copy(documentId="foreign",uri=DocumentsContract.buildDocumentUriUsingTree(tree,"foreign").toString())
        assertThrows(IllegalStateException::class.java) { s.open(d) }
        assertEquals(0,provider.opens)
    }
    /** Design 3.2 #2: 2500 recordings, 2000 of them before 10/1 (2025 filename time). The old ones are dropped while
     * listing and never counted, so the three calls that just ended are all listed and nothing is truncated. */
    @Test fun R72OldRecordingsAreDroppedBeforeTheLimitSoNewCallsAreAlwaysListed() {
        provider.documents.clear()
        fun put(id:String,name:String,modified:Long){provider.documents[id]=SourceDocument(id,DocumentsContract.buildDocumentUriUsingTree(tree,id).toString(),name,52,"audio/wav",modified)}
        val recent=GeneralLocalStoreTest.NOW
        // Old files first in provider order, with a recent modified time (moved/restored): the filename time decides.
        for(i in 0 until 2000)put("old$i","통화 녹음_합성_250806_1503${"%02d".format(i%60)}_$i.wav",recent-60_000)
        for(i in 0 until 497)put("mid$i","통화 녹음_합성_261015_10${"%02d".format(i/60%60)}${"%02d".format(i%60)}.wav",recent-86_400_000)
        listOf("new1","new2","new3").forEachIndexed{i,id->put(id,"통화 녹음_합성_261104_23${"%02d".format(10+i)}00.wav",recent)}
        val s=SafSelectedRecordingSource(context,settings,tree.toString(),kr.callbrief.pipeline.FirstPass.FROM_MS)
        val inv=s.inventory()
        assertEquals(500,inv.documents.size);assertFalse(inv.truncated);assertTrue(inv.complete)
        assertTrue(inv.documents.map{it.documentId}.containsAll(listOf("new1","new2","new3")))
        assertTrue(inv.documents.none{it.documentId.startsWith("old")})
        // Without a filename time the modified time decides.
        put("plain","SYNTHETIC_PLAIN.wav",kr.callbrief.pipeline.FirstPass.FROM_MS-1)
        assertTrue(s.inventory().documents.none{it.documentId=="plain"})
    }
    /** More recordings from 10/1 on than the limit: the newest are kept and the listing says it was truncated. */
    @Test fun R72OverTheLimitKeepsTheNewestAndMarksTruncated() {
        provider.documents.clear()
        for(i in 0 until 30){val id="d$i";provider.documents[id]=SourceDocument(id,DocumentsContract.buildDocumentUriUsingTree(tree,id).toString(),"통화 녹음_합성_2610${"%02d".format(i/24+10)}_${"%02d".format(i%24)}0000.wav",52,"audio/wav",GeneralLocalStoreTest.NOW)}
        val inv=SafSelectedRecordingSource(context,settings,tree.toString(),kr.callbrief.pipeline.FirstPass.FROM_MS,keepLimit=10).inventory()
        assertTrue(inv.truncated);assertFalse(inv.complete)
        assertEquals((20 until 30).map{"d$it"}.toSet(),inv.documents.map{it.documentId}.toSet())
    }
    @Test fun C05OlderApiHoldsBeforeUnsupportedMembershipRpc() {
        val s=source();val d=s.inventory().documents.single();val original=android.os.Build.VERSION.SDK_INT
        try {
            // API35 host runtime with a synthetic SDK value; not an actual API28 device run.
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java,"SDK_INT",28)
            val error=runCatching { s.open(d) }.exceptionOrNull()
            assertEquals("SOURCE_TREE_MEMBERSHIP_REQUIRES_API29",error?.message)
            assertEquals(0,provider.opens);assertEquals(0,provider.membershipCalls)
        } finally { org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java,"SDK_INT",original) }
    }
}
