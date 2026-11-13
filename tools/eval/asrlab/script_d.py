"""묶음 D: 지어낸 업무 통화 대본(숫자·이름). 실제 사람·업체 이름이나 실제 번호는 쓰지 않는다:
- 전화번호는 가운데 자리가 0으로 시작하는 형식(010-0xxx-xxxx, 070-0xxx-xxxx) — 국내에서 배정되지 않는 자리.
- 업체 이름은 뜻 있는 낱말 둘을 붙여 지어낸 것(실제 상호와 겹치는지 검색하지 않았다 — 겹쳐도 대본에서는 지어낸 이름이다).
- 사람 이름은 성 + 지어낸 두 글자.
정답(ref)은 사람이 받아 적듯 숫자로 쓰고, TTS에는 한국어로 읽는 꼴(spoken)을 넣는다. 둘은 ko_cer 정규화에서 같은 값이 된다.
python3 -I tools/eval/asrlab/script_d.py <출력 json>  (고정 시드)"""
import json
import random
import sys

DIG = '영일이삼사오육칠팔구'
NATIVE_H = {1: '한', 2: '두', 3: '세', 4: '네', 5: '다섯', 6: '여섯', 7: '일곱', 8: '여덟', 9: '아홉', 10: '열', 11: '열한', 12: '열두'}


def sino4(n):
    """0 < n < 10000"""
    out = ''
    for unit, v in (('천', 1000), ('백', 100), ('십', 10), ('', 1)):
        d = n // v % 10
        if d:
            out += ('' if d == 1 and unit else DIG[d]) + unit
    return out


def sino(n):
    if n == 0:
        return '영'
    parts = []
    eok, rest = divmod(n, 10 ** 8)
    man, one = divmod(rest, 10 ** 4)
    if eok:
        parts.append(sino4(eok) + '억')
    if man:
        parts.append(('' if man == 1 else sino4(man)) + '만')
    if one:
        parts.append(sino4(one))
    return ' '.join(parts)


def digits_spoken(s):
    return ''.join('공' if c == '0' else DIG[int(c)] for c in s)


def won(n):
    """금액: 정답은 '125만 원'/'3,500원', 읽기는 '백이십오만 원'."""
    if n >= 10000 and n % 10000 == 0:
        return '%d만 원' % (n // 10000), sino(n) + ' 원'
    return '{:,}원'.format(n), sino(n) + ' 원'


SURNAMES = ['한', '윤', '서', '남궁', '봉', '탁', '어', '제갈', '피', '모']
GIVEN = ['도결', '이솔', '라온', '해담', '시우람', '채겸', '누리안', '보람솔', '다엘', '하운']
ROLES = ['과장', '대리', '팀장', '실장', '주임', '부장']
COMP_A = ['새벽별', '누리솔', '푸른결', '한빛길', '바람꽃', '은하수', '가람숲', '별무리', '도담', '해오름']
COMP_B = ['정밀', '상사', '테크', '물류', '산업', '로지텍', '기공', '유통']
ITEMS = ['감속기', '모터 드라이버', '센서 모듈', '배터리 팩', '충전 거치대', '바퀴 조립품', '브래킷', '케이블 묶음']
DOCS = ['견적서', '세금계산서', '거래명세서', '발주서', '납품확인서']


def make(seed=1913, n=80):
    rnd = random.Random(seed)
    out = []

    def person():
        s = rnd.choice(SURNAMES)
        g = rnd.choice(GIVEN)
        return s + g, rnd.choice(ROLES)

    def company():
        return rnd.choice(COMP_A) + ' ' + rnd.choice(COMP_B)

    def date():
        m = rnd.randint(1, 12)
        d = rnd.randint(1, 28)
        return '%d월 %d일' % (m, d), '%s월 %s일' % ({6: '유', 10: '시'}.get(m, sino(m)), sino(d))

    def time_():
        h = rnd.randint(1, 12)
        mi = rnd.choice([0, 10, 20, 30, 40, 50])
        ap = rnd.choice(['오전', '오후'])
        if mi:
            return '%s %d시 %d분' % (ap, h, mi), '%s %s 시 %s 분' % (ap, NATIVE_H[h], sino(mi))
        return '%s %d시' % (ap, h), '%s %s 시' % (ap, NATIVE_H[h])

    def qty():
        q = rnd.choice([3, 5, 12, 20, 40, 150, 200, 1200])
        return '%d개' % q, sino(q) + ' 개'

    def phone():
        head = rnd.choice(['010', '070'])
        mid = '0' + ''.join(str(rnd.randint(0, 9)) for _ in range(3))
        tail = ''.join(str(rnd.randint(0, 9)) for _ in range(4))
        return '%s-%s-%s' % (head, mid, tail), '%s, %s, %s' % (digits_spoken(head), digits_spoken(mid), digits_spoken(tail))

    def money():
        n = rnd.choice([rnd.randint(1, 99) * 10000, rnd.randint(1, 30) * 100000, rnd.randint(100, 999) * 1000, rnd.randint(2, 9) * 1000000 + rnd.randint(1, 9) * 100000])
        return won(n)

    templates = [
        lambda: (lambda c, p, r: ('안녕하세요, %s %s %s입니다.' % (c, p, r), '안녕하세요, %s %s %s입니다.' % (c, p, r), [c, p]))(company(), *person()),
        lambda: (lambda m, d: ('%s 단가는 %s으로 맞춰 드릴게요.' % (rnd.choice(ITEMS), m[0]), '%s 단가는 %s으로 맞춰 드릴게요.' % ('{}', m[1]), []))(money(), None),
        lambda: (lambda d, t: ('납품은 %s %s까지 가능합니다.' % (d[0], t[0]), '납품은 %s %s까지 가능합니다.' % (d[1], t[1]), []))(date(), time_()),
        lambda: (lambda q, doc: ('%s %s 기준으로 %s 다시 보내 주세요.' % (rnd.choice(ITEMS), q[0], doc), '%s %s 기준으로 %s 다시 보내 주세요.' % ('{}', q[1], doc), []))(qty(), rnd.choice(DOCS)),
        lambda: (lambda ph: ('제 번호는 %s입니다.' % ph[0], '제 번호는 %s입니다.' % ph[1], []))(phone()),
        lambda: (lambda m, doc: ('%s 금액이 %s으로 잘못 나왔어요.' % (doc, m[0]), '%s 금액이 %s으로 잘못 나왔어요.' % (doc, m[1]), []))(money(), rnd.choice(DOCS)),
        lambda: (lambda c, d: ('%s 쪽 %s 발행은 %s에 할게요.' % (c, rnd.choice(DOCS), d[0]), '%s 쪽 %s 발행은 %s에 할게요.' % (c, '{}', d[1]), [c]))(company(), date()),
        lambda: (lambda p, r, t: ('%s %s님께 %s에 다시 전화드린다고 전해 주세요.' % (p, r, t[0]), '%s %s님께 %s에 다시 전화드린다고 전해 주세요.' % (p, r, t[1]), [p]))(*person(), time_()),
        lambda: (lambda q, m: ('샘플 %s 먼저 보내 주시면 %s 선금으로 넣겠습니다.' % (q[0], m[0]), '샘플 %s 먼저 보내 주시면 %s 선금으로 넣겠습니다.' % (q[1], m[1]), []))(qty(), money()),
        lambda: (lambda d: ('네, 그럼 %s 오전에 다시 확인하겠습니다.' % d[0], '네, 그럼 %s 오전에 다시 확인하겠습니다.' % d[1], []))(date()),
    ]
    for i in range(n):
        ref, spoken, names = templates[i % len(templates)]()
        if '{}' in spoken:
            # 같은 낱말을 정답과 읽기에 같이 넣는다(템플릿 안 rnd.choice가 두 번 불리지 않게)
            word = [w for w in ITEMS + DOCS if w in ref]
            spoken = spoken.replace('{}', word[0] if word else '')
        out.append(dict(id='D%03d' % i, ref=ref, spoken=spoken, names=names))
    return out


if __name__ == '__main__':
    d = make()
    json.dump(d, open(sys.argv[1], 'w'), ensure_ascii=False, indent=1)
    print(len(d), 'sentences')
