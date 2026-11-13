"""Build with an explicitly prepared JDK/SDK; outputs remain in this checkout."""
from pathlib import Path
import argparse, json, os, subprocess, sys

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--log', required=True)
parser.add_argument('--offline', action='store_true')
parser.add_argument('tasks', nargs=argparse.REMAINDER)
args = parser.parse_args()
if not args.tasks:
    parser.error('Gradle tasks required')
env = os.environ.copy()
for variable in ('JAVA_HOME', 'ANDROID_SDK_ROOT'):
    if not env.get(variable):
        parser.error(variable + ' must point to your prepared local toolchain')
env['ANDROID_HOME'] = env['ANDROID_SDK_ROOT']
env['ANDROID_USER_HOME'] = str(root / 'android-user')
env.setdefault('GRADLE_USER_HOME', str(root / 'gradle-user'))
env['PATH'] = str(Path(env['JAVA_HOME']) / 'bin') + os.pathsep + env.get('PATH', '')
env['GRADLE_OPTS'] = env.get('GRADLE_OPTS', '') + ' -Duser.home=' + str(root / 'android-user')
if env.get('IEUM_SKIP_ANDROID_NATIVE') != '1':
    subprocess.run([sys.executable, str(root / 'tools/build_android_whisper.py')], cwd=root, env=env, check=True)
    # r18-bench: the engine bench's sherpa-onnx libraries (official AAR, arm64-v8a), loaded only while the bench runs.
    if env.get('IEUM_SKIP_SHERPA') != '1':
        subprocess.run([sys.executable, str(root / 'tools/fetch_sherpa_onnx.py')], cwd=root, env=env, check=True)
log = root / 'evidence' / args.log
log.parent.mkdir(parents=True, exist_ok=True)
if log.exists():
    parser.error('Log already exists; choose a new label')
gradle = env.get('IEUM_GRADLE_BINARY')
if gradle:
    command = [gradle]
elif os.name == 'nt':
    command = ['cmd', '/c', str(root / 'android/gradlew.bat')]
else:
    command = ['sh', str(root / 'android/gradlew')]
command += ['--no-daemon', '--console=plain', '-Pandroid.builder.sdkDownload=false']
if args.offline:
    command += ['--offline']
if env.get('IEUM_EXISTING_DEBUG_KEYSTORE'):
    command += ['-PeumExistingDebugKeystore=' + env['IEUM_EXISTING_DEBUG_KEYSTORE']]
command += args.tasks
with log.open('x', encoding='utf-8') as output:
    result = subprocess.run(command, cwd=root / 'android', env=env, stdout=output, stderr=subprocess.STDOUT)
print(json.dumps({'exit_code': result.returncode, 'log': str(log), 'tasks': args.tasks}))
raise SystemExit(result.returncode)
