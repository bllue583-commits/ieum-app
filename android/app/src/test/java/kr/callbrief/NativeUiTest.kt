package kr.callbrief

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.FileOutputStream

/** Synthetic-only tests of the actual activity. Renders are JVM native graphics, not device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-xhdpi",shadows=[ShadowTextKeys::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeUiTest : ProtectedTextHostTestBase() {
    @Before fun normalFont() { RuntimeEnvironment.setFontScale(1f) }

    private fun demoIntent(): Intent {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        return Intent(app, MainActivity::class.java).putExtra("demo", true)
    }
    private fun activity(): MainActivity = Robolectric.buildActivity(MainActivity::class.java, demoIntent()).setup().get().also { idle() }
    private fun idle() { shadowOf(android.os.Looper.getMainLooper()).idle() }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun root(activity: MainActivity): View = field(activity, "root") as View
    private fun texts(root: View): List<String> = views(root).filterIsInstance<TextView>().map { it.text.toString() }
    private fun taskState(activity: MainActivity): String = (field(activity, "tasks") as JSONArray).toString()
    private fun click(root: View, label: String) {
        val target = views(root).filterIsInstance<Button>().first {
            it.contentDescription?.toString() == label || it.text.toString().removePrefix("✓ ").startsWith(label)
        }
        assertTrue("$label should be enabled", target.isEnabled)
        assertTrue(target.performClick()); idle()
    }
    private fun click(activity: MainActivity, label: String) = click(root(activity), label)
    private fun navigation(activity: MainActivity): ViewGroup = requireNotNull(root(activity).findViewWithTag<ViewGroup>("primary_navigation"))
    private fun tab(activity: MainActivity, index: Int) {
        val target = requireNotNull(navigation(activity).findViewWithTag<Button>("primary_tab_$index"))
        assertTrue(target.isEnabled); assertTrue(target.performClick()); idle()
    }
    private fun dialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    private fun render(view: View, name: String) {
        val width = 822; val height = 1782
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE); view.draw(Canvas(bitmap))
        val directory = File("build/reports/ui-native").apply { mkdirs() }
        FileOutputStream(File(directory, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun fourBottomTabsShowDailyCallsBeforeWeeklySummaries() {
        val activity = activity(); render(root(activity), "dates-four-tabs")
        val labels = texts(root(activity))
        val buttons = views(root(activity)).filterIsInstance<Button>().map { it.text.toString() }
        assertTrue(buttons.containsAll(listOf("날짜별", "업무별", "할일", "보관")))
        assertFalse(buttons.contains("초기 설정"))
        assertTrue(labels.any { it == "통화 2개" })
        assertFalse(labels.any { it.startsWith("원본 통화 날짜:") || it.startsWith("수집:") })
        assertTrue(labels.indexOfFirst { it.contains("준비물 확인이 추가") } < labels.indexOf("주간 요약"))
        val layout = root(activity) as ViewGroup
        val bottom = layout.getChildAt(layout.childCount - 1)
        assertEquals(listOf("날짜별", "업무별", "할일", "보관"), views(bottom).filterIsInstance<Button>().map { it.text.toString() })
        assertTrue(views(bottom).filterIsInstance<Button>().all { it.compoundDrawables[1] != null })
    }

    @Test fun unknownOriginalDateRemainsSeparateFromItsCollectionDate() {
        val activity = activity(); click(activity, "날짜 확인 필요 · 1개")
        render(root(activity), "unknown-date")
        val labels = texts(root(activity))
        assertTrue(labels.any { it == "통화 1개" })
        assertTrue(labels.any { it == "통화 날짜·시각 확인 필요" })
        assertTrue(labels.any { it == "선택한 날짜로 돌아가기" })
        assertTrue(labels.any { it.contains("원본 날짜를 확인해야 주간 범위에 포함") })
        assertFalse(labels.any { it.contains("준비물 확인이 추가") })
        val unknown = DemoFixtures.catalogFiles().getJSONObject(3)
        assertTrue(unknown.isNull("original_date")); assertTrue(unknown.isNull("original_started_at"))
        assertEquals("2026-10-10T08:00:00Z", unknown.getString("collected_at"))
    }

    @Test fun storedVersionsAndRawTextCanBeComparedWithoutChangingTasks() {
        val activity = activity(); val before = taskState(activity)
        click(activity, "상세 보기")
        val comparison = dialog(); render(comparison.window!!.decorView, "summary-version-comparison")
        val labels = texts(comparison.window!!.decorView)
        assertTrue(labels.contains("이전 요약")); assertTrue(labels.contains("현재 요약"))
        assertTrue(labels.any { it.contains("준비물 추가 요청은 아직 없어요") })
        assertTrue(labels.any { it.contains("준비물 확인이 추가됐어요") })
        assertTrue(labels.any { it.contains("할일 목록에 자동 적용하지 않아요") })
        click(comparison.window!!.decorView, "원문·요약 1차 보기")
        assertTrue(texts(dialog().window!!.decorView).any { it.contains("[합성 원문 1]") })
        assertEquals(before, taskState(activity))
        val versions = DemoFixtures.history("demo-file-a").getJSONArray("versions")
        assertEquals(2, versions.length())
        assertEquals(64, versions.getJSONObject(0).getString("audio_sha256").length)
        assertNotEquals(versions.getJSONObject(0).getString("audio_sha256"), versions.getJSONObject(1).getString("audio_sha256"))
    }

    @Test fun previousDayReviewRetainsOlderOriginalDatesAndNeverAppliesChanges() {
        val activity = activity(); val before = taskState(activity)
        click(activity, "기록 관리"); click(dialog().window!!.decorView, "어제 수집·변경 확인")
        val reviewDialog = dialog(); render(reviewDialog.window!!.decorView, "previous-day-review")
        val labels = texts(reviewDialog.window!!.decorView)
        assertTrue(labels.any { it == "원본 날짜: 2026-10-09" })
        assertTrue(labels.any { it == "원본 날짜: 2026-10-08" })
        assertTrue(labels.any { it == "원본 날짜: 확인 필요" })
        assertTrue(labels.any { it.contains("할일 상태나 완료 여부를 자동으로 바꾸지 않아요") })
        assertEquals(before, taskState(activity))
        val review = DemoFixtures.review()
        assertEquals("2026-10-10", review.getString("date")); assertFalse(review.getBoolean("applied"))
        assertEquals(5, review.getJSONArray("file_changes").length())
    }

    @Test fun sameOpaqueNumberGroupDoesNotMergeDistinctWorkGroups() {
        val files = DemoFixtures.catalogFiles()
        assertEquals(files.getJSONObject(0).getString("counterpart_ref"), files.getJSONObject(1).getString("counterpart_ref"))
        assertNotEquals(files.getJSONObject(0).getString("case_id"), files.getJSONObject(1).getString("case_id"))
        val activity = activity(); tab(activity, 1); render(root(activity), "work-groups")
        val labels = texts(root(activity))
        assertTrue(labels.any { it == "예시 고객 A · 장비 납품과 설치" })
        assertTrue(labels.any { it == "예시 고객 A · 담당 확인 필요 · 별도 정산 건" })
        assertTrue(labels.any { it == "저장 통화 3개 · 날짜 확인 필요 0개" })
        assertTrue(labels.any { it == "저장 통화 1개 · 날짜 확인 필요 0개" })
        assertTrue(labels.any { it == "업무 미분류 · 1개" })
    }

    @Test fun completionUndoPreservesPriorManualLocksAndAddsHistoryWithoutNetwork() {
        val activity = activity(); tab(activity, 2)
        assertTrue(texts(root(activity)).contains("todo"))
        assertTrue(views(navigation(activity)).filterIsInstance<Button>().any { it.text.toString() == "할일" })
        click(activity, "수정·완료 체크")
        val edit = dialog()
        views(edit.window!!.decorView).filterIsInstance<CheckBox>().first().isChecked = true; idle()
        edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val task = (field(activity, "tasks") as JSONArray).getJSONObject(0)
        assertEquals("done", task.getString("status")); assertEquals(2, task.getInt("version"))
        click(activity, "완료"); click(activity, "수정·완료 체크")
        val undo = dialog()
        views(undo.window!!.decorView).filterIsInstance<EditText>().first().setText("[합성 직접 수정] 준비물 목록 재확인")
        views(undo.window!!.decorView).filterIsInstance<CheckBox>().first().isChecked = false; idle()
        undo.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        click(activity, "할일\n")
        assertEquals("open", task.getString("status")); assertEquals(3, task.getInt("version"))
        val locks = task.getJSONArray("manual_fields")
        assertTrue((0 until locks.length()).map { locks.getString(it) }.containsAll(listOf("status", "title")))
        assertEquals(3, task.getJSONArray("history").length())
        assertEquals("manual", task.getJSONArray("history").getJSONObject(2).getString("source"))
        assertNull(field(activity, "settings"))
    }

    @Test fun largeFontKeepsAllFourBottomChoicesAndSeparateSettingsReachable() {
        RuntimeEnvironment.setFontScale(1.5f)
        val activity = activity(); render(root(activity), "dates-font150")
        val settingsButton = views(root(activity)).filterIsInstance<Button>().first { it.contentDescription == "초기 설정 열기" }
        val settingsBounds = Rect()
        assertTrue("settings must be visible: x=${settingsButton.x}, y=${settingsButton.y}, w=${settingsButton.width}, h=${settingsButton.height}", settingsButton.getGlobalVisibleRect(settingsBounds))
        assertTrue(settingsBounds.width() >= settingsButton.minHeight && settingsBounds.height() >= settingsButton.minHeight)
        val minimum = (56 * activity.resources.displayMetrics.density + 0.5f).toInt()
        assertTrue(views(root(activity)).filterIsInstance<Button>().all { it.minHeight >= minimum })
        val minimumText = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 18f, activity.resources.displayMetrics)
        assertTrue(views(root(activity)).filterIsInstance<TextView>().filter { it.text.isNotEmpty() }.all { it.textSize >= minimumText - 0.5f })
        fun assertFourVisibleButtons() {
            val nav = navigation(activity)
            assertEquals(2, nav.childCount)
            assertTrue((0 until nav.childCount).all { (nav.getChildAt(it) as ViewGroup).childCount == 2 })
            val buttons = views(nav).filterIsInstance<Button>()
            assertEquals(listOf("날짜별", "업무별", "할일", "보관"), buttons.map { it.text.toString() })
            assertTrue(buttons.all { it.isShown && it.minHeight >= minimum && it.height >= minimum })
            assertTrue(buttons.all { it.compoundDrawables[1] != null })
            assertTrue(views(nav).filterIsInstance<Spinner>().isEmpty())
        }
        assertFourVisibleButtons()
        for (index in 1..3) {
            tab(activity, index); assertEquals(index, field(activity, "tab"))
            render(root(activity), "tab-$index-font150"); assertFourVisibleButtons()
            if (index == 2) assertTrue(texts(root(activity)).contains("todo"))
        }
        click(activity, "초기 설정 열기")
        render(root(activity), "settings-font150")
        assertTrue(texts(root(activity)).any { it == "합성 예시의 초기 설정" })
        assertFalse(views(root(activity)).any { it is EditText })
        assertTrue(views(root(activity)).filterIsInstance<Spinner>().isEmpty())
        assertNull(root(activity).findViewWithTag<View>("primary_navigation"))
        click(activity, "4탭으로 돌아가기")
        render(root(activity), "archive-return-from-settings-font150")
        assertEquals(3, field(activity, "tab")); assertFourVisibleButtons()
    }

    @Test fun largeFontTaskEditorHasMultilineInputAndLargeSaveCancel() {
        RuntimeEnvironment.setFontScale(1.5f)
        val activity = activity(); tab(activity, 2); click(activity, "수정·완료 체크")
        val edit = dialog(); render(edit.window!!.decorView, "task-edit-font150")
        val minimum = (56 * activity.resources.displayMetrics.density + 0.5f).toInt()
        val save = edit.getButton(AlertDialog.BUTTON_POSITIVE); val cancel = edit.getButton(AlertDialog.BUTTON_NEGATIVE)
        assertTrue(save.isShown && cancel.isShown); assertTrue(save.minHeight >= minimum && cancel.minHeight >= minimum)
        val title = views(edit.window!!.decorView).filterIsInstance<EditText>().first()
        assertTrue(title.minLines >= 3); assertFalse(title.isSingleLine)
        val before = taskState(activity); title.setText("[합성 초안] 저장하지 않음")
        cancel.performClick(); idle(); assertEquals(before, taskState(activity))
    }

    @Test fun datePickerClearAndCompletionStatusStaySynchronized() {
        val activity = activity(); tab(activity, 2); click(activity, "수정·완료 체크")
        val edit = dialog(); val due = views(edit.window!!.decorView).filterIsInstance<EditText>()[2]
        click(edit.window!!.decorView, "기한 없음"); assertEquals("", due.text.toString())
        click(edit.window!!.decorView, "날짜 선택")
        val picker = dialog() as DatePickerDialog
        picker.datePicker.updateDate(2026, 10, 5); picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertEquals("2026-11-05", due.text.toString())
        val complete = views(edit.window!!.decorView).filterIsInstance<CheckBox>().first()
        val spinner = views(edit.window!!.decorView).filterIsInstance<Spinner>().first()
        complete.isChecked = true; idle(); assertEquals(kr.callbrief.core.TaskStatus.DONE.label, spinner.selectedItem.toString())
        spinner.setSelection(kr.callbrief.core.TaskStatus.entries.indexOf(kr.callbrief.core.TaskStatus.WAITING)); idle(); assertFalse(complete.isChecked)
    }

    @Test fun archiveIsExplicitlyUnavailableAndCannotDeleteOrHideAnything() {
        val activity = activity(); val before = taskState(activity)
        tab(activity, 3); render(root(activity), "archive-unavailable")
        val labels = texts(root(activity))
        assertTrue(labels.any { it == "보관 기능은 준비 중이에요. 원본 녹음은 삭제하지 않아요." })
        assertTrue(labels.any { it.contains("아직 구현되지 않아") })
        val unavailable = views(root(activity)).filterIsInstance<Button>().first { it.text == "목록 숨기기 · 준비 중" }
        assertFalse(unavailable.isEnabled)
        assertFalse(labels.any { it.contains("보관했어요") || it.contains("서버") || it.contains("카카오 예약") })
        assertEquals(before, taskState(activity)); assertEquals(5, (field(activity, "catalogFiles") as JSONArray).length())
    }

    @Test fun syntheticModeDoesNotReadOrWriteEvenMalformedPrivateSettings() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("private_settings", android.content.Context.MODE_PRIVATE)
        val fabricated = "deliberately-malformed-synthetic-ciphertext"
        assertTrue(prefs.edit().putString("state", fabricated).commit())
        val activity = activity(); click(activity, "기록 관리"); click(dialog().window!!.decorView, "저장 목록 새로고침")
        tab(activity, 1); tab(activity, 2); click(activity, "수정·완료 체크")
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle()
        tab(activity, 3); click(activity, "초기 설정 열기")
        assertNull(field(activity, "settings")); assertFalse(field(activity, "workObserved") as Boolean)
        assertEquals(fabricated, prefs.getString("state", null))
        assertFalse(texts(root(activity)).any { it.contains("토큰") || it.contains("HTTPS") })
    }

    @Test fun recreationPreservesSyntheticEditsTabAndUnknownDateWithoutPrivateStorage() {
        val intent = demoIntent()
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get(); tab(activity, 2); click(activity, "수정·완료 체크")
        views(dialog().window!!.decorView).filterIsInstance<CheckBox>().first().isChecked = true; idle()
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        val before = taskState(activity)
        tab(activity, 0); click(activity, "날짜 확인 필요 · 1개"); tab(activity, 1)
        val saved = Bundle(); controller.pause().saveInstanceState(saved).stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java, intent).create(saved).start().resume().visible().get(); idle()
        assertEquals(1, field(restored, "tab")); assertNull(field(restored, "selectedCallDate"))
        assertEquals(before, taskState(restored)); assertNull(field(restored, "settings"))
        tab(restored, 0); assertTrue(texts(root(restored)).any { it == "통화 1개" })
    }
}
