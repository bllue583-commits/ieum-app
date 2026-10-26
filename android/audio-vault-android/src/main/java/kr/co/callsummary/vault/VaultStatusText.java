package kr.co.callsummary.vault;

/** File/key status text only; never changes processing, summaries, history, or login state. */
public final class VaultStatusText {
    private VaultStatusText(){}
    public static String initializationKorean(InitializationReport report){
        switch(report.state){
            case EMPTY:return "원음 보호 저장소가 아직 초기화되지 않았습니다.";
            case PENDING:return "원음 보호 초기화가 끝나지 않았습니다. 새 저장소인지 확인한 뒤 초기화를 이어갈 수 있습니다.";
            case READY:return "원음 보호 저장소가 준비되었습니다. 원음을 열 때 무결성을 검사합니다.";
            case USED:return "원음 보호 저장소에 사용 이력이 있습니다. 기존 암호화 키를 유지합니다.";
            case LEGACY_REVIEW_REQUIRED:return "이전 원음 보호 저장소입니다. 초기화 이력을 확인해야 합니다. 파일과 기록은 유지됩니다.";
            default:return "원음 보호 초기화 이력을 확인하지 못했습니다. 파일과 기록은 유지됩니다.";
        }
    }
    public static String korean(EncryptedAudioVault.Status status){
        switch(status){
            case ENCRYPTED_REFERENCE_PRESENT:return "암호화 원음이 보관되어 있습니다. 열 때 무결성을 검사합니다.";
            case KEY_UNAVAILABLE:return "암호화 키를 찾을 수 없어 원음을 열 수 없습니다. 파일과 이력은 유지됩니다.";
            case KEY_INVALIDATED:return "암호화 키를 사용할 수 없어 원음을 열 수 없습니다. 파일과 이력은 유지됩니다.";
            case KEY_LOCKED:return "암호화 키 사용이 잠시 제한되어 있습니다. 파일과 이력은 유지됩니다.";
            case KEY_SERVICE_UNAVAILABLE:return "암호화 저장소에 접근하지 못했습니다. 파일과 이력은 유지됩니다.";
            case STORAGE_MISSING:return "보관된 원음 파일을 찾을 수 없습니다. 기록 이력은 유지됩니다.";
            case BINDING_MISMATCH:return "원음과 기록의 버전 연결이 맞지 않아 열기를 중단했습니다.";
            case UNSUPPORTED_FORMAT:return "현재 앱에서 지원하지 않는 원음 보관 형식입니다. 파일과 이력은 유지됩니다.";
            default:return "보관된 원음의 무결성을 확인하지 못해 열기를 중단했습니다.";
        }
    }
}
