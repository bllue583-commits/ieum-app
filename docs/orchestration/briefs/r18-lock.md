# r18-lock: 배포 잠금 (v3.8, DESIGN_V7 4.2 — 사용자가 토큰 값을 모르는 조건으로 다시 짬)

브랜치 `feature/r18-lock`(작업 나무 `/home/claude/wt/lock`, 기준 bc089c3). 보고는 한국어.

## 왜
이 앱은 통화 녹음을 다루고, 폰은 우편함(비공개 저장소)에 올라온 새 버전을 사람 손 없이 설치한다. 감사에서 열려 있던 것(DESIGN_V7 4.2 첫 문단): 우편함 토큰이 APK에 평문, 서명 키가 그 토큰에서 파생, 폰은 받은 APK의 서명을 비교하지 않음, 배포본이 디버그 빌드, 번호 해시에 소금 없음. 이어지면 "토큰 하나 유출 → 가짜 업데이트 자동 설치"가 된다.

## 11/13에 바뀐 조건 (설계도 1번을 대체)
- 사용자는 우편함 토큰 값을 보관하지 않았다. 토큰은 세분화(fine-grained) 토큰이고 만료 없음, 이 저장소의 비밀 값을 다룰 권한이 없다(복사 작업으로 확인). 그래서 **`IEUM_SIGNING_SEED`에 토큰 값을 넣는 설계도 1번은 할 수 없다.**
- 지금 준비된 `tools/rotate_signing_key.sh`는 매 빌드마다 옛 키를 다시 만들어 계보를 붙이므로 토큰 값(= 옛 키의 씨앗)이 영원히 필요하다. 이것을 **"옛 키가 새 키를 보증하는 계보(lineage)를 한 번만 만들어 저장하고, 이후 빌드는 새 키와 저장된 계보만으로 서명"**하게 고친다. 그러면 계보를 만든 뒤에는 토큰을 새로 발급해도 된다.
- 사용자가 할 일은 두 가지뿐이 되게 한다: 저장소 비밀 값 `IEUM_SIGNING_SEED_V2`(무작위 32자 이상)를 만들고, 저장소 변수 `IEUM_ROTATE`를 1로 켜기.

## 할 일
1. **키 교체를 "계보 한 번" 방식으로** (`tools/rotate_signing_key.sh`, `.github/workflows/release-apk.yml`, `update-smoke`):
   - `IEUM_ROTATE=1`이고 `IEUM_SIGNING_SEED_V2`가 있을 때: 저장된 계보가 없으면(첫 회) 옛 키(지금처럼 `IEUM_SIGNING_SEED` 또는 토큰에서 파생)와 새 키로 `apksigner rotate`해 `lineage.bin`을 만들고 **우편함 저장소 `app/signing-lineage.bin`에 올린다**(인증서와 서명만 들어 있어 비밀이 아니다. sha256도 `app/version.json`에 적는다). 저장된 계보가 있으면 그것을 받아 **새 키 + 계보만으로** 서명한다(옛 키 불필요). 계보의 첫 인증서가 직전 배포본의 인증서와 같고 마지막이 새 키의 인증서인지 매번 확인하고, 아니면 배포를 멈춘다.
   - apksigner가 "새 서명자 하나 + `--lineage`"로 v3(Android 9+) 회전 서명을 만들 수 있는지, `minSdk = 26`(Android 8.0~8.1은 v2만 봄)에서 무엇이 필요한지 **공식 문서와 실제 실행으로 확인**한다(build-tools 35 apksigner). 실사용 폰은 Android 16 한 대뿐이므로 Android 8.x를 버리는 선택(`--min-sdk-version 28` 서명 또는 minSdk 28)은 가능하다 — 고른 것과 이유를 보고.
   - **에뮬레이터 검증이 필수:** update-smoke에 "옛 키로 서명된 지금 배포본 위에, 새 키 + 저장된 계보만으로 서명한 빌드를 덮어 설치 → 성공하고 데이터가 남는다"와 "그 위에 또 한 번 새 키 + 계보 빌드를 설치 → 성공"을 넣는다(시험용 임시 씨앗으로, 실제 비밀 값은 쓰지 않는다). 이것이 통과하지 않으면 이 묶음은 배포할 수 없다.
   - `IEUM_ROTATE`가 꺼져 있으면 지금과 똑같이 서명한다(지문 불변). 켠 뒤 되돌리는 법과 계보 파일을 잃었을 때(토큰이 그대로면 다시 만들 수 있다, 토큰을 바꾼 뒤면 불가)를 스크립트 머리말과 `docs/OPERATIONS.md`에 적는다.
2. **설치 전 서명 비교** (`pipeline/CallUpdate.kt`): 받은 APK의 서명 인증서(계보 포함: `PackageManager.getPackageArchiveInfo`의 `SigningInfo`, API 28+)를 자기 인증서와 비교해, 같거나 자기 인증서가 그 APK 계보의 조상이면 허용, 아니면 설치하지 않고 `update.error=CERT`. sha256 확인은 그대로. 이 검사가 잘못 막으면 폰이 업데이트를 영영 못 받으므로: 판정 로직을 순수 함수로 빼서 시험하고(같음, 조상, 무관, 읽기 실패), **읽기 실패(예외·null)는 막지 않고 통과시키되 `update.certUnknown`을 센다**(안드로이드 설치기가 어차피 서명 불일치를 거부한다 — 이 검사는 방어선 하나를 더하는 것이지 유일한 선이 아니다).
3. **정식(release) 빌드 유형** (`android/app/build.gradle.kts`, `release-apk.yml`): release 유형에 지금 debug와 **같은 keystore 서명 설정**을 넣고 `assembleRelease`로 배포(debuggable 끔, `SYNTHETIC_TOOLS` 끔). 축소(minify)는 켜지 않는다(이번엔 위험을 늘리지 않음). `lintVitalRelease`가 막는지 확인. debug 전용으로 묶여 있던 기능 중 아내 폰에서 실제로 쓰는 것이 release에서 사라지지 않는지 전부 찾아 보고(`BuildConfig.DEBUG`, `SYNTHETIC_TOOLS_ENABLED`, debug 소스셋, 매니페스트 차이). update-smoke에 "지금 배포본(debug) 위에 release 덮어 설치" 경로. `app/version.json`에 `buildType`, `seedIndependent`(회전 서명일 때 true)를 쓴다.
4. **번호 해시 소금** (`LocalPrivacy.kt` 등): 기기 비밀 소금(봉인 설정의 무작위 32바이트)을 넣고, 기존 해시를 폰 안에서 한 번 다시 해시하는 이전을 같이 한다(원본 번호가 폰에 있어야 다시 해시할 수 있다 — 어디에 원본이 있고 어디에는 해시만 있는지 먼저 감사해서, 다시 만들 수 없는 해시가 있으면 그 표는 이전하지 않고 옛 방식과 새 방식 둘 다로 대조하게 한다). 코드에 상수로 있는 개인 번호 해시 목록은 설정으로 옮긴다. 이전이 중간에 끊겨도 안전(멱등, 한 트랜잭션). 하트비트 `security{certCheck, saltedHash}`.
5. **비밀 스캔:** ci에 gitleaks(또는 같은 급의 도구) 단계를 넣고 건수를 우편함 `status/secrets-scan.json`에(release-apk 단계에서). 오탐 규칙은 최소로.
6. **시험·문서:** 위 각 항목의 단위 시험, `tools/inbox/score.py`의 보안 항목(8번)이 읽는 키와 맞는지(`buildType`, `seedIndependent`, `certCheck`, `saltedHash`, `secretFindings` — score.py를 읽고 키 이름을 맞춘다). `docs/DESIGN_V7.md` 4.2절을 이 방식으로 고쳐 쓰고 11절에 "다르게 정한 것", `docs/OPERATIONS.md`에 사용자가 할 일의 정확한 순서(비밀 값 만들기 → 변수 켜기 → 첫 회전 배포 확인 → 폰 설치 확인 → 그 뒤에만 토큰 새 발급(만료일 있는 것으로) → 새 토큰 배포 확인 → 옛 토큰 폐기)와 되돌리는 법. `docs/VERSION`·`docs/UPDATE_NOTE.txt`는 건드리지 말 것.

## 가장 조심할 것
- **이 묶음의 버그는 "폰이 다시는 업데이트를 못 받는 것"으로 나타난다.** 서명·빌드 유형·서명 비교 어느 하나라도 틀리면 그렇다. 그래서 (a) `IEUM_ROTATE`가 꺼진 기본 상태에서 서명 지문이 지금과 같아야 하고(release-apk의 기존 지문 비교 유지), (b) debug → release 덮어 설치가 에뮬레이터에서 통과해야 하며, (c) 서명 비교는 확신할 때만 막는다.
- 실제 비밀 값(토큰, 씨앗)은 읽거나 출력하거나 파일에 남기지 않는다. 시험은 임시 값으로.
- GitHub 비밀 값·변수·저장소 설정은 건드리지 않는다(사용자가 한다). 이 작업 환경에서는 비밀 값 API가 막혀 있다.

## 확인 방법
- 컴파일: `cd /home/claude/wt/lock && . /home/claude/toolchain/env.sh && flock /home/claude/gradle.lock python3 run_gradle.py --log r18l.log :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileReleaseKotlin`(release 변형이 생기면). 단위 시험은 로컬에서 못 돌림(Robolectric 없음).
- 파이썬·셸: `python3 -m unittest discover -s tools -p 'test_*.py'`, `python3 -m unittest discover -s tools/inbox -p 'test_*.py'`, `python3 tools/check_ui_rules.py`, `python3 tools/scan_export.py`. apksigner는 `$ANDROID_HOME/build-tools/35.0.0/apksigner`가 있으면 로컬에서 회전 서명을 직접 실행해 확인.
- ci: `git push -u origin feature/r18-lock` 뒤 `gh api "repos/bllue583-commits/ieum-app/actions/runs?head_sha=<sha>"`에서 ci가 success일 때까지. update-smoke는 `gh workflow run update-smoke.yml --ref feature/r18-lock`(입력은 워크플로를 읽고)로 직접 돌려 결과를 확인한다. 실패 원인은 `gh api repos/bllue583-commits/ieum-app/actions/jobs/<job id>/logs | grep '##\[error\]'`.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 과 `Claude-Session: https://claude.ai/code/session_013kyAU6nSXKy8Jb23SkiER1`. `[skip ci]`를 넣지 말 것.

## 금지
- `feature/lean-calls`, `main`에 push 금지(폰에 자동 배포된다).
- `/home/claude/ieum-inbox`의 `status/calllog.txt`, `inbox/`, `results/`, `ledger/`, `tidy/`, `story/`, `report/` 본문을 읽지 말 것. 우편함 저장소에 직접 쓰지 말 것(배포 워크플로만 쓴다).
- 자막(`CallTranscription*`), 카톡 보고(`pipeline/report/`), 저장소 정리(`LedgerPrune`)는 건드리지 말 것. 다른 작업자가 엔진 측정대를 `feature/r18-bench`에서 만들고 있다(`PhoneStatus.kt`는 새 키를 더하는 최소 변경만).

## 보고에 넣을 것
항목별로 한 것, 설계와 다르게 정한 것과 이유(특히 apksigner·minSdk 선택), release에서 사라지는 기능 목록, 에뮬레이터 덮어 설치 시험 결과(실행 번호), ci 결과, 사용자가 할 일의 정확한 순서와 링크, 확인하지 못한 것.
