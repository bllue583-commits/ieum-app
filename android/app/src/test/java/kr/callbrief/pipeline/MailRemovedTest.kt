package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** v3.7 (DESIGN_V7 3.3, user decision 11/7): mail is gone. The saved mailbox settings are cleared once, a mail record left
 * from before is never sent, an answer for one is ignored, and texts and calls go on as before. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MailRemovedTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val now=1_793_300_000_000L
    private class FakeInbox:CallInboxStore {
        val files=LinkedHashMap<String,String>()
        override fun put(path:String,json:String){ files[path]=json }
        override fun replace(path:String,json:String){}
        override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha-$it"}
        override fun get(path:String)=files.getValue(path)
        override fun delete(path:String,sha:String){ files.remove(path) }
    }
    @Before fun setup() { context=RuntimeEnvironment.getApplication() }
    private fun runner(inbox:FakeInbox,s:JSONObject)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ now },inbox,
        save={ edit -> edit(s) },callLog={ emptyList() },smsAllowed={ false })

    @Test fun theSavedMailboxIsClearedAndOtherSettingsStay() {
        val s=JSONObject().put("mailImapHost","imap.example.com").put("mailUser","kim@example.com").put("mailAppPassword","app-pass")
            .put("messagesLastMailMs",5).put("mailLastError","x").put("firstPassMailCursor",1).put("firstPassMailUntil",2)
            .put("mailErrorCodes","{\"AUTH\":1}").put(PipelineSettingsKeys.LAST_SMS_MS,7).put(PipelineSettingsKeys.CONSENT_GRANTED,true)
        assertTrue(MessageIntake.clearMail(s))
        assertTrue(s.toString(),MessageIntake.REMOVED_MAIL_KEYS.none { s.has(it) })
        assertFalse("no address or password left",s.toString().contains("@") || s.toString().contains("app-pass"))
        assertEquals(7L,s.getLong(PipelineSettingsKeys.LAST_SMS_MS));assertTrue(s.getBoolean(PipelineSettingsKeys.CONSENT_GRANTED))
        assertFalse("a second time finds nothing",MessageIntake.clearMail(s))
        // The sealed settings themselves: cleared at app start, the rest kept.
        val settings=kr.callbrief.SecureSettings(context)
        settings.update { it.put("mailAppPassword","app-pass").put("mailUser","kim@example.com").put(PipelineSettingsKeys.LAST_SMS_MS,9) }
        MessageIntake.forgetMail(settings)
        val after=settings.snapshot()
        assertFalse(after.has("mailAppPassword"));assertFalse(after.has("mailUser"));assertEquals(9L,after.getLong(PipelineSettingsKeys.LAST_SMS_MS))
        MessageIntake.forgetMail(settings) // nothing left: no write, no failure
    }

    @Test fun anOldMailRecordIsNotSentAndItsAnswerIsIgnored() {
        val inbox=FakeInbox()
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,now-3_600_000L)
        val mailId=CallRecordStore(context){ now }.use { db ->
            assertTrue(db.insertMessage("${MessageKind.MAIL}1:1",now-60_000,"kim@example.com","제목: 견적\n보낸 사람: 김민수\n\n견적서는 내일까지 보내드리겠습니다."))
            assertTrue(db.insertMessage("${MessageKind.SMS}01011112222:1",now-30_000,"01011112222","[10:00 받은 문자] 견적서는 내일까지 보내드리겠습니다."))
            db.pending().single { it.kind=="메일" }.recordId.also { db.markSent(it,now-120_000) }
        }
        // An answer the routine wrote for the mail request before the update: ignored and removed, nothing set aside.
        inbox.files["results/old-mail.json"]=JSONObject().put("id",CallInbox.recordKey(mailId))
            .put("analysis",ClaudeCallAnalyzerTest.analysis()).toString()
        assertTrue(runner(inbox,s).runPending())
        assertFalse(inbox.files.containsKey("results/old-mail.json"));assertFalse(inbox.files.keys.any { it.startsWith("bad/") })
        val sent=inbox.files.filterKeys { it.startsWith("inbox/") }.values.map { JSONObject(it) }
        assertEquals("only the text goes up",1,sent.size)
        assertTrue(sent.single().getString("message").contains("종류: 문자"))
        assertFalse(inbox.files.values.any { it.contains("종류: 메일") || it.contains("example.com") })
        CallRecordStore(context){ now }.use { db ->
            assertNull("the mail record is gone",db.record(mailId))
            assertTrue("its answer made no check item",db.openItems().isEmpty())
            assertEquals(1,db.pending().count { it.kind=="문자" })
        }
    }
}
