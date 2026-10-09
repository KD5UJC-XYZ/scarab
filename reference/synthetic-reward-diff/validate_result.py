# SPDX-License-Identifier: Apache-2.0
"""Strict finite result transport validator; no native reward expectations."""
import hashlib, json, math, re, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parent

def load(path):
    raw = Path(path).read_bytes()
    if len(raw) > 1048576: raise ValueError('result exceeds 1 MiB')
    def pairs(xs):
        d = {}
        for k, v in xs:
            if k in d: raise ValueError('duplicate field: ' + k)
            d[k] = v
        return d
    return json.loads(raw, object_pairs_hook=pairs,
        parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))

def validate(value, cases):
    schema = load(ROOT / 'result.schema.json')
    def check(v, s):
        if '$ref' in s: return check(v, schema['$defs'][s['$ref'].split('/')[-1]])
        if 'anyOf' in s:
            for candidate in s['anyOf']:
                try: check(v, candidate); return
                except (ValueError, TypeError): pass
            raise ValueError('no schema alternative')
        if 'const' in s and (type(v) is not type(s['const']) or v != s['const']): raise ValueError('wrong constant')
        if 'enum' in s and v not in s['enum']: raise ValueError('wrong enum')
        kind = s.get('type')
        types = {'object':dict, 'array':list, 'string':str, 'null':type(None)}
        if kind and type(v) is not types[kind]: raise ValueError('wrong type')
        if kind == 'object':
            if set(v) != set(s['required']): raise ValueError('missing or extra fields')
            for k, x in v.items(): check(x, s['properties'][k])
        if kind == 'array':
            if not s.get('minItems',0) <= len(v) <= s.get('maxItems',100): raise ValueError('array bounds')
            for x in v: check(x, s['items'])
        if 'pattern' in s and not re.fullmatch(s['pattern'],v): raise ValueError('noncanonical text')
    check(value, schema)
    if value['inputSha256'] != hashlib.sha256((ROOT/'cases.json').read_bytes()).hexdigest(): raise ValueError('input hash')
    if [c['id'] for c in value['cases']] != [c['id'] for c in cases['cases']]: raise ValueError('case order/identity')
    def ck(c): return (0 if c.startswith('script:') else 1, c.split(':')[1])
    def ordered(keys):
        if keys != sorted(keys) or len(keys) != len(set(keys)): raise ValueError('ordering/duplicate identity')
    def rewards(rs):
        ordered([(ck(r['credential']),0 if r['kind']=='member' else 1,r['pool']) for r in rs])
        if any(r['kind']=='member' and int(r['amount'])==0 for r in rs): raise ValueError('zero member reward')
    def balances(bs, positive=False):
        ordered([ck(b['credential']) for b in bs])
        if positive and any(int(b['amount'])==0 for b in bs): raise ValueError('zero credit')
    def ratio(v):
        if math.gcd(int(v['n']),int(v['d'])) != 1: raise ValueError('nonreduced ratio')
    def amount(rs): return sum(int(r['amount']) for r in rs)
    for out, case in zip(value['cases'], cases['cases']):
        ini=out['initial']
        if int(ini['chunk']) <= 0: raise ValueError('zero chunk')
        ordered([p['pool'] for p in ini['pools']])
        for p in ini['pools']:
            ratio(p['sigma']); ratio(p['snapshot']['margin']); ordered(p['snapshot']['owners'])
            rewards([p['leader']])
            if p['leader']['kind']!='leader' or p['leader']['pool']!=p['pool'] or p['leader']['credential']!=p['snapshot']['rewardAccount']: raise ValueError('pool leader identity')
        if [s['label'] for s in out['steps']] != [a['label'] for a in case['actions']]: raise ValueError('step domain')
        for step in out['steps']:
            phase=step['phase']
            if phase=='Pulsing':
                if step['remaining'] is None or step['members'] is None or step['complete'] is not None: raise ValueError('pulsing shape')
                ordered([ck(x['credential']) for x in step['remaining']]); rewards(step['members'])
                if any(int(x['stake'])==0 for x in step['remaining']) or any(x['kind']!='member' for x in step['members']): raise ValueError('pulsing content')
            else:
                if step['remaining'] is not None or step['members'] is not None: raise ValueError('terminal cursor')
                c=step['complete']
                if (phase=='Complete') != (c is not None): raise ValueError('completion shape')
                if c:
                    rewards(c['rewards'])
                    if sum(int(c[k]) for k in ('deltaT','deltaR','deltaF'))+amount(c['rewards']) != 0: raise ValueError('delta conservation')
        a=out['application']
        if case['applyRegistration'] != (a is not None): raise ValueError('application scope')
        if a:
            for k in ('registered','unregistered'): rewards(a[k])
            balances(a['credited'],True); balances(a['balances'])
            final=out['steps'][-1]['complete']
            if final is None: raise ValueError('application requires complete')
            identity=lambda r:(r['credential'],r['kind'],r['pool'],r['amount'])
            union=a['registered']+a['unregistered']
            if len({identity(r)[:3] for r in union})!=len(union) or sorted(map(identity,union))!=sorted(map(identity,final['rewards'])): raise ValueError('reward partition')
            if amount(a['unregistered'])!=int(a['totalUnregistered']) or amount(a['registered'])!=amount(a['credited']): raise ValueError('application split')
            if [b for b in a['balances'] if int(b['amount'])>0] != a['credited']: raise ValueError('zero-origin balances')
            if sum(map(int,a['pots'].values()))+amount(a['balances']) != 1000+int(case['fees']): raise ValueError('application conservation')
    return True

if __name__ == '__main__':
    validate(load(sys.argv[1]),load(ROOT/'cases.json'))
    print('strict synthetic result schema and conservation checks passed')
