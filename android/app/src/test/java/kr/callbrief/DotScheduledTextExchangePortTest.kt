package kr.callbrief

import android.content.Context
import kr.callagent.drive.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

/** Production three-ID text publisher/reader and protected journal; fake owner grant/Drive only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DotScheduledTextExchangePortTest:ProtectedTextHostTestBase() {
    private class Memory:GoogleConnectionSession.Persistence{var saved=JSONObject();override fun read()=JSONObject(saved.toString());override fun write(value:JSONObject){saved=JSONObject(value.toString())}}
    private val job="00000000-0000-4000-8000-000000000001";private val queue="PUBLIC_APPROVED_TEXT_QUEUE";private val time=Instant.parse("2026-10-14T08:00:00Z")
    private fun session()=GoogleConnectionSession(Memory()).also{val e=it.begin();it.activate(e,"PUBLIC_TEST@example.invalid","PUBLIC_TEST@example.invalid","PUBLIC_TEST_OWNER","PUBLIC_TEST_MEMORY_TOKEN")}
    private class CreateOnlyDrive(val d:GoogleConnectionRegressionTest.FakeDrive):DriveTransport by d {
        val order=mutableListOf<String>();var loseDescriptorReply=false
        override fun create(spec:DriveTransport.FileSpec,content:ByteArray):DriveTransport.Metadata {
            if(spec.role==DriveTransport.Role.PRIVATE_FOLDER&&spec.parentId!=null)throw DriveTransport.DriveException(DriveTransport.Failure.PERMANENT)
            if(d.specs.containsKey(spec.id))throw DriveTransport.DriveException(DriveTransport.Failure.CONFLICT)
            val result=d.create(spec,content);order+=spec.name
            if(loseDescriptorReply&&spec.name.startsWith("enrollment-")){loseDescriptorReply=false;throw DriveTransport.DriveException(DriveTransport.Failure.TRANSIENT)}
            return result
        }
    }
    private fun initialized(ctx:Context,session:GoogleConnectionSession)=GoogleConnectionRegressionTest.FakeDrive().also{d->
        GeneralLocalStore.catalog(AccountStorageContext(ctx,session.ownerBinding()))
        d.specs[queue]=DriveTransport.FileSpec(queue,null,"public",DriveTransport.FOLDER_MIME,session.ownerBinding(),DriveTransport.Role.PRIVATE_FOLDER);d.bytes[queue]=byteArrayOf();d.versions[queue]=1
    }
    private fun result(registration:DotScheduledTextExchangePort.Registration,input:ByteArray):ByteArray {
        val x=JSONObject(String(javaClass.getResourceAsStream("/automation-proofs/scheduled-result.json")!!.readBytes(),Charsets.UTF_8));x.put("job_id",registration.job);x.put("source_file_id",registration.input);x.put("produced_at_utc",time.plusSeconds(60).toString());return x.toString().toByteArray()
    }
    @Test fun fixedTextThreeIdsPublishLastAndPollKnownSlotWithNoAudioOrSchedulerClaim()=runBlocking(Dispatchers.IO) {
        val ctx:Context=RuntimeEnvironment.getApplication();val s=session();val d=initialized(ctx,s);val transport=CreateOnlyDrive(d)
        val port=DotScheduledTextExchangePort(ctx,s,{DotTextExchangeConfiguration(true,queue)},transport){time.plusSeconds(120)}
        val submitted=port.publishFixedSynthetic(job);assertEquals("SENT_AWAITING_SCHEDULED_DOT",submitted.state);val r=submitted.registration!!;assertEquals(3,d.counter);assertTrue(transport.order.last().startsWith("enrollment-"));assertFalse(r.schedulerRegistered);assertFalse(r.dotInvoked)
        assertEquals("DOT_TEXT_PENDING",port.poll(job).state);assertEquals("SENT",port.publishFixedSynthetic(job).state);assertEquals(3,d.counter)
        // Simulate the external scheduled writer in this host seam; real root run evidence is separate.
        val x=JSONObject(String(result(r,d.bytes.getValue(r.input))));x.put("produced_at_utc",time.plusSeconds(180).toString());d.bytes[r.result]=x.toString().toByteArray();d.versions[r.result]=2
        val receiver=DotScheduledTextExchangePort(ctx,s,{DotTextExchangeConfiguration(true,queue)},transport){time.plusSeconds(240)}
        val read=receiver.poll(job);assertEquals("READ_ONLY_SCHEDULED_TEXT_PROPOSAL",read.state);assertEquals(2,read.proposal!!.tasks.size);assertFalse(read.nativeAudioRequestCompleted);assertFalse(read.sttExecuted);assertFalse(read.kakaoSent);assertFalse(read.canonicalWriteAllowed)
        x.put("summary","changed after receipt");d.bytes[r.result]=x.toString().toByteArray();d.versions[r.result]=3
        try{receiver.poll(job);fail("mutable result replaced original receipt")}catch(_:IllegalStateException){}
    }
    @Test fun uncertainCreateResumesSameDurableIdsAndDisabledPortDoesNoRemoteWork()=runBlocking(Dispatchers.IO) {
        val ctx:Context=RuntimeEnvironment.getApplication();val s=session();val d=initialized(ctx,s);val transport=CreateOnlyDrive(d).apply{loseDescriptorReply=true}
        val disabled=DotScheduledTextExchangePort(ctx,s,transport=transport){time};assertEquals("DOT_TEXT_EXCHANGE_NOT_CONFIGURED",disabled.publishFixedSynthetic(job).state);assertEquals(0,d.counter)
        val port=DotScheduledTextExchangePort(ctx,s,{DotTextExchangeConfiguration(true,queue)},transport){time}
        val initial=port.publishFixedSynthetic(job);assertEquals("PUBLICATION_UNCERTAIN",initial.state);assertEquals(3,d.counter)
        assertEquals("DOT_TEXT_PUBLICATION_RECONCILE_REQUIRED",port.publishFixedSynthetic(job).state);val resumed=port.resumePublication(job);assertEquals("SENT_AWAITING_SCHEDULED_DOT",resumed.state);assertEquals(initial.registration!!.input,resumed.registration!!.input);assertEquals(3,d.counter);assertEquals(3,transport.order.size)
    }
}
