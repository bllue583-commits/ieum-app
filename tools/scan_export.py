"""Local export guard. Print file/line/reason only, never matching secret values.

This is a bounded pattern/filename scan, not proof that arbitrary secrets cannot exist.
Review fixtures, binary origins and staged diff separately (docs/MIGRATION.md).
"""
from pathlib import Path
import json, re, subprocess, sys
root=Path(__file__).resolve().parent.parent
skip={'.git','.gradle','build','.cxx','gradle-user','android-user','runtime','node_modules','__pycache__','external-fixtures','evidence','deliverables','.venv'}
result=subprocess.run(['git','-C',str(root),'ls-files','-z','--cached'],capture_output=True)
files=[root/x.decode() for x in result.stdout.split(b'\0') if x] if result.returncode==0 and result.stdout else [p for p in root.rglob('*') if p.is_file() and not (set(p.relative_to(root).parts)&skip)]
patterns={
 'private_key':r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----',
 'github_credential':r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b',
 'aws_access_key':r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b',
 'openai_credential':r'\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{30,}',
 'google_api_key':r'\bAIza[0-9A-Za-z_-]{30,}',
 'embedded_url_auth':r'https?://[^/\s:]+:[^@\s]+@',
 'home_path':r'(?:/' + 'Users' + r'/[^/\s]+/|[A-Za-z]:\\' + 'Users' + r'\\[^\\\s]+\\)',
}
blocked_ext={'.apk','.aab','.db','.sqlite','.keystore','.jks','.p12','.pfx','.pem','.key','.bin','.so','.dll','.dylib'}
findings=[];bytes_total=0
for path in files:
 rel=str(path.relative_to(root))
 if not path.exists():findings.append({'path':rel,'reason':'MISSING_TRACKED_FILE'});continue
 if path.is_symlink():findings.append({'path':rel,'reason':'SYMLINK_REVIEW_REQUIRED'});continue
 bytes_total+=path.stat().st_size
 if path.suffix.lower() in blocked_ext or path.name=='.env' or path.name.startswith('.env.'):
  findings.append({'path':rel,'reason':'EXCLUDED_FILE_KIND'})
 if path.stat().st_size>10*1024*1024:findings.append({'path':rel,'reason':'LARGE_FILE'})
 try:content=path.read_text(encoding='utf-8')
 except UnicodeError:continue
 for line_no,line in enumerate(content.splitlines(),1):
  for code,pattern in patterns.items():
   if re.search(pattern,line):findings.append({'path':rel,'line':line_no,'reason':code})
print(json.dumps({'scope':'TRACKED_FILES' if result.returncode==0 and result.stdout else 'EXPORT_CANDIDATES','files':len(files),'bytes':bytes_total,'findings':findings,'limitation':'Pattern scan plus manual fixture/binary review; no entropy-based detector'},indent=2))
sys.exit(bool(findings))
