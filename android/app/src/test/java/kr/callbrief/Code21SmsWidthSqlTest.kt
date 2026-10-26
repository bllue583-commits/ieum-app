package kr.callbrief

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.CancellationSignal
import kr.callbrief.core.PrivacyRules
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real production SQL on the independent synthetic provider; no real SMS or grant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class Code21SmsWidthSqlTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context;private lateinit var provider:Code20IndependentSmsSqlTest.Provider
    private val phone="010"+"0".repeat(8);private var record=""
    private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=try{worker.submit<T>{f()}.get(30,TimeUnit.SECONDS)}catch(e:java.util.concurrent.ExecutionException){throw e.cause!!}
    @Before fun setup(){context=RuntimeEnvironment.getApplication();provider=Code20IndependentSmsSqlTest.Provider();provider.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="sms"});ShadowContentResolver.registerProviderInternal("sms",provider);shadowOf(context as Application).grantPermissions(Manifest.permission.READ_SMS)
        io{GeneralLocalStoreTest.host(context,SyntheticPilotIntegrationTest.HostKeys()).use{s->s.initializeEmpty();s.importSelected(GeneralLocalStoreTest.Source().apply{put()});val r=s.snapshot().getJSONObject(0);record=r.getString("file_id");s.edit(record,r,"PUBLIC_WIDTH_MANUAL_DONE","done")};RecordingWorkflowStore(context).use{it.confirmWork(record,it.snapshot(record),"합성 사람","합성 회사","합성 업무",phone)};RelatedReadOnlyStore(context).use{s->val snapshot=RecordingWorkflowStore(context).use{it.snapshot(record)};s.configureSms(record,snapshot,1000,4000,true)}}
    }
    private fun read()=RelatedReadOnlyStore(context).use{s->val scope=s.smsScope(record)!!;AndroidSmsReadOnlySource(context).read(scope,{s.current(scope)},CancellationSignal())}
    @After fun stop(){assertEquals(0,provider.mutations);provider.close();worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS))}
    @Test fun W01MixedWidthInternationalPunctuationAndNfkcSpacesMatch():Unit=io {
        val forms=listOf("０１０－００００－００００","０１０＿0000（0000）","＋８２（０）１０－００００－００００","００８２　１０　００００　００００","010\u00a00000\u00a00000","010\u202f0000\u202f0000")
        for(raw in forms){assertEquals(phone,PrivacyRules.validatedPhone(raw));provider.seed(Triple(raw,2000L,1));val batch=read();assertEquals(raw,1,batch.messages.size);assertFalse(batch.incomplete);assertEquals(phone,batch.messages.single().phone)}
        assertEquals(forms.size,provider.bodyQueries)
    }
    @Test fun W02WidthDoesNotBroadenNumberTimeOrDirection():Unit=io {
        provider.seed(Triple("０１０－００００－００００",2000L,1),Triple("０１０－００００－０００１",2000L,1),Triple("０１０－００００－００００",999L,1),Triple("０１０－００００－００００",4000L,1),Triple("０１０－００００－００００",2000L,5));val batch=read();assertEquals(1,batch.messages.size);assertEquals(1,provider.bodyQueries)
    }
    @Test fun W03NonnumericSenderIsNeverTranslatedToAValidPhone():Unit=io {
        provider.seed(Triple("PUBLIC_BRAND_０１０００００００００",2000L,1));assertTrue(read().messages.isEmpty());assertEquals(0,provider.bodyQueries)
        RecordingWorkflowStore(context).use{s->assertEquals("done",s.snapshot(record).getString("manual_status"));assertEquals("PUBLIC_WIDTH_MANUAL_DONE",s.snapshot(record).getString("manual_text"))}
    }
}
