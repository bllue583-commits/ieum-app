"""tools/setup_pipeline.py: gh and node are mocked; secrets must never reach argv or stdout."""
import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'tools'))
import setup_pipeline as sp  # noqa: E402

KEYS = {'signing': {'publicKey': 'PUBSIGN_fake_public', 'privateKey': 'PRIVSIGN_fake_secret_value'},
        'encryption': {'publicKey': 'PUBENC_fake_public', 'privateKey': 'PRIVENC_fake_secret_value'}}
ANTHROPIC = 'sk-ant-FAKE-test-secret'
INBOX_TOKEN = 'github_pat_FAKE_test_secret'
SECRETS = (KEYS['signing']['privateKey'], KEYS['encryption']['privateKey'], ANTHROPIC, INBOX_TOKEN)


class FakeRunner:
    def __init__(self, repo_exists=False, secrets=()):
        self.calls, self.repo_exists, self.secrets = [], repo_exists, set(secrets)

    def run(self, argv, stdin=None, check=True):
        self.calls.append((list(argv), stdin))
        out, code = '', 0
        if argv[0].endswith('node'):
            out = json.dumps(KEYS)
        elif argv[:3] == ['gh', 'repo', 'view']:
            code = 0 if self.repo_exists else 1
        elif argv[:3] == ['gh', 'secret', 'list']:
            out = json.dumps([{'name': n} for n in sorted(self.secrets)])
        elif argv[:3] == ['gh', 'secret', 'set']:
            self.secrets.add(argv[3])
        elif argv[:3] == ['gh', 'api', f'repos/{sp.DEFAULT_INBOX_REPO}/contents/README.md']:
            out = 'abc123\n'
        if check and code:
            raise sp.SetupError('failed')
        return subprocess.CompletedProcess(argv, code, out, '')

    def argv_text(self):
        return '\n'.join(' '.join(a) for a, _ in self.calls)

    def secret_sets(self):
        return {a[3]: stdin for a, stdin in self.calls if a[:3] == ['gh', 'secret', 'set']}


class SetupPipelineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.config = Path(self.tmp.name) / 'config' / 'pipeline.json'
        self.config.parent.mkdir()
        self.config.write_text(json.dumps({'note': 'n', 'inboxRepository': sp.DEFAULT_INBOX_REPO, 'serverSigningKey': '', 'serverEncryptionKey': ''}))
        patches = [mock.patch.object(sp, 'CONFIG', self.config), mock.patch.object(sp.shutil, 'which', lambda name: '/usr/bin/' + name)]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        self.addCleanup(self.tmp.cleanup)

    def invoke(self, argv, runner, answers=(ANTHROPIC, INBOX_TOKEN)):
        answers = list(answers)
        prompts = []

        def ask(prompt):
            prompts.append(prompt)
            return answers.pop(0)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = sp.main(argv, runner=runner, ask=ask)
        return code, out.getvalue(), prompts

    def assert_no_leak(self, runner, stdout):
        for s in SECRETS:
            self.assertNotIn(s, runner.argv_text())
            self.assertNotIn(s, stdout)
            self.assertNotIn(s, self.config.read_text())

    def test_first_run_creates_repo_sets_secrets_via_stdin_and_writes_public_keys(self):
        runner = FakeRunner()
        code, out, prompts = self.invoke([], runner)
        self.assertEqual(code, 0)
        self.assertIn(['gh', 'repo', 'create', sp.DEFAULT_INBOX_REPO, '--private', '--add-readme', '--description', 'ieum pipeline inbox (encrypted, private)'], [a for a, _ in runner.calls])
        sets = runner.secret_sets()
        self.assertEqual(sets, {'IEUM_SERVER_SIGNING_KEY': KEYS['signing']['privateKey'], 'IEUM_SERVER_ENCRYPTION_KEY': KEYS['encryption']['privateKey'],
                                'ANTHROPIC_API_KEY': ANTHROPIC, 'IEUM_INBOX_TOKEN': INBOX_TOKEN})
        for argv, _ in runner.calls:
            if argv[:3] == ['gh', 'secret', 'set']:
                self.assertEqual(argv, ['gh', 'secret', 'set', argv[3], '--repo', sp.DEFAULT_APP_REPO])
        cfg = json.loads(self.config.read_text())
        self.assertEqual((cfg['serverSigningKey'], cfg['serverEncryptionKey']), ('PUBSIGN_fake_public', 'PUBENC_fake_public'))
        self.assertEqual(cfg['note'], 'n')
        self.assertEqual(len(prompts), 2)
        self.assertIn('다음 단계', out)
        self.assertIn('Contents: Read and write', out)
        self.assert_no_leak(runner, out)
        node = [a for a, _ in runner.calls if a[0].endswith('node')]
        self.assertEqual(len(node), 1)
        self.assertIn('newKeyPair', node[0][-1])

    def test_second_run_is_idempotent_and_refuses_to_regenerate_keys(self):
        runner = FakeRunner()
        self.invoke([], runner)
        before = self.config.read_text()
        again = FakeRunner(repo_exists=True, secrets=runner.secrets)
        code, out, prompts = self.invoke([], again, answers=())
        self.assertEqual(code, 0)
        self.assertEqual(self.config.read_text(), before)
        self.assertEqual(again.secret_sets(), {})
        self.assertEqual(prompts, [])
        self.assertFalse(any(a[0].endswith('node') for a, _ in again.calls))
        self.assertFalse(any(a[:3] == ['gh', 'repo', 'create'] for a, _ in again.calls))
        self.assertIn('--rotate', out)

    def test_rotate_replaces_keys(self):
        self.config.write_text(json.dumps({'note': 'n', 'inboxRepository': sp.DEFAULT_INBOX_REPO, 'serverSigningKey': 'OLD', 'serverEncryptionKey': 'OLD'}))
        runner = FakeRunner(repo_exists=True, secrets={'ANTHROPIC_API_KEY', 'IEUM_INBOX_TOKEN', *sp.KEY_SECRETS})
        code, out, prompts = self.invoke(['--rotate'], runner, answers=())
        self.assertEqual(code, 0)
        self.assertEqual(set(runner.secret_sets()), set(sp.KEY_SECRETS))
        self.assertEqual(json.loads(self.config.read_text())['serverSigningKey'], 'PUBSIGN_fake_public')
        self.assertEqual(prompts, [])
        self.assert_no_leak(runner, out)

    def test_dry_run_changes_nothing(self):
        before = self.config.read_text()
        runner = FakeRunner()
        code, out, prompts = self.invoke(['--dry-run'], runner, answers=())
        self.assertEqual(code, 0)
        self.assertEqual(self.config.read_text(), before)
        self.assertEqual(prompts, [])
        mutating = [a for a, _ in runner.calls if a[:3] in (['gh', 'repo', 'create'], ['gh', 'secret', 'set'], ['gh', 'variable', 'set']) or a[0].endswith('node') or '-X' in a]
        self.assertEqual(mutating, [])
        self.assertIn('[dry-run] create private repository', out)
        self.assertIn('[dry-run] generate server signing and encryption key pairs', out)

    def test_update_secrets_and_custom_inbox_variable(self):
        self.config.write_text(json.dumps({'note': 'n', 'inboxRepository': 'someone/other-inbox', 'serverSigningKey': 'K', 'serverEncryptionKey': 'K'}))
        runner = FakeRunner(repo_exists=True, secrets={'ANTHROPIC_API_KEY', 'IEUM_INBOX_TOKEN', *sp.KEY_SECRETS})
        code, out, prompts = self.invoke(['--update-secrets'], runner)
        self.assertEqual(code, 0)
        self.assertEqual(set(runner.secret_sets()), {'ANTHROPIC_API_KEY', 'IEUM_INBOX_TOKEN'})
        self.assertIn(['gh', 'variable', 'set', 'IEUM_INBOX_REPOSITORY', '--repo', sp.DEFAULT_APP_REPO, '--body', 'someone/other-inbox'], [a for a, _ in runner.calls])
        self.assert_no_leak(runner, out)

    def test_gh_missing_or_failing_reports_error_without_secrets(self):
        with mock.patch.object(sp.shutil, 'which', lambda name: None):
            code, out, _ = self.invoke([], FakeRunner(), answers=())
        self.assertEqual(code, 1)
        self.assertIn('gh', out)

        class Failing(FakeRunner):
            def run(self, argv, stdin=None, check=True):
                if argv[:3] == ['gh', 'secret', 'set']:
                    raise sp.SetupError('command failed (1): gh secret')
                return super().run(argv, stdin, check)
        runner = Failing(repo_exists=True)
        code, out, _ = self.invoke([], runner, answers=())
        self.assertEqual(code, 1)
        self.assertIn('ERROR', out)
        self.assertEqual(json.loads(self.config.read_text())['serverSigningKey'], '')
        self.assert_no_leak(runner, out)

    def test_real_runner_error_message_never_contains_stdin(self):
        with self.assertRaises(sp.SetupError) as ctx:
            sp.Runner().run([sys.executable, '-c', 'import sys; sys.stdin.read(); sys.exit(3)'], stdin=ANTHROPIC)
        self.assertNotIn(ANTHROPIC, str(ctx.exception))
        with self.assertRaises(sp.SetupError):
            sp.Runner().run(['/nonexistent/ieum-tool'])


if __name__ == '__main__':
    unittest.main()
