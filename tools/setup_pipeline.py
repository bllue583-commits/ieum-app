#!/usr/bin/env python3
"""One-time setup of the ieum daily pipeline (docs/pipeline-server.md). Standard library only.

Run from the ieum-app checkout on your PC with the GitHub CLI logged in (gh auth login):

    python3 tools/setup_pipeline.py [--dry-run] [--rotate] [--update-secrets]

- creates the private inbox repository (default bllue583-commits/ieum-inbox) with a README if missing
- generates the server signing and encryption key pairs with worker/src/pipeline/contract.mjs newKeyPair
- stores the private keys as ieum-app Actions secrets (values go through stdin, never argv, never printed)
- asks (getpass) for the Anthropic API key and the inbox token of the Actions workflow and stores them
- writes the two PUBLIC keys into config/pipeline.json (commit that file afterwards)

It refuses to regenerate keys when config/pipeline.json already has them unless --rotate is given.
"""
import argparse
import base64
import getpass
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / 'config' / 'pipeline.json'
CONTRACT = ROOT / 'worker' / 'src' / 'pipeline' / 'contract.mjs'
DEFAULT_APP_REPO = 'bllue583-commits/ieum-app'
DEFAULT_INBOX_REPO = 'bllue583-commits/ieum-inbox'
KEY_SECRETS = ('IEUM_SERVER_SIGNING_KEY', 'IEUM_SERVER_ENCRYPTION_KEY')
PROMPTED_SECRETS = (
    ('ANTHROPIC_API_KEY', 'Anthropic API 키 (console.anthropic.com, 입력은 화면에 보이지 않습니다): '),
    ('IEUM_INBOX_TOKEN', 'Actions용 ieum-inbox 토큰 (fine-grained, Contents 읽기/쓰기, 입력은 보이지 않습니다): '),
)
INBOX_README = """# ieum-inbox (private)

이음 앱과 ieum-app의 매일 분석 작업(GitHub Actions)이 주고받는 암호화 파일 저장소입니다.
모든 내용은 봉인(ieum.sealed.v1)되어 있으며 음성 파일은 올라오지 않습니다.
직접 수정하지 마세요. 매주 월요일 첫 실행에서 기록이 하나의 commit으로 다시 쓰입니다.
"""


class SetupError(Exception):
    pass


class Runner:
    """Runs external commands. Secrets are passed only through `stdin`; output is captured, never echoed."""

    def run(self, argv, stdin=None, check=True):
        try:
            p = subprocess.run(argv, input=stdin, capture_output=True, text=True, cwd=ROOT)
        except FileNotFoundError:
            if check:
                raise SetupError(f'command not found: {argv[0]}') from None
            return subprocess.CompletedProcess(argv, 127, '', '')
        if check and p.returncode != 0:
            raise SetupError(f'command failed ({p.returncode}): {argv[0]} {argv[1] if len(argv) > 1 else ""}')
        return p


def say(msg=''):
    print(msg, flush=True)


def load_config():
    try:
        return json.loads(CONFIG.read_text(encoding='utf-8'))
    except FileNotFoundError:
        return {'note': 'Public configuration only. Server public keys are written by tools/setup_pipeline.py; private keys live in GitHub Actions secrets.',
                'inboxRepository': DEFAULT_INBOX_REPO, 'serverSigningKey': '', 'serverEncryptionKey': ''}


def write_config(cfg):
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    CONFIG.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def generate_keys(runner):
    script = ("import {newKeyPair} from " + json.dumps(CONTRACT.as_uri()) + ";"
              "process.stdout.write(JSON.stringify({signing: newKeyPair(), encryption: newKeyPair()}));")
    node = shutil.which('node') or 'node'
    out = runner.run([node, '--input-type=module', '-e', script]).stdout
    try:
        keys = json.loads(out)
        for k in ('signing', 'encryption'):
            assert keys[k]['publicKey'] and keys[k]['privateKey']
    except Exception:
        raise SetupError('key generation returned unexpected output') from None
    return keys


def existing_secrets(runner, repo):
    p = runner.run(['gh', 'secret', 'list', '--repo', repo, '--json', 'name'], check=False)
    if p.returncode != 0:
        return None
    try:
        return {s['name'] for s in json.loads(p.stdout or '[]')}
    except ValueError:
        return None


def set_secret(runner, repo, name, value):
    runner.run(['gh', 'secret', 'set', name, '--repo', repo], stdin=value)


def main(argv=None, runner=None, ask=getpass.getpass):
    ap = argparse.ArgumentParser(description='Set up the ieum daily pipeline (inbox repo, server keys, Actions secrets).')
    ap.add_argument('--dry-run', action='store_true', help='print the planned actions without changing anything')
    ap.add_argument('--rotate', action='store_true', help='generate new server keys even if config/pipeline.json has them')
    ap.add_argument('--update-secrets', action='store_true', help='ask again for ANTHROPIC_API_KEY and IEUM_INBOX_TOKEN')
    ap.add_argument('--app-repo', default=DEFAULT_APP_REPO)
    ap.add_argument('--inbox-repo', default=None, help='default: inboxRepository in config/pipeline.json')
    args = ap.parse_args(argv)
    runner = runner or Runner()
    cfg = load_config()
    inbox = args.inbox_repo or cfg.get('inboxRepository') or DEFAULT_INBOX_REPO
    app = args.app_repo
    dry = args.dry_run
    plan = (lambda m: say('[dry-run] ' + m)) if dry else (lambda m: say('- ' + m))

    try:
        if shutil.which('gh') is None and not dry:
            raise SetupError('GitHub CLI (gh) not found. Install it and run: gh auth login')
        if not dry:
            runner.run(['gh', 'auth', 'status'])

        # 1. inbox repository
        if runner.run(['gh', 'repo', 'view', inbox, '--json', 'name'], check=False).returncode == 0:
            say(f'inbox repository exists: {inbox}')
        else:
            plan(f'create private repository {inbox} with a README')
            if not dry:
                runner.run(['gh', 'repo', 'create', inbox, '--private', '--add-readme', '--description', 'ieum pipeline inbox (encrypted, private)'])
                # replace the generated README with ours (content via stdin JSON, no secrets involved)
                p = runner.run(['gh', 'api', f'repos/{inbox}/contents/README.md', '--jq', '.sha'], check=False)
                body = {'message': 'Describe the ieum inbox', 'content': base64.b64encode(INBOX_README.encode()).decode()}
                if p.returncode == 0 and p.stdout.strip():
                    body['sha'] = p.stdout.strip()
                runner.run(['gh', 'api', '-X', 'PUT', f'repos/{inbox}/contents/README.md', '--input', '-'], stdin=json.dumps(body), check=False)

        # 2. server keys
        have_keys = bool(cfg.get('serverSigningKey')) and bool(cfg.get('serverEncryptionKey'))
        secrets = existing_secrets(runner, app)
        if have_keys and not args.rotate:
            say('server keys already in config/pipeline.json; not regenerating (use --rotate to replace them)')
            if secrets is not None and not set(KEY_SECRETS) <= secrets:
                say('WARNING: the key secrets are missing on ' + app + '. Private keys cannot be recovered; run again with --rotate.')
        else:
            if args.rotate and have_keys:
                plan('ROTATE: new server keys; the app must be rebuilt with the new config/pipeline.json')
            plan(f'generate server signing and encryption key pairs; set {", ".join(KEY_SECRETS)} on {app}; write public keys to config/pipeline.json')
            if not dry:
                keys = generate_keys(runner)
                set_secret(runner, app, 'IEUM_SERVER_SIGNING_KEY', keys['signing']['privateKey'])
                set_secret(runner, app, 'IEUM_SERVER_ENCRYPTION_KEY', keys['encryption']['privateKey'])
                cfg['inboxRepository'] = inbox
                cfg['serverSigningKey'] = keys['signing']['publicKey']
                cfg['serverEncryptionKey'] = keys['encryption']['publicKey']
                write_config(cfg)
                keys = None
                say('server keys stored as secrets; public keys written to config/pipeline.json')

        # 3. prompted secrets
        for name, prompt in PROMPTED_SECRETS:
            if secrets is not None and name in secrets and not args.update_secrets:
                say(f'{name} already set on {app} (use --update-secrets to replace it)')
                continue
            plan(f'ask for {name} and set it on {app}')
            if dry:
                continue
            value = ask(prompt).strip()
            if not value:
                say(f'WARNING: empty input, {name} not set')
                continue
            set_secret(runner, app, name, value)
            value = None
            say(f'{name} set on {app}')

        # 4. inbox repository variable when not the default
        if inbox != DEFAULT_INBOX_REPO:
            plan(f'set Actions variable IEUM_INBOX_REPOSITORY={inbox} on {app}')
            if not dry:
                runner.run(['gh', 'variable', 'set', 'IEUM_INBOX_REPOSITORY', '--repo', app, '--body', inbox])
    except SetupError as e:
        say('ERROR: ' + str(e))
        return 1

    say()
    say('다음 단계:')
    say('1. config/pipeline.json(공개 키만 들어 있음)을 commit하고 push하세요. 앱은 이 공개 키로 빌드됩니다.')
    say('   git add config/pipeline.json && git commit -m "Set pipeline server public keys" && git push')
    say('2. GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens에서 토큰 2개를 만드세요.')
    say(f'   - 휴대폰용: Repository access "Only select repositories" → {inbox} 하나만, Permissions → Contents: Read and write. 앱 설정에 입력합니다.')
    say(f'   - Actions용: 같은 설정({inbox} 하나만, Contents: Read and write). 이 스크립트의 IEUM_INBOX_TOKEN 입력에 사용합니다.')
    say('   두 토큰은 만료일을 정하고, 다른 저장소 권한은 주지 마세요. 토큰을 파일·채팅·commit에 남기지 마세요.')
    say(f'3. 시험 실행: gh workflow run ieum-daily.yml --repo {app}  (결과: gh run list --workflow ieum-daily.yml --repo {app})')
    say('4. 매일 05:07(재시도 05:37) KST에 자동 실행됩니다. 자세한 운영 안내: docs/pipeline-server.md')
    return 0


if __name__ == '__main__':
    sys.exit(main())
