# Git 이관 검증 — 2026-10-26

## 소스와 보존

실행 호스트는 사용자 MacBook Pro입니다. 확인한 원본은 `2026-10-15/task-5/code34-android-local-whisper-runner-v1`, code34 v2, versionCode34 / `0.13.4-android-local-whisper-dev`입니다. 해당 디렉터리와 상위는 Git 저장소가 아니므로 원본 branch/remote/history/미커밋 변경은 해당 없음입니다. 기존 이음 remote를 발견하지 못했습니다. 원본의 제품 디렉터리에는 백업 이후 추가 파일이 없었습니다.

원본 백업 47058330 bytes/SHA256 `4d610dd012a2760c0a1ea2cf132141f80d4bfd19276d0e5176aa857e6fd06cf0`을 로컬 보존본에서 검증하고, 백업 `source/`의 355파일과 Mac의 현재 파일을 전부 SHA256 대조했습니다. 변경0/누락0입니다. 원본은 읽기만 했고 새 Git 사본을 `2026-10-26/task/ieum-app`에 구성했습니다. 기준 manifest는 `source-baseline-manifest.json`입니다. 기존 원본·APK·Library 백업은 덮어쓰지 않았습니다. NAS 작업은 수행하지 않았습니다.

제품 Kotlin/Java/C++/SQL, Gradle 모듈 설정과 라이선스는 바이트 그대로 보존했습니다. 기존 테스트는 아래 외부 입력 경로 변경 외에 보존했습니다. 이관 사본의 변경은 아래 8파일과 새 개발문서/검사기/ignore/줄바꿈 규칙입니다.

- `installed-runtime.example.json`: 개인 절대 경로를 예시 경로로 변경.
- `worker/config.example.json`: 계정 예시를 비식별 주소로 변경.
- `run_gradle.py`: Mac 개인 고정 경로를 JDK/SDK/Node/Gradle/선택 서명키 환경변수와 OS별 wrapper로 변경.
- `tools/build_android_whisper.py`: CMake/make 환경변수 및 PATH 사용, 새 사본 evidence 디렉터리 생성. 모델·소스 핀과 ABI는 보존.
- `tools/launch_private_avd.py`, `tools/run_android_device_tests.py`: 개인 SDK 경로를 명시적 환경변수로 변경. 기존 별도 AVD 전용 도구이며 이번에는 실행하지 않음.
- `android/app/src/test/java/kr/callbrief/ScheduledTextNativeIngressIntegrationTest.kt`: 예시 설정의 과거 개인 경로 의존을 `IEUM_HISTORICAL_SYNTHETIC_AUDIO`로 변경. 고정 audio SHA 검사는 유지하며 미준비 시 명시 SKIP.
- `tests/test_local_stt.py`: 과거 관측 디렉터리를 환경변수로 변경. 외부 입력 미준비의 두 시험은 명시 SKIP.

원본 source355에 AGENTS/skills 파일은 없었습니다. 원본의 별도 handoff/evidence/runtime/cache/Library helper 트리는 그대로 두고 복제하지 않았습니다. 원본 Git history가 없어 재작성할 history도 없으며, 새 export 저장소를 main의 새 초기 commit으로 구성합니다.

## 업로드 범위와 검사

포함: 제품 소스·Gradle wrapper/설정·필요 도구·기존 테스트·Whisper 고정 소스 tar/MIT 고지·필수 공개 합성 WAV/JSON·개발문서·미구현 인계 계약/시나리오. 계획의 개인 Windows 경로 두 곳은 비식별화했고 Git의 LF 정규화를 적용했습니다.

제외: 원본 APK·모델·SDK/JDK/NDK/cache·build/JNI·실사용 DB·통화/문자/메일/연락처 원본·토큰/키/비밀번호/.env/서명키·AVD·사설 다운로드 URL·개인 경로 로그·전송 helper/영수증. 자산 위치·공개 출처/해시와 재생성 절차는 ASSETS.md에 있습니다.

`tools/scan_export.py`로 staged 파일 전체의 알려진 키/토큰 패턴·개인 home 경로·금지 파일 유형·대용량을 검사했습니다. 발견값은 출력하지 않고 파일/행/이유만 보고합니다. 고정 test canary·계정 모양 fixture·공개 OAuth client ID와 URL host를 별도로 검토했습니다. Whisper tar는 검증된 upstream source SHA, wrapper JAR/공개 합성 WAV는 기준 manifest와 대조했습니다. 이 검사는 모든 가능한 비밀 부재를 수학적으로 증명하는 entropy detector는 아닙니다.

Windows 예약명·금지 문자·대소문자 충돌 검사는 발견0입니다. `git diff --check`의 기존 BUILD_STATUS.md 마지막 빈 줄 경고는 원본 유지로 남겼고, 새 변경은 `core.whitespace=-blank-at-eof`로 검사했습니다.

## 이번 실행 결과

- Node 기존 worker 29시험: PASS29 / 실패0 / skip0.
- Python 기존 adapter: PASS3 / SKIP2 / 실패0. 외부 과거 관측 입력은 이 Git 사본에 없음.
- 맥락 설계 입력 검사: PASS12. Android·DB·엔진 실행0, 제품 시나리오30 NOT_RUN.
- KST 설계 입력 검사: PASS18. 최초 누락 provenance로 1오류가 있었고 필요한 비식별 provenance를 포함한 뒤 재실행18 PASS. 제품 인수40 NOT_RUN.
- Android core/app JVM·lint·assembleDebug: 결과는 아래 최종 실행 기록을 따릅니다. JNI 생성 생략(`IEUM_SKIP_ANDROID_NATIVE=1`)으로 공개 기기 STT APK의 기능 인수를 주장하지 않습니다.
- 실제 기기/AVD·Google/GMS/Drive·새 OAuth·실자료 전송·유료 호출·Kakao 수신·Windows 실빌드: NOT_RUN.

원본 보존 재확인: 제품355의 SHA 불변, 원본 APK38417366 bytes/SHA256 `440629f74494459c33bc9b2b349845291add6e7ff9a9cda74cfae752ad746b1e`, 원본 ZIP 해시 불변.

## 원격 업로드 상태

GitHub 연결 계정은 `bllue583-commits`(user ID344692378)입니다. 소유 저장소 조회에는 private `workspace`만 있었고 이음 대상임을 확인할 근거가 없어 사용하지 않았습니다. 전용 `ieum-app` 조회는 404였습니다.

현재 도구에는 저장소 생성 기능이 없고, Mac Git credential helper에 기존 GitHub 인증이 없으며 브라우저는 GitHub 로그아웃 상태입니다. 전용 빈 비공개 저장소 생성/브라우저 직접 로그인 요청이 사용자 응답 대기입니다. 비밀번호·토큰을 채팅으로 요청하지 않았습니다. 새 OAuth/지속 접근/보안설정 변경은 실행하지 않았습니다.

따라서 **저장소 생성·remote 설정·push는 미실행**이며 공개범위·원격 commit 일치·원격 clean clone 검증은 대기입니다. 임시 local clone 검증은 원격 인증/접근 검증과 구별합니다. 이 상태에서 후속 worker가 원격 개발을 시작할 수 있다고 선언하지 않습니다.
