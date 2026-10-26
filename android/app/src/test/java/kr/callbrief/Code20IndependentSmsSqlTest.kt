package kr.callbrief

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import kr.callbrief.core.PrivacyRules
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthetic SQLite provider executes the production WHERE clause, unlike a MatrixCursor
 * which returns every row regardless of selection. Framework permission shadow only.
 * No device grant, personal SMS, Google/Naver account or service transmission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class Code20IndependentSmsSqlTest:ProtectedTextHostTestBase() {
    class Provider:ContentProvider() {
        private lateinit var db:SQLiteDatabase
        var metadataQueries=0;var bodyQueries=0;var mutations=0
        var afterMetadata:(()->Unit)?=null
        override fun onCreate():Boolean {db=SQLiteDatabase.create(null);db.execSQL("CREATE TABLE sms(_id INTEGER PRIMARY KEY,address TEXT,date INTEGER,type INTEGER,body TEXT)");return true}
        fun seed(vararg rows:Triple<String,Long,Int>) {db.delete("sms",null,null);rows.forEachIndexed { i,r ->db.insertOrThrow("sms",null,ContentValues().apply{put("_id",i+1);put("address",r.first);put("date",r.second);put("type",r.third);put("body","PUBLIC_SQL_SMS_"+(i+1))})}}
        fun setFixtureBody(value:String){db.execSQL("UPDATE sms SET body=? WHERE _id=1",arrayOf(value))}
        override fun query(u:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor {if(p!!.contains("body"))bodyQueries++ else metadataQueries++;return db.query("sms",p,s,a,null,null,sort).also{if(!p.contains("body"))afterMetadata?.invoke()}}
        override fun getType(u:Uri)="vnd.android.cursor.dir/sms"
        override fun insert(u:Uri,v:ContentValues?):Uri?{mutations++;throw AssertionError("read only")}
        override fun update(u:Uri,v:ContentValues?,s:String?,a:Array<out String>?):Int{mutations++;throw AssertionError("read only")}
        override fun delete(u:Uri,s:String?,a:Array<out String>?):Int{mutations++;throw AssertionError("read only")}
        fun close(){db.close()}
    }
    private lateinit var context:Context;private lateinit var provider:Provider
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private var id="";private val phone="010"+"0".repeat(8)
    private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=try{worker.submit<T>{f()}.get(30,TimeUnit.SECONDS)}catch(e:java.util.concurrent.ExecutionException){throw e.cause!!}
    private fun snap()=RecordingWorkflowStore(context).use{it.snapshot(id)}
    @Before fun setup(){context=RuntimeEnvironment.getApplication();keys=SyntheticPilotIntegrationTest.HostKeys();provider=Provider();provider.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="sms"});ShadowContentResolver.registerProviderInternal("sms",provider)
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_SMS)
        io {GeneralLocalStoreTest.host(context,keys).use{s->s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply{put()});val r=s.snapshot().getJSONObject(0);id=r.getString("file_id");s.edit(id,r,"PUBLIC_SQL_MANUAL_DONE","done")};RecordingWorkflowStore(context).use{it.confirmWork(id,it.snapshot(id),"합성 담당자","합성 회사","합성 업무",phone)};RelatedReadOnlyStore(context).use{it.configureSms(id,snap(),1000,4000,true)}}
    }
    @After fun stop(){assertEquals(0,provider.mutations);provider.close();worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS))}
    private fun read()=RelatedReadOnlyStore(context).use{s->val scope=s.smsScope(id)!!;val b=AndroidSmsReadOnlySource(context).read(scope,{s.current(scope)},CancellationSignal());s.storeSms(scope,b,{s.current(scope)});b}
    private fun proof(name:String,vararg values:Pair<String,Any?>){val o=JSONObject().put("scope","INDEPENDENT_API35_NATIVE_SQL_PROVIDER_SHADOW_PERMISSION_NOT_DEVICE_SMS");values.forEach{o.put(it.first,it.second ?: JSONObject.NULL)};File("build/reports/code20-independent").apply{mkdirs()}.resolve(name+".json").writeText(o.toString(2)+"\n")}
    @Test fun Q01SqlQueryMatchesCanonicalAndInternationalAsciiForms():Unit=io {
        val forms=listOf(phone,"010-0000-0000","+82 10-0000-0000","0082 10 0000 0000","+82(0)10_0000_0000")
        forms.forEach { raw->provider.seed(Triple(raw,2000L,1));assertEquals(phone,PrivacyRules.validatedPhone(raw));val b=read();assertEquals("query and verifier must agree for $raw",1,b.messages.size);assertFalse(b.incomplete)}
        proof("Q01","acceptedForms" to forms.size,"providerMutations" to provider.mutations,"manualStatus" to snap().getString("manual_status"));assertEquals("done",snap().getString("manual_status"))
    }
    @Test fun Q02AcceptedNfkcPhoneCannotBecomeSuccessfulEmptyQuery():Unit=io {
        val raw="０１０－００００－００００";assertEquals(phone,PrivacyRules.validatedPhone(raw));provider.seed(Triple(raw,2000L,1));val b=read()
        proof("Q02","sharedValidatorAccepts" to true,"messages" to b.messages.size,"incomplete" to b.incomplete,"bodyQueries" to provider.bodyQueries,"scan" to RelatedReadOnlyStore(context).use{it.lastSmsScan(id)})
        assertEquals("Shared NFKC-normalized accepted number was omitted by the actual SQL selector",1,b.messages.size)
    }
    @Test fun Q03ExactPhoneTimeAndTypeSqlDoNotFetchOtherBodies():Unit=io {
        provider.seed(Triple(phone,999L,1),Triple(phone,1000L,1),Triple(phone,3999L,2),Triple(phone,4000L,1),Triple("01000000001",2000L,1),Triple(phone,2000L,5));val b=read();assertEquals(setOf(1000L,3999L),b.messages.map{it.dateMs}.toSet());assertEquals(2,provider.bodyQueries);proof("Q03","bodyQueries" to provider.bodyQueries,"matched" to b.messages.size)
    }
    @Test fun Q04OwnerFenceAfterMetadataAdmitsNoBodyQuery():Unit=io {
        provider.seed(Triple(phone,2000L,1));var ownerCurrent=true;provider.afterMetadata={ownerCurrent=false}
        RelatedReadOnlyCoordinator(context,{ownerCurrent}).use{try{it.scanSms(id);fail()}catch(e:IllegalStateException){assertEquals("RELATED_READ_CANCELLED",e.message)}}
        assertEquals(1,provider.metadataQueries);assertEquals(0,provider.bodyQueries);assertEquals(0,RelatedReadOnlyStore(context).use{it.smsCandidates(id).length()});proof("Q04","metadataQueries" to provider.metadataQueries,"bodyQueries" to provider.bodyQueries)
    }
    @Test fun Q05AbaSourceHistoryAndConfirmationKeepManualDone():Unit=io {
        repeat(3){i->provider.seed(Triple(phone,2000L,1));provider.setFixtureBody(if(i==1)"PUBLIC_SQL_BODY_B" else "PUBLIC_SQL_BODY_A");read();RelatedReadOnlyStore(context).use{s->val head=s.smsCandidates(id).getJSONObject(0);s.confirmSms(id,head.getString("digest"),snap())}}
        val r=snap();assertEquals("done",r.getString("manual_status"));assertEquals("PUBLIC_SQL_MANUAL_DONE",r.getString("manual_text"));assertEquals(3,r.getJSONArray("related").length());assertEquals(0,r.getJSONArray("items").length());assertEquals(3L,RelatedReadOnlyStore(context).use{it.smsCandidates(id).getJSONObject(0).getLong("version")});proof("Q05","manualDonePreserved" to true,"evidenceVersions" to r.getJSONArray("related").length(),"automaticItems" to r.getJSONArray("items").length())
    }
}
