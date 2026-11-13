"""<lab>/scores/*.json → 마크다운 표. 문서의 <!-- tables:start --> … <!-- tables:end --> 사이를 바꾼다(손으로 숫자를 옮기지 않기 위해).
python3 -I tools/eval/asrlab/summarize.py --lab /home/claude/asrlab --doc docs/eval/ASR_LAB_1113.md"""
import argparse
import json
import os
import re

ORDER = ['A', 'B_nb_ulaw', 'B_wb_opus12', 'B_nb_pink20', 'B_wb_babble10', 'C', 'D_clean', 'D_nb', 'E']
LABEL = {'A': 'A 낭독', 'B_nb_ulaw': 'B 좁은 대역 μ-law+AAC', 'B_wb_opus12': 'B 넓은 대역 Opus 12k+AAC', 'B_nb_pink20': 'B 좁은 대역+분홍 잡음 SNR 20',
         'B_wb_babble10': 'B 넓은 대역+잡담 잡음 SNR 10', 'C': 'C 즉흥(YODAS)', 'D_clean': 'D 숫자·이름(TTS 원음)', 'D_nb': 'D 숫자·이름(TTS, 좁은 대역)', 'E': 'E 긴 통화(좁은 대역)'}
ENG = {'q5': '(가) q5_0', 'q8': '(나) q8_0', 'sv': '(다) SenseVoice', 'lv3': 'large-v3', 'zip': 'zipformer'}


def pct(x):
    return '—' if x is None else '%.1f' % (100 * x)


def frac(a):
    return '—' if not a or not a[1] else '%d/%d (%.0f%%)' % (a[0], a[1], 100 * a[0] / a[1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--lab', default='/home/claude/asrlab')
    ap.add_argument('--doc', required=True)
    a = ap.parse_args()
    S = {}
    for s in ORDER:
        p = os.path.join(a.lab, 'scores', s + '.json')
        if os.path.exists(p):
            S[s] = json.load(open(p))
    L = []
    L.append('### 표 1. 묶음×엔진 정규화 글자 오류율(%, 95% 구간), 숫자·이름(통화 단위 KoCer 규칙), 정리·환각')
    L.append('')
    L.append('| 묶음 | 엔진 | 녹음/문장 | 분 | 오류율 % [95% 구간] | 숫자 일치 | 이름·업체 일치 | 앱 정리로 지운 조각 | 반복 고리 | 묵음 환각(통화당) | 1초 미만 1~2글자 | 실시간 배수(이 기계) |')
    L.append('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |')
    for s, d in S.items():
        for e in ('q5', 'q8', 'sv', 'lv3', 'zip'):
            r = d['engines'].get(e)
            if not r:
                continue
            L.append('| %s | %s | %d/%d | %.1f | %s [%s, %s] | %s | %s | %d | %d | %d (%.2f) | %d | %.2f |' % (
                LABEL[s], ENG[e], r['recs'], r['utts'], r['audio_min'], pct(r['cer']), pct(r['ci'][0]), pct(r['ci'][1]), frac(r['numbers']),
                frac(r['names']), r['cleaned'], r['loops'], r['silence_halluc'], r['halluc_per_call'] or 0, r.get('short_halluc', 0), r['rtf'] or 0))
    L.append('')
    L.append('### 표 1b. 5.2 규칙 2의 "은 골든" 방식: (가)의 자막을 정답으로 놓은 정규화 글자 오류율(%) — 같은 녹음만')
    L.append('')
    L.append('| 묶음 | 엔진 | 녹음 | (가) 대비 오류율 % |')
    L.append('| --- | --- | --- | --- |')
    for s, d in S.items():
        for e, v in d.get('silver', {}).items():
            if e in ('sv', 'q8', 'lv3'):
                L.append('| %s | %s | %d | %s |' % (LABEL[s], ENG[e], v['recs'], pct(v['cer_vs_base'])))
    L.append('')
    L.append('### 표 2. (가) 대비 짝 비교(같은 문장만): 오류 수의 비 = 엔진/(가), 95% 구간, p(엔진이 (가)보다 낫지 않음)')
    L.append('')
    L.append('| 묶음 | 엔진 | 문장 | (가) 오류율 % | 엔진 오류율 % | 비 [95% 구간] | p |')
    L.append('| --- | --- | --- | --- | --- | --- | --- |')
    for s, d in S.items():
        for e, p in d['paired'].items():
            if p['vs'] != 'q5':
                continue
            L.append('| %s | %s | %d | %s | %s | %.2f [%.2f, %.2f] | %.3f |' % (LABEL[s], ENG[e], p['utts'], pct(p['cer_base']), pct(p['cer']), p['ratio'], p['ratio_ci'][0], p['ratio_ci'][1], p['p_not_better']))
    L.append('')
    L.append('### 표 3. 초벌 (다)와 확정 (가)/(나)의 숫자·이름 불일치(정답과 무관하게 두 자막끼리)')
    L.append('')
    L.append('| 묶음 | 확정 | 확정의 숫자 토큰 | 초벌에 없는 것 | 불일치율 | 이름 토큰 | 이름 불일치 | 불일치가 1개 이상인 통화 |')
    L.append('| --- | --- | --- | --- | --- | --- | --- | --- |')
    for s, d in S.items():
        for k, v in d['draft_final'].items():
            L.append('| %s | %s | %d | %d | %s%% | %d | %d | %d/%d |' % (LABEL[s], ENG[k.split('_')[0]], v['number_tokens'], v['number_mismatch'], pct(v['number_mismatch_rate']),
                                                                  v['name_tokens'], v['name_mismatch'], v['calls_with_mismatch'], v['calls']))
    L.append('')
    L.append('### 표 4. 기준 자막 방법(정답이 있는 묶음에서, 모든 엔진이 끝낸 녹음만)')
    L.append('')
    L.append('| 묶음 | 기준 자막 | 대조 모델 | 분 | 기준 자막 오류율 % | 기준 자막 숫자 오류 | 그중 대조 모델이 맞혀 드러난 것 | 불일치 구간/분 (숫자 구간/분) | 볼 글자/분 | 불일치로 잡힌 오류 글자 비율 | 다 고친 뒤 남는 오류 %(전부 볼 때) |')
    L.append('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |')
    for s, d in S.items():
        for k, v in d['reference'].items():
            if v['ref'] not in ('lv3', 'q5', 'q8'):
                continue
            if v['check_against'] not in (None, 'sv', 'q5', 'q8', 'lv3'):
                continue
            ne = [v['number_errors'][1] - v['number_errors'][0], v['number_errors'][1]]
            if v['check_against'] is None:
                L.append('| %s | %s | — | %.1f | %s | %s | — | — | — | — | %s |' % (LABEL[s], ENG[v['ref']], v['audio_min'], pct(v['cer']), frac([v['number_errors'][0], v['number_errors'][1]]), pct(v['cer'])))
            else:
                L.append('| %s | %s | %s | %.1f | %s | %s | %d/%d | %.1f (%.1f) | %.0f | %s%% | %s |' % (LABEL[s], ENG[v['ref']], ENG[v['check_against']], v['audio_min'], pct(v['cer']),
                         frac([v['number_errors'][0], v['number_errors'][1]]), v['number_errors_caught'][0], v['number_errors_caught'][1],
                         v['spans_per_min'] or 0, v['number_spans_per_min'] or 0, v['check_chars_per_min'] or 0, pct(v['flagged_share_of_errors']), pct(v['residual_err_char_rate'])))
    L.append('')
    text = '\n'.join(L)
    doc = open(a.doc, encoding='utf-8').read()
    doc = re.sub(r'<!-- tables:start -->.*<!-- tables:end -->', lambda m: '<!-- tables:start -->\n' + text + '\n<!-- tables:end -->', doc, flags=re.S)
    open(a.doc, 'w', encoding='utf-8').write(doc)
    print('tables', len(L), 'lines')


if __name__ == '__main__':
    main()
