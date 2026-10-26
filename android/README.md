# code30 · 자동 처리·로컬06·예약 dot 텍스트 통합

versionCode30 /0.12.5-scheduled-dot-local06-dev. 앞선 v1 소스279/검사1003은 예약 텍스트 통합 전 checkpoint이며 최종 고유 검사에 더하지 않습니다.
Drive 담당의 add-only 01-scheduled-dot-text-lane.patch 7경로와 기존 API 회귀/fixture를 원문 SHA 그대로 통합했습니다. 최신 처리자 import가 정상화되어 임시 compile-time alias는 제거했습니다.
새 ScheduledDotProofCoordinator는 승인 후 하나의 durable UUID/queue를 고정하고 publish/poll의 같은 input/result/enrollment ID만 사용합니다. publication uncertain은 원문 포트의 명시 재개만 허용합니다. 기본 plan 미설정은 원격 작업0입니다. 별도15분 WorkManager 경로가 있어 SAF 음성 폴더 유무와 독립적으로 상태를 확인할 수 있지만, 이 작업에서는 실제 plan/grant/예약을 켜지 않았습니다.
결과는 READ_ONLY_SCHEDULED_TEXT_PROPOSAL로만 표시하고 native audio 완료/STT/model identity/업무 확정/Kakao 전송으로 승격하지 않습니다. 상위 담당의 실제 one-shot 합성 텍스트 wake/write/readback 증거는 별도 인계 자료이며 새 Android 슬롯의 단말 왕복·반복 예약 성공이 아닙니다.
외부 담당880은 app만의 값(기존872+새8)이고 기존967은 다른 모듈95를 포함합니다. 원래 테스트 소스·클래스를 보존한 채 새 통합본에서 모든 모듈을 재실행합니다. Node8과 Kakao48은 별도 집계입니다.
기존 안전한 누적 정책·수동 DONE/편집/제외·로컬06 보관/알림은 유지하며 Kakao 자동 sender는 정책 보류로 항상 DISABLED입니다. 사용자 직접 sendFinalized 포트만 남겨 두었고 실제 발송0입니다.
최종 수치·소스 SHA·APK·담당별 patch 재구성과 미실행 조건은 전달 v2의 REPORT_KO.html/FINAL_VALIDATION.json을 기준으로 확인하세요.

아래는 이전 후보/안정본의 역사 기록입니다.

# code30 · 자동 처리와 06시 로컬 보고 · Kakao 정책 보류

versionCode30 /0.12.4-local-06-policy-held-dev. 기존 code30 source265/ZIP/APK는 보존한 별도 사본입니다.
허용된 합성 native 수집 → 중복 방지 작업 → 기존 frozen 요청 → 외부 processing port → 보호 receipt → 안전한 파생 누적/할일 → 06시 로컬 보관본·알림을 연결했습니다.
기본 provider UNCONFIGURED는 준비/전송0입니다. 실행·source·의미·개인정보·자동사용 근거가 모두 검증된 adapter만 안전한 파생 반영을 허용합니다. 현재 API v2는 독립 검증되지 않은 read-only 제안이므로 자동 권한을 얻지 않습니다.
수동 DONE·편집·잠금·현재 revision·제외·보관·근거 동의 철회·계정 epoch를 검사합니다. 변형된 결과/분수 cursor를 정상화하지 않으며 접수 불확실은 원 작업 조회만 합니다.
06시는 기존 WorkManager 목표입니다. 분석 대기·실패·검토 필요도 부분 로컬 보고로 보관하고, 알림은 원문 없는 안내만 표시합니다. 기존 POST_NOTIFICATIONS가 없으면 권한 대기로 보관하며 새 prompt/grant는 없습니다. 실제 기기 정시 실행/Doze/읽음은 인수되지 않았습니다.
최신 정책 지시로 앱의 Kakao 자동 sender는 빌드 flag/등록과 무관하게 항상 DISABLED입니다. 기존 사용자 직접 sendFinalized 포트는 유지하고 무인 보고를 지원/완료로 표시하지 않습니다. 실로그인·등록·전송·수신도 미실행입니다. 로컬 대안은 원래 자동 Kakao 요구의 완료가 아닙니다.
외부 처리자 파일8개는 원문 SHA로 통합하고 수정하지 않았습니다. 카카오 연결 patch ZIP aeabfc39417bf5a01abedf4cc1fe64dbf8dca1babb44844ff9c449dfe138b7e9를 검증해 통합한 뒤 앱 자동 sender/문구에 정책 보류를 적용했습니다.
실제 STT/high/dot·업로드·OAuth·권한 변경·배포·외부 메시지·기기/AVD 실행은0입니다. 합성 host의 Android NotificationManager shadow는 실제 OS 알림이나 권한 부여 증거가 아닙니다.
최종 전체 XML/실행 로그/APK SHA/patch와 미실행 조건은 evidence/ 및 전달 패키지의 REPORT_KO.html에 기록합니다.

아래는 이전 후보/안정본의 역사 기록입니다. 그때의 판정·경로는 보존합니다.

# code30 · Android 자동화 coordinator 별도 사본

versionCode30 / 0.12.3-automation-kakao-spine-dev. 기존 source265와 안정 ZIP/APK는 보존합니다.
기존 자동 발견 worker/watch 뒤에 내구성 admission → native 고정 요청 → 승인된 processing port → 결과 → 누적·할일 → 06시 보고 입력 큐를 연결했습니다.
기본 provider는 UNCONFIGURED이며 원음 전송0입니다. 일반 SAF 수집 제한, frozen v0/RO1, 사용자 DONE·잠금·수정·보관·제외·동의 철회와 기존 보고의 수동 검토 의미를 유지합니다.
외부 processing 담당의 파일 8개는 SHA 고정 원문으로 통합했고 재작성하지 않았습니다. 해당 사본의 누락 import는 별도 compile-time typealias 하나로 기존 보호 DB 타입만 연결합니다.
새 화면·탭은 없습니다. 기존 목록의 상태 문구와 data projection만 연결합니다. 현재 native grant가 없으면 WAITING_AUTH, 실행자 미설정/미승인은 별도 대기로 표시합니다.
검증된 adapter의 source/execution/semantic/privacy/automatic-use 증거가 모두 있을 때만 이미 연결된 업무의 파생 요약·근거 할일을 자동 반영합니다. 현재 외부 API v2는 명시 read-only이므로 이 자동 권한을 얻지 않습니다.
06시 큐는 기존 ReportWindow와 DailyReportLedger 슬롯에 연결되지만 reportBatch의 자동 출처를 받는 보고 모듈 소비 계약은 아직 인수되지 않았습니다. 기존 reviewed-only freeze를 사용자 검토로 위조하지 않으며 Kakao 전송 완료로 표시하지 않습니다.
실계정·grant·원음 업로드·유료 모델·배포·Kakao·실기기/AVD 실행은 이 차수에서0입니다. 로컬 합성 전체 흐름 및 regression 증거는 루트 evidence/와 최종 전달 패키지를 확인하세요.

아래는 원본 code30 및 이전 차수의 역사 기록입니다. 당시 경로와 판정은 보존합니다.

# code30 · Drive A 복원 무결성·보관 충돌 수정

versionCode30 /0.12.1-drive-a-integrity-fix-dev. code29 v2 독립 검증 C29F01/C29F02 두 결함을 수정한 별도 사본입니다.
기존 code29 v2 소스263개와 전달 ZIP/APK, code28 안정본은 변경하지 않았습니다. 새 기능·제품 기본 연결·permission·계정 capability·고정 계약 변경은 없습니다.

- C29F01: 같은 복원 reader를 준비/frozen 조회/로컬 reopen, 원격 조회 전, 결과 admission 트랜잭션 안에 공통으로 적용합니다. job cursor·head·불변 receipt의 profile/revision/digest/bytes가 불일치하면 STORE_REVIEW로 중단합니다. 누락/소수/digest 불일치 head를 새 정상 결과로 덮어 고치지 않습니다. 인계 장부와 기존 receipt의 대조도 새 조회 전에 수행합니다.
- C29F02: conflict는 저장 receipt와 현재 신뢰된 수동 revision에서 계산합니다. hidden과 독립적으로 conflict=true를 유지하며 숨긴 본문·분석은 null로 반환합니다. 직접 DONE/수정·불변 receipt·canonical 업무는 그대로입니다.

제공된 원문 I01/I02/I03/I08: 수정 전4FAIL → 수정 후 같은 source/필터/assertion4PASS. 독립8 및 새 race/current-conflict2도 PASS입니다. 반복 실행을 고유 시험 수에 더하지 않습니다.
전체 회귀 기준: 기존957 +원문 독립8 +새2 =고유 JUnit967. 별도 Kakao36. 실제 DT01~16과 실제50대/S25 사용성 DT16은 NOT_RUN으로 구분합니다.
현 차수에 실제 로그인·grant·dot/모델·외부 전송·새 AVD 실행은 없습니다. APK는 새 debug/validation 빌드와 공개 signer만 확인하며 현재 콘솔/S25/grant 일치를 뜻하지 않습니다.

오프라인 런타임은 기존 검증된 JAR와 캐시를 별도 사본에 복사했습니다. 초기 파일명/런타임 설정 오류는 실행 로그에 보존하고 실제 assertion4FAIL과 분리했습니다.
재현 소스 원문, 네 반례 before/after XML/관측 JSON, 전체 로그와 SHA/patch는 새 전달 묶음의 REPORT_KO.html / DEVELOPER_HANDOFF_KO.txt에 있습니다.

아래는 code29 v2와 이전 안정본의 역사 기록입니다. 당시 경로·값은 그대로 보존합니다.

# code29 · Drive A 로컬 foreground 후보

버전 29 / 0.12.0-drive-a-local-candidate-dev. code28 안정본을 별도로 보존한 개발 후보입니다.
기존 본인 계정의 고정 합성 한 건을 위한 로컬 인계 자료·보호 장부·설치 진단·읽기 전용 결과 복원을 추가했습니다.
일반 계정 capability, 기본 NativeRecordingAnalysisPort factory와 supportedHandoff=null, frozen v0/RO1/SG, 원음 승인·lease·수동 DONE 보호는 유지합니다.

검증: 전체 JUnit 957개(실패/오류/skip 0), 별도 Kakao 계약 36개 PASS. 새 Drive A host 35개 + UI 9개는 이 전체 수에 포함합니다.
code28 독립 84개도 새 전체 회귀의 부분집합으로 통과했으며 Drive DT13 결과로 승계하지 않습니다. lint 오류 0 / 기존 경고 30.
실제 DT01~DT16은 모두 NOT_RUN입니다. DT15 배우자 B/C는 미선택·범위 밖입니다.
현재 콘솔/서명 등록, S25 설치/GMS/grant, 지정 파일 writer, STT/high/dot 실행·provenance, 원격 즉시철회와 원자 CAS는 미확인입니다.

로컬 공유 패키지는 원음·토큰·비밀번호를 포함하지 않습니다. 사용자가 별도로 분석 포함에 승인한 관련 원문이 기존 frozen context에 있으면 그 정확한 context는 포함될 수 있습니다.
공유 버튼은 보호 장부의 첫 명시 시도만 허용하고 이후 UNKNOWN을 유지합니다. 공유 화면은 dot 접수·실행 근거가 아닙니다.
외부 dot 호출/접수 어댑터는 연결하지 않았습니다. 결과 확인만 기존 Drive metadata→제한 bytes→metadata 검증을 명시적으로 사용합니다.
metadata version 누락/null과 같은 version에서 size 변화를 보류합니다. fresh readback/단일 writer는 원격 CAS 보증이 아닙니다.
준비·로컬 reopen은 원음 복호화 없이 현재 승인·선택 폴더·파일/키 가용성·source/lease metadata를 확인합니다. 기존 게시/결과 회수의 전체 원음 검증은 변경하지 않았습니다.

오프라인 재현은 이 후보 루트의 run_gradle.py와 tools/를 사용합니다. 기존 SDK/JDK/Gradle 캐시 및 기존 서명 키의 로컬 경로가 필요하며 서명 키는 산출물에 포함하지 않습니다.
생산 코드 시험은 android/ 아래에서 실행합니다. 테스트 기록, DT 로컬/실제 분리, APK SHA와 patch는 전달 패키지의 REPORT_KO.html / DEVELOPER_HANDOFF_KO.txt에 있습니다.


최종 v2는 SQLite REAL revision이 getLong으로 잘리는 복원 결함 2개를 재현하고 cursor/head의 정수 token·범위 검사로 수정했습니다. 숫자가 손상된 기존 행은 STORE_REVIEW로 보존하며 조회/초기화로 고치지 않습니다.

아래 code28 본문은 이전 안정본의 역사적 기록이며 해당 경로·해시를 그대로 보존합니다.

# code28 — 0.11.1-synthetic-text-contract-dev

code27 독립 검증의 C27F01/C27F02 재개방 손상 주입 방어 결함을 수정한 별도 사본입니다. versionCode28입니다. 원본 code27 소스252개 및 전달 ZIP은 보존했습니다.

원본 저장 JSON을 재직렬화 전에 엄격히 파싱하고 cached 상태 enum/단계 불변 조건을 검사합니다. RESULT_RECEIVED에는 필수 시작 정보·result ID/hash·유효 결과·동일 불변 RESULT_READBACK이 필요합니다. restored START의 exact shape·UUID·v4 Date.toISOString 밀리초 문자열·safe integer 시각·정확24h TTL·revision/status를 검사하고 모든 불변 START의 원본 proof/generation/created/expires와 결합합니다. queued0→ready1 캐시 진전에는 검증된 readback이 필요합니다. START receipt를 동일 보호 transaction에서 먼저 추가해 admission과 함께 commit합니다.

손상은 STORE_REVIEW/null view로 보류하며 기존 행·ID·결과·영수증·키를 삭제하거나 초기화하지 않습니다. 정상 UNKNOWN/EXPIRED가 보존하는 이전 결과는 계속 검증해 유지합니다. 기존 녹음/Drive/수동 편집/완료/30분 원음 lease/native gate는 그대로입니다. 새 Drive 경로는 포함하지 않았습니다.

수정 전 독립10개 중2 FAIL 재현 → 수정 후 제공된 독립72개 PASS, 추가 재개방12개 PASS. 집중84개는 전체913 JUnit의 부분집합입니다. 별도 Kakao 계약36, debug/validation 빌드·기존 signer·lint0errors30warnings 통과. API35/36 fresh headless debug APK의 실제 Keystore에서 정상 재시작 및 state-only/invalidISO/wrongTTL 보류·원본 보존을 확인했습니다. validation runtime은 미실행입니다.

alias2→3은 추가된 비활성 빈 ieum-daily-reports-v2.db의 최초 보호 키로 분류했습니다. 두 SG key 지문과 생성시각은 불변입니다. 원본 code27 AVD 독립 사본에서도 동일3종 키와 config enabled0/generation0/reports0을 확인했습니다. 당시 초기화의 정확한 broadcast/APP_REOPEN trigger는 원래 로그가 없어 미확인입니다. 키/토큰 값을 출력하거나 기존 키를 삭제·재생성하지 않았습니다.

실제 Android↔Site native 연결은 별도 미연결 상태입니다. 이번 수정과 로컬 합성 검증은 연결 완료·모델 실행·업무 반영·메시징 완료를 의미하지 않습니다. 상세 증거와 정확한 식별은 새 전달 묶음의 REPORT_KO.html, FINAL_SOURCE_MANIFEST.json, APK_IDENTITY.json, evidence/에 있습니다.

아래는 code27 및 그 이전의 역사 기록이며 과거 경로와 당시 상태를 보존합니다.

---

# code27 — 0.11.0-synthetic-text-contract-dev

현재 개발본은 code26 242개 소스 파일을 기준으로 별도의 고정 SG v1 텍스트 기능을 추가했습니다. 원본 code26 체크아웃은 변경하지 않았습니다.

`계정과 연결` → `고정 합성 텍스트 확인`에서 기본 native 반환 상태는 `NATIVE_RETURN_UNSUPPORTED`입니다. 정확한 owner-private Site의 ACTION_VIEW는 브라우저 인계만 요청하며 연결·결과 수신 성공이 아닙니다. 공식 visitor-authenticated Android 채널과 신뢰 가능한 결과 반환 경로는 아직 구현·검증되지 않았습니다. 기존 Drive/legacy Bearer 경로를 대신 사용하지 않습니다.

debug/validation 합성 도구에서는 `기기 안 합성 흐름 검증`으로 고정 문장 한 건의 로컬 MCP 계약을 검사합니다. 실제 Site 연결이나 모델 실행이 아닙니다. proof UUID와 result UUID suffix를 요청 전에 별도 Keystore-sealed 저장소에 보존하며, 같은 generation의 revision1/result_ready·ID·canonical SHA256·applied:false readback 후만 미적용 제안을 표시합니다. 응답 유실·취소·재시작은 같은 키로 명시 재개합니다. 만료·범위 변경·충돌·키 소실은 기록을 보존하고 보류합니다.

기존 code26 UX, 수동 완료/편집, 녹음 저장소, Drive v0/readonly v1, 30분 원음 lease와 NativeAnalysisAvailability gate는 유지했습니다. 실제 계정 로그인·원격 배포·권한 동의·개인 통화·문자·메일·카카오 전송은 수행하지 않았습니다.

최종 오프라인 JUnit 883개(새 SG32, 기존 code26 UX12 포함), 별도 Kakao 계약36, assembleDebug/assembleValidation/lintDebug가 통과했습니다. lint는 0 errors/30 warnings입니다. API35/36 fresh headless AVD에서 전달 debug APK와 설치 APK SHA 일치, 실제 Android Keystore, Activity recreation, force-stop 후 다른 PID로 동일 영수증 복원을 확인했습니다. validation APK는 빌드·서명 확인만 했습니다. host JCE/SQLite 시험과 실제 AVD 검증 범위는 별도로 집계했습니다.

원본 후보 디렉터리의 `run_gradle.py`는 기존 승인 SDK/JDK/Gradle 캐시·debug 서명을 참조합니다. 도구·대형 캐시·키는 전달 ZIP에 포함하지 않습니다. 전체 실행 로그·XML·AVD 증거·정확한 APK/source identity는 code27 전달 묶음의 REPORT_KO.html 및 evidence/FINAL_TEST_SUMMARY.json을 확인하세요.

다음 문서는 최초 code4 개발 당시의 역사 기록입니다. 그 당시의 미검증 상태와 숫자를 현재 code27의 판정으로 사용하지 마세요. 역사적 경로와 설명을 그대로 보존합니다.

---

# 통화 내역 요약 — 0.3.0-dev/code4 개발·검증 인계

새 APK와 소스는 기존 0.2.0-dev/code3와 별도로 보존한 개발본입니다. 원본 0.1.1/code2 APK와 최초 복원 소스 0.1.0/code1은 일치한다고 주장하지 않습니다. 기존 source/code3 APK/ZIP·계정·실제 통화·기존 AVD는 변경하지 않았습니다.

## 이번 구현

- 날짜별·업무별·할일·보관 네 탭과 제목 `todo`를 유지했습니다. 날짜별은 선택 날짜의 통화 먼저, 최근 7일 요약은 아래에 둡니다.
- 기본 날짜 탐색을 한 행으로 모으고 날짜 미확정 항목만 경고 필터로 접근합니다. 이전 선택 날짜·스크롤 복귀를 보존하며 수집일을 통화일로 대신하지 않습니다.
- 카드의 사람·확인된 통화 시각 → 업무 제목 → 요약 → 업무 진행 → 상세 보기 순서입니다. 원본 날짜·수집 시각·버전·폴더 정보는 상세로 이동했습니다. 기본 720×1600 호스트 화면에서 첫 요약 전체가 보입니다. 150/200%에서는 글자·줄 수를 줄이지 않고 높이를 늘리며 요약에 스크롤로 접근합니다.
- 폴더 URI와 결과 안내는 한 설정 저장 호출로 묶었습니다. 실패·저장 응답 불확실 시 기존/새 SAF grant를 섣불리 해제하지 않습니다. 합성 protocol 검사이며 실제 문서 제공자 grant 시험이 아닙니다.
- 암호화 설정 읽기는 기존 키만 사용합니다. 기존 sealed 설정의 키 소실 시 새 키 생성·초기 설정 덮어쓰기를 막습니다. 새 키 생성은 실제 빈 설정의 최초 저장만 가능합니다.
- 별도 **합성 암호화 저장·검토** 화면에 공식 v2.1 AES-256-GCM 원음 저장 모듈을 연결했습니다. 전체 인증 태그·평문 SHA256·크기를 확인한 뒤만 메모리 원음 읽기를 허용합니다. 평문 임시 파일 fallback은 없습니다.
- 첫 초기화 DB intent와 파일 journal을 연결했습니다. DB PENDING과 동일 intent/alias·참조/원음 없음 예약을 확인하는 명시 재개만 제공합니다. 첫 키 생성 완료 후 DB 응답 소실은 기존 키를 채택합니다. READY/USED/legacy/corrupt 상태와 기존 암호문 키 소실은 키를 재생성하지 않습니다.
- raw audio hash/size와 ciphertext storage hash/size를 분리한 참조·수집 영수증을 저장합니다. 내용 같은 이름 변경은 버전 유지, A→B→A는 새 버전입니다. 파일 rename과 DB commit은 별도이며 frozen intake fence와 인증된 orphan 확인으로 중단 상태를 보존합니다.
- SQLite `INSERT OR REPLACE`로 참조·영수증·초기화 ID·dispatch reservation·pending fence를 교체하지 못하도록 INSERT guard를 추가했습니다. D05/D13은 내부 불변 계약 공백이며 공개 SQL 주입을 입증한 것이 아닙니다.
- 원음 전달은 현재 owner/source/hash/version/lease/identity/user/lock/task/board/보관 revision을 검사하고 매 read·취소·종료에서 차단합니다. 이미 돌아온 안전한 mock 변경안은 사용자 revision만 달라졌을 때 검토 후보로 보존하고 직접 수정·완료·유효 요약을 덮어쓰지 않습니다. 원음 권한 철회·source 변경·보관 epoch 변경·만료 lease에는 저장을 보류합니다.
- 명시 승인/이전 요약 복구만 유효 요약을 갱신하고 새 사용자 revision을 만듭니다. 합성 보관·복원은 원음 삭제 없이 목록 상태만 바꾸며 승인 직전 CAS를 다시 검사합니다. 합성 화면은 시스템 표시줄 inset을 적용하고 `다시 읽기`를 프로세스 재시작과 구분합니다.

## 저장소와 연결의 실제 범위

**기본 네 탭**은 `callbrief-local-v1.db`의 기존 읽기 adapter와 메모리 demo를 유지합니다. 새 암호화 변경 API는 별도 `callbrief-synthetic-pilot-v2.db`/noBackup 합성 저장소입니다. 이를 기본 네 탭의 일반 수정·보관, 실제 SAF 수집 또는 S25 개인 녹음 연결 성공으로 표현하면 안 됩니다. 이전 `callbrief-synthetic-pilot-v1.db`와 평문 prototype을 자동 이동·삭제하지 않습니다.

`NativeAnalysisAvailability.canTransfer=false`입니다. 인증·원음 upload·dot 기동·결과 회수·실제 STT·카카오·문자·네이버 메일·06시 자동 왕복은 연결되지 않았습니다. 기존 쿠키·토큰을 추출하지 않았고 실제 번호/개인 통화를 사용하지 않았습니다. projection v1은 새 의미 반례 실패 때문에 APK에 연결하지 않았으며 worker wire/공유 SQL을 바꾸지 않았습니다.

DB/WAL 및 전사·요약의 암호화가 완료된 앱은 아닙니다. manifest backup 제외 규칙은 정적 검사만 했습니다. 실제 Android Keystore·OEM backup/restore·25MB peak memory·장치 fsync/atomic move·OS kill/재부팅·보관 기간은 미검증입니다. 매 read predicate도 검사→복사→network를 한 transaction으로 만들지 않으며 이미 복사된 buffer를 회수하지 못합니다. 실전송 reservation/cancel 인수시험이 남습니다.

## 이 Mac에서 직접 실행한 검증

| 범위 | 결과 | 제한 |
| --- | --- | --- |
| 앱 Kotlin/Java + API35 Robolectric/native SQLite | 89/89, 실패/오류/skipped 0 | 실제 Android Keystore는 공개 JCE fixture로 대체; 기기/AVD 시험 아님 |
| 기존 core JVM | 11/11 | 별도 집계 |
| APK assemble + lint | 성공, 0 errors / 9 warnings | compile/target36; API36 runtime 미실행 |
| 독립 D01~D14 동일 SQL 재현 | 14/14 | Python SQLite3.53.1; crypto VERIFIED는 fixture metadata |
| 공식 v2.1 모듈 자체 재실행 | 신규22조건/116assertions, ported88assertions | 21 기대동작+R20 남은 native race 진단. 신규 초기화 halt3·ported halt3은 host JVM 종료이며 앱 OS lifecycle 아님 |
| 코드3 보존 | source57파일 + 기존 ZIP/APK hash 동일 | 기존 결과를 code4 pass로 승계하지 않음 |

최종 원본 XML은 앱8개 suite/core2개 suite입니다. 역사 XML·초기 실패는 `evidence/history`로 구분합니다. 초기 test 오류 두 건과 receipt의 outcome/후보의 review reason을 혼동한 검사 한 건을 수정한 로그를 보존했습니다. 빌드 재실행은 최종 성공 결과만 현재 검증 수로 집계합니다.

## 실행 화면

`screens/host/`의 PNG는 이 앱 View를 실행한 Robolectric API35 native graphics입니다. 720×1600/date/font100·150·200 및 822×1782 합성 암호화 화면이 포함됩니다. 실제 code4 APK 실행 화면이 아니며 TalkBack·터치 정확도·50대 참가자 검수도 미실행입니다. 200%의 첫 요약은 처음 화면 아래에 있어 스크롤이 필요합니다.

`screens/historical-code3/`는 공식 cloud AOSP API35 code3 증거입니다. 종료 PID1364→새 PID4828, DB/WAL 9시점에서 취소·수동 완료·보관 복원·재시작 보존을 이 Mac의 복사본으로 재확인했습니다. 이는 cloud code3의 별도 합성 DB이고 새로운 Mac/code4 실행으로 집계하지 않습니다. 초기 am start 시간 초과만으로 홈 실패나 앱 크래시를 단정하지 않습니다. system ANR와 QA 후 emulator 종료도 앱 오류로 집계하지 않습니다.

## 재현과 다음 검수

`source/android`에 AGP8.11.1 / Gradle8.13 / Kotlin2.1.21 / JDK17 / compile36 핀이 있습니다. 테스트 runtime은 Robolectric4.17/API35 android-all-instrumented `15-robolectric-13954326-i7`입니다. 이미 확보한 runtime JAR를 `app/build/synthetic-test-home/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7`에 두어야 하며 빈 cache에서 offline 실행 성공을 보장하지 않습니다. 도구·대형 runtime JAR는 이 ZIP에 포함하지 않습니다.

    sh ./gradlew --no-daemon --console=plain --offline -Pandroid.builder.sdkDownload=false :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

모듈/독립 SQL 재현은 `reproduce/README_KO.txt`에 있습니다. Mac APK 설치/AVD 실행은 0건입니다. 기존 ADB의 HOME/.android mkdir 접근 거부를 우회하지 않았습니다. code4 실기기/승인된 별도 AVD에서 N01~N12를 검증해야 합니다. code3와 code4는 같은 debug signer이지만 실제 upgrade 자료 보존은 미검증입니다. 원본 0.1.1은 signer가 다르며 uninstall/reinstall을 upgrade 성공으로 부르면 안 됩니다.

인계의 최종 식별값은 `APK_IDENTITY.json`, `FINAL_SOURCE_MANIFEST.json`, `MANIFEST.json`에 있습니다. 기존 code3 Library `libfile_15a299ba20188191a9732de026c58fd7`는 그대로 보존했습니다.
