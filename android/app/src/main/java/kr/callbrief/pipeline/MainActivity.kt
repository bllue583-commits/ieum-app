package kr.callbrief.pipeline

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

/** The five places of the app (design G1). Each one is drawn by its own [AppScreen] into the one content column of [MainActivity]. */
enum class Screen { HOME,CALLS,TASKS,COMPANIES,SETTINGS;
    internal val tab:CallUi.Tab get()=CallUi.Tab.valueOf(name)
}

/** One screen of [MainActivity]: draws into [body] (the page column, shared by all screens), reads in the background and draws
 * only while it is the screen in front ([live]). */
internal abstract class AppScreen(val a:MainActivity) {
    /** True while the Activity is alive and this screen is the one shown; a read that finishes later draws nothing. */
    fun live()=!a.isFinishing && !a.isDestroyed && a.current===this
    /** The screen comes to the front (a tab, back, an Intent) or the app comes back (onResume): read and draw. */
    abstract fun enter()
    /** The screen goes behind another one, or the app goes to the background. */
    open fun leave() {}
    /** Its tab was tapped while it is in front: close a detail, clear the search and the day, back to the top of the list. */
    open fun reselect() { enter();CallUi.scrollTo(a.column,0) }
    /** Back: true when the screen closed something of its own (a detail page). */
    open fun back():Boolean=false
    /** Id of the detail page open on this screen (a call, a company), or null on its list. */
    open val detail:String? get()=null
    open fun save(out:Bundle) {}
    open fun restore(saved:Bundle) {}
}

/** The only Activity of the app (design G1). The state is which [Screen] is in front and, on calls and companies, which detail page is
 * open. The bottom bar only changes the screen; tapping the screen's own tab goes back to its list. Back closes a detail, then goes
 * home, and from home leaves the app, however the app was opened (launcher or notification). A notification opens this Activity with
 * the extras [EXTRA_SCREEN], [EXTRA_DAY_MS] and [EXTRA_CALL_ID]. */
class MainActivity:ComponentActivity() {
    companion object {
        /** Which [Screen] to show (its name). */ const val EXTRA_SCREEN="screen"
        /** A day to show on calls (the evening report's day). */ const val EXTRA_DAY_MS="dayMs"
        /** A call to open in its detail page. */ const val EXTRA_CALL_ID="callId"
        /** A tap of the same opening within this time is the same tap (a quick double tap opens once). */
        const val TAP_MS=600L
        /** The fade between screens. */
        const val FADE_MS=120L
        internal fun intent(c:Context,screen:Screen,dayMs:Long?=null,callId:String?=null)=Intent(c,MainActivity::class.java)
            .putExtra(EXTRA_SCREEN,screen.name).apply { dayMs?.let { putExtra(EXTRA_DAY_MS,it) };callId?.let { putExtra(EXTRA_CALL_ID,it) } }
    }
    internal lateinit var column:LinearLayout
        private set
    internal val home=HomeScreen(this)
    internal val calls=CallsScreen(this)
    internal val tasks=TasksScreen(this)
    internal val companies=CompaniesScreen(this)
    internal val settings=SettingsScreen(this)
    internal var screen=Screen.HOME
        private set
    internal val current:AppScreen get()=of(screen)
    /** The detail page open in front: a call id on calls, a company id on companies, else null. */
    internal val detail:String? get()=current.detail
    private fun of(s:Screen):AppScreen=when(s) { Screen.HOME -> home;Screen.CALLS -> calls;Screen.TASKS -> tasks;Screen.COMPANIES -> companies;Screen.SETTINGS -> settings }
    private var resumed=false

    // Results come back to the Activity; the screen in front reads again (the folder, a permission).
    internal val pickFolder=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if(r.resultCode==RESULT_OK) CallFolder.save(this,r.data)?.let { Toast.makeText(this,it,Toast.LENGTH_LONG).show() }
        current.enter()
    }
    internal val askNotify=registerForActivityResult(ActivityResultContracts.RequestPermission()) { current.enter() }
    internal val askSms=registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        ReadingPermission.noteResult(this,android.Manifest.permission.READ_SMS,ok);CallPipelineStartup.settingsChanged(this);current.enter() }
    internal val askCallLog=registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        ReadingPermission.noteResult(this,android.Manifest.permission.READ_CALL_LOG,ok);CallPipelineStartup.settingsChanged(this);current.enter() }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        column=CallUi.page(this,screen.tab) { t -> tab(Screen.valueOf(t.name)) }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() { back() } })
        if(savedInstanceState!=null) {
            savedInstanceState.getString("screen")?.let { n -> runCatching { Screen.valueOf(n) }.getOrNull() }?.let { screen=it }
            listOf(home,calls,tasks,companies,settings).forEach { it.restore(savedInstanceState) }
        } else take(intent)
        CallUi.markTab(this,window.decorView.findViewWithTag("bottom_bar"),screen.tab)
        // The pipeline is registered by IeumApplication (app start), the boot and update receivers, and the hourly tick.
        if(LaunchIntro.claim(this,intent?.action==Intent.ACTION_MAIN,savedInstanceState!=null)) LaunchIntro.show(this)
    }
    override fun onResume() {
        super.onResume();resumed=true
        current.enter()
        if(intent?.getBooleanExtra("install_update",false)==true) { intent.removeExtra("install_update");home.updateNow() }
        else if(CallUpdate.ready(this)!=null && CallUpdate.takeInstallWish(this)) CallUpdate.install(this) // the asked-for download finished meanwhile
    }
    override fun onPause() { super.onPause();resumed=false;current.leave() }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        // While resumed (rare: a notification tapped over the app) the screen is drawn here; otherwise onResume draws it.
        if(take(intent) && resumed) current.enter()
    }
    override fun onSaveInstanceState(out:Bundle) {
        super.onSaveInstanceState(out);out.putString("screen",screen.name)
        listOf(home,calls,tasks,companies,settings).forEach { it.save(out) }
    }

    /** Reads a notification's extras: the screen, the day of calls, the call to open. False when the Intent names no screen. */
    private fun take(i:Intent?):Boolean {
        val target=i?.getStringExtra(EXTRA_SCREEN)?.let { n -> runCatching { Screen.valueOf(n) }.getOrNull() }
        val call=i?.getStringExtra(EXTRA_CALL_ID);val day=i?.getLongExtra(EXTRA_DAY_MS,0L)?.takeIf { it>0 }
        if(target==null && call==null) {
            // The "new version" notification opens home (its install Intent is unchanged).
            if(i?.getBooleanExtra("install_update",false)==true) switchTo(Screen.HOME,enter=false)
            return false
        }
        val s=if(call!=null) Screen.CALLS else target!!
        if(s==Screen.CALLS) calls.show(day?.let(CallRecordStore::kstDay) ?: CallUi.today(),call)
        if(s==Screen.COMPANIES) companies.closeAll()
        switchTo(s,enter=false)
        i.removeExtra(EXTRA_SCREEN);i.removeExtra(EXTRA_CALL_ID);i.removeExtra(EXTRA_DAY_MS)
        return true
    }

    /** A tap on the bottom bar: another screen comes to the front; the screen's own tab goes back to the top of its list. */
    internal fun tab(s:Screen) { lastOpen=null;if(s==screen) current.reselect() else switchTo(s) }

    /** Back: a detail page closes, then home, then the app leaves (back from home always ends, design G1). */
    internal fun back() {
        lastOpen=null // what was closed can be opened again at once
        if(current.back()) return
        if(screen!=Screen.HOME) { switchTo(Screen.HOME);return }
        finish()
    }

    /** Brings [s] to the front: the old screen stops, the column is drawn again for the new one and fades in. */
    internal fun switchTo(s:Screen,enter:Boolean=true) {
        if(s!=screen) { current.leave();screen=s }
        CallUi.markTab(this,window.decorView.findViewWithTag("bottom_bar"),s.tab)
        if(!enter) return
        // The old screen goes at once (its title too); the new one is drawn when its read is back, fading in over 120 ms.
        (column.parent as? LinearLayout)?.let { h -> h.removeViewAt(0);h.addView(LinearLayout(this).apply { tag="page_band" },0) }
        column.removeAllViews();scroll()?.scrollTo(0,0)
        column.animate().cancel();column.alpha=0f;column.animate().alpha(1f).setDuration(FADE_MS).start()
        current.enter()
    }

    private fun scroll()=window.decorView.findViewWithTag<android.widget.ScrollView>("page_scroll")
    private var putBackListener:android.view.View.OnLayoutChangeListener?=null
    /** Puts the page back at [y] once its content is tall enough: a list drawn in parts reaches its old place when those parts are in. */
    internal fun putBack(y:Int) {
        val s=scroll() ?: return;val inner=s.getChildAt(0) ?: return
        putBackListener?.let(inner::removeOnLayoutChangeListener);putBackListener=null
        if(y<=0) return
        fun tryNow():Boolean { if(inner.height-s.height<y) return false;s.scrollTo(0,y);return true }
        if(inner.isLaidOut && tryNow()) return
        val l=object:android.view.View.OnLayoutChangeListener { override fun onLayoutChange(v:android.view.View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int) {
            if(tryNow()) { v.removeOnLayoutChangeListener(this);if(putBackListener===this) putBackListener=null } } }
        putBackListener=l;inner.addOnLayoutChangeListener(l)
    }

    private var lastOpenMs=0L
    private var lastOpen:String?=null
    /** The same opening twice within [TAP_MS] (a quick double tap) is done once. */
    internal fun once(key:String):Boolean {
        val now=SystemClock.uptimeMillis()
        if(key==lastOpen && now-lastOpenMs<TAP_MS) return false
        lastOpen=key;lastOpenMs=now;return true
    }
    /** Opens a call's detail page from anywhere (home memo, a check item, a company's calls): calls in front, its day, the call. */
    internal fun openCall(recordId:String,dayMs:Long) {
        if(!once("call:$recordId")) return
        calls.show(CallRecordStore.kstDay(dayMs),recordId)
        if(screen==Screen.CALLS) calls.enter() else switchTo(Screen.CALLS)
    }
    /** Opens a company's detail page by its name (a call's company link). */
    internal fun openCompany(name:String) {
        if(!once("company:$name")) return
        companies.showByName(name)
        if(screen==Screen.COMPANIES) companies.enter() else switchTo(Screen.COMPANIES)
    }
}
