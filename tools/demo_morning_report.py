"""Run the synthetic host demo using the existing prepared Android/JDK/Gradle tools.

No account login, Android device, model, SMS/mail access or external send is used.
Requires the README host tool environment and tools/prepare_host_tests.py first.
"""
import argparse
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--log', default='morning-demo.log', help='New evidence log name; never overwritten')
args = parser.parse_args()
env = os.environ.copy()
env['IEUM_SKIP_ANDROID_NATIVE'] = '1'
result = subprocess.run([sys.executable, str(root / 'run_gradle.py'), '--offline', '--log', args.log,
                         ':app:testDebugUnitTest', '--tests', 'kr.callbrief.MorningReportRuntimeTest.localDemo',
                         '--rerun-tasks'], cwd=root, env=env)
if result.returncode:
    raise SystemExit(result.returncode)
output = root / 'android/app/build/reports/morning-demo.txt'
if not output.is_file():
    raise SystemExit('DEMO_OUTPUT_MISSING')
print(output.read_text(encoding='utf-8'))
print('Synthetic host demo output:', output)
