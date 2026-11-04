package kr.callbrief.pipeline

import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment

/** Starts the one Activity (design G1) on [screen], as a notification would (screen, day and call extras), for the screen tests. */
internal fun launchMain(screen:Screen=Screen.HOME,dayMs:Long?=null,callId:String?=null):MainActivity =
    Robolectric.buildActivity(MainActivity::class.java,MainActivity.intent(RuntimeEnvironment.getApplication(),screen,dayMs,callId)).setup().get()
