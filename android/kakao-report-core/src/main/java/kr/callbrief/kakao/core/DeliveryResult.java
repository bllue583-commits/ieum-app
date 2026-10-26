package kr.callbrief.kakao.core;

import java.time.Instant;

public final class DeliveryResult {
    public enum Status {
        DISABLED, NOT_FINALIZED, LOCAL_CONSENT_REQUIRED, ACCOUNT_MISMATCH, AUTH_REQUIRED, SCOPE_REQUIRED,
        CONFIG_REQUIRED, RATE_LIMITED, KAKAO_TALK_UNAVAILABLE, CONNECTION_CHECK_FAILED, CANCELLED, FAILED, UNKNOWN,
        IN_FLIGHT, SENT, TEST_CONTRACT_PASSED, ALREADY_SENT, ATTEMPTS_EXHAUSTED, STORAGE_FAILURE
    }
    public final Status status;
    public final Instant retryNotBefore;
    private final boolean priorReportHold;
    public DeliveryResult(Status status) { this(status,Instant.EPOCH); }
    public DeliveryResult(Status status,Instant retryNotBefore) { this(status,retryNotBefore,false); }
    private DeliveryResult(Status status,Instant retryNotBefore,boolean priorReportHold) { this.status=status;this.retryNotBefore=retryNotBefore;this.priorReportHold=priorReportHold; }
    public static DeliveryResult priorReportHeld() { return new DeliveryResult(Status.LOCAL_CONSENT_REQUIRED,Instant.EPOCH,true); }
    public String messageKo() {
        if(priorReportHold)return "수신 선택 이전 날짜의 보고는 앱에 보관하며 자동으로 보내지 않아요. 이전 보고는 직접 선택해 확인해 주세요.";
        switch(status) {
            case SENT: return "카카오 API가 발송 성공을 응답했어요. 읽음 여부는 확인할 수 없어요.";
            case TEST_CONTRACT_PASSED: return "합성 전송 계약 테스트 통과 · 실제 카카오 발송 없음";
            case ALREADY_SENT: return "이 날짜의 요약은 이미 발송 확인을 받아 다시 보내지 않았어요.";
            case AUTH_REQUIRED: return "카카오 연결을 다시 확인해 주세요. 요약은 보내지 않았어요.";
            case SCOPE_REQUIRED: return "카카오톡 메시지 전송 동의가 필요해요. 설정에서 다시 연결해 주세요.";
            case CONNECTION_CHECK_FAILED: return "카카오 연결·동의 상태를 확인하지 못해 요약을 보내지 않았어요. 다시 확인해 주세요.";
            case CANCELLED: return "카카오 연결을 취소했어요. 요약 수신 선택은 꺼져 있어요.";
            case LOCAL_CONSENT_REQUIRED: return "‘나에게 요약 받기’를 선택하면 연결을 준비할 수 있어요.";
            case ACCOUNT_MISMATCH: return "보고 대상 계정이 달라 보내지 않았어요. 본인 계정 연결을 다시 확인해 주세요.";
            case UNKNOWN: case IN_FLIGHT: return "발송 결과를 확인하지 못했어요. 중복 방지를 위해 다시 보내지 않아요. 나와의 채팅을 확인해 주세요.";
            case RATE_LIMITED: return "카카오 발송 한도에 도달했어요. 재시도 가능 시각 이후에 다시 확인해 주세요.";
            case CONFIG_REQUIRED: return "카카오 앱 등록·동의·링크 설정 확인이 필요해요.";
            case KAKAO_TALK_UNAVAILABLE: return "이 계정에서 카카오톡 메시지를 사용할 수 없어요.";
            case NOT_FINALIZED: return "요약이 완성된 뒤에 보내기를 준비할 수 있어요.";
            case STORAGE_FAILURE: return "발송 기록을 안전하게 저장하지 못했어요. 추가 발송을 중단했어요.";
            case ATTEMPTS_EXHAUSTED: return "이 날짜의 재시도를 중단했어요. 연결과 발송 기록을 확인해 주세요.";
            case FAILED: return "카카오 발송이 거절됐어요. 설정과 요약 형식을 확인해 주세요.";
            default: return "카카오 연결 준비 중 · 오전 6시 예약 미구현 · 실제 발송 미검증";
        }
    }
    @Override public String toString() { return "DeliveryResult["+status+"]"; }
}
