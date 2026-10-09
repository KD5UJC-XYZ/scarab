#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate native diagnostics and write bounded TSV for the actual Scala model.
This is transport only: no Python floating-point implementation or parity claim.
"""
import argparse,hashlib
from pathlib import Path
from validate_result import load,validate,read_bounded,decode
HERE=Path(__file__).resolve().parent

def prepare(result,output):
    cases=load(HERE/'cases.json'); rawResult=read_bounded(result); native=decode(rawResult);validate(native,cases)
    lines=['non-myopic-comparison-v1\t'+native['inputSha256']+'\t'+hashlib.sha256(rawResult).hexdigest()]
    def rows(tag,values):
        for v in values:lines.append(tag+'\t'+v['pool']+'\t'+','.join(v['weights']))
    for c,n in zip(cases['cases'],native['cases']):
        lines.append('CASE\t'+c['id']+'\t'+c['oldPot']+'\t'+c['pot']+'\t'+str(c['scalaAdmitted']).lower())
        rows('HISTORY',c['history']);rows('FRESH',c['fresh']);rows('AFTER',n['after']['likelihoods'])
        lines.append('POT\t'+n['after']['rewardPot']);lines.append('END')
    for e in native['emptyProbes']:
        lines.append('EMPTY\t'+e['fees']+'\t'+e['completed']['rewardPot']+'\t'+e['applied']['rewardPot'])
    lines.append('GENERATION_DIAGNOSTIC_ONLY\t3')
    raw=('\n'.join(lines)+'\n').encode('ascii')
    if len(raw)>1048576:raise ValueError('comparison size')
    with Path(output).open('xb') as f:f.write(raw)
    return hashlib.sha256(raw).hexdigest()

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('result',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();print(prepare(a.result,a.output))
