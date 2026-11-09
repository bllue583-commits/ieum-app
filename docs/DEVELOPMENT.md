# 개발 방식과 변경 경계 (2026-11-10 기준)

현재 상태와 다음 할 일은 [HANDOFF.md](HANDOFF.md), 현재 설계도(기술)는 [DESIGN.md](DESIGN.md), 제품 설계는 [PRODUCT.md](PRODUCT.md), 운영은 [OPERATIONS.md](OPERATIONS.md)를 따릅니다. 이 문서는 작업하는 방식과 환경만 적습니다. 버전별 설계·구현 대조 이력은 `docs/history/`에 있습니다.

## 작업 방식 (v3.1부터 쓰는 릴리스 트레인)
1. **설계도 먼저.** 이번 버전의 변경 설계(묶음, 묶음별 파일 소유 표, 확인 기준, UPDATE_NOTE 문구)를 먼저 쓰고 설계도대로 개발합니다. 설계와 구현이 다르면 어느 한쪽을 기준으로 삼지 말고 맞는 쪽으로 구현한 뒤, 버전마다 "다르게 정한 것" 절에 남깁니다(11/4 사용자).
2. **키 커밋.** 오케스트레이터가 새 설정 키(`PipelineSettingsKeys`의 그 버전 묶음), 하트비트 자리(`PhoneStatus`), 묶음 사이 함수의 빈 자리를 한 커밋으로 통합 브랜치(`feature/r<회차>-v<버전>`)에 먼저 올립니다.
3. **Opus 작업자 병렬.** 묶음마다 작업자 하나(Agent 도구 `subagent_type=general-purpose`, `model=opus`, `isolation=worktree`, 추론 중간), 각자 worktree와 브랜치 `feature/r<회차>-v<버전>-r<n>`. 공통 지침은 [`docs/orchestration/worker-brief.md`](orchestration/worker-brief.md), 묶음 지시문은 `docs/orchestration/briefs/`, 작업자 보고서는 오케스트레이터가 마지막 답을 받아 `docs/orchestration/reports/`에 저장합니다(작업자는 worktree 밖에 못 씀).
4. **파일 소유 표.** 묶음끼리 같은 파일을 고치지 않게 설계의 "묶음별 파일 소유" 표로 못 박습니다. `CallRecordStore.kt`처럼 여럿이 필요한 파일은 한 묶음이 먼저 하고 다음 묶음이 그 브랜치에서 시작합니다. `docs/HANDOFF.md`, `docs/DESIGN.md`, `docs/VERSION`, `docs/UPDATE_NOTE.txt`, `docs/history/*`, `.github/workflows/*`는 오케스트레이터만 고칩니다.
5. **통합.** 오케스트레이터가 묶음을 통합 브랜치에 차례로 합치고(충돌은 둘 다 살리는 쪽으로), 남은 연결 줄을 넣고, 화면 규칙·비밀 스캔을 돌립니다.
6. **ci.** 통합 브랜치 push로 `ci`(JVM 단위 시험, lint, 도구 시험, 화면 규칙, 내보내기 검사, 약 5분)를 통과시킵니다. 기기 시험(device-test)은 그 버전의 시험 게이트일 때만 기다립니다.
7. **lean-calls 배포.** `feature/lean-calls`에 합쳐 push하면 같은 커밋의 ci(와 필요하면 update-smoke)를 기다린 뒤 `release-apk`가 우편함에 올리고 폰이 받습니다. 사용자에게 보이는 변경이면 `docs/VERSION`을 0.1 올리고 `docs/UPDATE_NOTE.txt` 첫 줄을 "v3.x: …"로 씁니다. 합친 시험 브랜치는 원격에서 지웁니다(자동 권한 검사가 막으면 사용자가 Actions → cleanup-branches).
8. **HANDOFF 갱신.** lean-calls에서만 고칩니다(시험 브랜치에서는 고치지 않음).
9. **실측 게이트(11/10).** 배포 뒤 그 버전의 확인 기준을 하트비트로 판정해 HANDOFF 4절 판정표(통과/실패/미확인)에 적습니다. 판정 전에는 다음 버전 코드를 배포하지 않습니다(최소 평일 하루). 대기열·예약 작업 수·알림 수를 늘리는 변경은 설계에 "들어오는 양, 처리하는 양, 예산" 계산을 적습니다. v3.5 실패 자막 재큐가 계산 없이 대기열에 70건을 넣어 배터리 60% 사고가 났습니다.
10. **검토는 닫아야 끝.** 독립 검토의 치명·높음 지적은 같은 버전이나 바로 다음 배포에서 닫고, 미루면 DESIGN.md 11절에 이유와 함께 적습니다.

- 통과했다고 보고하기 전에 종료 코드와 결과 파일을 직접 확인합니다(`| tail`로 종료 코드를 가리지 않음).
- 통합 브랜치 머리에 `[skip ci]` 커밋만 있으면 ci가 돌지 않습니다. 합친 뒤에는 코드나 워크플로가 바뀐 커밋을 머리에 둡니다.
- 하위 작업자 모델: 프로젝트 설정은 "워커는 opus 중간". Fable이나 Opus high 이상을 쓰면 사용자에게 알립니다.

## 지켜야 할 것
- 패키지 `kr.callbrief`와 서명 키 유도(`tools/derive_signing_key.py`)를 바꾸지 않습니다. 서명이 달라지면 설치된 앱이 업데이트되지 않습니다(1039~1049 인증서 `e4ee1db4…` 같음).
- 녹음 파일과 통화 내용은 폰 밖으로 내보내지 않습니다(자막만 우편함으로). 자막은 정리 14일 뒤 비웁니다.
- 비밀번호·토큰·키·통화 내용·전화번호·개인 경로를 파일, 로그, 커밋, 보고서에 남기지 않습니다(`tools/scan_export.py`). 우편함의 `status/calllog.txt`, `inbox/`, `results/`, `ledger/`, `tidy/`, `story/`는 읽지 않습니다(읽어도 되는 것: `status/phone.json`, `status/last-run*.json`, `app/version.json`, `routines/`, `tools/`).
- 화면 글 규칙(ci `tools/check_ui_rules.py`): 가운데 점·말줄임표·영어 상태값·빨강 금지, 읽는 글 18sp 이상(16sp는 META 줄과 배지만), "체크할 일", 요청 날짜, "~해요" 체. 자세한 것은 PRODUCT.md 4.4.
- 화면은 IO 스레드에서만 저장소를 읽습니다(`MAIN_THREAD_CHECK`). 저장소는 프로세스 하나(`IeumApplication.store`)를 닫지 않고 씁니다.
- 예약 작업 지시문은 `docs/routines/*.md`가 원본입니다. [분석 규칙]·[결과 형식]을 바꾸면 `ClaudeCallAnalyzer.SYSTEM`/`SCHEMA`, `PROMPT_VERSION`(지금 8), 지시문 버전을 함께 올립니다(`RoutineDocTest`).
- 코드와 문서를 지울 때는 먼저 `git grep`으로 참조를 확인합니다. 이력은 git에 남으므로 `archive/` 폴더를 만들지 않습니다(정리 전 상태는 태그 `archive/pre-cleanup-20261104`).
- 원격 브랜치 삭제, 예약 작업 끄기·지우기·지금 실행, 저장소 공개 설정은 이 세션의 자동 권한 검사가 막을 수 있습니다. 그때는 사용자에게 넘깁니다.

## 로컬 빌드 환경 (클라우드 작업 공간)
- **툴체인 준비:** 새 환경에서는 `apt-get update`를 한 번 한 뒤 `bash tools/local_toolchain.sh`. JDK 17(apt `openjdk-17-jdk-headless`), Android SDK(platform 36, build-tools 35.0.0, platform-tools), Robolectric jar를 `/home/claude/toolchain/`에 받고 `/home/claude/toolchain/env.sh`(JAVA_HOME, ANDROID_SDK_ROOT, `GRADLE_USER_HOME=/home/claude/gradle-user`, `IEUM_SKIP_ANDROID_NATIVE=1`)를 만든 뒤 메인 체크아웃에서 한 번 데워 둡니다.
- **메이븐 미러:** repo.maven.apache.org가 429를 자주 냅니다. `$GRADLE_USER_HOME/init.d/mirror.gradle`에 `https://maven-central.storage-download.googleapis.com/maven2/`를 맨 앞 저장소로 넣으면 대부분 풀립니다(저장소 설정은 바꾸지 않음, 파일은 저장소 밖). 그래도 `status code 429`가 보이면 60초 뒤 다시 돌립니다.
- **Gradle 실행:** `. /home/claude/toolchain/env.sh` 뒤 `flock /home/claude/gradle.lock python3 run_gradle.py --log <새 라벨>.log <작업>`. `run_gradle.py`는 JDK·SDK를 확인하고 로그를 `evidence/<라벨>.log`에 남기며, 같은 라벨이 있으면 멈춥니다(라벨은 매번 새로). `IEUM_SKIP_ANDROID_NATIVE=1`이면 Whisper 네이티브 빌드를 건너뜁니다(시험·lint 전용).
- **CPU 2개:** 작업자 둘 이상이 Gradle을 번갈아 기다립니다. 작업자는 컴파일과 자기 묶음 시험 클래스만 로컬에서 돌리고, 전체 시험(`:core:test :app:testDebugUnitTest :app:lintDebug`, 로컬 약 4분)은 push 뒤 GitHub ci로 판정합니다.
- **Robolectric:** worktree마다 처음 한 번 `python3 tools/prepare_host_tests.py /home/claude/toolchain/robolectric-jar/android-all.jar`.
- **APK:** 네이티브 빌드(NDK 28.2.13676358, CMake 3.22.1)가 필요해 배포 워크플로가 만듭니다.
- **gh:** 이 세션에서는 GraphQL이 막혀 `gh run list`/`gh workflow run`이 안 될 수 있습니다. REST로: `gh api repos/<owner>/<repo>/actions/workflows/<file>/dispatches -f ref=<branch>`, `.../actions/runs/<id>/jobs`, `.../actions/jobs/<id>/logs`.
- 집 Mac·회사 Windows PC: README의 "개발" 절 명령을 그대로 씁니다(JDK 17, Android SDK 36). Windows에서는 Robolectric 단위 시험 대부분이 `.bak` 문제로 실패하므로 판정은 ci로 합니다.

## 제안: push 때 루틴 깨우기 (v2.9 r10-3, 아직 하지 않음)
**상태(11/6):** 하지 않음. 루틴은 매시 34분(07~22시)이고 30분 간격은 예약 작업이 허용하지 않으며, v3.4부터 폰이 루틴 일정의 +3·+8·+15분에 결과를 받아 대기가 줄었다. GitHub Actions에서 부를 수 있는 공개 API와 자격 증명은 여전히 확인하지 못했다.

(v2.9 때의 30분 간격 제안 원문은 git 이력에 있습니다. 지금 루틴은 매시 34분이고, 밤 회차 추가는 DESIGN_V7 2.2절.)
