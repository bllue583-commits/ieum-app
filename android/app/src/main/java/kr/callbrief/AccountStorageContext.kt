package kr.callbrief

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Original device-local data is retained; Google account stores never inherit it automatically. */
internal class AccountStorageContext(base: Context, val ownerId: String) : ContextWrapper(base), kr.co.callsummary.core.android.ProtectedDatabase.LegacyPaths {
    val scope = GoogleConnectionSession.scopeKey(ownerId)
    private fun named(name: String): String { require(!name.contains('/') && !name.contains('\\')); return "eum_" + scope + "_" + name }
    override fun getApplicationContext(): Context = this
    override fun getDatabasePath(name: String): File = File(noBackupFilesDir,"databases/"+name.also { named(it) }).apply { parentFile?.mkdirs() }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
    override fun legacyDatabaseFiles(name: String): List<File> {
        val old=baseContext.getDatabasePath(named(name))
        return listOf("","-wal","-shm","-journal").map { File(old.path+it) }
    }
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(named(name), mode)
    override fun getFilesDir(): File = File(baseContext.filesDir, "eum-accounts/" + scope).apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = File(baseContext.noBackupFilesDir, "eum-accounts/" + scope).apply { mkdirs() }
    companion object {
        fun selected(context: Context): Context {
            val owner = GoogleConnectionSession.get(context).view().ownerId
            return if (owner.isBlank()) context else AccountStorageContext(context, owner)
        }
    }
}
