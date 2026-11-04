package kr.callbrief.pipeline

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout

/** Small parts of the v2 screens that belong to no single screen: the search box, its matching rule, the words for who does a check item. */
internal object ScreenParts {
    /** Every word of [query] (split at spaces) appears in one of [fields]; spaces and case are ignored, so "김 민수" finds "김민수". An empty query matches all. */
    fun matches(query:String,vararg fields:String?):Boolean {
        val words=query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }
        if(words.isEmpty()) return true
        val haystack=fields.filterNotNull().joinToString(" ").lowercase()
        val packed=haystack.filterNot { it.isWhitespace() }
        return words.all { haystack.contains(it) || packed.contains(it) }
    }

    /** One search line under the title. [onChange] runs on every edit with the text as typed. */
    fun searchBox(a:Activity,hint:String,start:String,tag:String,onChange:(String)->Unit):EditText=EditText(a).apply {
        this.tag=tag;this.hint=hint;setText(start);isSingleLine=true;inputType=InputType.TYPE_CLASS_TEXT;imeOptions=EditorInfo.IME_ACTION_SEARCH
        textSize=CallUi.BODY;typeface=CallUi.font(a,CallUi.Weight.REGULAR);setTextColor(CallUi.INK);setHintTextColor(CallUi.MUTED)
        minHeight=CallUi.dp(a,52);minimumHeight=minHeight;importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        background=CallUi.sheet(a,CallUi.CARD,radius=14)
        setCompoundDrawablesRelativeWithIntrinsicBounds(CallUi.icon(a,"search",CallUi.MUTED,22),null,null,null);compoundDrawablePadding=CallUi.dp(a,10)
        setPadding(CallUi.dp(a,16),0,CallUi.dp(a,16),0)
        addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,af:Int) {}
            override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int) {}
            override fun afterTextChanged(s:Editable?) { onChange(s?.toString().orEmpty()) }
        })
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=CallUi.dp(a,4);bottomMargin=CallUi.dp(a,12) }
    }

    /** "제가 하기로" / "상대가 하기로" for a check item a call made; empty when the owner is not known. */
    fun ownerWords(owner:String?)=when(owner) { "me" -> "제가 하기로";"them" -> "상대가 하기로";else -> "" }
    /** The quote under a check item; a quote that was not found letter for letter says so. Empty when there is none. */
    fun quoteLine(quote:String?,fuzzy:Boolean):String {
        val q=quote?.trim().orEmpty();if(q.isEmpty()) return ""
        return (if(fuzzy) "비슷한 말: " else "")+"‘$q’"
    }
}
