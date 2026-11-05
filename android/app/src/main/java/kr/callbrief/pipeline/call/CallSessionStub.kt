package kr.callbrief.pipeline.call

/** 임시 정의(r10-2): r10-1의 `CallSession.kt`가 들어오면 이 파일은 통째로 지운다. 모양은 r10-1 지시문의 약속 그대로다. */
object CallSession {
    enum class Direction { IN, OUT }
    data class CallEnd(val phoneKey:String?,val direction:Direction,val startedMs:Long,val endedMs:Long,val answered:Boolean)
}
