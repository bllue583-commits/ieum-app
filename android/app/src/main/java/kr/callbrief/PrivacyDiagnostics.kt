package kr.callbrief

/** No Throwable message/body, contact, URI, owner or token is copied to diagnostics or notices. */
internal object PrivacyDiagnostics {
    fun code(error:Throwable):String=when(error) {
        is SecurityException -> "PERMISSION_REVIEW"
        is kr.co.callsummary.core.CoreException -> "PROTECTED_STORE_REVIEW"
        is java.io.IOException -> "LOCAL_IO_REVIEW"
        is java.util.concurrent.CancellationException -> "CANCELLED"
        else -> "OPERATION_REVIEW"
    }
    fun userMessage(error:Throwable):String=when(code(error)) {
        "PERMISSION_REVIEW" -> "선택 폴더의 읽기 권한을 확인해 주세요. 기존 자료는 유지했어요."
        "PROTECTED_STORE_REVIEW" -> "기존 기록과 암호화 키를 보존했어요. 저장소 전환·복구 확인이 필요해요."
        "CANCELLED" -> "작업을 중단했어요. 이미 저장한 자료는 유지돼요."
        else -> "작업을 확인하지 못했어요. 기존 자료는 유지했어요. 다시 확인해 주세요."
    }
    fun record(error:Throwable,count:Int,digest:String?):org.json.JSONObject {
        require(count>=0 && (digest==null||digest.matches(Regex("[0-9a-f]{64}"))))
        return org.json.JSONObject().put("code",code(error)).put("count",count).put("digest",digest ?: org.json.JSONObject.NULL)
    }
}
