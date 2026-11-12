# r18-bench: 엔진 측정대 (v3.8, DESIGN_V7 4.4) — 결정은 v3.9에서, 여기서는 재는 도구만

브랜치 `feature/r18-bench`(작업 나무 `/home/claude/wt/bench`, 기준 bc089c3). 보고는 한국어.

## 왜
"오늘 통화는 오늘 정리"가 지금 엔진으로는 안 된다: 2분 조각 하나에 약 5분(11/13 밤 중앙값 301초, 29조각), 11/12 그날 지연 p50 102분·p90 732분. 실제 통화 653건 시뮬레이션에서는 녹음 1분을 1분 안에 바꾸는 엔진이어야 채점 항목 1·2를 통과한다. 엔진을 바꿀지는 **아내 폰(Galaxy S26, Exynos 2600, Android 16)의 실제 통화로 잰 숫자**로만 정한다(DESIGN_V7 5.2의 결정 규칙). 그 숫자를 내는 측정대를 만든다.

## 무엇을 (DESIGN_V7 4.4 표가 원본 — 먼저 그 절과 5.1·5.2를 읽을 것)
1. **후보:** (가) 지금: whisper.cpp v1.7.6 large-v3-turbo q5_0, background. (나) 엔진 유지 최적화: turbo **q8_0** + (가능하면) whisper.cpp 최신 안정판 + i8mm 빌드 + 포그라운드. (다) **SenseVoice-Small int8**(sherpa-onnx, 언어 ko, 숫자 표기 변환 켬) + Silero VAD.
   - (다)가 가장 값진 숫자다(결정 규칙 2). (나)의 whisper.cpp 재빌드가 이 묶음에서 위험하거나 오래 걸리면, (나)는 "지금 라이브러리 + q8_0 + 포그라운드"로 줄이고 i8mm·새 버전은 "하지 못한 것"으로 보고한다.
   - sherpa-onnx는 공식 배포본(AAR/`.so`, arm64만)을 쓴다. 버전·출처·sha256을 `docs/ASSETS.md`에 적고, 라이선스(sherpa-onnx Apache-2.0, SenseVoice는 FunASR 모델 라이선스 — 출처 표기 의무)를 `THIRD_PARTY_NOTICES.md`와 설정 › 정보에 표기. 내려받을 주소·크기·sha256은 공식 릴리스에서 WebFetch로 확인하고 추측하지 말 것.
2. **켜는 방법:** 아내 화면에는 아무것도 보이지 않게. 관리자가 폰을 만지지 않아도 켤 수 있게 **우편함 `app/config.json`의 `bench` 값**(예: `{"bench":{"on":true,"candidates":["b","c"],"calls":20}}`)을 폰이 하트비트 때 읽는다(파일이 없거나 못 읽으면 꺼짐). 기존 측정 메뉴가 `SYNTHETIC_TOOLS_ENABLED` 뒤에 있으면(`SettingsScreen.kt`) 별도 설정 키로도 켤 수 있게 하되, 화면에 새 항목을 늘리지 않는다(기능 동결).
3. **모델 받기:** 측정이 켜졌을 때만, 충전 중 + 와이파이에서. SenseVoice int8 약 227MB, q8_0 약 874MB. sha256 확인, 이어받기, 폰 저장 공간이 2GB 미만이면 받지 않음. 측정이 끝나거나 꺼지면 받은 측정용 모델을 지운다(지금 쓰는 q5_0 모델은 건드리지 않는다).
4. **재는 때:** 충전 중이고 자막 대기열이 0이고 통화 중이 아닐 때만. 최근 통화 녹음 20건(1~5분, 10분 넘는 것 2건 포함, 개인 통화로 판정된 것은 제외). 통화가 오거나 충전이 끊기면 그 자리에서 멈추고 다음 기회에 이어서. 하룻밤에 다 못 해도 된다. **기존 자막 대기열·전력 정책·통화 카드를 방해하면 안 된다**(측정은 가장 낮은 우선순위).
5. **재는 값(숫자·부호만, 하트비트 `bench`):** 후보별 — 실시간 배수(처리 시간 ÷ 녹음 길이, 벽시계와 스레드 CPU 시간 둘 다), 조각/통화당 mAh(충전 중이라 못 재면 CPU 시간으로 대신하고 그렇게 표시), (가)의 기존 자막을 기준으로 한 **정규화 글자 오류율**(숫자 표기·띄어쓰기·문장부호 정규화 — `tools/eval/ko_cer.py`와 같은 규칙을 Kotlin으로 옮기고, 같은 입력에 같은 값이 나오는지 시험 자료로 교차 확인), 숫자 토큰 일치율, 장부 이름 일치율, 묵음 구간 환각 수(1초 미만 조각의 한두 글자 출력), 통화 수·총 분, 실패 수와 부호. 통화별 값은 길이 구간(1분 미만/1~3분/3~10분/10분 초과)으로만 묶는다. **자막 글, 이름, 번호, 파일 이름은 폰 밖으로 내보내지 않는다.**
6. **사람 정답(금 골든) 입력:** 4.4 표의 "골든 측정" 화면이 이미 있으면 그대로 두고 `status/gold-transcript.json`을 올리는 부분만 확인한다. 새 화면은 만들지 않는다.
7. **시험:** 정규화·오류율(파이썬과 교차), 켜짐 조건(충전·대기열·통화), 중단과 이어 하기, 모델 받기(가짜 서버), 하트비트 값에 글이 없는지, 측정이 꺼져 있을 때 앱 동작이 지금과 같은지(가장 중요). 네이티브 라이브러리가 들어가도 기존 whisper 경로가 그대로 도는지 기기 시험(device-test)이 있으면 확인.
8. **문서:** `docs/DESIGN_V7.md` 4.4절(실제로 만든 것, 못 한 것), `docs/OPERATIONS.md`(켜는 법 `app/config.json`, 하트비트 `bench` 키, 판정 표와 5.2 규칙으로 읽는 법), `docs/ASSETS.md`, `THIRD_PARTY_NOTICES.md`. `docs/VERSION`·`docs/UPDATE_NOTE.txt`는 건드리지 말 것.

## 조심할 것
- APK가 약 31MB 커진다(sherpa `.so`). release-apk의 크기 한도·분할 설정이 있으면 확인.
- 측정이 꺼져 있을 때(기본) 새 코드가 아무 일도 하지 않아야 한다. 네이티브 라이브러리는 측정이 켜졌을 때만 로드한다.
- 다른 작업자가 `feature/r18-lock`에서 빌드 유형·서명·`CallUpdate`를 고치고 있다: `build.gradle.kts`는 의존성·ABI 추가만, `CallUpdate`와 서명 관련 워크플로는 건드리지 말 것. `PhoneStatus.kt`는 새 키를 더하는 최소 변경만.

## 확인 방법
- 컴파일: `cd /home/claude/wt/bench && . /home/claude/toolchain/env.sh && flock /home/claude/gradle.lock python3 run_gradle.py --log r18b.log :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`(단위 시험은 로컬에서 못 돌림 — Robolectric 없음; 네이티브 빌드가 로컬 도구로 안 되면 ci로 판정).
- 파이썬: `python3 -m unittest discover -s tools -p 'test_*.py'`, `python3 -m unittest discover -s tools/inbox -p 'test_*.py'`, `python3 tools/check_ui_rules.py`, `python3 tools/scan_export.py`. `/home/claude/lab-b`, `/home/claude/lab-c`에 이전 토론 때 받은 sherpa-onnx·SenseVoice 자료가 있을 수 있다(있으면 x86에서 동작 확인에 쓰되, 주소·버전은 공식 릴리스로 다시 확인).
- ci: `git push -u origin feature/r18-bench` 뒤 `gh api "repos/bllue583-commits/ieum-app/actions/runs?head_sha=<sha>"`에서 ci가 success일 때까지. 실패 원인은 `gh api repos/bllue583-commits/ieum-app/actions/jobs/<job id>/logs | grep '##\[error\]'`.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 과 `Claude-Session: https://claude.ai/code/session_013kyAU6nSXKy8Jb23SkiER1`. `[skip ci]`를 넣지 말 것.

## 금지
- `feature/lean-calls`, `main`에 push 금지(폰에 자동 배포된다). 우편함 저장소에 직접 쓰지 말 것.
- `/home/claude/ieum-inbox`의 `status/calllog.txt`, `inbox/`, `results/`, `ledger/`, `tidy/`, `story/`, `report/` 본문을 읽지 말 것.
- 녹음·자막이 폰 밖으로 나가는 길을 만들지 말 것. 비밀번호·토큰·키를 파일에 남기지 말 것.

## 보고에 넣을 것
만든 것과 못 한 것(특히 (나)의 범위), 받은 라이브러리·모델의 출처·버전·크기·sha256, APK 크기 변화, 켜는 법, 하트비트 `bench`의 정확한 모양과 5.2 규칙으로 읽는 법, 시험과 ci 결과, 실기기에서만 확인되는 것.
