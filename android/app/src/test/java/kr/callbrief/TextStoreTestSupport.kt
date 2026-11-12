package kr.callbrief

import android.content.Context
import android.database.Cursor
import kr.co.callsummary.core.android.ProtectedDatabase
import java.io.File

/** Synthetic sandbox only. These bytes are never read from a user's installed app. */
internal object TextStoreTestSupport {
    fun reset() {
        val c=org.robolectric.RuntimeEnvironment.getApplication()
        // The process store (r8-1) outlives a test in the same sandbox; let it go before its file is removed.
        IeumApplication.shutdown()
        kr.callbrief.pipeline.OpenUpdate.reset() // v3.7: an Activity a test left open does not keep "the app is in front"
        File(c.noBackupFilesDir,"protected-databases-v1").deleteRecursively()
        File(c.noBackupFilesDir,"eum-accounts").walkTopDown().filter {it.name=="protected-databases-v1"}.toList().forEach {it.deleteRecursively()}
        ShadowTextKeys.KEYS.clear();ShadowTextKeys.CREATES.set(0);ShadowTextKeys.deny=false
    }
    fun open(context:Context,name:String)=TestDatabase(ProtectedDatabase.open(context,name,null))
    fun generatedSource(source:SelectedRecordingSource)=object:SelectedRecordingSource by source {override val explicitlySyntheticScope=true}
    class TestDatabase(private val store:ProtectedDatabase):AutoCloseable {
        private val db=store.database()
        fun rawQuery(sql:String,args:Array<String>?)=ProtectedDatabase.read(db){db.rawQuery(sql,args)}
        fun execSQL(sql:String)=store.transaction {db.execSQL(sql)}
        fun execSQL(sql:String,args:Array<out Any?>)=store.transaction {db.execSQL(sql,args)}
        fun query(table:String,columns:Array<String>?,where:String?,args:Array<String>?,group:String?,having:String?,order:String?):Cursor=
            ProtectedDatabase.read(db){db.query(table,columns,where,args,group,having,order)}
        fun delete(table:String,where:String?,args:Array<String>?)=store.transaction {db.delete(table,where,args)}
        override fun close()=store.close()
    }
}

abstract class ProtectedTextHostTestBase {
    @org.junit.Before fun resetTextProtectionSandbox() { TextStoreTestSupport.reset() }
}
