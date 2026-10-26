package kr.callbrief.kakao.android

import android.app.Activity
import android.app.AlertDialog
import android.view.WindowManager
import android.widget.*

/** Additive settings view. No synthetic result can produce a real delivery-success label. */
object KakaoSettingsPanel {
    fun create(activity: Activity,appUserKey: String,client:KakaoReportClient=KakaoReportClient.get(activity),ownerStillCurrent:()->Boolean): LinearLayout {
        val panel=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        fun label(s: String)=TextView(activity).apply { text=s; textSize=18f; setPadding(0,12,0,12) }
        panel.addView(label("카카오 사용자 직접 보내기 준비"))
        panel.addView(label("보고를 직접 선택해 본인의 ‘나와의 채팅’으로 보내기 위한 연결 인터페이스예요. 무인 예약 발송은 정책 미확인으로 비활성이고 카카오 대화나 친구 메시지는 읽지 않아요."))
        panel.addView(label(KakaoReportClient.UNVERIFIED_LABEL))
        val status=label(if(client.localOptIn()) "이 기기의 직접 보내기 준비 켜짐" else "직접 보내기 준비 꺼짐")
        panel.addView(status)
        fun action(text: String,block: () -> Unit)=Button(activity).apply {
            this.text=text; textSize=18f; minHeight=(56*resources.displayMetrics.density).toInt(); setOnClickListener { block() }
        }
        val connect=action(if(client.config.canLogin) "카카오 선택 연결" else "카카오 연결 준비 중") {
            client.connect(activity,appUserKey,ownerStillCurrent) { session,problem ->
                activity.runOnUiThread {
                    if(session==null) status.text=problem?.messageKo() ?: "연결을 완료하지 못했어요."
                    else {
                        status.text="본인 카카오 계정과 메시지 전송 동의를 확인했어요. 직접 보내기 준비은 아직 꺼져 있어요."
                        val dialog=AlertDialog.Builder(activity).setTitle("이 계정으로 직접 보내기를 준비할까요?")
                            .setMessage("선택한 통화·메모의 완성된 요약(최대 200자)을 카카오 서버로 보내 본인의 나와의 채팅에 전달해요. 원문은 앱에서 볼 수 있어요. 보내진 요약은 카카오에도 저장되며, 읽음·삭제 여부는 이음에서 확인할 수 없어요.\n\n실제 발송은 사용자가 정확한 보고를 직접 선택해야 해요. 이 선택은 무인 발송을 허용하지 않아요. 오전 6시는 로컬 보고·알림 목표이며 실제 카카오 수신은 검증되지 않았어요. 언제든 여기서 직접 보내기 준비를 끌 수 있어요.")
                            .setNegativeButton("나중에",null).setPositiveButton("직접 보내기 준비 확인") { _,_ ->
                                try { client.confirmOptIn(appUserKey,session,ownerStillCurrent); status.text="이 기기의 직접 보내기 준비 켜짐 · ${KakaoReportClient.UNVERIFIED_LABEL}" }
                                catch(e: Exception) { status.text="계정·동의 저장을 확인하지 못해 직접 보내기 준비을 켜지 않았어요." }
                            }.create()
                        checkNotNull(dialog.window).addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        dialog.show()
                        dialog.findViewById<TextView>(android.R.id.message)?.textSize=18f
                    }
                }
            }
        }.apply { isEnabled=client.config.canLogin }
        panel.addView(connect)
        panel.addView(action("직접 보내기 준비 끄기") {
            try { client.disableReports(); status.text="직접 보내기 준비을 껐어요. 이미 전송 중인 메시지는 취소할 수 없어요." }
            catch(e: Exception) { status.text="설정 저장을 확인하지 못했어요. 기기 저장소 상태를 확인해 주세요." }
        })
        panel.addView(action("이 기기에서 연결 해제") {
            try { client.disconnectLocally(); status.text="이 기기의 연결을 지웠어요. 카카오의 연결된 서비스·동의 설정은 직접 관리할 수 있어요." }
            catch(e: Exception) { status.text="연결 해제 저장을 확인하지 못했어요." }
        })
        return panel
    }
}
