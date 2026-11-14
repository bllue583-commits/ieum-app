package kr.callbrief.pipeline.call

import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.PhoneStatus
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** v4.0 r21-2 (DESIGN_V8 6 v4.2): a card drawn on a later try counts as drawn and as retried; every failure moment is one
 * cause code in `today.causes` (a new day starts them again), the `recent` entry carries the tries, the failures, the screen,
 * the call's state and how the last process ended; codes and numbers only. Synthetic values only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R21CardStatsTest:ProtectedTextHostTestBase() {
    private val app get()=RuntimeEnvironment.getApplication()
    private var now=1_800_000_000_000L
    @After fun done() { CardStats.resetForHost() }
    private fun heartbeat():JSONObject { CardStats.awaitIdle();return CardStats.heartbeat(SecureSettings(app).snapshot(),now)!! }

    @Test fun aRetriedCardIsDrawnAndRetriedAndTheCausesAreToday() {
        CardStats.clock={ now }
        CardStats.verdict(app,CardStats.Attempt(CardStats.DRAWN,CallCardOverlay.CAUSE_OK_RETRY,true,true,null,retries=1,fails="addView:BadTokenException",
            screenOn=true,callState="RINGING",exit=10,exitAgo="m5"))
        CardStats.verdict(app,CardStats.Attempt(CardStats.DRAWN,CallCardOverlay.CAUSE_OK,true))
        CardStats.failure(app,"add:BadToken:cold:x10:m5");CardStats.failure(app,"add:BadToken:cold:x10:m5");CardStats.failure(app,"hidden")
        CardStats.failure(app,"010-1234-5678") // never anything like a number
        val hb=heartbeat()
        assertEquals(2,hb.getInt(CardStats.DRAWN));assertEquals(1,hb.getInt(CardStats.RETRIED));assertEquals(1,hb.getJSONObject("today").getInt(CardStats.RETRIED))
        val causes=hb.getJSONObject("today").getJSONObject(CardStats.CAUSES)
        assertEquals(2,causes.getInt("add:BadToken:cold:x10:m5"));assertEquals(1,causes.getInt("hidden"));assertEquals(2,causes.length())
        val e=hb.getJSONArray("recent").getJSONObject(0)
        assertEquals(1,e.getInt("retries"));assertEquals("addView:BadTokenException",e.getString("fails"));assertTrue(e.getBoolean("screenOn"))
        assertEquals("RINGING",e.getString("callState"));assertEquals(10,e.getInt("exit"));assertEquals("m5",e.getString("exitAgo"))
        assertFalse("no tries: no key",hb.getJSONArray("recent").getJSONObject(1).has("retries"))
        // The heartbeat carries the causes as they are (code keys, whole numbers).
        val sent=PhoneStatus.callCardJson(hb)
        assertEquals(2,sent.getJSONObject("today").getJSONObject(CardStats.CAUSES).getInt("add:BadToken:cold:x10:m5"))
        // A new day: today's causes start again; the totals stay.
        now+=86_400_000L
        val next=heartbeat();assertFalse(next.getJSONObject("today").has(CardStats.CAUSES));assertEquals(1,next.getInt(CardStats.RETRIED))
        CardStats.failure(app,"hidden:lk")
        assertEquals(JSONObject().put("hidden:lk",1).toString(),heartbeat().getJSONObject("today").getJSONObject(CardStats.CAUSES).toString())
    }
    @Test fun aCauseCodeIsShortAndSaysThePhone() {
        val t=1_800_000_000_000L
        assertEquals("hidden",CallCardOverlay.failCode("hidden",true,0,false,false,true,null,t))
        assertEquals("add:BadToken:d0:m1:cold:lk:off:x10:m5",CallCardOverlay.failCode("addView:BadTokenException",false,1,true,true,false,10 to t-60_000,t))
        assertEquals("mode not read (-1) or allowed (0): nothing","add:BadToken",CallCardOverlay.failCode("addView:BadTokenException",true,-1,false,false,true,10 to t,t))
        assertEquals("the last exit only on a cold process","hidden:m4",CallCardOverlay.failCode("hidden",true,4,false,false,true,10 to t,t))
        val long=CallCardOverlay.failCode("addView:WindowManagerInvalidDisplayException",false,3,true,true,false,4 to t-50*3_600_000L,t)
        assertTrue(long,long.length<=40);assertTrue(long.startsWith("add:WindowManagerInv:"))
        assertEquals(listOf("m5","m30","h2","h12","old"),listOf(60_000L,10*60_000L,3_600_000L,5*3_600_000L,13*3_600_000L).map(CallCardOverlay::agoBucket))
    }
}
