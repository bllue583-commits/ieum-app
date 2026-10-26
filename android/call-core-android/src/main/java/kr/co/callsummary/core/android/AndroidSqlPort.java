package kr.co.callsummary.core.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import kr.co.callsummary.core.CoreException;
import kr.co.callsummary.core.SqlPort;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Existing SQL/trigger semantics over a bounded Keystore-sealed, noBackup store. */
public final class AndroidSqlPort implements SqlPort {
    public static final String PERSISTENCE_FORMAT="KEYSTORE_AES_GCM_SQL_SNAPSHOT_V1";
    private final ProtectedDatabase protectedStore;
    private final SQLiteDatabase db;
    public AndroidSqlPort(Context context,String dbName,InputStream schemaStream) {
        final String schema;
        try(InputStream in=schemaStream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            schema=new String(out.toByteArray(),StandardCharsets.UTF_8);
        }catch(Exception e){throw new CoreException("SCHEMA_READ_FAILED",e);}
        protectedStore=ProtectedDatabase.open(context,dbName,schema);db=protectedStore.database();
    }
    public <T>T transaction(Work<T> work) {
        return protectedStore.transaction(work::run);
    }
    public void execute(String sql,Object...args){protectedStore.transaction(()->{try{db.execSQL(sql,args);}catch(android.database.SQLException e){throw new CoreException(code(e.getMessage()));}return null;});}
    public void requireProtectedPersistence(){ProtectedDatabase.requireSealedPersistence(db);}
    public List<Map<String,Object>> query(String sql,Object...args){
        return ProtectedDatabase.read(db,()->queryInside(sql,args));
    }
    private List<Map<String,Object>> queryInside(String sql,Object...args){
        String[] strings=new String[args.length];for(int i=0;i<args.length;i++)strings[i]=args[i]==null?null:String.valueOf(args[i]);
        List<Map<String,Object>> rows=new ArrayList<>();
        try(Cursor cursor=db.rawQuery(sql,strings)){
            while(cursor.moveToNext()){
                Map<String,Object> row=new LinkedHashMap<>();
                for(int i=0;i<cursor.getColumnCount();i++){
                    Object value;
                    switch(cursor.getType(i)){
                        case Cursor.FIELD_TYPE_NULL:value=null;break;
                        case Cursor.FIELD_TYPE_INTEGER:value=cursor.getLong(i);break;
                        case Cursor.FIELD_TYPE_FLOAT:value=cursor.getDouble(i);break;
                        case Cursor.FIELD_TYPE_BLOB:value=cursor.getBlob(i);break;
                        default:value=cursor.getString(i);
                    }row.put(cursor.getColumnName(i),value);
                }rows.add(row);
            }
        }return rows;
    }
    private static final String[] SAFE_CODES=new String[]{"ACCEPT_REVISION_CONFLICT","CACHED_TRANSCRIPT_MISMATCH","CLAIM_BASE_MISMATCH","CONTENT_SIZE_CONFLICT","CONTENT_TRANSCRIPTION_BUSY","DOCUMENT_IDENTITY_CONFLICT","ENCRYPTED_SOURCE_NOT_VERIFIED","FOLDER_POLICY_CHANGED","IDEMPOTENCY_CONFLICT","IMMUTABLE_DISPATCH_RESERVATION","IMMUTABLE_HISTORY","IMMUTABLE_INITIALIZATION_INTENT","IMMUTABLE_INITIALIZATION_STATE","IMMUTABLE_INTAKE_SNAPSHOT","IMMUTABLE_PROPOSAL","IMMUTABLE_RECEIPT","IMMUTABLE_VAULT_RECEIPT","IMMUTABLE_VAULT_REFERENCE","INVALID_LEASE","INVALID_MANUAL_EDIT","INVALID_OBSERVATION","LEASE_BUSY","METADATA_CONFIRMATION_REQUIRED","NOT_RETRYABLE","ORIGINAL_TIMESTAMP_UNKNOWN","PROCESSING_ID_REUSED","READ_ONLY_RECEIPT_IMMUTABLE","SEALED_SOURCE_MISMATCH","SEMANTIC_REVIEW_REQUIRED","SOURCE_UNAVAILABLE","STALE_LEASE","STALE_OBSERVATION","STALE_SOURCE","STALE_SOURCE_OR_POLICY","STORAGE_REF_CONFLICT","SUMMARY_NOT_ACCEPTABLE","TASK_LINK_NOT_CONFIRMED","TASK_REVISION_CONFLICT","TRANSCRIPT_CACHE_CONFLICT","TRANSCRIPT_IDENTITY_CONFLICT","UNLINKED_TASK","USER_REVISION_CONFLICT","VAULT_SOURCE_BINDING_MISMATCH"};
    private static String code(String message){
        if(message!=null)for(String code:SAFE_CODES)if(java.util.regex.Pattern.compile("\\b"+code+"\\b").matcher(message).find())return code;
        return "SQLITE_OPERATION_FAILED";
    }
    public void close(){protectedStore.close();}
}
