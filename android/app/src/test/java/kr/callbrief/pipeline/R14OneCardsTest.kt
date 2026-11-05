package kr.callbrief.pipeline

import android.content.Context
import android.provider.CallLog
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.core.PrivacyRules
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

/** v3.4 r14-1 (DESIGN_V4 4.1-2, 4.1-6): the card file's blocked table (personal and excluded numbers, written again when the
 * rules change) and card JSON v2 (due date, check reason, last call length, 30-day count; a v1 card is read and written again
 * as v2). Synthetic numbers only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R14OneCardsTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    // The cards count the call log's last 30 days from the real clock.
    private var now=System.currentTimeMillis()
    private val started=now-2*86_400_000L
    private val clock={ now }
    private val work="01012345678";private val personal="01055550001";private val excluded="01077770002"

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        ContactCards.shutdown();ContactCards.clearPending()
    }
    @After fun tearDown() { ContactCards.shutdown();ContactCards.clearPending() }

    private fun store()=CallRecordStore(context,clock)
    private fun log(id:Long,key:String,at:Long,durationMs:Long)=FirstPass.LogEntry(id,key,null,at,durationMs,CallLog.Calls.INCOMING_TYPE)
    private fun exclude(key:String) = SecureSettings(context).update { it.put("exclusions",JSONArray().put(JSONObject().put("type","phone").put("hash",PrivacyRules.hash(key)))) }

    @Test fun theBlockedTableHoldsPersonalAndExcludedNumbersAndFollowsTheRules() = store().use { s ->
        s.reconcileCallLog(listOf(log(1,work,started,60_000),log(2,personal,started+1,30_000),log(3,excluded,started+2,30_000)))
        s.rememberPersonal(personal)
        assertNull("never written: the store answers",ContactCards.blockedState(context,work))
        ContactCards.rebuildAll(s)
        assertEquals(true,ContactCards.blockedState(context,personal))
        assertEquals(false,ContactCards.blockedState(context,work));assertEquals(false,ContactCards.blockedState(context,excluded))
        assertFalse(ContactCards.blockedStale(context))
        // The rules change: the table is stale until it is written again (after the next call, or at warm).
        exclude(excluded)
        assertTrue(ContactCards.blockedStale(context))
        ContactCards.rewriteBlocked(s)
        assertFalse(ContactCards.blockedStale(context))
        assertEquals(true,ContactCards.blockedState(context,excluded))
        // Read back from the file after the process is gone, any spelling.
        ContactCards.shutdown()
        assertEquals(true,ContactCards.blockedState(context,"010-7777-0002"))
        assertEquals(false,ContactCards.blockedState(context,"+82 10-1234-5678"))
        // A number found personal after the table was written: learnt after the call, from the store.
        s.rememberPersonal(work);ContactCards.clearPending()
        assertEquals(false,ContactCards.blockedState(context,work))
        ContactCards.afterCallNow(context,work,s)
        assertEquals(true,ContactCards.blockedState(context,work))
    }

    @Test fun cardJsonTwoCarriesTheNewFieldsAndThinCardsForCallLogOnlyNumbers() = store().use { s ->
        s.reconcileCallLog(listOf(log(1,work,now-3*86_400_000L,95_000),log(2,work,now-86_400_000L,125_000),log(3,work,now-40*86_400_000L,10_000)))
        ContactCards.rebuildAll(s)
        val card=ContactCards.get(context,work)!!
        assertNotNull("a call log number gets a thin card",card.thin)
        assertEquals(ContactCard.VERSION,card.version)
        assertEquals(125_000L,card.lastDurationMs);assertEquals(2,card.calls30d)
        val json=card.toJson()
        assertEquals(2,json.getInt("v"));assertEquals(125_000L,json.getLong("lastDurationMs"));assertEquals(2,json.getInt("calls30d"))
        assertFalse(json.toString(),json.toString().contains(work))
        assertEquals(card,ContactCard.parse(work,json))
    }

    @Test fun dueDatesAndCheckReasons() {
        // KST midnight of the date; anything that is not a plain date has no dueMs.
        assertEquals(Instant.parse("2026-11-06T15:00:00Z").toEpochMilli(),ContactCards.dueMs("2026-11-07"))
        assertEquals(Instant.parse("2026-11-06T15:00:00Z").toEpochMilli(),ContactCards.dueMs(" 2026-11-07 "))
        assertNull(ContactCards.dueMs("다음 주"));assertNull(ContactCards.dueMs(""));assertNull(ContactCards.dueMs(null));assertNull(ContactCards.dueMs("2026-13-40"))
        assertNull(ContactCards.checkReason(false,"메모"))
        assertEquals(ContactCard.CHECK_CONFUSABLE,ContactCards.checkReason(true,"비슷한 일이 있어요"))
        assertEquals(ContactCard.CHECK_UNSURE,ContactCards.checkReason(true,null))
        val task=ContactCard.Task("wf_1","견적서 보내기",started,"open",null,true,ContactCards.dueMs("2026-11-07"),ContactCard.CHECK_UNSURE)
        val card=ContactCard(work,emptyList(),null,started,"out",emptyList(),listOf(task),emptyList(),emptyList(),now,lastDurationMs=60_000,calls30d=4)
        val back=ContactCard.parse(work,card.toJson())
        assertEquals(task,back.openTasks.single());assertEquals(card,back)
    }

    @Test fun aVersionOneCardIsReadAndWrittenAgainAsVersionTwo() = store().use { s ->
        s.reconcileCallLog(listOf(log(1,work,now-86_400_000L,70_000)))
        val v1=JSONObject().put("v",1).put("people",JSONArray()).put("company",JSONObject.NULL).put("lastCallMs",now-86_400_000L).put("lastDirection","in")
            .put("recent",JSONArray()).put("openTasks",JSONArray().put(JSONObject().put("id","wf_1").put("title","견적서 보내기").put("requestedMs",started).put("status","open").put("aiNote",JSONObject.NULL)))
            .put("issues",JSONArray()).put("texts",JSONArray()).put("updatedMs",started).put("thin",JSONObject().put("name",JSONObject.NULL).put("calls30",3))
        // An old file: the row as v1 wrote it.
        AndroidSqlPort(context,ContactCards.DATABASE,"CREATE TABLE IF NOT EXISTS contact_cards(phone_key TEXT PRIMARY KEY,json TEXT NOT NULL,updated_ms INTEGER NOT NULL)".byteInputStream()).use { p ->
            p.transaction { p.execute("CREATE TABLE IF NOT EXISTS contact_cards(phone_key TEXT PRIMARY KEY,json TEXT NOT NULL,updated_ms INTEGER NOT NULL)")
                p.execute("INSERT OR REPLACE INTO contact_cards VALUES(?,?,?)",work,v1.toString(),started);null } }
        ContactCards.shutdown()
        assertEquals(1,ContactCards.preload(context))
        val old=ContactCards.get(work)!!
        assertEquals(1,old.version);assertEquals(3,old.calls30d);assertNull(old.lastDurationMs);assertNull(old.openTasks.single().dueMs)
        assertTrue("read as it is, written again at the next flush",ContactCards.wholeRebuildPending)
        ContactCards.flushPending(s)
        ContactCards.shutdown()
        val now2=ContactCards.get(context,work)!!
        assertEquals(2,now2.version);assertEquals(70_000L,now2.lastDurationMs);assertEquals(1,now2.calls30d)
    }
}
