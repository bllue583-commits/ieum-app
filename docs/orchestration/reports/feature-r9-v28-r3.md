# feature/r9-v28-r3 사람 화면 (7404a5e WIP, 기준 f7d091b) — 작업자 보고 요약
- screens/PersonScreen.kt 신설: 머리(이름, 직책·업체, 원형 44dp [전화 걸기][문자 보내기]) → 마지막 통화 → 체크할 일([다 됐어요]) → 진행 중인 일 → 최근 통화와 문자 10건 → [이름 고치기][이 번호는 개인으로 두기](확인 시트 → personal_numbers). 공유 번호면 사람 칩. 번호 비표시. 태그 person_*.
- MainActivity: Screen.PERSON(탭 없음, 연 화면의 탭 표시), 뒤로 가면 왔던 화면. 알림 extra PERSON은 무시. `openPerson(personId:String)`(internal), personFrom, barTab().
- 진입점: 통화 목록 행 배지·이름, 통화 상세 제목·"김민수 님 보기", 업체 상세 사람 행(이름 고치기 링크는 사람 화면으로 이동), 체크 META 이름, 홈 오늘 통화 메모·먼저 볼 체크할 일. CheckRows.row에 personId:String?=null. nameSheet → internal object NameSheet.
- CallRecordStore 끝: personCard(personId):PersonCard?, personIdsOf(recordIds):Map<String,String>, data class PersonCard·LastCall.
- 테스트 PersonScreenTest 6개, ContextLedgerTest·CheckCompanySettingsTest 수정. r9-3-compile-1 OK, r9-3-test-2(7개 클래스 86개) OK, ui rules 0. 회귀 11개 클래스(r9-3-test-3)는 결과 못 봄 → 통합 때 전체 시험.
- 남은 것: r9-2 ContactCards 연결(개인으로 두기 뒤 카드 삭제, 사람 화면을 카드로 읽기), 검색 결과(사람)·업체 상세 최근 통화 이름 링크, device-test 스크린샷. 주의: 체크 META 이름은 counterpart 원문이라 다른 화면에선 번호가 보일 수 있음(사람 화면에서만 숨김) → 통합 때 noNumber 적용 검토.
