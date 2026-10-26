package kr.callbrief
import android.content.Context
import kr.co.callsummary.core.android.ProtectedDatabase
import kr.callbrief.kakao.core.DeliveryCoordinator
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.*
import javax.crypto.Cipher
import javax.crypto.spec.*
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KakaoStoredReportReaderTest:ProtectedTextHostTestBase() {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val profile="SYNTHETIC_KAKAO_READER_OWNER"
    private val date=LocalDate.parse("2026-10-13");private val zone=ZoneId.of("Asia/Seoul")
    private val body="합성 보관 보고 · 실제 발송 없음"
    private val cipher=object:ReportPayloadCipher {
        private val key=SecretKeySpec(ByteArray(32){11},"AES")
        override fun seal(text:String,aad:String,hasExistingPayload:Boolean):ByteArray {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);c.updateAAD(aad.toByteArray());return c.iv+c.doFinal(text.toByteArray())}
        override fun open(bytes:ByteArray,aad:String):String {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(0,12)));c.updateAAD(aad.toByteArray());return String(c.doFinal(bytes.copyOfRange(12,bytes.size)))}
    }
    private fun create(){DailyReportLedger(context,profile,cipher).use { l ->
        val ready=DailyReportInput(ReportReadiness.REVIEWED,listOf(ReviewedReportSource("SYN-READER-SOURCE","a".repeat(64),1,1,DeliveryCoordinator.sha256(body),"SYN-RECEIPT",1,1,1,body)),1,0,DeliveryCoordinator.sha256("synthetic-policy"))
        val config=l.setEnabled(true,Instant.parse("2026-10-14T00:00:00Z"),zone);val row=l.ensure(ReportWindow(date,zone),config.generation);l.freeze(row.id,ready,ready.basisHash,body,true,config.generation)
    }}
    @Test fun absentStoreDoesNotCreateProtectedFile()=runBlocking(Dispatchers.IO){val f=ProtectedDatabase.file(context,DailyReportLedger.DATABASE);assertFalse(f.exists());assertTrue(KakaoStoredReportReader.read(context,profile,date,cipher).isEmpty());assertFalse(f.exists())}
    @Test fun realLedgerPayloadIsReadWithoutChangingSealedBytes()=runBlocking(Dispatchers.IO){create();val f=ProtectedDatabase.file(context,DailyReportLedger.DATABASE);val before=f.readBytes();val rows=KakaoStoredReportReader.read(context,profile,date,cipher);assertEquals(1,rows.size);assertEquals(body,rows.single().second);assertArrayEquals(before,f.readBytes())}
    @Test fun otherProfileCannotUseExistingCipherOrReport()=runBlocking(Dispatchers.IO){create();val f=ProtectedDatabase.file(context,DailyReportLedger.DATABASE);val before=f.readBytes();try{KakaoStoredReportReader.read(context,"SYNTHETIC_OTHER_PROFILE",date,cipher);fail()}catch(_:IllegalStateException){};assertArrayEquals(before,f.readBytes())}
    @Test fun wrongPayloadHashIsHeldAndNotRepaired()=runBlocking(Dispatchers.IO){create();TextStoreTestSupport.open(context,DailyReportLedger.DATABASE).use{it.execSQL("DROP TRIGGER immutable_report");it.execSQL("UPDATE reports SET payload_hash=?",arrayOf("f".repeat(64)))};val f=ProtectedDatabase.file(context,DailyReportLedger.DATABASE);val before=f.readBytes();try{KakaoStoredReportReader.read(context,profile,date,cipher);fail()}catch(_:IllegalStateException){};assertArrayEquals(before,f.readBytes())}
}
