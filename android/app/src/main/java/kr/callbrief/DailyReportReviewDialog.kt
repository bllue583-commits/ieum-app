package kr.callbrief

import android.app.Activity
import android.app.AlertDialog
import android.widget.*
import android.text.InputType

/** UI-only explicit confirmation. Ledger/scope/basis validation stays with the caller. */
internal object DailyReportReviewDialog {
    fun show(activity:Activity,window:ReportWindow,value:String,onConfirmed:(String)->Unit):AlertDialog {
        val editor=EditText(activity).apply { setText(value);textSize=18f;minLines=4;tag="daily_report_editor";isSaveEnabled=false;isSaveFromParentEnabled=false
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;setHorizontallyScrolling(false)
            importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
        val check=CheckBox(activity).apply { text="내용·개인정보 제외를 확인했고 보고 보관에 동의해요.";textSize=18f;tag="daily_report_confirmation" }
        val density=activity.resources.displayMetrics.density
        val previewHeight=(activity.resources.displayMetrics.heightPixels*.35f/activity.resources.configuration.fontScale.coerceAtLeast(1f)).toInt().coerceAtLeast((96*density).toInt())
        val preview=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;addView(TextView(activity).apply { text=window.rangeKo();textSize=18f });addView(editor) }
        val scroll=ScrollView(activity).apply { tag="daily_report_preview_scroll";addView(preview) }
        val layout=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding((12*density).toInt(),0,(12*density).toInt(),0)
            addView(scroll,LinearLayout.LayoutParams(-1,previewHeight));addView(check,LinearLayout.LayoutParams(-1,-2)) }
        val dialog=AlertDialog.Builder(activity).setTitle(window.titleKo()+" · 보관 전 확인").setView(layout).setNegativeButton("취소",null).setPositiveButton("앱에 보관",null).create()
        ProtectedDialogWindow.show(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
        check.setOnCheckedChangeListener { _,checked->dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=checked }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if(check.isChecked && !activity.isFinishing && !activity.isDestroyed){val text=editor.text.toString();dialog.dismiss();onConfirmed(text)} }
        return dialog
    }
}
