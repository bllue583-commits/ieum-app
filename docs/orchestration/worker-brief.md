# 이음 작업자 공통 지침 (v3.1부터, 오케스트레이터가 관리 — 11/5 저녁 Opus)

당신은 안드로이드 앱 "이음"(Kotlin, 패키지 `kr.callbrief`)의 개발 작업자입니다. 보고와 커밋 메시지는 한국어로 씁니다. 결정은 묻지 말고 "편리함 중점의 최선"으로 정하고 보고서에 한 줄로 적습니다.

## 저장소와 브랜치
- 당신은 자기만의 git worktree 안에서 일합니다(현재 디렉터리, 기준 커밋 = 지시받은 통합 브랜치의 HEAD(v3.1은 `feature/r12-v31`)). 시작하자마자 `git log --oneline -1`로 HEAD가 기준 브랜치(`git log --oneline -1 origin/<기준 브랜치>`)와 같은지 확인하고, 다르면(예: main에 있음) `git fetch origin && git reset --hard origin/<기준 브랜치>`를 먼저 한 뒤 `git checkout -b <지시받은 브랜치 이름>`을 합니다.
- 설계 원문: `docs/DESIGN_V4.md`(이번 작업의 기준, 2절 머리의 "하트비트 새 키"·"묶음별 파일 소유"와 자기 묶음 절), 그 전 설계 `docs/DESIGN_V3.md`. v3.0 검토 근거(파일:줄): `docs/orchestration/reports/review-r1~r4-*.md`, `review-design-v4.md`. 배경: `docs/HANDOFF.md`, `docs/PRODUCT.md`, `docs/DEVELOPMENT.md`.
- **키 커밋(통합 브랜치에 이미 있음):** 설정 키는 `PipelineSettingsKeys`의 "v3.1" 묶음(`RECORDING_GAP`, `GAP_*`, `FOLLOWUP_NO_RECORDING`, `STORE_OPEN_ERROR`, `RESULTS_*`, `RESULT_NOT_APPLIED`, `TRANSCRIPTION_FAILED`, `LATENCY`, `UPDATE_PROGRESS`)을 그대로 쓰고, 하트비트(`PhoneStatus.V31`)는 그 키를 이름으로 읽는다 — 자기 묶음은 **값을 쓰기만** 하면 된다. 묶음 사이 함수는 자리만 있다(`RecordingGap.last/banner/lightText` → r12-1, `CallUpdate.state/setupStep/openInstallSourceSettings/checkNow/notInstalledForADay` → r12-2, `CallTranscriptionStore.oldestQueuedMs` → r12-3). 쓰는 쪽은 지금 시그니처대로 부르고, 채우는 쪽은 **시그니처를 바꾸지 않고** 몸통만 채운다(꼭 바꿔야 하면 보고서 맨 위에).
- **담당 파일 밖은 고치지 않습니다.** 다른 묶음이 같은 시간에 다른 파일을 고치고 있습니다. 꼭 필요하면 최소한으로 고치고 보고서에 적습니다. `docs/HANDOFF.md`, `docs/VERSION`, `docs/UPDATE_NOTE.txt`, `docs/DESIGN_V3.md`, `docs/DESIGN_V4.md`, `.github/workflows/*`는 오케스트레이터만 고칩니다. 설계와 다르게 정한 것은 보고서 "설계와 다르게 정한 것"에 적으면 오케스트레이터가 DESIGN_V4에 옮깁니다.
- 패키지 이름, 서명 키 유도(`tools/derive_signing_key.py`), 워크플로 파일은 바꾸지 않습니다(워크플로 변경이 꼭 필요하면 보고서에 제안만).

## 빌드와 시험
- 로컬 툴체인이 있으면(`/home/claude/toolchain/env.sh`가 있을 때) `. /home/claude/toolchain/env.sh` 뒤 `python3 run_gradle.py --log <고유라벨>.log <gradle 작업>`으로 돌립니다. 라벨은 매번 새로(`r7-1-compile-1`, `r7-1-test-2` …). Gradle은 CPU가 2개뿐이라 **반드시 `flock /home/claude/gradle.lock python3 run_gradle.py ...`로 한 번에 하나만** 돌립니다. 컴파일 확인은 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin`, 시험은 `:app:testDebugUnitTest --tests 'kr.callbrief.pipeline.XxxTest'`처럼 자기 묶음 관련 클래스만. 결과는 `evidence/<라벨>.log`에서 `BUILD SUCCESSFUL`과 `e:` 줄을 직접 확인합니다(`| tail`로 가리지 말 것).
- **빌드 자원(11/6):** 이 작업 공간은 CPU 2개라 여러 작업자가 Gradle을 번갈아 기다린다. 로컬에서는 컴파일 확인과 **자기 묶음 시험 클래스만** 돌리고, 전체 시험(`:core:test :app:testDebugUnitTest :app:lintDebug`)은 push 뒤 GitHub `ci`(CPU 4개)로 판정한다. 로컬 전체 시험은 돌리지 않는다.
- Robolectric 시험은 worktree에서 처음 한 번 `python3 tools/prepare_host_tests.py /home/claude/toolchain/robolectric-jar/android-all.jar`를 실행한 뒤 돌릴 수 있습니다. GitHub Actions는 11/5부터 다시 돈다(저장소 공개). 그래도 판정은 로컬 시험으로 합니다. 메이븐 429는 `$GRADLE_USER_HOME/init.d/mirror.gradle`(구글 미러)이 대부분 막아 주고, 로그에 `status code 429`가 보이면 60초 뒤 다시 돌립니다. 보고서는 마지막 답에 전문으로 넣으세요(worktree 밖 쓰기는 막혀 있음).
- 툴체인이 없거나 실패하면 push 뒤 GitHub `ci`(약 5분)로 판정합니다: `gh run list --repo bllue583-commits/ieum-app --branch <브랜치> --json name,status,conclusion,url` → 실패면 `gh run view <id> --log-failed`로 원인을 보고 고칩니다. ci는 `android/**`가 바뀐 push에만 돕니다.
- 고친 동작마다 단위 테스트(Robolectric 또는 순수 JVM)를 더합니다. 기존 테스트가 "옛 동작"을 검사하고 있으면 설계대로 고칩니다.
- 화면 글 규칙(`tools/check_ui_rules.py`로 ci가 검사): 가운데 점(·), 말줄임표(…), 영어 상태값, 빨간색 금지. 읽는 글 18sp 이상, 곁들이는 글 16sp, 배지 15sp. "할 일"이 아니라 "체크할 일", 기한 대신 요청 날짜, "~해요" 체.
- 비밀, 전화번호, 통화 내용, 개인 경로를 코드·로그·커밋·보고서에 남기지 않습니다(`tools/scan_export.py`).

## 커밋과 push
- 작게 여러 번 커밋해도 되지만 마지막에는 브랜치가 컴파일되고 자기 묶음 테스트가 통과한 상태여야 합니다.
- 커밋 메시지는 한국어 한 줄 요약 + 빈 줄 + 설명. 끝에 아래 한 줄을 붙입니다.
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  ```
- `git push -u origin <브랜치>`. HTTP 429(동시 전송 제한)가 나면 5초 뒤 한 번 더. force push 금지.

## 보고
- 끝나면 마지막 답에 한국어 전문으로(오케스트레이터가 `docs/orchestration/reports/`에 저장): 바꾼 파일 목록(한 줄씩 무엇을), 더한 테스트, 로컬 시험 결과(라벨과 BUILD 결과) 또는 ci 실행 URL과 결론, 설계와 다르게 정한 것과 이유, 못 한 것과 이유, 다른 묶음이 알아야 할 것(새 설정 키, 하트비트 필드 이름, 바뀐 함수 시그니처).
- 마지막 답(오케스트레이터에게)은 보고서 경로와 요약 10줄(한국어)만.
