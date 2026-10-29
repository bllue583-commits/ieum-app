package kr.callbrief

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kr.callbrief.core.SeoulTime
import kr.callbrief.core.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Four large-text views over saved records. Initial setup is a separate screen. */
open class MainActivity : ComponentActivity() {
    private lateinit var settings: SecureSettings
    private var storageContext: android.content.Context? = null
    private var storageScope = ""
    private var storageEpoch = -1L
    private var folderSelectionEpoch = -1L
    private fun storage() = checkNotNull(storageContext) { "ACCOUNT_STORAGE_UNAVAILABLE" }
    private fun localUiPrefs() = storage().getSharedPreferences("local_ui_v2",MODE_PRIVATE)
    private fun selectStorageScope() {
        val v=GoogleConnectionSession.get(this).view()
        storageContext=if(v.ownerId.isBlank()) this else AccountStorageContext(this,v.ownerId)
        storageScope=v.ownerId;storageEpoch=v.epoch
    }
    private fun openConnections() {
        requestLocalCancel();localOperationGeneration++;busy=false
        fileHistoryDialog?.dismiss();fileHistoryId=null
        startActivity(Intent(this,ConnectionCenterActivity::class.java))
    }
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var launchIntro: LaunchIntro? = null
    private var tab = 0
    private var settingsShowing = false
    private var selectedCallDate: String? = null
    private var lastSelectedCallDate: String? = null
    private var dateReturnScroll = 0
    private var taskFilter: String? = "active"
    private var requestedReportDate: String? = null
    private var topics = JSONArray()
    private var tasks = JSONArray()
    private var busy = false
    private var demoMode = false
    private var catalogFiles = JSONArray()
    private var catalogLoaded = false
    private var catalogLoading = false
    private var catalogError: String? = null
    private var localDetailLoading = false
    private var localDetailGeneration = 0L
    private var workObserved = false
    private var syncRunning = false
    private var catalogGeneration = 0
    private var localSetupState: String? = null
    private var localSetupLoading = false
    private var localSetupGeneration = 0L
    private var localUiClosed = false
    private var localOperationGeneration = 0L
    private var fileHistoryId: String? = null
    private var fileHistoryDialog: AlertDialog? = null
    private var fileHistoryScroll: ScrollView? = null
    private fun localUiAvailable() = !localUiClosed && !isFinishing && !isDestroyed &&
        lifecycle.currentState != Lifecycle.State.DESTROYED
    private var automaticDiscoveryWatch: AutomaticDiscoveryWatch? = null
    protected open fun startAutomaticDiscovery() {
        if(demoMode || storageContext==null || !::settings.isInitialized || !localUiAvailable())return
        // Existing encrypted settings/key failures must hold discovery, not crash startup or reset policy.
        runCatching {
            if(settings.snapshot().optString("treeUri").isBlank()) { stopAutomaticDiscovery();return@runCatching }
            AutomaticRecordingDiscovery.ensure(storage())
            if(automaticDiscoveryWatch==null) {
                automaticDiscoveryWatch=AutomaticDiscoveryWatch(storage()) { cycle ->runOnUiThread {
                    if(localUiAvailable() && !demoMode && cycle!=null && cycle.localOutcomes.any { it=="STORED_LOCAL" || it=="RECEIPT_RECOVERED" || it=="UNCHANGED_BYTES" }) {
                        catalogLoaded=false;if(!busy)refreshLocalCatalog()
                    }
                } }
            }
        }.onFailure { stopAutomaticDiscovery() }
    }
    private fun stopAutomaticDiscovery(){val watch=automaticDiscoveryWatch;automaticDiscoveryWatch=null;runCatching {watch?.close()}}
    @Volatile private var activeLocalStore: GeneralLocalStore? = null
    @Volatile private var localCancelRequested = false
    private fun requestLocalCancel() {
        // Record cancellation before the IO thread publishes a newly constructed store.
        localCancelRequested = true
        activeLocalStore?.cancel()
    }
    protected open fun createLocalStore() = GeneralLocalStore(storage())
    protected open fun readLocalSetupState(context: android.content.Context) = GeneralLocalStore(context).use { it.setupState() }
    protected open fun launchIntroNow(): Instant = Instant.now()
    protected open fun launchIntroZone(): ZoneId = ZoneId.systemDefault()
    protected open fun launchIntroMotionAllowed(): Boolean = LaunchIntroPolicy.motionAllowed(this)
    private fun activeFiles() = list(catalogFiles).filter { !it.optBoolean("archived") }
    private val timeFormat = DateTimeFormatter.ofPattern("MM월 dd일 HH:mm")
    private val accent = Color.rgb(13, 34, 112)
    private val ink = Color.rgb(13, 34, 112)
    private val sky = Color.rgb(221, 241, 248)
    private val selection = Color.rgb(188, 231, 246)

    private val pickFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (demoMode) return@registerForActivityResult
        if(folderSelectionEpoch>=0 && folderSelectionEpoch!=GoogleConnectionSession.get(this).epoch()) {
            notice("계정이 바뀌어 폴더 선택을 저장하지 않았어요.");return@registerForActivityResult
        }
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            notice("읽기 권한을 확인하지 못했어요. 이전 폴더 선택은 유지해요."); return@registerForActivityResult
        }
        val selectionResult = FolderSelectionChange.replace(uri.toString(),
            current = { settings.snapshot().optString("treeUri") },
            acquire = { contentResolver.takePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) },
            saveUriAndMessage = { selected -> settings.update {
                it.put("treeUri", selected)
                it.put("automaticDiscoverySelectionRevision",it.optLong("automaticDiscoverySelectionRevision",0L)+1L)
                it.put("statusMessage", "선택 폴더의 새 녹음을 자동 확인해요. 저장 중인 파일과 상대 미확정 파일은 보류하며 외부 전송은 하지 않아요.")
                it.put("statusAt", Instant.now().toString())
            } },
            release = { contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        when (selectionResult) {
            FolderSelectionChange.Result.SAVED, FolderSelectionChange.Result.SAVED_PREVIOUS_GRANT_RETAINED -> {
                stopAutomaticDiscovery();startAutomaticDiscovery()
                runCatching { if(settingsShowing)renderSettings() else { buildShell();refreshLocalCatalog() } }.onFailure { notice("폴더 선택은 저장됐어요. 설정 화면을 다시 열어 확인해 주세요.") }
                if (selectionResult == FolderSelectionChange.Result.SAVED_PREVIOUS_GRANT_RETAINED) notice("폴더 선택은 저장됐어요. 이전 폴더 권한 정리는 확인이 필요해요.")
            }
            FolderSelectionChange.Result.GRANT_REJECTED -> notice("이 폴더의 읽기 권한을 유지하지 못했어요. 이전 폴더 선택은 유지해요.")
            FolderSelectionChange.Result.SAVE_UNCONFIRMED -> notice("폴더 선택 저장을 확인하지 못했어요. 기존 권한과 새 권한은 유지했어요. 설정을 다시 열어 확인해 주세요.")
            FolderSelectionChange.Result.CURRENT_STATE_UNREADABLE -> notice("이전 폴더 설정을 읽지 못했어요. 폴더 선택은 변경하지 않았어요.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try { selectStorageScope() } catch(_:Exception) { storageContext=null }
        val accountSavedState=savedInstanceState?.takeIf { !it.containsKey("storageScope") || it.getString("storageScope")==storageScope }
        val savedUi=(storageContext ?: this).getSharedPreferences("local_ui_v2",MODE_PRIVATE)
        tab = (accountSavedState?.getInt("tab", 0) ?: savedUi.getInt("tab",0)).coerceIn(0, 3)
        settingsShowing = accountSavedState?.getBoolean("settingsShowing", false) ?: false
        requestedReportDate = intentDate(intent)
        val debugDemo = intent.getBooleanExtra("demo", false) &&
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val restoredDemo = if (accountSavedState?.containsKey("demoMode") == true) accountSavedState.getBoolean("demoMode") else debugDemo
        if (restoredDemo) {
            enableDemo()
            accountSavedState?.getString("syntheticTasks")?.let { tasks = JSONArray(it) }
            accountSavedState?.getString("syntheticTopics")?.let { topics = JSONArray(it) }
        }
        else loadSavedSettings()
        selectedCallDate = if (accountSavedState?.getBoolean("unknownDateSelected", false) ?: (!demoMode && requestedReportDate==null && savedUi.getBoolean("unknownDateSelected",false))) null
            else accountSavedState?.getString("selectedCallDate") ?: requestedReportDate ?: (if(!demoMode) savedUi.getString("selectedCallDate",null) else null) ?: SeoulTime.yesterday(screenNow()).toString()
        lastSelectedCallDate = accountSavedState?.getString("lastSelectedCallDate") ?: (if(!demoMode) savedUi.getString("lastSelectedCallDate",null) else null) ?: selectedCallDate ?: SeoulTime.yesterday(screenNow()).toString()
        taskFilter=accountSavedState?.getString("taskFilter") ?: (if(!demoMode) savedUi.getString("taskFilter","active") else "active")
        dateReturnScroll = accountSavedState?.getInt("dateReturnScroll", 0) ?: 0
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(sky) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(16), insets.systemWindowInsetTop + dp(12), dp(16), insets.systemWindowInsetBottom + dp(12)); insets
        }
        val appHost = FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(appHost)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    launchIntro?.isShowing == true -> launchIntro?.finish()
                    settingsShowing -> { settingsShowing = false; buildShell() }
                    tab != 0 -> { tab = 0; buildShell() }
                    selectedCallDate == null -> returnToSelectedDate()
                    else -> finish()
                }
            }
        })
        buildShell()
        // The default Android splash ends normally. This optional local decoration never holds it.
        val launcher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        if (LaunchIntroSession.claim(launcher, accountSavedState != null)) {
            launchIntro = LaunchIntro(this, appHost, root, launchIntroNow(), launchIntroZone(),
                ::localUiAvailable, ::launchIntroMotionAllowed).also { it.start() }
        }
        if (!demoMode) startSavedWork()
        if (!demoMode) DailyReportSchedule.ensureAsync(this)
        if(launcher && accountSavedState==null && !demoMode && !getSharedPreferences("eum_onboarding_v1",MODE_PRIVATE).getBoolean("seen",false)) {
            root.postDelayed({ if(localUiAvailable() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                requestLocalCancel();startActivity(Intent(this,ConnectionCenterActivity::class.java).putExtra("onboarding",true))
            } },1200)
        }
    }
    override fun onResume() {
        super.onResume()
        if(!demoMode) {
            val previousReportOwner=storageScope;val previousReportEpoch=storageEpoch
            val changed=runCatching { val v=GoogleConnectionSession.get(this).view();v.ownerId!=storageScope || v.epoch!=storageEpoch }.getOrDefault(true)
            if(changed) {
                stopAutomaticDiscovery()
                requestLocalCancel();catalogGeneration++;localOperationGeneration++;localDetailGeneration++
                catalogFiles=JSONArray();catalogLoaded=false;catalogLoading=false;localDetailLoading=false;busy=false
                fileHistoryDialog?.dismiss();fileHistoryId=null;fileHistoryDialog=null
                try { selectStorageScope() } catch(_:Exception) { storageContext=null }
                loadSavedSettings();localSetupState=null;localSetupLoading=false;localSetupGeneration++
                val prefs=storageContext?.getSharedPreferences("local_ui_v2",MODE_PRIVATE)
                tab=prefs?.getInt("tab",0)?.coerceIn(0,3) ?: 0
                selectedCallDate=prefs?.getString("selectedCallDate",null) ?: SeoulTime.yesterday(screenNow()).toString()
                lastSelectedCallDate=selectedCallDate;taskFilter=prefs?.getString("taskFilter","active") ?: "active"
                dateReturnScroll=0;if(::root.isInitialized)buildShell()
            }
            DailyReportSchedule.accountResumed(this,previousReportOwner,previousReportEpoch)
        }
        if (!demoMode && ::settings.isInitialized) { startAutomaticDiscovery();refreshLocalCatalog() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("storageScope",storageScope)
        outState.putInt("tab", tab); outState.putBoolean("settingsShowing", settingsShowing)
        outState.putBoolean("demoMode", demoMode); outState.putString("selectedCallDate", selectedCallDate)
        outState.putBoolean("unknownDateSelected", selectedCallDate == null)
        outState.putString("lastSelectedCallDate", lastSelectedCallDate); outState.putInt("dateReturnScroll", dateReturnScroll)
        outState.putString("taskFilter",taskFilter)
        if (demoMode) { outState.putString("syntheticTasks", tasks.toString()); outState.putString("syntheticTopics", topics.toString()) }
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        launchIntro?.finish()
        super.onNewIntent(intent); setIntent(intent); requestedReportDate = intentDate(intent)
        selectedCallDate = requestedReportDate ?: SeoulTime.yesterday(screenNow()).toString()
        tab = 0; settingsShowing = false; buildShell(); refreshLocalCatalog()
    }
    private fun screenNow(): Instant = if (demoMode) DemoFixtures.now else Instant.now()
    private fun loadSavedSettings() {
        settings = SecureSettings(storageContext ?: this)
        // Legacy remote boards are not the native app's authoritative state.
        topics = JSONArray(); tasks = JSONArray()
    }
    private fun startSavedWork() {
        if (demoMode) return
        startAutomaticDiscovery()
        if (!NativeAnalysisAvailability.canTransfer()) { refreshLocalCatalog();return }
        if (!workObserved) {
            workObserved = true
            WorkManager.getInstance(this).getWorkInfosByTagLiveData(SyncSchedule.TAG).observe(this) { infos ->
                if (demoMode) return@observe
                val wasRunning = syncRunning
                syncRunning = infos.any { it.state == androidx.work.WorkInfo.State.RUNNING }
                if (!busy && !settingsShowing && tab == 2) renderHome(syncRunning)
                if (wasRunning && !syncRunning) refreshLocalCatalog()
            }
        }
        runCatching { SyncSchedule.ensure(this) }
        refreshLocalCatalog()
    }
    private fun intentDate(intent: Intent): String? {
        val candidate = intent.getStringExtra("report_date") ?: runCatching { intent.data?.takeIf { !it.isOpaque }?.getQueryParameter("date") }.getOrNull() ?: return null
        return runCatching { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(candidate)); LocalDate.parse(candidate).toString() }.getOrNull()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun text(value: String, size: Float = 18f, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size.coerceAtLeast(18f); setTextColor(ink); setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 18f; isAllCaps = false; minHeight = dp(56); setTextColor(accent)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(28).toFloat(); setStroke(dp(1), Color.rgb(209, 228, 237)) }
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { if (!busy) action() }; isEnabled = !busy
    }
    private fun stack(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun choice(labels:List<String>,selected:Int,onChange:(Int)->Unit):Spinner = Spinner(this).apply {
        adapter=object : ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels) {
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View = super.getView(position,convertView,parent).apply {
                minimumHeight=dp(56);if(this is TextView) { textSize=18f;setTextColor(ink) }
            }
            override fun getDropDownView(position:Int,convertView:View?,parent:android.view.ViewGroup):View = super.getDropDownView(position,convertView,parent).apply {
                minimumHeight=dp(56);if(this is TextView) { textSize=18f;setTextColor(ink) }
            }
        }
        minimumHeight=dp(56);setSelection(selected,false)
        onItemSelectedListener=object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { if(position!=selected) onChange(position) }
            override fun onNothingSelected(parent:AdapterView<*>?) = Unit
        }
    }
    private fun selectedButton(button:Button) {
        button.backgroundTintList=android.content.res.ColorStateList.valueOf(selection);button.setTextColor(ink);button.setTypeface(button.typeface,Typeface.BOLD)
        if(Build.VERSION.SDK_INT>=30) button.stateDescription="선택됨"
    }
    private fun card(parent: LinearLayout = content): LinearLayout = stack().apply {
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.rgb(209, 228, 237)) }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun field(parent: LinearLayout, label: String, value: String = "", secret: Boolean = false): EditText {
        parent.addView(text(label, bold = true))
        return EditText(this).apply {
            textSize = 18f; minHeight = dp(56); setTextColor(ink); setText(value); isSaveEnabled = !secret
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            parent.addView(this, LinearLayout.LayoutParams(-1,-2))
        }
    }
    private fun check(parent: LinearLayout, label: String, checked: Boolean = false): CheckBox = CheckBox(this).apply {
        text = label; textSize = 18f; minHeight = dp(56); isChecked = checked; setTextColor(ink); parent.addView(this)
    }
    private fun list(array: JSONArray): List<JSONObject> = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    private fun displayTime(value: String): String = runCatching { Instant.parse(value).atZone(SeoulTime.zone).format(timeFormat) }.getOrDefault("아직 없음")
    private fun styleDialog(dialog: AlertDialog) {
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply { textSize = 18f; minHeight = dp(56); isAllCaps = false }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.textSize = 18f
        dialog.listView?.let { list ->
            for (index in 0 until list.childCount) (list.getChildAt(index) as? TextView)?.apply { textSize = 18f; minHeight = dp(56) }
        }
    }
    private fun showReadDialog(title: String, body: LinearLayout): AlertDialog? {
        if (!localUiAvailable()) return null
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(body) }).setPositiveButton("닫기", null).create()
        ProtectedDialogWindow.show(dialog); styleDialog(dialog)
        return dialog
    }
    private fun notice(value: String) {
        if (!localUiAvailable()) return
        val body = stack().apply { setPadding(dp(16), 0, dp(16), 0); addView(text(value)) }
        showReadDialog("확인", body)
    }
    private fun openSettings() { settingsShowing = true; buildShell() }
    /** Drawn line icons stay local and scale with density, without bundled image assets. */
    private fun lineIcon(kind: String): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            when (kind) {
                "calendar" -> {
                    canvas.drawRoundRect(RectF(3f, 5f, 21f, 21f), 2f, 2f, paint); canvas.drawLine(3f, 10f, 21f, 10f, paint)
                    canvas.drawLine(8f, 3f, 8f, 7f, paint); canvas.drawLine(16f, 3f, 16f, 7f, paint)
                }
                "document" -> {
                    canvas.drawPath(Path().apply { moveTo(5f, 2f); lineTo(15f, 2f); lineTo(21f, 8f); lineTo(21f, 22f); lineTo(5f, 22f); close() }, paint)
                    canvas.drawPath(Path().apply { moveTo(15f, 2f); lineTo(15f, 8f); lineTo(21f, 8f) }, paint)
                    canvas.drawLine(9f, 12f, 17f, 12f, paint); canvas.drawLine(9f, 16f, 17f, 16f, paint)
                }
                "check" -> {
                    canvas.drawRoundRect(RectF(3f, 4f, 21f, 22f), 2f, 2f, paint); canvas.drawLine(8f, 1f, 16f, 1f, paint)
                    canvas.drawPath(Path().apply { moveTo(7f, 12f); lineTo(11f, 16f); lineTo(18f, 9f) }, paint)
                }
                "refresh" -> {
                    canvas.drawArc(RectF(4f, 4f, 20f, 20f), -45f, 300f, false, paint)
                    canvas.drawPath(Path().apply { moveTo(21f, 4f); lineTo(21f, 10f); lineTo(15f, 10f) }, paint)
                }
                "archive" -> {
                    canvas.drawRoundRect(RectF(2f, 3f, 22f, 8f), 1f, 1f, paint); canvas.drawRect(RectF(4f, 8f, 20f, 22f), paint)
                    canvas.drawLine(9f, 12f, 15f, 12f, paint)
                }
                else -> {
                    canvas.drawCircle(12f, 12f, 8f, paint); canvas.drawCircle(12f, 12f, 4f, paint)
                    for (index in 0 until 8) { canvas.save(); canvas.rotate(index * 45f, 12f, 12f); canvas.drawLine(12f, 1f, 12f, 4f, paint); canvas.restore() }
                }
            }
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = dp(24)
        override fun getIntrinsicHeight(): Int = dp(24)
    }
    private fun renderCurrentTab() {
        if (!localUiAvailable()) return
        if (settingsShowing) renderSettings()
        else when (tab) { 0 -> renderDates(); 1 -> renderTopics(); 2 -> renderHome(syncRunning); else -> renderArchive() }
    }

    private fun buildShell() {
        if (!localUiAvailable()) return
        root.removeAllViews()
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; tag = "app_header" }
        header.addView(text(LaunchIntroPolicy.APP_NAME, 24f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("") { openSettings() }.apply {
            tag = "open_settings"
            contentDescription = "초기 설정 열기"; setCompoundDrawablesWithIntrinsicBounds(lineIcon("gear"), null, null, null)
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        root.addView(header)
        if(busy) root.addView(Button(this).apply {
            text="로컬 작업 중단";textSize=18f;minHeight=dp(56)
            setOnClickListener { requestLocalCancel() }
        })
        if (demoMode) root.addView(text("합성 예시 · 실제 전송 없음"))
        if (settingsShowing) root.addView(button("4탭으로 돌아가기") { settingsShowing = false; buildShell() })
        val scroll = ScrollView(this).apply { isFillViewport = true; tag = "main_content_scroll" }
        content = stack(); scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!settingsShowing) {
            val names = listOf("날짜별", "업무별", "할일", "보관")
            val icons = listOf("calendar", "document", "check", "archive")
            fun navigationButton(index: Int): Button = button(names[index]) { tab = index; buildShell() }.apply {
                tag = "primary_tab_$index"
                setCompoundDrawablesWithIntrinsicBounds(null, lineIcon(icons[index]), null, null)
                compoundDrawablePadding = dp(4)
                contentDescription = if (index == tab) "${names[index]} · 선택됨" else names[index]
                if (index == tab) selectedButton(this)
            }
            val nav = LinearLayout(this).apply { tag = "primary_navigation" }
            if (resources.configuration.fontScale >= 1.25f) {
                nav.orientation = LinearLayout.VERTICAL
                listOf(listOf(0, 1), listOf(2, 3)).forEachIndexed { rowIndex, indices ->
                    val row = LinearLayout(this)
                    indices.forEach { index -> row.addView(navigationButton(index), LinearLayout.LayoutParams(0, -2, 1f)) }
                    nav.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = dp(4) })
                }
            } else {
                names.indices.forEach { index -> nav.addView(navigationButton(index), LinearLayout.LayoutParams(0, -2, 1f)) }
            }
            root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        }
        renderCurrentTab()
    }
    private fun nullableString(row: JSONObject, key: String): String? =
        if (!row.has(key) || row.isNull(key)) null else row.optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun originalDate(row: JSONObject): LocalDate? = nullableString(row, "original_date")?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
    private fun storedSummary(row: JSONObject): String? = when (val value = row.opt("summary")) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject -> nullableString(value, "summary") ?: nullableString(value, "text")
        else -> null
    }
    private fun topicFor(row: JSONObject): JSONObject? = nullableString(row, "case_id")?.let { id ->
        list(topics).firstOrNull { it.optString("id") == id }
    }
    private fun recordingLabel(row: JSONObject): String {
        val topic = topicFor(row)
        val counterpart = nullableString(row, "counterpart_label") ?: topic?.let { nullableString(it, "counterpart") } ?: "상대방 확인 필요"
        val case = nullableString(row, "case_label") ?: topic?.let { nullableString(it, "topic") } ?: "업무 이름 확인 필요"
        return "$counterpart · $case"
    }
    private fun displaySummary(row: JSONObject): String? = storedSummary(row)?.let {
        if (demoMode) it.removePrefix("[합성 예시] ") else it
    }
    private fun recordingStatus(row: JSONObject): String = when (row.optString("analysis_result_state")) {
        "ERROR" -> "분석 실패 · 이전 확인 요약·메모·완료는 유지해요."
        "BINDING_REVIEW" -> "분석 결과 연결 확인 필요 · 기존 기록 유지"
        else -> row.optString("automation_status_label").takeIf {it.isNotBlank()} ?: legacyRecordingStatus(row)
    }
    private fun legacyRecordingStatus(row: JSONObject): String = when (row.optString("status")) {
        "processed", "completed", "summarized" -> if (storedSummary(row) != null) "저장된 요약 있음" else "요약 결과 확인 필요"
        "uploaded", "pending", "processing" -> "요약 처리 확인 필요"
        "collected", "discovered", "ready", "new" -> "기기 목록에 수집됨 · 요약 대기"
        "unknown_time", "unknown_date", "date_required", "needs_confirmation", "blocked_unknown_time" -> "통화 날짜·시각 확인 필요"
        "identity_required", "blocked_identity", "blocked_unknown_identity" -> "상대방 확인 필요"
        "unlinked" -> "사람·업무 연결 확인 필요"
        "needs_review" -> "변경안 검토 필요 · 자동 적용되지 않음"
        "source_unavailable" -> "원본 위치 확인 필요 · 기록 유지"
        "blocked", "excluded" -> "처리 차단"
        "unsupported", "oversized", "error", "failed" -> "파일·처리 상태 확인 필요"
        else -> "처리 상태 확인 필요"
    }
    private fun presenceLabel(row: JSONObject): String = when (row.optString("presence")) {
        "present", "available" -> "선택한 폴더에서 마지막 확인됨"
        "missing", "absent" -> "최근 확인 때 원본을 찾지 못함 · 저장 기록은 유지"
        else -> "원본 존재 여부 아직 확인되지 않음"
    }
    private fun refreshLocalCatalog() {
        if (!localUiAvailable()) return
        if(!demoMode && storageContext==null) { catalogError="계정 저장 정보를 읽지 못했어요. 다른 계정 자료를 표시하지 않았어요.";catalogLoaded=false;catalogLoading=false;if(::content.isInitialized)renderCurrentTab();return }
        if (demoMode) { catalogFiles = DemoFixtures.catalogFiles(); catalogLoaded = true; catalogError = null; renderCurrentTab(); return }
        if (catalogLoading) return
        val generation = catalogGeneration
        catalogLoading = true; catalogError = null
        if (::content.isInitialized && !settingsShowing && tab == 0) renderDates()
        lifecycleScope.launch {
            try {
                val scoped=storage()
                val saved = withContext(Dispatchers.IO) { GeneralLocalStore.catalog(scoped).listFiles() }
                if (!currentCoroutineContext().isActive || !localUiAvailable() || demoMode || generation != catalogGeneration) return@launch
                catalogFiles = saved
                catalogLoaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentCoroutineContext().isActive && localUiAvailable() && !demoMode && generation == catalogGeneration) catalogError = "기기에 저장된 통화 목록을 읽지 못했어요. 목록이 비었다고 판단하지 않았어요."
            } finally {
                if (!demoMode && generation == catalogGeneration) {
                    catalogLoading = false
                    if (currentCoroutineContext().isActive && localUiAvailable() && ::content.isInitialized && !settingsShowing) renderCurrentTab()
                }
            }
        }
    }
    private fun appendCatalogReadStatus(body: LinearLayout) {
        if (catalogLoading) body.addView(text("기기 저장 목록을 읽는 중이에요."))
        catalogError?.let { body.addView(text(it, bold = true)) }
        if (catalogLoaded && (catalogLoading || catalogError != null)) {
            body.addView(text("아래 기록·건수는 마지막으로 읽은 목록 기준이에요. 새로고침으로 다시 확인해 주세요."))
        }
    }
    private fun catalogEmptyMessage(confirmedEmpty: String): String = when {
        catalogError != null -> "통화 목록을 확인하지 못했어요."
        catalogLoading -> "저장 목록을 확인하고 있어요."
        !catalogLoaded -> "아직 저장 목록을 확인하지 않았어요. 새로고침으로 확인해 주세요."
        else -> confirmedEmpty
    }
    private fun renderDates() {
        if (settingsShowing || tab != 0) return
        content.removeAllViews()
        val heading = card().apply { tag = "date_heading"; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val date = selectedCallDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (date != null) lastSelectedCallDate = date.toString()
        val navigationDate = date ?: lastSelectedCallDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: SeoulTime.yesterday(screenNow())
        val large = resources.configuration.fontScale >= 1.25f
        fun stepDate(days: Long) {
            selectedCallDate = navigationDate.plusDays(days).toString(); renderDates()
        }
        val picker = button(date?.format(DateTimeFormatter.ofPattern(if (large) "M월 d일" else "M월 d일 (EEE)", Locale.KOREAN)) ?: "날짜 선택") { chooseCallDate() }.apply {
            tag = "date_picker"
            contentDescription = date?.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE · 날짜 선택", Locale.KOREAN)) ?: "날짜 확인 필요 · 날짜 선택"
            minWidth = 0; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val dateActions = LinearLayout(this).apply { tag = "date_controls"; gravity = android.view.Gravity.CENTER_VERTICAL }
        val previous = button("‹") { stepDate(-1) }.apply { tag = "date_previous"; contentDescription = "전날"; minWidth = 0 }
        val next = button("›") { stepDate(1) }.apply { tag = "date_next"; contentDescription = "다음 날"; minWidth = 0 }
        dateActions.addView(previous, LinearLayout.LayoutParams(dp(56), -2))
        dateActions.addView(picker, LinearLayout.LayoutParams(0, -2, 1f))
        dateActions.addView(next, LinearLayout.LayoutParams(dp(56), -2))
        heading.addView(dateActions, LinearLayout.LayoutParams(-1, -2))
        val unknown = activeFiles().filter { originalDate(it) == null }
        val daily = if (date == null) unknown else activeFiles().filter { originalDate(it) == date }
        val count = text(if (large && unknown.isNotEmpty()) "통화\n${daily.size}개" else "통화 ${daily.size}개").apply {
            tag = "daily_count"; contentDescription = "통화 ${daily.size}개"
        }
        if (unknown.isNotEmpty()) {
            val filter = button(if (large) "날짜 확인 필요\n${unknown.size}개" else "날짜 확인 필요 · ${unknown.size}개") {
                if (selectedCallDate != null) dateReturnScroll = root.findViewWithTag<ScrollView>("main_content_scroll")?.scrollY ?: 0
                selectedCallDate = null; renderDates()
            }.apply { tag = "unknown_date_filter"; contentDescription = "날짜 확인 필요 · ${unknown.size}개"; minWidth = 0; if (date == null) selectedButton(this) }
            val filterRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; tag = "date_filter_row" }
            filterRow.addView(filter, LinearLayout.LayoutParams(0, -2, 1f))
            if (large) count.gravity = android.view.Gravity.CENTER
            filterRow.addView(count, LinearLayout.LayoutParams(if (large) dp(80) else -2, -2).apply { leftMargin = dp(8) })
            heading.addView(filterRow, LinearLayout.LayoutParams(-1, -2))
        } else heading.addView(count)
        if (date == null) heading.addView(button("선택한 날짜로 돌아가기") { returnToSelectedDate() }.apply { tag = "return_to_selected_date" })
        appendCatalogReadStatus(heading)
        if (daily.isEmpty()) card().addView(text(catalogEmptyMessage(if (date == null)
            "현재 저장 목록에서 날짜 확인 대상은 없어요." else
            "이 날짜로 확인된 통화가 저장 목록에 없어요. 날짜 미확인 항목도 확인해 주세요.")))
        if(!demoMode && catalogLoaded && !catalogLoading && catalogError==null && catalogFiles.length()==0 &&
            runCatching { settings.snapshot().optString("treeUri").isBlank() }.getOrDefault(false)) {
            val next=card();next.addView(text("기존 통화 녹음을 보려면 녹음이 있는 폴더를 먼저 선택해 주세요."))
            next.addView(button("녹음 폴더 선택") { selectRecordingFolder() }.apply { tag="empty_recording_folder" })
        }
        daily.sortedWith(compareBy<JSONObject> { nullableString(it, "original_started_at") == null }
            .thenBy { nullableString(it, "original_started_at").orEmpty() }).forEach { renderCatalogFile(it) }
        // The selected day's calls must be shown before the saved summaries for the week.
        renderWeeklySummaries(date)
        card().apply {
            tag = "record_management"
            addView(button("기록 관리") { showRecordingManagement() }.apply { tag = "open_record_management" })
        }
        if (demoMode) card().addView(button("합성 예시 닫기") { closeDemo() })
    }
    private fun showRecordingManagement() {
        if (!localUiAvailable()) return
        val body = stack().apply { setPadding(dp(16), 0, dp(16), 0) }
        var dialog: AlertDialog? = null
        body.addView(button("저장 목록 새로고침") { dialog?.dismiss(); refreshLocalCatalog() })
        if (!demoMode) body.addView(button("선택 폴더에서 가져오기") { dialog?.dismiss(); importLocalRecordings() })
        body.addView(button("어제 수집·변경 확인") { dialog?.dismiss(); showPreviousDayReview() })
        body.addView(button("읽기 전용 분석 제안") { dialog?.dismiss();startActivity(Intent(this,ReadOnlyProposalActivity::class.java)) })
        if (!demoMode && catalogFiles.length() == 0) body.addView(button("합성 사용 예시 보기") {
            dialog?.dismiss(); enableDemo(); selectedCallDate = SeoulTime.yesterday(screenNow()).toString(); buildShell()
        })
        dialog = showReadDialog("기록 관리", body)
    }
    private fun returnToSelectedDate() {
        selectedCallDate = lastSelectedCallDate ?: SeoulTime.yesterday(screenNow()).toString(); renderDates()
        root.findViewWithTag<ScrollView>("main_content_scroll")?.let { scroll -> scroll.post { scroll.scrollTo(0, dateReturnScroll) } }
    }
    private fun chooseCallDate() {
        val initial = (selectedCallDate ?: lastSelectedCallDate)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: SeoulTime.yesterday(screenNow())
        val picker = DatePickerDialog(this, { _, year, month, day ->
            selectedCallDate = LocalDate.of(year, month + 1, day).toString(); renderDates()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        ProtectedDialogWindow.show(picker); styleDialog(picker)
    }
    private fun renderCatalogFile(row: JSONObject, parent: LinearLayout = content, businessFirst: Boolean = false) {
        val item = card(parent)
        item.tag = "recording_${row.optString("file_id")}"
        val topic = topicFor(row)
        val counterpart = nullableString(row, "counterpart_label") ?: topic?.let { nullableString(it, "counterpart") } ?: "상대방 확인 필요"
        val company = nullableString(row, "company_label")
        val start = nullableString(row, "original_started_at")?.let { original -> runCatching {
            Instant.parse(original).atZone(SeoulTime.zone).takeIf { it.toLocalDate() == originalDate(row) }
                ?.format(DateTimeFormatter.ofPattern("HH:mm"))
        }.getOrNull() }
        val contextView = text(listOfNotNull(counterpart, company, start).joinToString(" · ")).apply { tag = "call_context" }
        val titleView = text(nullableString(row, "case_label") ?: topic?.let { nullableString(it, "topic") }
            ?: start?.let { "$it 통화" } ?: "시각 확인 필요 통화", 20f, true).apply { tag = "call_title" }
        val note=if(!demoMode) nullableString(row,"manual_note") else null
        val summary = displaySummary(row)
        val googleProposal=nullableString(row,"google_proposal_summary")
        val businessState = nullableString(row, "manual_status") ?: topic?.let { nullableString(it, "status") }
        fun addBusinessStatus() {
            if (businessState != null) item.addView(text("업무 진행: ${TaskStatus.fromWire(businessState).label}").apply { tag = "call_business_status" })
        }
        if (businessFirst) {
            addBusinessStatus()
            item.addView(titleView); item.addView(contextView)
        } else {
            item.addView(contextView); item.addView(titleView)
        }
        fun addStatus() {
            if (!businessFirst) addBusinessStatus()
            if (storedSummary(row) == null || row.optString("status") !in setOf("processed", "completed", "summarized") ||
                row.optString("analysis_result_state") in setOf("ERROR","BINDING_REVIEW")) {
                val analysis = if(!demoMode && storedSummary(row)==null && row.optString("analysis_result_state") !in setOf("ERROR","BINDING_REVIEW")) "분석 대기 · ${recordingStatus(row)}" else recordingStatus(row)
                item.addView(text(if (businessFirst) analysis.replaceFirst(" · ", "\n") else analysis).apply { tag = "call_analysis_status" })
            }
            if (originalDate(row) == null && row.optString("status") !in setOf("unknown_time", "unknown_date", "date_required", "needs_confirmation", "blocked_unknown_time")) item.addView(text("통화 날짜·시각 확인 필요"))
            if (row.optBoolean("summary_is_stale")) item.addView(text("이 요약은 이전 파일 버전에서 적용했어요. 새 파일 내용과 비교해 주세요.", bold = true))
        }
        if (summary != null) {
            if (businessFirst) addStatus()
            if(row.optString("summary_origin")=="USER_REVIEWED_LOCAL")item.addView(text("직접 확인 요약",bold=true))
            item.addView(text(summary).apply { tag = "call_summary" })
            if (!businessFirst) addStatus()
        } else {
            addStatus()
            if (note != null) {
                // Preserve every user character; omit the generated marker if their text already contains it.
                if (!note.contains("메모")) item.addView(text("직접 메모", bold = true).apply { tag = "manual_note_label" })
                item.addView(text(note).apply { tag = "call_summary" })
            }
        }
        if(!demoMode && (googleProposal!=null || nullableString(row,"google_proposal_raw")!=null)) {
            item.addView(text(row.optString("google_proposal_label"),bold=true).apply { tag="google_native_proposal_label" })
            googleProposal?.let { item.addView(text(it).apply { tag="google_native_proposal_summary" }) }
            if(row.optBoolean("google_proposal_conflict"))item.addView(text("직접 수정·완료 후 받은 제안이에요. 현재 메모·완료는 유지했어요."))
            item.addView(button("제안 원문·근거 보기") {
                val body=stack();body.addView(text("정확한 파일·버전에 연결된 읽기 전용 제안이에요. 사람·업무 의미와 dot 실행/작성자 증명은 별도 확인이 필요해요."))
                body.addView(text(row.optString("google_proposal_raw")));body.addView(text(row.optString("google_proposal_analysis")))
                showReadDialog("이 통화의 요약 제안",body)
            }.apply { tag="google_native_proposal_detail" })
        }
        if(!demoMode && row.optBoolean("own_summary_review_stale"))item.addView(text("이전 직접 확인 요약 · 변경 후 다시 확인 필요"))
        val documents=list(row.optJSONArray("workflow_items") ?: JSONArray()).filter { it.optString("kind")=="DOCUMENT" }
        if(!demoMode && documents.isNotEmpty())item.addView(text("필요 서류: "+documents.joinToString(" · ") { it.optString("title")+it.optString("edition").takeIf(String::isNotBlank)?.let { edition->" ($edition)" }.orEmpty()+" · "+TaskStatus.fromWire(it.optString("status")).label }))
        if(!demoMode && nullableString(row,"business_status")!=null)item.addView(text("직접 확인 업무 진행: "+TaskStatus.fromWire(row.getString("business_status")).label))
        val id = nullableString(row, "file_id")
        if (id != null) {
            val label = if (businessFirst && nullableString(row, "case_id") != null) "이 통화 이전·현재 비교"
                else if (row.optString("status") == "needs_review") "변경안 검토" else "상세 보기"
            item.addView(button(label) { showFileHistory(id) })
        }
        else item.addView(text("비교할 파일 식별정보가 없어 확인이 필요해요."))
    }
    private fun renderWeeklySummaries(date: LocalDate?) {
        val weekly = card().apply { tag = "weekly_summary" }
        weekly.addView(text("주간 요약", 24f, true))
        if (date == null) { weekly.addView(text("원본 날짜를 확인해야 주간 범위에 포함할 수 있어요.")); return }
        val first = date.minusDays(6)
        weekly.addView(text("$first ~ $date · 기기에 저장된 통화 요약 모음"))
        weekly.addView(text("이 내용을 할일 목록에 자동 적용하지 않아요."))
        val rows = activeFiles().filter { row -> originalDate(row)?.let { !it.isBefore(first) && !it.isAfter(date) } == true && storedSummary(row) != null }
        if (rows.isEmpty()) { weekly.addView(text(catalogEmptyMessage("이 범위에서 확인된 요약이 없어요. 미처리·날짜 미확인 항목은 별도로 확인해 주세요."))); return }
        rows.groupBy { nullableString(it, "case_id") ?: "unassigned:${it.optString("file_id")}" }.forEach { (_, calls) ->
            weekly.addView(text(recordingLabel(calls.first()), 20f, true))
            calls.sortedBy { originalDate(it) }.forEach { row -> weekly.addView(text("${originalDate(row)}\n${displaySummary(row)}")) }
        }
    }
    private fun readLocalDetail(synthetic: () -> JSONObject, saved: () -> JSONObject, show: (JSONObject) -> Unit) {
        if (!localUiAvailable() || localDetailLoading) return
        if (demoMode) { show(synthetic()); return }
        val generation = catalogGeneration
        val detailRequest = ++localDetailGeneration
        localDetailLoading = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { saved() }
                if (currentCoroutineContext().isActive && localUiAvailable() && !demoMode && generation == catalogGeneration) show(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (currentCoroutineContext().isActive && localUiAvailable() && !demoMode && generation == catalogGeneration) notice("저장된 기록을 읽지 못했어요. 이전 기록이 없다고 판단하지 않았어요.") }
            finally { if (generation == catalogGeneration && detailRequest == localDetailGeneration) localDetailLoading = false }
        }
    }
    private fun showFileHistory(fileId: String) = readLocalDetail(
        { DemoFixtures.history(fileId) }, { GeneralLocalStore.catalog(storage()).history(fileId) }, { renderFileHistory(it) }
    )
    private fun renderFileHistory(history: JSONObject, reuseOpenDialog: Boolean = false) {
        if (!localUiAvailable()) return
        val body = stack().apply { setPadding(dp(16), 0, dp(16), 0) }
        body.addView(text("저장된 결과를 비교해요. 비교 내용을 할일 목록에 자동 적용하지 않아요."))
        list(catalogFiles).firstOrNull { it.optString("file_id") == history.optString("file_id") }?.let { row ->
            val metadata = card(body); metadata.addView(text("원본·수집 정보", 22f, true))
            nullableString(row, "display_name")?.let { metadata.addView(text("원본 파일명: $it")) }
            metadata.addView(text("원본 통화 날짜: ${originalDate(row)?.toString() ?: "확인 필요"}"))
            val start = nullableString(row, "original_started_at")?.let { runCatching { Instant.parse(it).atZone(SeoulTime.zone).format(timeFormat) }.getOrNull() }
            metadata.addView(text("통화 시작: ${start ?: "확인 필요"}"))
            metadata.addView(text("수집: ${displayTime(row.optString("collected_at"))}"))
            metadata.addView(text("파일 버전: ${if (row.has("source_version")) row.optInt("source_version").toString() else "미기록"}"))
            metadata.addView(text("저장 상태: ${recordingStatus(JSONObject(row.toString()).put("analysis_result_state",history.opt("analysis_result_state") ?: JSONObject.NULL))}")); metadata.addView(text(presenceLabel(row)))
            if(!demoMode) {
                metadata.addView(text("새 원음 사본·목록·메모: 암호화 저장 · 기존 자료 별도 검토 · 실기기 검증 전"))
                nullableString(row,"filename_time_candidate")?.let { metadata.addView(text("파일명 시각 후보: ${displayTime(it)} · 서울 기준, 확인 전")) }
                metadata.addView(button("통화 날짜·시각 직접 확인") { confirmLocalTime(row) }.apply { tag="local_confirm_time" })
                metadata.addView(button("직접 메모·완료 상태 수정") { editLocalRecording(row) }.apply { tag="local_edit" })
                metadata.addView(button("분석·업무·문자·메일 관리") { RecordingWorkflowActivity.open(this,history.getString("file_id")) }.apply { tag="recording_workflow" })
                metadata.addView(button(if(row.optBoolean("archived")) "목록으로 되돌리기" else "보관하기") {
                    confirmLocalArchive(row)
                }.apply { tag="local_archive" })
            }
        }
        nullableString(history, "manual_text")?.let { manual ->
            val edited = card(body); edited.addView(text("직접 수정한 현재 내용", 22f, true)); edited.addView(text(manual))
            edited.addView(text("직접 수정한 내용은 변경안으로 덮어쓰지 않아요."))
        }
        if(!demoMode) list(history.optJSONArray("manual_history") ?: JSONArray()).forEach { change ->
            val item=card(body);item.addView(text("직접 변경 ${change.optInt("user_revision")}차",20f,true))
            item.addView(text(nullableString(change,"after_text").orEmpty()))
            nullableString(change,"after_status")?.let { item.addView(text(TaskStatus.fromWire(it).label)) }
        }
        val versions = list(history.optJSONArray("versions") ?: JSONArray()).sortedBy { it.optInt("version") }
        val entries = versions.flatMap { version ->
            list(version.optJSONArray("results") ?: JSONArray()).sortedBy { it.optInt("revision") }.map { version to it }
        }
        val ownReviews=list(history.optJSONArray("user_reviewed_history") ?: JSONArray())
        if(ownReviews.isNotEmpty()) {
            val own=card(body);own.addView(text("직접 확인 요약 이력",22f,true))
            own.addView(text("본인이 읽고 확인한 기록이에요. 모델 실행·작성자 증명과 별도로 보존해요."))
            ownReviews.forEach { reviewed->
                own.addView(text("파일 ${reviewed.optLong("source_version")} · 직접 확인 ${reviewed.optLong("revision")}차 · "+if(reviewed.optBoolean("is_current"))"현재 확인값" else "이전 이력 · 현재 파일·수동 상태 재확인 필요",20f,true))
                own.addView(text(reviewed.getString("text")))
                own.addView(text("저장: ${displayTime(reviewed.optString("created_at"))}"))
            }
        }
        if (entries.isEmpty()) body.addView(text(if(ownReviews.isEmpty())"저장된 요약 이력이 없어요. 파일 수집 이력은 아래에서 확인할 수 있어요." else "모델 결과 이력은 아직 없어요. 직접 확인 요약은 위에 보존돼 있어요."))
        else {
            val current = entries.last()
            val previous = entries.dropLast(1).lastOrNull()
            val effective = entries.firstOrNull { it.second.optBoolean("is_current") }
            if (effective != null && effective != current && nullableString(history, "manual_text") == null) {
                val saved = card(body); saved.addView(text("현재 적용된 요약", 22f, true)); renderHistoryResult(saved, effective.first, effective.second)
            }
            if (previous == null) body.addView(text("현재 결과만 저장돼 있어요. 이전 결과와의 비교는 아직 할 수 없어요."))
            else {
                val before = card(body); before.addView(text(if (previous.second.optBoolean("review_required")) "이전 변경안" else "이전 요약", 22f, true)); renderHistoryResult(before, previous.first, previous.second)
            }
            val after = card(body); after.addView(text(if (current.second.optBoolean("review_required")) "최근 변경안 · 검토 필요" else "현재 요약", 22f, true)); renderHistoryResult(after, current.first, current.second)
            if (versions.last().optInt("version") > current.first.optInt("version")) after.addView(text("새 파일 버전의 요약은 아직 없어요. 여기에는 마지막으로 저장된 결과를 표시해요.", bold = true))
            if (previous != null) body.addView(text(if (storedSummary(previous.second) == storedSummary(current.second))
                "저장된 요약 문장은 같아요. 원문과 파일 버전은 각각 확인해 주세요." else "저장된 요약 문장이 바뀌었어요. 내용을 직접 확인해 주세요.", bold = true))
        }
        body.addView(text("파일·결과 전체 이력", 22f, true))
        if (versions.isEmpty()) body.addView(text("파일 버전 정보가 저장되지 않았어요."))
        versions.forEach { version ->
            val item = card(body)
            item.addView(text("파일 버전 ${version.optInt("version")}", 20f, true))
            item.addView(text("수집: ${displayTime(version.optString("collected_at"))}"))
            item.addView(text("상태: ${recordingStatus(version)}"))
            val results = list(version.optJSONArray("results") ?: JSONArray()).sortedBy { it.optInt("revision") }
            if (results.isEmpty()) item.addView(text("이 파일 버전의 요약 결과는 아직 없어요."))
            results.forEach { result -> item.addView(button("원문·요약 ${result.optInt("revision")}차 보기") { showResult(version, result) }) }
        }
        val fileId = history.optString("file_id")
        val existing = fileHistoryDialog
        val scroll = fileHistoryScroll
        if (reuseOpenDialog && fileHistoryId == fileId && existing?.isShowing == true && scroll != null) {
            val position = scroll.scrollY
            scroll.removeAllViews(); scroll.addView(body)
            scroll.post { if (localUiAvailable() && fileHistoryDialog === existing && existing.isShowing) scroll.scrollTo(0, position) }
        } else {
            existing?.dismiss()
            val dialog = showReadDialog("이전·현재 요약 비교", body) ?: return
            fileHistoryId = fileId; fileHistoryDialog = dialog; fileHistoryScroll = body.parent as ScrollView
            dialog.setOnDismissListener {
                if (fileHistoryDialog === dialog) { fileHistoryId = null; fileHistoryDialog = null; fileHistoryScroll = null }
            }
        }
    }
    private fun refreshOpenFileHistory(fileId: String) {
        val dialog = fileHistoryDialog ?: return
        if (!localUiAvailable() || demoMode || fileHistoryId != fileId || !dialog.isShowing) return
        val generation = catalogGeneration
        val operation = localOperationGeneration
        val detailRequest = ++localDetailGeneration
        localDetailLoading = true
        lifecycleScope.launch {
            fun sameView() = localUiAvailable() && !demoMode && generation == catalogGeneration &&
                operation == localOperationGeneration && fileHistoryDialog === dialog && dialog.isShowing && fileHistoryId == fileId
            try {
                val saved = withContext(Dispatchers.IO) {
                    val catalog = GeneralLocalStore.catalog(storage())
                    catalog.listFiles() to catalog.history(fileId)
                }
                if (currentCoroutineContext().isActive && sameView()) {
                    catalogFiles = saved.first; catalogLoaded = true; catalogError = null
                    renderFileHistory(saved.second, reuseOpenDialog = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (currentCoroutineContext().isActive && sameView()) notice("저장은 완료했지만 열린 상세 기록을 다시 읽지 못했어요. 상세를 다시 열어 저장된 내용을 확인해 주세요.")
            } finally { if (generation == catalogGeneration && detailRequest == localDetailGeneration) localDetailLoading = false }
        }
    }
    private fun renderHistoryResult(parent: LinearLayout, version: JSONObject, result: JSONObject) {
        parent.addView(text("파일 ${version.optInt("version")} · 요약 ${result.optInt("revision")}차"))
        parent.addView(text("결과 저장: ${displayTime(result.optString("captured_at"))}"))
        parent.addView(text(storedSummary(result) ?: "이 결과의 요약 내용이 없어요."))
        if (result.optBoolean("review_required")) parent.addView(text("저장된 변경안이에요. 현재 내용이나 완료 상태에 적용하지 않았어요.", bold = true))
    }
    private fun showResult(version: JSONObject, result: JSONObject) {
        val body = stack().apply { setPadding(dp(16), 0, dp(16), 0) }
        renderHistoryResult(body, version, result)
        body.addView(text("원문", 22f, true))
        body.addView(text(nullableString(result, "raw_text") ?: "이 결과에는 저장된 원문이 없어요."))
        body.addView(text("기록 확인 정보", 20f, true))
        body.addView(text("음성 해시: ${nullableString(version, "audio_sha256") ?: "미기록"}"))
        body.addView(text("원문 해시: ${nullableString(result, "raw_text_sha256") ?: "미기록"}"))
        body.addView(text("요약 해시: ${nullableString(result, "summary_sha256") ?: "미기록"}"))
        showReadDialog("저장된 원문·요약", body)
    }
    private fun showPreviousDayReview() = readLocalDetail(
        { DemoFixtures.review(screenNow()) }, { GeneralLocalStore.catalog(storage()).reviewPreviousDay(screenNow()) }, { renderPreviousDayReview(it) }
    )
    private fun renderPreviousDayReview(review: JSONObject) {
        val body = stack().apply { setPadding(dp(16), 0, dp(16), 0) }
        body.addView(text("${nullableString(review, "date") ?: "날짜 확인 필요"} 수집·변경", 22f, true))
        body.addView(text("원본 통화 날짜와 별개로, 어제 수집하거나 변경된 기록을 확인해요."))
        body.addView(text("확인 화면이에요. 할일 상태나 완료 여부를 자동으로 바꾸지 않아요.", bold = true))
        val changes = list(review.optJSONArray("file_changes") ?: JSONArray())
        if (changes.isEmpty()) body.addView(text("이 결과에 기록된 수집·변경 항목이 없어요. 수집 범위의 완전성은 별도로 확인해야 해요."))
        changes.forEach { change ->
            val item = card(body)
            val fileId = nullableString(change, "file_id")
            val file = list(catalogFiles).firstOrNull { nullableString(it, "file_id") == fileId }
            item.addView(text(file?.let { recordingLabel(it) } ?: "통화 기록", 20f, true))
            item.addView(text("원본 날짜: ${file?.let { originalDate(it)?.toString() } ?: nullableString(change, "original_date") ?: "확인 필요"}"))
            item.addView(text(nullableString(change, "description") ?: nullableString(change, "change") ?: "수집·변경 이력 있음 · 상세 비교 필요"))
            if (fileId != null) item.addView(button("이 기록 이전·현재 비교") { showFileHistory(fileId) })
        }
        body.addView(text("확인 필요", 22f, true))
        val checks = review.optJSONArray("review_required") ?: JSONArray()
        if (checks.length() == 0) body.addView(text("이 결과에 별도 확인 항목이 없어요. 처리 완료를 뜻하지는 않아요."))
        for (index in 0 until checks.length()) {
            val check = checks.opt(index)
            body.addView(text(when (check) {
                is JSONObject -> nullableString(check, "reason") ?: nullableString(check, "description") ?: "수집·변경 내역 확인 필요"
                is String -> check
                else -> "수집·변경 내역 확인 필요"
            }))
        }
        showReadDialog("어제 수집·변경 확인", body)
    }
    private fun renderArchive() {
        if (settingsShowing || tab != 3) return
        content.removeAllViews()
        val body = card()
        body.addView(text("보관", 24f, true))
        if(!demoMode) {
            body.addView(text("보관한 통화는 기본 목록에서 숨겨요. 원본 녹음과 저장한 원음 사본은 삭제하지 않아요."))
            appendCatalogReadStatus(body)
            val saved=list(catalogFiles).filter { it.optBoolean("archived") }
            if(saved.isEmpty()) body.addView(text(catalogEmptyMessage("보관한 통화가 없어요.")))
            saved.forEach { renderCatalogFile(it) }
            body.addView(button("저장 목록 새로고침") { refreshLocalCatalog() });return
        }
        body.addView(text("보관 기능은 준비 중이에요. 원본 녹음은 삭제하지 않아요.", bold = true))
        body.addView(text("아직 구현되지 않아 현재는 항목을 숨기거나 되돌릴 수 없어요."))
        body.addView(button("목록 숨기기 · 준비 중") {}.apply { isEnabled = false })
        body.addView(button("날짜별 통화 보기") { tab = 0; buildShell() })
        if (demoMode) body.addView(text("합성 예시에서도 보관 완료를 표시하지 않아요."))
    }
    private fun renderHome(running: Boolean = false) {
        if (settingsShowing || tab != 2) return
        content.removeAllViews()
        val summary = card()
        summary.addView(text("todo", 24f, true))
        summary.addView(text(if (demoMode) "합성 예시 · 실제 통화·업로드·카카오 전송 없음" else NativeAnalysisAvailability.MESSAGE))
        if (!demoMode) {
            summary.addView(text("직접 관리한 통화와 todo를 보여줘요. 분석 제안은 사용자 확인 전 자동 완료하지 않아요."))
            appendCatalogReadStatus(summary)
            val saved=activeFiles().filter { nullableString(it,"manual_status")!=null }
            val entered=activeFiles().flatMap { row ->list(row.optJSONArray("workflow_items") ?: JSONArray())
                .filter { it.optString("kind")=="TODO" }.map { it to row } }
            val filters=listOf("active" to "할일","review" to "대기·확인","done" to "완료")
            summary.addView(choice(filters.map { (s,l)->"$l · ${saved.count { matchesFilter(it.optString("manual_status"),s) }+entered.count { matchesFilter(it.first.optString("status"),s) }}개" },filters.indexOfFirst { it.first==taskFilter }.coerceAtLeast(0)) { taskFilter=filters[it].first;renderHome() })
            val visible=saved.filter { matchesFilter(it.optString("manual_status"),taskFilter ?: "active") }
            val visibleItems=entered.filter { matchesFilter(it.first.optString("status"),taskFilter ?: "active") }
            if(visible.isEmpty()&&visibleItems.isEmpty()) card().addView(text(catalogEmptyMessage("이 상태로 직접 관리한 통화나 todo가 없어요. 통화 상세에서 추가할 수 있어요.")))
            visible.forEach { renderCatalogFile(it) }
            visibleItems.forEach { (todo,row) ->val item=card();item.addView(text(todo.getString("title"),22f,true));item.addView(text(TaskStatus.fromWire(todo.getString("status")).label));item.addView(text(recordingLabel(row)))
                if(todo.optString("due_text").isNotBlank())item.addView(text("직접 입력 기한: ${todo.getString("due_text")}"))
                item.addView(button("todo 내용·완료 수정") { RecordingWorkflowActivity.open(this,row.getString("file_id"),todo.getString("id")) }.apply { tag="edit_todo_${todo.getString("id")}" }) }
            if(pipelineReviewCount>0) card().addView(button("AI 분석 검토 · ${pipelineReviewCount}건 확인 필요") {
                startActivity(Intent(this,kr.callbrief.pipeline.PipelineReviewActivity::class.java))
            }.apply { tag="pipeline_review_badge" })
            refreshPipelineReviewCount()
            val actions = card()
            actions.addView(button("어제 수집·변경 확인") { showPreviousDayReview() })
            actions.addView(button("기기 저장 목록 새로고침") { refreshBoard() })
            actions.addView(button("합성 사용 예시 보기") { enableDemo(); buildShell() })
            return
        }
        if (running && !demoMode) summary.addView(text("기기 작업 상태를 확인하고 있어요. 분석 완료는 아직 확인되지 않았어요.", bold = true))
        val all = list(tasks)
        val filters=listOf("active" to "할일", "review" to "대기·확인", "done" to "완료")
        if(resources.configuration.fontScale>=1.25f) {
            val selected=filters.indexOfFirst { it.first==taskFilter }.coerceAtLeast(0)
            summary.addView(choice(filters.map { (status,label) -> "$label · ${all.count { matchesFilter(it.optString("status"),status) }}개" },selected) { taskFilter=filters[it].first;renderHome() })
        } else {
            val filterRow=LinearLayout(this)
            filters.forEach { (status,label) ->
                val count=all.count { matchesFilter(it.optString("status"),status) }
                val item=button("${if(taskFilter==status) "✓ " else ""}$label\n${count}개") { taskFilter=status;renderHome() }
                if(taskFilter==status) selectedButton(item)
                filterRow.addView(item,LinearLayout.LayoutParams(0,-2,1f))
            }
            summary.addView(filterRow)
        }
        val selected = all.filter { taskFilter == null || matchesFilter(it.optString("status"),taskFilter!!) }
        if (selected.isEmpty()) {
            val empty = card()
            val message = when {
                !demoMode -> "할일 저장·편집 연결이 아직 준비되지 않았어요. 기기 통화와 버전 기록은 날짜별·업무별에서 볼 수 있어요."
                all.isNotEmpty() -> "이 상태의 항목은 없어요."
                else -> "표시할 합성 할일이 없어요."
            }
            empty.addView(text(message))
            if (!demoMode) empty.addView(button("합성 사용 예시 보기") { enableDemo(); buildShell() })
        }
        for (task in selected) {
            val item = card()
            val status = TaskStatus.fromWire(task.optString("status"))
            val topic = list(topics).firstOrNull { it.optString("id") == task.optString("case_id") }
            item.addView(text(task.optString("title"), 20f, true))
            item.addView(text("상태: ${status.label}"))
            item.addView(text("상대방: ${topic?.optString("counterpart") ?: "미분류"} · 업무: ${topic?.optString("topic") ?: "미분류"}"))
            item.addView(text("담당: ${task.optString("assignee", "확인 필요")} · 기한: ${if (task.isNull("due_date")) "확인 필요" else task.optString("due_date")}"))
            if ((task.optJSONArray("manual_fields")?.length() ?: 0) > 0) item.addView(text("직접 수정한 항목은 AI가 덮어쓰지 않아요."))
            item.addView(button("수정·완료 체크") { editTask(task) })
            item.addView(button("근거 통화·변경 이력") { showEvidence(task) })
        }
        val actions = card()
        if (demoMode) actions.addView(button("합성 예시 닫기") { closeDemo() })
        actions.addView(button(if (requestedReportDate == null) "어제 브리핑 보기" else "$requestedReportDate 브리핑 보기") { showDigest() })
        actions.addView(button("목록 새로고침") { refreshBoard() })

    }
    private var pipelineReviewCount = 0
    private var pipelineReviewLoading = false
    /** Review badge: read-only count of open AI review items; never creates or migrates a store. */
    private fun refreshPipelineReviewCount() {
        val scoped = storageContext ?: return
        if (pipelineReviewLoading || demoMode) return
        pipelineReviewLoading = true
        lifecycleScope.launch {
            val count = runCatching { withContext(Dispatchers.IO) { kr.callbrief.pipeline.PipelineReviewBadge.count(scoped) } }.getOrDefault(0)
            pipelineReviewLoading = false
            if (!localUiAvailable() || demoMode) return@launch
            if (count != pipelineReviewCount) { pipelineReviewCount = count; if (!settingsShowing && tab == 2) renderHome(syncRunning) }
        }
    }
    private fun enableDemo() {
        if(!BuildConfig.SYNTHETIC_TOOLS_ENABLED){notice("합성 예시는 검증용 빌드에서만 사용할 수 있어요.");return}
        demoMode=true; catalogGeneration++; catalogLoading=false; localDetailLoading=false
        val examples=DemoFixtures.board();topics=examples.first;tasks=examples.second
        catalogFiles = DemoFixtures.catalogFiles(); catalogLoaded = true; catalogError = null
        // Screenshots are permitted only while fabricated content is visible; real setup remains inaccessible.
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun closeDemo() {
        demoMode = false; catalogGeneration++; window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        catalogFiles = JSONArray(); catalogLoaded = false; catalogError = null
        loadSavedSettings(); selectedCallDate = SeoulTime.yesterday(screenNow()).toString()
        buildShell(); startSavedWork()
    }
    private fun matchesFilter(status: String, filter: String): Boolean = when (filter) {
        "active" -> status in listOf("open","in_progress")
        "review" -> status in listOf("waiting","needs_confirmation")
        "done" -> status == "done"
        else -> true
    }
    private fun renderTopics() {
        if (settingsShowing || tab != 1) return
        content.removeAllViews()
        val top = card().apply { tag = "business_header" }
        val heading = LinearLayout(this).apply { tag = "business_title_row"; gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(text("업무별", 24f, true).apply { contentDescription = "업무별 통화·요약" }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(button("") { refreshLocalCatalog() }.apply {
            tag = "business_refresh"; contentDescription = "저장 목록 새로고침"; tooltipText = "저장 목록 새로고침"
            setCompoundDrawablesWithIntrinsicBounds(lineIcon("refresh"), null, null, null)
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(8) })
        top.addView(heading, LinearLayout.LayoutParams(-1, -2))
        if (!demoMode) {
            if (!NativeAnalysisAvailability.canTransfer()) top.addView(text("분석 연결 준비 중").apply { tag = "business_connection_status" })
            appendCatalogReadStatus(top)
        }
        val groups = activeFiles().filter { nullableString(it, "case_id") != null }.groupBy { it.getString("case_id") }
        for ((caseId, calls) in groups) {
            val topic = list(topics).firstOrNull { it.optString("id") == caseId }
            val c = card().apply { tag = "business_group_$caseId" }
            c.addView(text(recordingLabel(calls.first()), 22f, true).apply { tag = "business_group_title" })
            val progress = if (demoMode) topic?.let { nullableString(it, "status") } else storedBusinessProgress(calls)
            c.addView(text(if (progress != null) "업무 진행: ${TaskStatus.fromWire(progress).label}" else "업무 진행 확인 필요").apply { tag = "business_group_status" })
            c.addView(text("저장 통화 ${calls.size}개 · 날짜 확인 필요 ${calls.count { originalDate(it) == null }}개"))
            calls.sortedBy { nullableString(it, "original_started_at").orEmpty() }.forEach { call ->
                renderCatalogFile(call, c, businessFirst = true)
            }
            if (topic != null) {
                c.addView(text("저장된 업무 요약", 20f, true))
                c.addView(text(topic.optString("summary", "저장된 누적 요약 없음")).apply { tag = "business_accumulated_summary" })
            }
            if (topic != null) c.addView(button("이 업무의 합성 할일 보기") { showTopic(topic) })
        }
        val unassigned = activeFiles().filter { nullableString(it, "case_id") == null }
        if (unassigned.isNotEmpty()) {
            val pending = card().apply { tag = "business_unassigned" }
            pending.addView(text("업무 미분류 · ${unassigned.size}개", 22f, true).apply { tag = "business_group_title" })
            pending.addView(text("업무 연결 확인 필요").apply { tag = "business_group_status" })
            unassigned.forEach { renderCatalogFile(it, pending, businessFirst = true) }
        }
        if (groups.isEmpty() && unassigned.isEmpty()) card().addView(text(catalogEmptyMessage("저장된 업무별 통화가 없어요.")))
        val guide = card().apply { tag = "business_guide" }
        guide.addView(text("같은 이름·번호로 사람·업무를 합치지 않아요. 미분류 연결을 확인해 주세요."))
        if (unassigned.isEmpty()) guide.addView(text("업무 미분류 · 0개"))
        guide.addView(text(if (demoMode) "합성 예시 · 실제 연결 없음" else NativeAnalysisAvailability.MESSAGE))
    }
    private fun storedBusinessProgress(calls: List<JSONObject>): String? {
        if (calls.isEmpty()) return null
        val task = nullableString(calls.first(), "task_id") ?: return null
        val status = nullableString(calls.first(), "business_status") ?: return null
        val revision = calls.first().optLong("task_revision", -1)
        if (revision < 0 || TaskStatus.entries.none { it.wire == status }) return null
        return status.takeIf {
            calls.all { nullableString(it, "task_id") == task && nullableString(it, "business_status") == status && it.optLong("task_revision", -1) == revision }
        }
    }
    private fun selectRecordingFolder() {
        if(!localUiAvailable() || demoMode || storageContext==null)return
        try {
            settings.snapshot() // Hold selection if existing encrypted policy cannot be read.
            folderSelectionEpoch=GoogleConnectionSession.get(this).epoch()
            pickFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        } catch(_:Exception) { notice("기존 폴더 설정을 읽지 못했어요. 이전 선택과 권한은 유지해요.") }
    }
    private fun refreshLocalSetupState() {
        if(!localUiAvailable() || demoMode || busy || storageContext==null || localSetupLoading)return
        val scoped=storage();val owner=storageScope;val epoch=storageEpoch
        val ticket=++localSetupGeneration;val operation=localOperationGeneration
        localSetupLoading=true
        lifecycleScope.launch {
            try {
                val saved=withContext(Dispatchers.IO) { runCatching { readLocalSetupState(scoped) }.getOrDefault("REVIEW") }
                val current=runCatching { val v=GoogleConnectionSession.get(this@MainActivity).view();v.ownerId==owner && v.epoch==epoch }.getOrDefault(false)
                if(currentCoroutineContext().isActive && localUiAvailable() && !demoMode && current &&
                    owner==storageScope && epoch==storageEpoch && ticket==localSetupGeneration && operation==localOperationGeneration) localSetupState=saved
            } finally {
                if(ticket==localSetupGeneration) {
                    localSetupLoading=false
                    if(currentCoroutineContext().isActive && localUiAvailable() && settingsShowing && !demoMode && localSetupState!=null)renderSettings()
                }
            }
        }
    }
    private fun renderSettings() {
        if (!settingsShowing) return
        content.removeAllViews()
        if (demoMode) {
            val example = card(); example.addView(text("합성 예시의 초기 설정", 24f, true))
            example.addView(text("실제 연결·개인정보 설정을 표시하거나 읽지 않아요. 실제 설정을 시작하려면 합성 예시를 닫아 주세요."))
            if (BuildConfig.SYNTHETIC_TOOLS_ENABLED) example.addView(button("기기에 저장하는 합성 검증") {
                startActivity(Intent(this, SyntheticPilotActivity::class.java))
            })
            example.addView(button("합성 예시 닫고 실제 설정 보기") { closeDemo() }); return
        }
        val connections=card();connections.addView(text("계정과 연결",24f,true))
        connections.addView(text(runCatching { val v=GoogleConnectionSession.get(this).view();if(v.accountLabel.isBlank()) "Google 미연결 · 기기의 기존 기록" else "Google 계정: ${v.accountLabel}" }.getOrDefault("계정 정보 확인 필요")))
        connections.addView(button("Google 계정 관리") { openConnections() })
        connections.addView(button("네이버 메일 새 연결 · 설정") { requestLocalCancel();ScopedNaverMailSettingsActivity.open(this) }.apply { tag="naver_mail_settings" })
        connections.addView(button("저장된 네이버 메일 후보 검토") { startActivity(Intent(this,NaverMailReviewActivity::class.java)) })
        connections.addView(button("카카오 새 연결 · 준비 중") { requestLocalCancel();startActivity(Intent(this,ScopedKakaoSettingsActivity::class.java)) })
        connections.addView(button("매일 오전 6시 보고 · 보관과 실행 상태") {
            requestLocalCancel();startActivity(Intent(this,DailyReportActivity::class.java))
        }.apply { tag="daily_report_settings" })
        connections.addView(button("아침 보고") { requestLocalCancel();startActivity(Intent(this,kr.callbrief.pipeline.PipelineReportActivity::class.java)) }.apply { tag="pipeline_report_entry" })
        connections.addView(button("AI 분석 설정") { requestLocalCancel();startActivity(Intent(this,kr.callbrief.pipeline.PipelineSettingsActivity::class.java)) }.apply { tag="pipeline_settings_entry" })
        connections.addView(button("AI 분석 검토") { requestLocalCancel();startActivity(Intent(this,kr.callbrief.pipeline.PipelineReviewActivity::class.java)) }.apply { tag="pipeline_review_entry" })
        if(storageContext==null) { card().addView(text("계정 저장 정보를 읽지 못했어요. 기존 자료와 설정은 바꾸지 않았어요."));return }
        val state = try { settings.snapshot() } catch (_: Exception) {
            card().addView(text("기기 설정을 읽지 못했어요. 저장된 설정을 지우거나 바꾸지 않았어요.", bold = true)); return
        }
        val intro = card(); intro.addView(text("이 휴대전화의 초기 설정", 24f, true))
        intro.addView(text("접근 가능한 기존 통화 녹음 폴더를 선택해요. 폴더를 골라도 분석 연결이 준비되기 전에는 전달하지 않아요."))
        intro.addView(button("설정 안내 자세히 보기") { notice("공유 저장소의 Recordings / Call 폴더 등 실제 접근 가능한 폴더를 선택해 주세요. Android/data나 저장소 루트는 사용할 수 없어요. 기존 녹음만 읽으며, 전화 자동 녹음·연락처·통화 기록 권한은 사용하지 않아요.") })
        intro.addView(button(if (state.optString("treeUri").isBlank()) "녹음 폴더 선택" else "녹음 폴더 변경") {
            selectRecordingFolder()
        })
        intro.addView(text(if (state.optString("treeUri").isBlank()) "폴더: 아직 선택하지 않음" else "폴더: 선택됨 · 읽기 전용", bold = true))
        if (state.optString("treeUri").isNotBlank()) intro.addView(button("녹음 폴더 접근 확인") { showRecordings() })
        if(localSetupState==null && !localSetupLoading && !busy)refreshLocalSetupState()
        val local=card();local.addView(text("이 휴대전화에 사본 저장",22f,true))
        local.addView(text("개발 검증용이에요. 합성 파일만 사용해 주세요. 새 원음 사본·목록·메모는 암호화 저장해요. 기존 자료 이관·실기기 복구 검증은 아직 완료되지 않았어요.",bold=true))
        local.addView(text("${when(localSetupState) { "READY"->"원음 저장소 준비됨";"EMPTY"->"새 빈 저장소 준비 필요";"PENDING"->"초기 준비 중단 · 복구 확인 필요";"REVIEW"->"저장소·키 복구 확인 필요";else->"저장소 상태 확인 필요" }}"))
        local.addView(button("저장소 상태 확인") { checkLocalSetup() })
        if(localSetupState=="EMPTY") local.addView(button("새 빈 저장소 준비") { confirmLocalSetup(false) })
        if(localSetupState=="PENDING") local.addView(button("중단된 첫 준비 복구") { confirmLocalSetup(true) })
        local.addView(button("선택 폴더에서 가져오기") { importLocalRecordings() }.apply { isEnabled=localSetupState=="READY" && !busy })
        if(busy) local.addView(Button(this).apply { text="가져오기 중단";textSize=18f;minHeight=dp(56);setOnClickListener { requestLocalCancel() } })
        val native = card(); native.addView(text("분석 연결 준비 중", 22f, true))
        native.addView(text(NativeAnalysisAvailability.MESSAGE))
        native.addView(text("새 저장 방식과 보관·백업 정책은 아직 검토 중이에요. 실제 분석·자동 예약·카카오 연결은 확인되지 않았어요."))
        native.addView(button("Google로 합성 원음 검토") { requestLocalCancel();startActivity(Intent(this,NativeGoogleTrialActivity::class.java)) }.apply { tag="native_google_trial" })
        renderPrivacySettings(state)
    }
    private fun refreshBoard() {
        refreshLocalCatalog()
        if (!demoMode) notice("기기에 저장한 통화·직접 메모·상태를 다시 확인해요. 자동 분석과 외부 전송은 연결 준비 중이에요.")
    }
    private fun showDigest() {
        if (!demoMode) { showPreviousDayReview(); return }
        val day = requestedReportDate ?: SeoulTime.yesterday(screenNow()).toString()
        val digest = DemoFixtures.digest(day)
        val body = stack().apply { setPadding(dp(16), 0, dp(16), 0) }
        body.addView(text("합성 예시 · 실제 전송 없음", bold = true))
        body.addView(text(digest.optString("summary")))
        showReadDialog("$day 합성 브리핑", body)
    }
    private fun editTask(task: JSONObject) {
        if (!demoMode) { notice("네이티브 할일 저장 연결은 아직 확인되지 않았어요. 실제 항목을 수정하지 않았어요."); return }
        val baseVersion = task.getInt("version")
        fun taskValue(key: String): String = nullableString(task, key).orEmpty()
        val form = stack().apply { setPadding(dp(16), 0, dp(16), 0) }
        form.addView(text("합성 예시의 직접 수정이에요. 실제 저장 목록에는 반영하지 않아요."))
        val title = field(form, "할일 내용", taskValue("title")).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false); setHorizontallyScrolling(false); minLines = 3; maxLines = 8; gravity = android.view.Gravity.TOP
        }
        val assignee = field(form, "담당", taskValue("assignee"))
        val due = field(form, "기한 (선택, 직접 입력도 가능)", taskValue("due_date")).apply { hint = "기한 없음 · YYYY-MM-DD" }
        val dateActions = LinearLayout(this)
        dateActions.addView(button("날짜 선택") {
            val initial = runCatching { LocalDate.parse(due.text.toString()) }.getOrDefault(screenNow().atZone(SeoulTime.zone).toLocalDate())
            val picker = DatePickerDialog(this, { _, year, month, day -> due.setText(LocalDate.of(year, month + 1, day).toString()) }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
            ProtectedDialogWindow.show(picker); styleDialog(picker)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        dateActions.addView(button("기한 없음") { due.setText("") }, LinearLayout.LayoutParams(0, -2, 1f)); form.addView(dateActions)
        form.addView(text("진행 상태", bold = true))
        val states = TaskStatus.entries
        val spinner = choice(states.map { it.label }, states.indexOf(TaskStatus.fromWire(taskValue("status")))) {}
        form.addView(spinner)
        val complete = check(form, "내가 확인해서 완료함", taskValue("status") == "done")
        var changingStatus = false
        complete.setOnCheckedChangeListener { _, checked -> if (!changingStatus) spinner.setSelection(if (checked) states.indexOf(TaskStatus.DONE) else states.indexOf(TaskStatus.OPEN)) }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { changingStatus = true; complete.isChecked = states[position] == TaskStatus.DONE; changingStatus = false }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        form.addView(text("직접 바꾼 필드의 보호 표시와 변경 이력을 합성 데이터에서 유지해요."))
        val dialog = AlertDialog.Builder(this).setTitle("할일 수정·완료")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("취소", null).setPositiveButton("합성 변경 저장", null).create()
        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    require(task.getInt("version") == baseVersion) { "다른 변경이 먼저 저장됐어요. 현재 내용을 다시 열어 확인해 주세요." }
                    require(title.text.isNotBlank()) { "할일 내용을 입력해 주세요." }
                    val date = due.text.toString().trim()
                    if (date.isNotBlank()) { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date)) { "기한은 YYYY-MM-DD 형식으로 입력해 주세요." }; LocalDate.parse(date) }
                    val updates = JSONObject()
                    if (title.text.toString() != taskValue("title")) updates.put("title", title.text.toString())
                    if (assignee.text.toString() != taskValue("assignee")) updates.put("assignee", assignee.text.toString().ifBlank { "확인 필요" })
                    if (date != taskValue("due_date")) updates.put("due_date", if (date.isBlank()) JSONObject.NULL else date)
                    val status = states[spinner.selectedItemPosition].wire
                    if (status != taskValue("status")) updates.put("status", status)
                    if (updates.length() > 0) {
                        val changes = JSONObject()
                        val locks = (task.optJSONArray("manual_fields") ?: JSONArray()).let { (0 until it.length()).map { index -> it.getString(index) }.toMutableSet() }
                        updates.keys().forEach { key ->
                            changes.put(key, JSONObject().put("from", task.opt(key) ?: JSONObject.NULL).put("to", updates.get(key)))
                            task.put(key, updates.get(key)); locks.add(key)
                        }
                        task.put("version", baseVersion + 1); task.put("manual_fields", JSONArray(locks.toList()))
                        val history = task.optJSONArray("history") ?: JSONArray()
                        history.put(JSONObject().put("at", screenNow().toString()).put("source", "manual").put("changes", changes))
                        task.put("history", history)
                    }
                    dialog.dismiss(); renderHome()
                } catch (e: Exception) { notice(if (e is java.time.DateTimeException) "기한은 실제 날짜의 YYYY-MM-DD 형식으로 입력해 주세요. 모르면 비워 두세요." else "입력을 확인해 주세요.") }
            }
        }
        ProtectedDialogWindow.show(dialog)
    }
    private fun showEvidence(task: JSONObject) {
        val fileId = nullableString(task, "evidence_recording_id")
        if (!demoMode) {
            if (fileId != null && list(catalogFiles).any { nullableString(it, "file_id") == fileId }) showFileHistory(fileId)
            else notice("할일과 저장 통화의 근거 연결은 아직 확인되지 않았어요.")
            return
        }
        val body = stack().apply { setPadding(dp(16), 0, dp(16), 0) }
        body.addView(text("합성 예시 · 실제 통화 없음", bold = true))
        body.addView(text("근거 인용: ${task.optString("evidence_quote", "확인 필요")}"))
        list(task.optJSONArray("history") ?: JSONArray()).forEach { entry ->
            body.addView(text("${displayTime(entry.optString("at"))} · ${if (entry.optString("source") == "manual") "직접 변경" else "합성 분석 결과"}"))
            body.addView(text(entry.optJSONObject("changes")?.toString() ?: "변경 내용 미기록"))
        }
        if (fileId != null) body.addView(button("원문·요약 버전 비교") { showFileHistory(fileId) })
        showReadDialog("근거 통화·변경 이력", body)
    }
    private fun showTopic(topic: JSONObject) {
        val body = stack().apply { setPadding(dp(16), 0, dp(16), 0) }
        body.addView(text(topic.optString("summary", "저장된 누적 요약 없음")))
        list(tasks).filter { it.optString("case_id") == topic.optString("id") }.forEach { task ->
            val item = card(body); item.addView(text(task.optString("title"), 20f, true))
            item.addView(text(TaskStatus.fromWire(task.optString("status")).label))
        }
        showReadDialog("업무의 합성 할일", body)
    }
    private fun showRecordings() {
        if (demoMode) { notice("합성 예시에서는 실제 녹음 폴더를 읽지 않아요."); return }
        if (localDetailLoading) return
        val generation = catalogGeneration
        localDetailLoading = true
        lifecycleScope.launch {
            try {
                val scan = withContext(Dispatchers.IO) {
                    val state = settings.snapshot()
                    require(state.optString("treeUri").isNotBlank()) { "먼저 녹음 폴더를 선택해 주세요." }
                    SafRecordings(this@MainActivity).scan(Uri.parse(state.getString("treeUri")))
                }
                if (demoMode || generation != catalogGeneration || isFinishing) return@launch
                val body = stack().apply { setPadding(dp(16), 0, dp(16), 0) }
                body.addView(text("폴더의 파일 정보만 확인해요. 분석에 전달하지 않았어요."))
                body.addView(text("기존 로컬 제외 규칙에 해당하는 ${scan.excludedCount}개는 표시하지 않아요."))
                if (scan.truncated) body.addView(text("일부 파일만 확인했어요. 전체 폴더를 확인한 결과는 아니에요.", bold = true))
                if (scan.recordings.isEmpty()) body.addView(text("표시할 파일을 찾지 못했어요. 접근 권한과 선택 폴더를 확인해 주세요."))
                scan.recordings.takeLast(100).forEach { recording ->
                    val item = card(body); item.addView(text(recording.name, 20f, true))
                    item.addView(text("원본 통화 시작: ${recording.startedAt?.let { displayTime(it.toString()) } ?: "확인 필요"}"))
                    item.addView(text(recording.reason(screenNow()) ?: "파일 정보 확인됨 · 분석 미전달"))
                    item.addView(text("상대방·업무·통화 시각의 직접 저장 연결은 준비 중이에요."))
                }
                if (scan.recordings.size > 100) body.addView(text("최근 파일 정보 100개만 표시해요."))
                showReadDialog("녹음 폴더 접근 확인", body)
            } catch (e: Exception) {
                if (!demoMode && generation == catalogGeneration) notice(PrivacyDiagnostics.userMessage(e))
            } finally { if (generation == catalogGeneration) localDetailLoading = false }
        }
    }
    private fun localOperation(action: (GeneralLocalStore)->String, completed: ()->Unit = {}) {
        if(!localUiAvailable() || demoMode || busy || storageContext==null) return
        val operation = ++localOperationGeneration
        localCancelRequested=false;busy=true;buildShell()
        lifecycleScope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    if(localCancelRequested) error("CANCELLED")
                    createLocalStore().use { store ->
                        activeLocalStore=store
                        try {
                            if(localCancelRequested) { store.cancel();error("CANCELLED") }
                            action(store) to store.setupState()
                        } finally { activeLocalStore=null }
                    }
                }
                if(currentCoroutineContext().isActive && localUiAvailable() && operation == localOperationGeneration) { localSetupState=result.second;completed();notice(result.first) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                if(currentCoroutineContext().isActive && localUiAvailable() && operation == localOperationGeneration) notice(when {
                    error.message.orEmpty().contains("PROVIDER_LIST_FAILED")->"선택 폴더 목록을 읽지 못했어요. 파일이 없다고 판단하지 않았어요. 다시 가져와 확인해 주세요."
                    error.message.orEmpty().contains("CANCELLED")->"가져오기를 중단했어요. 이미 저장된 기록은 유지돼요. 중단된 파일은 다시 가져와 확인해 주세요."
                    error.message.orEmpty().contains("REVISION_CONFLICT")->"다른 변경이 먼저 저장됐어요. 현재 기록을 다시 열어 수정해 주세요. 기존 내용은 덮어쓰지 않았어요."
                    error.message.orEmpty().contains("NOT_READY")->"초기 설정에서 원음 저장소 상태를 확인하고 준비해 주세요."
                    else->"작업 완료를 확인하지 못했어요. 기존 기록은 유지했어요. 저장소·선택 폴더 상태를 확인한 뒤 다시 시도해 주세요."
                })
            } finally {
                if (operation == localOperationGeneration) {
                    busy=false;catalogLoaded=false
                    if(currentCoroutineContext().isActive && localUiAvailable()) { buildShell();refreshLocalCatalog() }
                }
            }
        }
    }
    private fun checkLocalSetup() {
        localOperation({ store ->
            val state=store.setupState()
            "저장소 상태: ${when(state) { "READY"->"준비됨";"EMPTY"->"새 빈 저장소";"PENDING"->"첫 준비 중단 · 복구 가능 여부 확인 필요";else->"복구 검토 필요" }}"
        })
    }
    private fun confirmLocalSetup(recover:Boolean) {
        val dialog=AlertDialog.Builder(this).setTitle(if(recover) "중단된 첫 준비 복구" else "새 빈 저장소 준비")
            .setMessage("이 개발 버전의 새 원음 저장소만 준비해요. 기존 code4 기록은 이전하지 않아요. 합성 파일로 검증해 주세요.")
            .setNegativeButton("취소",null).setPositiveButton("준비하기") { _,_ ->
                localOperation({ store ->
                    if(recover) store.recoverInitialization() else store.initializeEmpty()
                    check(store.setupState()=="READY")
                    "원음 저장소가 준비됐어요. 선택 폴더에서 가져오기를 눌러 사본을 저장할 수 있어요."
                })
            }.create()
        ProtectedDialogWindow.show(dialog);styleDialog(dialog)
    }
    private fun importLocalRecordings() {
        if(demoMode || busy) return
        val selected=runCatching { settings.snapshot().optString("treeUri") }.getOrNull()
        if(selected.isNullOrBlank()) { notice("초기 설정에서 읽을 폴더를 먼저 선택해 주세요.");return }
        val dialog=AlertDialog.Builder(this).setTitle("선택 폴더에서 가져오기")
            .setMessage("합성 파일만 사용해 주세요. 제외 규칙을 먼저 확인하고 이 휴대전화에 암호화 사본을 저장해요. 분석·외부 전송은 하지 않아요. 파일명 날짜는 직접 확인하기 전까지 후보로만 유지해요.")
            .setNegativeButton("취소",null).setPositiveButton("가져오기") { _,_ ->
                localOperation({ store ->
                    val report=store.importSelected(SafSelectedRecordingSource(this,settings,selected))
                    "새 파일 버전 ${report.newVersions}개 · 동일 내용 ${report.sameContent}개 · 읽기 전 제외 ${report.excluded}개 · 보류 ${report.held}개${if(report.incomplete) "\n일부 목록만 확인됐어요. 전체 수집 완료가 아니에요." else ""}\n번호·이름의 범위나 상대 번호를 확인하지 못한 파일은 원음을 읽지 않고 보류해요. 저장된 항목은 분석 대기예요. 날짜 확인 필요 목록에서 통화 날짜를 직접 확인해 주세요."
                })
            }.create()
        ProtectedDialogWindow.show(dialog);styleDialog(dialog)
    }
    private fun editLocalRecording(row:JSONObject) {
        val form=stack().apply { setPadding(dp(16),0,dp(16),0) }
        form.addView(text("직접 메모와 상태를 이 휴대전화에 저장해요. 분석 요약으로 표시하지 않아요."))
        val note=field(form,"직접 메모",nullableString(row,"manual_note").orEmpty()).apply {
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines=3;setSingleLine(false)
        }
        form.addView(text("진행 상태",bold=true))
        val states=TaskStatus.entries
        val choice=choice(states.map { it.label },states.indexOf(TaskStatus.fromWire(nullableString(row,"manual_status") ?: "open"))) {}
        form.addView(choice)
        val dialog=AlertDialog.Builder(this).setTitle("직접 메모·완료 상태 수정")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("취소",null).setPositiveButton("기기에 저장",null).create()
        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                localOperation({ store ->
                    store.edit(row.getString("file_id"),row,note.text.toString(),states[choice.selectedItemPosition].wire)
                    "직접 메모와 상태를 저장했어요. 원음 버전이 바뀌어도 직접 수정·완료 표시는 유지해요."
                }, { dialog.dismiss(); refreshOpenFileHistory(row.getString("file_id")) })
            }
        }
        ProtectedDialogWindow.show(dialog)
    }
    private fun confirmLocalTime(row:JSONObject) {
        val form=stack().apply { setPadding(dp(16),0,dp(16),0) }
        val candidate=nullableString(row,"original_started_at") ?: nullableString(row,"filename_time_candidate")
        val formatted=candidate?.let { runCatching { Instant.parse(it).atZone(SeoulTime.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull() }.orEmpty()
        form.addView(text("직접 확인한 통화 시작 날짜·시각을 입력해요. 서울 시간 기준이에요. 모르면 취소하고 확인 필요로 유지해 주세요."))
        val at=field(form,"통화 시작 (YYYY-MM-DD HH:mm)",formatted)
        val dialog=AlertDialog.Builder(this).setTitle("통화 날짜·시각 확인")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("취소",null).setPositiveButton("직접 확인해 저장",null).create()
        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val epoch=runCatching {
                    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}").matches(at.text.toString()))
                    java.time.LocalDateTime.parse(at.text.toString(),DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(SeoulTime.zone).toInstant().toEpochMilli()
                }.getOrNull()
                if(epoch==null) { notice("실제 날짜·시각을 YYYY-MM-DD HH:mm 형식으로 입력해 주세요.");return@setOnClickListener }
                localOperation({ store ->store.confirmTime(row.getString("file_id"),row,epoch);"통화 날짜·시각을 직접 확인해 저장했어요." }, { dialog.dismiss(); refreshOpenFileHistory(row.getString("file_id")) })
            }
        }
        ProtectedDialogWindow.show(dialog)
    }
    private fun confirmLocalArchive(row:JSONObject) {
        val restore=row.optBoolean("archived")
        val dialog=AlertDialog.Builder(this).setTitle(if(restore) "목록으로 되돌리기" else "통화 보관")
            .setMessage(if(restore) "같은 기록을 기본 목록으로 되돌려요. 직접 메모·완료·파일 버전은 유지해요." else "기본 목록에서 숨겨 보관해요. 원본 녹음과 원음 사본은 삭제하지 않아요.")
            .setNegativeButton("취소",null).setPositiveButton(if(restore) "되돌리기" else "보관하기") { _,_ ->
                localOperation({ store ->store.archive(row.getString("file_id"),row,!restore);if(restore) "기본 목록으로 되돌렸어요." else "보관했어요. 보관 탭에서 되돌릴 수 있어요." }, { refreshOpenFileHistory(row.getString("file_id")) })
            }.create()
        ProtectedDialogWindow.show(dialog);styleDialog(dialog)
    }
    override fun onDestroy() {
        stopAutomaticDiscovery()
        localUiClosed = true
        launchIntro?.dispose()
        requestLocalCancel()
        fileHistoryDialog?.dismiss()
        super.onDestroy()
    }
    override fun onStop() {
        stopAutomaticDiscovery()
        launchIntro?.finish()
        if(!demoMode && storageContext!=null) localUiPrefs().edit().putInt("tab",tab)
            .putBoolean("unknownDateSelected",selectedCallDate==null).putString("selectedCallDate",selectedCallDate)
            .putString("lastSelectedCallDate",lastSelectedCallDate).putString("taskFilter",taskFilter).apply()
        super.onStop()
    }
    private fun renderPrivacySettings(state: JSONObject) {
        val body = card(); body.addView(text("기존 로컬 제외 규칙", 22f, true))
        body.addView(text("저장된 제외 설정을 읽기 전용으로 보여줘요. 새 저장·보관 정책 검토가 끝나기 전에는 이 화면에서 변경하지 않아요."))
        body.addView(text("${LocalPrivacy.PERSONAL_PHONE_LABEL} · ${if (state.optBoolean("personalPhoneDisabled")) "기존 설정: 꺼짐" else "기존 설정: 차단"}"))
        body.addView(text("${LocalPrivacy.PERSONAL_ALIAS_LABEL} · ${if (state.optBoolean("personalAliasDisabled")) "기존 설정: 꺼짐" else "기존 설정: 차단"}"))
        body.addView(text("추가 제외 규칙 ${(state.optJSONArray("exclusions") ?: JSONArray()).length()}개"))
        body.addView(text("원본 녹음은 삭제하지 않아요."))
    }
}
