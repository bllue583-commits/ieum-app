package kr.callbrief

/**
 * A Sites browser session or a configured legacy device token is not proof that
 * this native app can authenticate to dot or deliver background analysis jobs.
 * Replace this gate only after an official native round trip is verified.
 */
object NativeAnalysisAvailability {
    const val STATUS = "DISCONNECTED"
    const val MESSAGE = "dot 분석 연결은 아직 준비 중이에요. 녹음을 전달하지 않았어요."
    fun canTransfer(): Boolean = false
}
