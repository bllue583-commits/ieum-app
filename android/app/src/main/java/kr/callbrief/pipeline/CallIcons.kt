package kr.callbrief.pipeline

import android.graphics.Path
import androidx.core.graphics.PathParser

/** The app's line icons, drawn for this app on a 24 unit grid and meant to be stroked 2 units wide with round ends
 * and joins (one weight everywhere). Each icon is a few SVG path strings; nothing is downloaded or bundled as an image. */
internal object CallIcons {
    private val DATA:Map<String,List<String>> =mapOf(
        "home" to listOf("M3,11.5L12,4l9,7.5","M5.5,10v9.5a1.5,1.5 0 0 0 1.5,1.5h10a1.5,1.5 0 0 0 1.5,-1.5V10","M10,21v-5.5a1,1 0 0 1 1,-1h2a1,1 0 0 1 1,1V21"),
        "call" to listOf("M5,4L9,4L11,9L8.5,10.5C9.6,12.8 11.2,14.4 13.5,15.5L15,13L20,15L20,19C20,20.1 19.1,21 18,21C10,20.5 3.5,14 3,6C3,4.9 3.9,4 5,4Z","M14,4.5a5.5,5.5 0 0 1 5.5,5.5","M14,8a2,2 0 0 1 2,2"),
        "sms" to listOf("M20,15a2,2 0 0 1 -2,2H8l-4,4V6a2,2 0 0 1 2,-2h12a2,2 0 0 1 2,2z","M8,9h8","M8,12.5h5"),
        "mail" to listOf("M4,5h16a1,1 0 0 1 1,1v12a1,1 0 0 1 -1,1H4a1,1 0 0 1 -1,-1V6a1,1 0 0 1 1,-1z","M3,7l9,6.5l9,-6.5"),
        "task" to listOf("M5,3h14a2,2 0 0 1 2,2v14a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2V5a2,2 0 0 1 2,-2z","M8,12.5l3,3l5,-6"),
        "company" to listOf("M4,21V5a1,1 0 0 1 1,-1h8a1,1 0 0 1 1,1v16","M14,10h5a1,1 0 0 1 1,1v10","M2.5,21h19","M8,8h2","M8,12h2","M8,16h2","M17,14h0.01","M17,18h0.01"),
        "person" to listOf("M16,8a4,4 0 1 1 -8,0a4,4 0 0 1 8,0z","M4.5,21v-1a5.5,5.5 0 0 1 5.5,-5.5h4a5.5,5.5 0 0 1 5.5,5.5v1"),
        "gear" to listOf("M9.9,5.1 L10.1,2.6 L13.9,2.6 L14.1,5.1 L15.4,5.6 L17.3,4.0 L20.0,6.7 L18.4,8.6 L18.9,9.9 L21.4,10.1 L21.4,13.9 L18.9,14.1 L18.4,15.4 L20.0,17.3 L17.3,20.0 L15.4,18.4 L14.1,18.9 L13.9,21.4 L10.1,21.4 L9.9,18.9 L8.6,18.4 L6.7,20.0 L4.0,17.3 L5.6,15.4 L5.1,14.1 L2.6,13.9 L2.6,10.1 L5.1,9.9 L5.6,8.6 L4.0,6.7 L6.7,4.0 L8.6,5.6z","M15,12a3,3 0 1 1 -6,0a3,3 0 0 1 6,0z"),
        "clock" to listOf("M21,12a9,9 0 1 1 -18,0a9,9 0 0 1 18,0z","M12,7v5l3,2"),
        "dial" to listOf("M5,4L9,4L11,9L8.5,10.5C9.6,12.8 11.2,14.4 13.5,15.5L15,13L20,15L20,19C20,20.1 19.1,21 18,21C10,20.5 3.5,14 3,6C3,4.9 3.9,4 5,4Z"),
        "undo" to listOf("M9,14L4,9l5,-5","M4,9h10.5a5.5,5.5 0 0 1 0,11H11"),
        "search" to listOf("M18,11a7,7 0 1 1 -14,0a7,7 0 0 1 14,0z","M21,21l-4.2,-4.2"),
        "bell" to listOf("M6,9a6,6 0 0 1 12,0c0,6 2,7.5 2,7.5H4s2,-1.5 2,-7.5z","M10,20a2,2 0 0 0 4,0"),
        "back" to listOf("M15,5L8,12l7,7"),
        // Small helpers that are not among the 14 named icons.
        "chevron" to listOf("M9,5L16,12L9,19"),
        "tick" to listOf("M5,12.5L10,17.5L19,7"),
        "plus" to listOf("M12,5v14","M5,12h14"))
    /** The 14 icons of the design (home, calls, texts, mail, check, companies, people, settings, clock, dial, undo, search, bell, back). */
    val NAMED=listOf("home","call","sms","mail","task","company","person","gear","clock","dial","undo","search","bell","back")
    val kinds:Set<String> get()=DATA.keys
    private val cache=HashMap<String,List<Path>>()
    @Synchronized fun paths(kind:String):List<Path> =cache.getOrPut(kind) { (DATA[kind] ?: DATA.getValue("tick")).map { PathParser.createPathFromPathData(it) } }
}
