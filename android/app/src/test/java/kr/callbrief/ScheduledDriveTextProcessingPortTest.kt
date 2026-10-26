package kr.callbrief

import kr.callagent.drive.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Real parent's original JSON bytes; Android grant/transport below are synthetic host seams. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ScheduledDriveTextProcessingPortTest {
    private val source=javaClass.getResourceAsStream("/automation-proofs/scheduled-input.json")!!.readBytes()
    private val result=javaClass.getResourceAsStream("/automation-proofs/scheduled-result.json")!!.readBytes()
    private val pins=ScheduledDriveTextPins("9ec58d97-4db8-4e8c-a045-7c13c638e45b","1KStRmDwrPiWR9ww_11GBbWLdYFkrP2VG","15mym-b8GPMyK0xubZlEkE5dwKg6JaJ5i","1O52nj6l10ygN4dJSmL1mW4ojLPAxIVyx","0160c9eedd1686de229fcc3816d746fb2fb02f1efa9d4c1fe2c004978dc02740","262a9a23949c0cd11cefcd1e39724d235d293c089d3b6f398c9392c1f7d19e96",Instant.parse("2026-10-14T08:06:39Z"))
    private class Memory:GoogleConnectionSession.Persistence {var saved=JSONObject();override fun read()=JSONObject(saved.toString());override fun write(value:JSONObject){saved=JSONObject(value.toString())}}
    private fun session()=GoogleConnectionSession(Memory()).also { val e=it.begin();it.activate(e,"PUBLIC_TEST@example.invalid","PUBLIC_TEST@example.invalid","PUBLIC_TEST_OWNER","PUBLIC_TEST_ONLY_MEMORY_TOKEN") }
    private inner class ReadOnlyDrive:DriveTransport {
        var owner="PUBLIC_TEST_OWNER";var appAuthorized=true;var changing=false;var reads=0
        override fun generateIds(count:Int):List<String> = error("NO_WRITES_IN_SCHEDULED_READER")
        override fun create(spec:DriveTransport.FileSpec,content:ByteArray):DriveTransport.Metadata=error("NO_WRITES_IN_SCHEDULED_READER")
        override fun metadata(id:String):DriveTransport.Metadata=DriveTransport.Metadata(id,if(id==pins.folder)null else pins.folder,"public",if(id==pins.folder)DriveTransport.FOLDER_MIME else DriveTransport.JSON_MIME,owner,if(changing&&reads>0)"2" else "1",false,appAuthorized,true,if(id==pins.input)source.size.toLong() else if(id==pins.result)result.size.toLong() else 0)
        override fun download(id:String,maxBytes:Int):DriveTransport.Download {reads++;return DriveTransport.Download(if(id==pins.input)source else result)}
    }
    @Test fun actualScheduledJsonUsesSeparateReadOnlyProofContract()=runBlocking(Dispatchers.IO) {
        val o=ScheduledDriveTextProcessingPort(session(),ReadOnlyDrive()){Instant.parse("2026-10-14T08:10:00Z")}.read(pins)
        assertEquals(2,o.proposal.tasks.size);assertTrue(o.proposal.tasks.all{it["due"]==null});assertTrue(o.readbackVerified)
        assertFalse(o.nativeAudioRequestCompleted);assertFalse(o.schedulerTriggerIndependentlyVerified);assertFalse(o.sttExecuted);assertFalse(o.kakaoSent);assertFalse(o.canonicalWriteAllowed)
        assertEquals(pins.resultSha,o.proposal.resultSha)
    }
    @Test fun sameOwnerIsNotProofOfAppFileGrantAndChangedSnapshotsFailClosed()=runBlocking(Dispatchers.IO) {
        for(mode in listOf("OWNER","APP_GRANT","CHANGED")) {
            val drive=ReadOnlyDrive().apply{if(mode=="OWNER")owner="PUBLIC_OTHER_OWNER";if(mode=="APP_GRANT")appAuthorized=false;if(mode=="CHANGED")changing=true}
            try{ScheduledDriveTextProcessingPort(session(),drive){Instant.parse("2026-10-14T08:10:00Z")}.read(pins);fail(mode)}catch(_:IllegalStateException){}
        }
    }
    @Test fun jobQuoteDueAndDoneCannotBeFabricatedByChangingTheResult() {
        val original=JSONObject(String(result,Charsets.UTF_8))
        for(mode in listOf("JOB","QUOTE","DUE","DONE")) {
            val x=JSONObject(original.toString());val t=x.getJSONArray("tasks").getJSONObject(0)
            when(mode){"JOB"->x.put("job_id","PUBLIC_OTHER_JOB");"QUOTE"->t.getJSONObject("evidence").put("quote","not in source");"DUE"->t.put("due","2026-10-20");"DONE"->t.put("status","done")}
            val b=x.toString().toByteArray();try{ScheduledTextProofCodec().validate(source,b,pins.job,pins.input,pins.inputSha,DriveRelay.sha256(b),pins.notBefore,Instant.parse("2026-10-14T08:10:00Z"));fail(mode)}catch(_:IllegalArgumentException){}
        }
    }
}
