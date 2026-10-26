import argparse,json
from pathlib import Path
from .adapter import Held,parse,probe,transcribe,package_prior_observed

p=argparse.ArgumentParser();p.add_argument('mode',choices=['probe','run','package-prior-observed']);p.add_argument('--configuration',type=Path,required=True);p.add_argument('--job');p.add_argument('--output',type=Path);p.add_argument('--prior-dir',type=Path);a=p.parse_args()
try:
    c=parse(a.configuration.read_bytes(),16384)
    if a.mode=='probe':r=probe(c)
    else:
        if not a.job or not a.output:p.error('--job and --output are required')
        if a.mode=='run':r=transcribe(c,a.job,a.output)
        else:
            if not a.prior_dir:p.error('--prior-dir is required')
            r=package_prior_observed(c,a.job,a.prior_dir,a.output)
    print(json.dumps(r,ensure_ascii=False,sort_keys=True))
except Held as e:
    print(json.dumps({'state':'HELD','code':str(e),'cloud_uploaded':False,'scheduler_created':False}));raise SystemExit(2)
