package kr.co.callsummary.core.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;
import android.util.Base64;
import kr.co.callsummary.core.CoreException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Bounded sealed persistence for the existing SQLite SQL/trigger contract.
 * SQLite is memory-only, including temp tables. All tables (commands, history, receipts,
 * metadata and BLOBs) are sealed together before an outer transaction reports success.
 * This is not SQLCipher or field-level searchable encryption. It is deliberately bounded
 * to 16 MiB and does not automatically migrate, erase, or open a legacy plaintext store.
 */
public final class ProtectedDatabase implements AutoCloseable {
    public interface Work<T>{T run();}
    public interface CommitDecider<T>{boolean commit(T result);}
    public interface LegacyPaths { List<File> legacyDatabaseFiles(String name); }
    private static final Map<String,State> STORES=new HashMap<>();
    private static final Map<SQLiteDatabase,State> BY_DATABASE=new IdentityHashMap<>();
    public static final int MAX_PLAIN_BYTES=16*1024*1024;
    public static final int MAX_ROW_BYTES=1024*1024;
    private final State state;
    private boolean closed;
    private ProtectedDatabase(State state){this.state=state;}
    public static File file(Context context,String name){
        name(name);return new File(new File(context.getNoBackupFilesDir(),"protected-databases-v1"),name+".sealed");
    }
    public static List<File> legacyFiles(Context context,String name){
        List<File> files=new ArrayList<>();File base=context.getDatabasePath(name);
        for(String suffix:new String[]{"","-wal","-shm","-journal"})files.add(new File(base.getPath()+suffix));
        if(context instanceof LegacyPaths)files.addAll(((LegacyPaths)context).legacyDatabaseFiles(name));
        return files;
    }
    private static void name(String name){if(!name.matches("[A-Za-z0-9_.-]{1,96}"))throw error("TEXT_STORE_NAME_INVALID");}
    public static ProtectedDatabase open(Context context,String name,String schema){
        Context app=context.getApplicationContext();File path=file(app,name);
        synchronized(STORES){
            try{
                for(File old:legacyFiles(app,name))if(old.exists())throw error("TEXT_STORE_LEGACY_REVIEW_REQUIRED");
                String identity=path.getCanonicalPath();State s=STORES.get(identity);
                if(s==null){
                    s=new State(path,schema);s.requireReady();STORES.put(identity,s);BY_DATABASE.put(s.db,s);
                }
                s.requireReady();s.refs++;return new ProtectedDatabase(s);
            }catch(CoreException e){throw e;}catch(Exception e){throw error("TEXT_STORE_OPEN_FAILED");}
        }
    }
    public SQLiteDatabase database(){if(closed)throw error("TEXT_STORE_CLOSED");return state.db;}
    public <T>T transaction(Work<T> work){if(closed)throw error("TEXT_STORE_CLOSED");return state.transaction(work);}
    /** Shared transaction boundary for relay modules that use the same SQLiteDatabase handle. */
    public static <T>T transaction(SQLiteDatabase database,Work<T> work){
        State s; synchronized(STORES){s=BY_DATABASE.get(database);}
        if(s==null){ // Protocol libraries can still be tested against their isolated memory DB.
            memoryOnly(database);
            database.beginTransaction();try{T r=work.run();database.setTransactionSuccessful();return r;}finally{database.endTransaction();}
        }
        return s.transaction(work);
    }
    public static <T>T conditionalTransaction(SQLiteDatabase database,Work<T> work,CommitDecider<T> decider){
        State s;synchronized(STORES){s=BY_DATABASE.get(database);}
        if(s==null){memoryOnly(database);database.beginTransaction();try{T r=work.run();if(decider.commit(r))database.setTransactionSuccessful();return r;}finally{database.endTransaction();}}
        return s.transaction(work,decider);
    }
    public static <T>T read(SQLiteDatabase database,Work<T> work){
        State s;synchronized(STORES){s=BY_DATABASE.get(database);}if(s==null){memoryOnly(database);return work.run();}
        synchronized(s){s.requireReady();return work.run();}
    }
    /** Capability gate; actual Keystore/OEM/personal-data acceptance remains separate. */
    public static void requireSealedPersistence(SQLiteDatabase database){
        State s;synchronized(STORES){s=BY_DATABASE.get(database);}
        if(s==null)throw error("TEXT_STORE_PROTECTED_PORT_REQUIRED");
        synchronized(s){s.requireReady();}
    }
    @Override public void close(){
        synchronized(STORES){if(closed)return;closed=true;if(--state.refs==0){
            STORES.remove(state.identity);BY_DATABASE.remove(state.db);state.close();
        }}
    }
    private static CoreException error(String code){return new CoreException(code);}
    private static void memoryOnly(SQLiteDatabase db){if(!":memory:".equals(db.getPath()))throw error("TEXT_STORE_UNREGISTERED_DISK_DATABASE");}
    private static String sha(byte[] data)throws Exception{byte[] h=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder s=new StringBuilder();for(byte b:h)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString();}
    private static String quote(String identifier){return "\""+identifier.replace("\"","\"\"")+"\"";}
    private static byte[] readBounded(InputStream in,int maximum)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        try{while((n=in.read(buffer))!=-1){if(out.size()+n>maximum)throw error("TEXT_STORE_CAPACITY_REQUIRES_REVIEW");out.write(buffer,0,n);}return out.toByteArray();}
        finally{Arrays.fill(buffer,(byte)0);}
    }
    private static final class State {
        final String identity,alias;final byte[] aad;final File path;final AtomicFile atomic;
        final SQLiteDatabase db;final KeystoreTextKeys keys=new KeystoreTextKeys();
        final RandomAccessFile lockFile;final FileChannel lockChannel;final FileLock lock;
        int refs,depth;volatile boolean held;boolean rollbackOnly;byte[] lastPlain,keyProof;String cipherHash;
        State(File path,String schema)throws Exception{
            this.path=path;identity=path.getCanonicalPath();alias="callsummary.text.v1."+sha(identity.getBytes(StandardCharsets.UTF_8));
            aad=("IEUM_SQL_SNAPSHOT_V1\n"+identity).getBytes(StandardCharsets.UTF_8);
            File dir=path.getParentFile();if(!dir.isDirectory()&&!dir.mkdirs())throw error("TEXT_STORE_DIRECTORY_FAILED");
            lockFile=new RandomAccessFile(new File(path.getPath()+".lock"),"rw");lockChannel=lockFile.getChannel();
            FileLock acquired;try{acquired=lockChannel.tryLock();}catch(Exception e){lockChannel.close();lockFile.close();throw error("TEXT_STORE_BUSY");}
            if(acquired==null){lockChannel.close();lockFile.close();throw error("TEXT_STORE_BUSY");}lock=acquired;
            atomic=new AtomicFile(path);db=SQLiteDatabase.create(null);
            try{
                db.execSQL("PRAGMA temp_store=MEMORY");db.setForeignKeyConstraintsEnabled(true);
                // Configure once before the handle is shared. Existing authenticated snapshots
                // retain their immutable DELETE triggers, which must also run for REPLACE.
                db.execSQL("PRAGMA recursive_triggers=ON");
                boolean existing=path.exists()||new File(path+".bak").exists()||new File(path+".new").exists();
                if(existing){
                    SecretKey key=keys.existing(alias);if(key==null)throw error("TEXT_STORE_KEY_UNAVAILABLE");
                    byte[] encoded;try(InputStream in=readMainPreservingArtifacts()){encoded=readBounded(in,MAX_PLAIN_BYTES+128);}
                    byte[] plain=unseal(encoded,key);try{restore(db,plain);lastPlain=plain.clone();keyProof=sealProof(key);cipherHash=sha(encoded);}finally{Arrays.fill(plain,(byte)0);Arrays.fill(encoded,(byte)0);}
                }else{
                    if(schema==null)throw error("TEXT_STORE_NOT_INITIALIZED");
                    for(String statement:schema.split("\n-- statement-break\n"))db.execSQL(statement);
                    byte[] plain=snapshot(db);try{SecretKey key=keys.existing(alias);if(key==null)key=keys.createForEmptyStore(alias);if(key==null)throw error("TEXT_STORE_KEY_UNAVAILABLE");persist(plain,key);lastPlain=plain.clone();keyProof=sealProof(key);}finally{Arrays.fill(plain,(byte)0);}
                }
            }catch(Exception e){close();if(e instanceof CoreException)throw e;throw error("TEXT_STORE_AUTHENTICATION_OR_OPEN_FAILED");}
        }
        byte[] sealProof(SecretKey key)throws Exception{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(aad);
            byte[] encrypted=cipher.doFinal("IEUM_TEXT_KEY_CHECK".getBytes(StandardCharsets.UTF_8));byte[] proof=new byte[13+encrypted.length];proof[0]=1;System.arraycopy(cipher.getIV(),0,proof,1,12);System.arraycopy(encrypted,0,proof,13,encrypted.length);return proof;
        }
        void prove(SecretKey key){if(keyProof==null)return;byte[] proof=unseal(keyProof,key);try{if(!Arrays.equals(proof,"IEUM_TEXT_KEY_CHECK".getBytes(StandardCharsets.UTF_8)))throw error("TEXT_STORE_KEY_UNAVAILABLE");}finally{Arrays.fill(proof,(byte)0);}}
        void requireReady(){if(held||!db.isOpen())throw error("TEXT_STORE_REVIEW_REQUIRED");try{SecretKey key=keys.existing(alias);if(key==null)throw error("TEXT_STORE_KEY_UNAVAILABLE");prove(key);}catch(CoreException e){throw e;}catch(Exception e){throw error("TEXT_STORE_KEY_UNAVAILABLE");}}
        /** AtomicFile.openRead normalizes .bak/.new before our AEAD check. Preserve and hold
         * ambiguous recovery artifacts instead; no promotion, deletion, reset or key creation. */
        InputStream readMainPreservingArtifacts()throws IOException{
            if(new File(path.getPath()+".bak").exists()||new File(path.getPath()+".new").exists()){
                held=true;throw error("TEXT_STORE_RECOVERY_REVIEW_REQUIRED");
            }
            return new FileInputStream(path);
        }
        synchronized <T>T transaction(Work<T> work){return transaction(work,result->true);}
        synchronized <T>T transaction(Work<T> work,CommitDecider<T> decider){
            requireReady();
            if(depth>0){depth++;try{T r=work.run();if(!decider.commit(r))rollbackOnly=true;return r;}catch(RuntimeException e){rollbackOnly=true;throw e;}finally{depth--;}}
            boolean outer=true;depth=1;rollbackOnly=false;db.beginTransaction();
            byte[] plain=null;boolean durable=false;
            try{
                T result=work.run();
                if(!decider.commit(result))return result;
                if(rollbackOnly)throw error("TEXT_STORE_TRANSACTION_ROLLED_BACK");
                if(outer){plain=snapshot(db);if(!Arrays.equals(lastPlain,plain)){
                    SecretKey key;try{key=keys.existing(alias);}catch(Exception e){throw error("TEXT_STORE_KEY_UNAVAILABLE");}
                    if(key==null)throw error("TEXT_STORE_KEY_UNAVAILABLE");persist(plain,key);durable=true;
                }}
                db.setTransactionSuccessful();if(outer&&plain!=null){if(lastPlain!=null)Arrays.fill(lastPlain,(byte)0);lastPlain=plain.clone();}return result;
            }catch(CoreException e){throw e;}catch(RuntimeException e){if(durable)held=true;throw e;}
            finally{try{db.endTransaction();}catch(RuntimeException e){held=true;throw error("TEXT_STORE_COMMIT_REVIEW_REQUIRED");}finally{depth--;if(plain!=null)Arrays.fill(plain,(byte)0);}}
        }
        byte[] unseal(byte[] encoded,SecretKey key){try{
            if(encoded.length<34||encoded[0]!=1)throw error("TEXT_STORE_FORMAT_REQUIRES_REVIEW");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encoded,1,12));cipher.updateAAD(aad);return cipher.doFinal(encoded,13,encoded.length-13);
        }catch(CoreException e){throw e;}catch(Exception e){throw error("TEXT_STORE_AUTHENTICATION_FAILED");}}
        void persist(byte[] plain,SecretKey key){byte[] ciphertext=null;FileOutputStream out=null;
            try{
                prove(key);
                if(cipherHash!=null){byte[] current;try(InputStream in=readMainPreservingArtifacts()){current=readBounded(in,MAX_PLAIN_BYTES+128);}try{if(!cipherHash.equals(sha(current))){held=true;throw error("TEXT_STORE_EXTERNAL_CHANGE_REQUIRES_REVIEW");}}finally{Arrays.fill(current,(byte)0);}}
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(aad);
                if(cipher.getIV().length!=12)throw error("TEXT_STORE_NONCE_INVALID");ciphertext=cipher.doFinal(plain);
                out=atomic.startWrite();out.write(1);out.write(cipher.getIV());out.write(ciphertext);out.getFD().sync();
                atomic.finishWrite(out);out=null;
                byte[] committed;try(InputStream in=readMainPreservingArtifacts()){committed=readBounded(in,MAX_PLAIN_BYTES+128);}
                try{
                    if(committed.length!=ciphertext.length+13||committed[0]!=1||!Arrays.equals(Arrays.copyOfRange(committed,1,13),cipher.getIV())||!Arrays.equals(Arrays.copyOfRange(committed,13,committed.length),ciphertext)){
                        held=true;throw error("TEXT_STORE_COMMIT_REVIEW_REQUIRED");
                    }cipherHash=sha(committed);
                }finally{Arrays.fill(committed,(byte)0);}
            }catch(Exception e){if(out!=null)atomic.failWrite(out);if(e instanceof CoreException)throw (CoreException)e;throw error("TEXT_STORE_COMMIT_FAILED");}
            finally{if(ciphertext!=null)Arrays.fill(ciphertext,(byte)0);}
        }
        void close(){if(lastPlain!=null)Arrays.fill(lastPlain,(byte)0);if(keyProof!=null)Arrays.fill(keyProof,(byte)0);if(db!=null&&db.isOpen())db.close();try{if(lock!=null&&lock.isValid())lock.release();lockChannel.close();lockFile.close();}catch(Exception ignored){}}
    }
    private static byte[] snapshot(SQLiteDatabase db){try{
        JSONArray objects=new JSONArray(),tables=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 WHEN 'view' THEN 2 ELSE 3 END,name",null)){
            while(c.moveToNext())objects.put(new JSONObject().put("type",c.getString(0)).put("name",c.getString(1)).put("sql",c.getString(2)));
        }
        try(Cursor names=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name!='sqlite_master' ORDER BY name",null)){
            int rows=0;while(names.moveToNext()){
                String table=names.getString(0);JSONArray columns=new JSONArray(),values=new JSONArray();
                // Do not ask CursorWindow to materialize an unbounded raw/body field first.
                StringBuilder sizeExpression=new StringBuilder("0");
                try(Cursor info=db.rawQuery("PRAGMA table_info("+quote(table)+")",null)){
                    while(info.moveToNext())sizeExpression.append("+coalesce(length(CAST(").append(quote(info.getString(info.getColumnIndexOrThrow("name")))).append(" AS BLOB)),0)");
                }
                try(Cursor size=db.rawQuery("SELECT max("+sizeExpression+") FROM "+quote(table),null)){
                    if(size.moveToFirst()&&!size.isNull(0)&&size.getLong(0)>MAX_ROW_BYTES)throw error("TEXT_STORE_CAPACITY_REQUIRES_REVIEW");
                }
                try(Cursor c=db.rawQuery("SELECT rowid AS __eum_rowid__,* FROM "+quote(table)+" ORDER BY rowid",null)){
                    for(String column:c.getColumnNames())columns.put(column);
                    while(c.moveToNext()){
                        if(++rows>100000)throw error("TEXT_STORE_CAPACITY_REQUIRES_REVIEW");JSONArray row=new JSONArray();
                        for(int i=0;i<c.getColumnCount();i++){
                            switch(c.getType(i)){
                                case Cursor.FIELD_TYPE_NULL:row.put(JSONObject.NULL);break;
                                case Cursor.FIELD_TYPE_INTEGER:row.put(new JSONObject().put("i",c.getLong(i)));break;
                                case Cursor.FIELD_TYPE_FLOAT:row.put(new JSONObject().put("f",c.getDouble(i)));break;
                                case Cursor.FIELD_TYPE_BLOB:row.put(new JSONObject().put("b",Base64.encodeToString(c.getBlob(i),Base64.NO_WRAP)));break;
                                default:row.put(new JSONObject().put("s",c.getString(i)));
                            }
                        }values.put(row);
                    }
                }tables.put(new JSONObject().put("name",table).put("columns",columns).put("rows",values));
            }
        }
        byte[] result=new JSONObject().put("format",1).put("objects",objects).put("tables",tables).toString().getBytes(StandardCharsets.UTF_8);
        if(result.length>MAX_PLAIN_BYTES){Arrays.fill(result,(byte)0);throw error("TEXT_STORE_CAPACITY_REQUIRES_REVIEW");}return result;
    }catch(CoreException e){throw e;}catch(Exception e){throw error("TEXT_STORE_SNAPSHOT_FAILED");}}
    private static void restore(SQLiteDatabase db,byte[] plain){try{
        JSONObject root=new JSONObject(new String(plain,StandardCharsets.UTF_8));if(root.getInt("format")!=1)throw error("TEXT_STORE_FORMAT_REQUIRES_REVIEW");
        JSONArray objects=root.getJSONArray("objects"),tables=root.getJSONArray("tables");
        db.setForeignKeyConstraintsEnabled(false);db.beginTransaction();try{
            for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);if(o.getString("type").equals("table")&&!o.getString("name").equals("android_metadata"))db.execSQL(o.getString("sql"));}
            for(int i=0;i<tables.length();i++){
                JSONObject t=tables.getJSONObject(i);String table=t.getString("name");JSONArray columns=t.getJSONArray("columns"),rows=t.getJSONArray("rows");
                if(table.equals("android_metadata")||table.equals("sqlite_sequence"))db.execSQL("DELETE FROM "+quote(table));
                StringBuilder col=new StringBuilder("rowid"),marks=new StringBuilder("?");for(int j=1;j<columns.length();j++){col.append(',').append(quote(columns.getString(j)));marks.append(",?");}
                String sql="INSERT INTO "+quote(table)+"("+col+") VALUES("+marks+")";
                for(int j=0;j<rows.length();j++){
                    JSONArray row=rows.getJSONArray(j);Object[] args=new Object[row.length()];
                    for(int k=0;k<args.length;k++){if(row.isNull(k))args[k]=null;else{JSONObject v=row.getJSONObject(k);if(v.has("i"))args[k]=v.getLong("i");else if(v.has("f"))args[k]=v.getDouble("f");else if(v.has("b"))args[k]=Base64.decode(v.getString("b"),Base64.NO_WRAP);else args[k]=v.getString("s");}}
                    db.execSQL(sql,args);for(Object arg:args)if(arg instanceof byte[])Arrays.fill((byte[])arg,(byte)0);
                }
            }
            for(int i=0;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);if(!o.getString("type").equals("table"))db.execSQL(o.getString("sql"));}
            try(Cursor c=db.rawQuery("PRAGMA foreign_key_check",null)){if(c.moveToFirst())throw error("TEXT_STORE_INTEGRITY_REQUIRES_REVIEW");}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();db.setForeignKeyConstraintsEnabled(true);}
    }catch(CoreException e){throw e;}catch(Exception e){throw error("TEXT_STORE_RESTORE_FAILED");}}
}
