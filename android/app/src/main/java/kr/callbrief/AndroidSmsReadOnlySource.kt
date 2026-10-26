package kr.callbrief

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.CancellationSignal
import android.provider.Telephony
import kr.callbrief.core.PrivacyRules

/** Exact-number, bounded SMS only. No permission request, contacts lookup, flags or writes. */
internal class AndroidSmsReadOnlySource(private val context:Context) {
    data class Message(val providerId:Long,val dateMs:Long,val type:Int,val phone:String,val text:String)
    data class Batch(val messages:List<Message>,val incomplete:Boolean,val skipped:Int)
    private data class Header(val id:Long,val date:Long,val type:Int,val phone:String)
    private val columns=arrayOf("_id","address","date","type")
    fun hasPermission()=context.checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED
    private fun requireRead(authorized:()->Boolean,signal:CancellationSignal) {
        check(!signal.isCanceled&&authorized()) { "RELATED_READ_CANCELLED" }
        check(hasPermission()) { "SMS_EXISTING_PERMISSION_REQUIRED" }
    }
    private fun header(c:Cursor):Header? {
        val phone=PrivacyRules.validatedPhone(c.getString(c.getColumnIndexOrThrow("address")).orEmpty()) ?: return null
        val id=c.getLong(c.getColumnIndexOrThrow("_id"));val date=c.getLong(c.getColumnIndexOrThrow("date"));val type=c.getInt(c.getColumnIndexOrThrow("type"))
        return if(id>0&&date>=0&&type in 1..2)Header(id,date,type,phone) else null
    }
    fun read(scope:RelatedReadOnlyStore.Scope,authorized:()->Boolean,signal:CancellationSignal):Batch {
        requireRead(authorized,signal)
        require(scope.phone==PrivacyRules.validatedPhone(scope.phone)&&scope.untilMs>scope.fromMs)
        // The shared validator accepts NFKC fullwidth digits and phone punctuation. Fold those
        // equivalent forms in the actual provider selector as well; never broaden to all SMS.
        var cleaned="address"
        for(digit in 0..9)cleaned="REPLACE($cleaned,'${('０'.code+digit).toChar()}','$digit')"
        for(symbol in " -._()+") {
            val wide=if(symbol==' ')'　' else (symbol.code+0xfee0).toChar()
            cleaned="REPLACE(REPLACE($cleaned,'$symbol',''),'$wide','')"
        }
        // These common no-break spaces normalize to ASCII space under the same NFKC validator.
        for(space in listOf('\u00a0','\u202f'))cleaned="REPLACE($cleaned,'$space','')"
        val variants=if(scope.phone.startsWith("0"))listOf(scope.phone,"82"+scope.phone.drop(1),"0082"+scope.phone.drop(1),"82"+scope.phone,"0082"+scope.phone) else listOf(scope.phone)
        val selection="$cleaned IN (${variants.joinToString(","){"?"}}) AND date>=? AND date<? AND type IN (1,2)"
        val args=(variants+listOf(scope.fromMs.toString(),scope.untilMs.toString())).toTypedArray()
        val headers=mutableListOf<Header>();var incomplete=false;var skipped=0;var examined=0
        val cursor=context.contentResolver.query(Telephony.Sms.CONTENT_URI,columns,selection,args,"date DESC, _id DESC",signal) ?: error("SMS_PROVIDER_UNAVAILABLE")
        cursor.use { c ->
            while(c.moveToNext()) {
                requireRead(authorized,signal)
                if(++examined>150){incomplete=true;break}
                val h=header(c)
                if(h==null||h.phone!=scope.phone||h.date !in scope.fromMs until scope.untilMs) { skipped++;incomplete=true;continue }
                if(headers.size==50){incomplete=true;break}
                if(headers.any{it.id==h.id})error("SMS_DUPLICATE_PROVIDER_ID")
                headers+=h
            }
        }
        val messages=mutableListOf<Message>()
        for(h in headers) {
            requireRead(authorized,signal)
            val body=context.contentResolver.query(Telephony.Sms.CONTENT_URI,columns+"body",selection+" AND _id=?",args+h.id.toString(),null,signal) ?: error("SMS_PROVIDER_UNAVAILABLE")
            body.use { c ->
                requireRead(authorized,signal)
                check(c.moveToFirst()&&header(c)==h) { "SMS_METADATA_CHANGED_RESCAN" }
                // Do not access the body before exact phone/time/id revalidation.
                val text=c.getString(c.getColumnIndexOrThrow("body"))
                requireRead(authorized,signal)
                check(!c.moveToNext()) { "SMS_DUPLICATE_PROVIDER_ID" }
                if(text.isNullOrBlank()||text.length>20000||text.contains('\u0000')){skipped++;incomplete=true}
                else messages+=Message(h.id,h.date,h.type,h.phone,text)
            }
        }
        requireRead(authorized,signal)
        return Batch(messages.toList(),incomplete,skipped)
    }
}
