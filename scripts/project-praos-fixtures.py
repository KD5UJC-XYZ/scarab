#!/usr/bin/env python3
"""Offline fixed public-fixture extraction/admission, not a general header decoder or native oracle."""
from pathlib import Path
import hashlib,json,re
ROOT=Path(__file__).resolve().parent.parent
D=ROOT/'fixtures/praos'; E=D/'evidence'
def require(ok,msg):
    if not ok: raise ValueError(msg)
def decode(raw):
    i=0
    def read(n):
        nonlocal i
        require(0 <= n <= len(raw)-i,'truncated CBOR')
        b=raw[i:i+n];i+=n;return b
    def go(depth=0):
        require(depth < 8,'fixture nesting limit')
        h=read(1)[0];m=h>>5;n=h&31
        if n>=24:
            require(n in (24,25,26,27),'unsupported CBOR argument')
            n=int.from_bytes(read(1<<(n-24)),'big')
        if m==0:return n
        if m==2:return read(n)
        if m==4:
            require(n<=20,'fixture array limit')
            return [go(depth+1) for _ in range(n)]
        raise ValueError('unsupported fixture CBOR type')
    v=go();require(i==len(raw),'trailing CBOR');return v

def projections():
    headers=json.loads((E/'public-header-results.json').read_text())[:2] + json.loads((E/'epoch166-header-results.json').read_text())
    require([h['slot'] for h in headers]==[70070331,70070379,70070426,70070464],'header identity')
    for h in headers:
        raw=(E/h['file']).read_bytes()
        require(hashlib.sha256(raw).hexdigest()==h['sha256'],'header hash')
        v=decode(raw);b=v[0]
        require(len(v)==2 and len(b)==10 and len(b[5])==2,'fixed fixture shape')
        require((b[1],b[4].hex(),b[5][0].hex(),b[5][1].hex())==(h['slot'],h['pk'],h['claimed'],h['proof']),'extraction mismatch')
        require(len(b[4])==32 and len(b[5][0])==64 and len(b[5][1])==80,'envelope length')
        require(h['native_rc']==0 and h['native_output']==h['claimed'] and h['claim_matches'],'native certificate evidence')
        block=re.search(r'static PREPROD_NONCES_'+str(h['slot'])+r':.*?epoch: Epoch::from\((\d+)\),\s*active: hash!\("([0-9a-f]{64})"\)',(E/'amaru-store.rs').read_text(),re.S)
        require(block is not None,'named nonce source block')
        require(int(block[1])==(165 if h['slot']<70070400 else 166) and block[2]==h['nonce'],'source epoch/active nonce binding')
        require(hashlib.blake2b(h['slot'].to_bytes(8,'big')+bytes.fromhex(h['nonce']),digest_size=32).hexdigest()==h['alpha'],'header alpha')
    controls=json.loads((E/'alpha-controls.json').read_text())
    domain=[(str(s),n) for s in (0,1,2**63-1,2**63,2**64-1) for n in (None,bytes(32).hex(),bytes(range(32)).hex())]
    require([(c['slot'],c['nonce']) for c in controls]==domain,'control identity/order')
    for c in controls:
        pre=int(c['slot']).to_bytes(8,'big')+bytes.fromhex(c['nonce'] or '')
        require(pre.hex()==c['preimage'] and hashlib.blake2b(pre,digest_size=32).hexdigest()==c['alpha'],'independent alpha control')
    alpha=''.join('\t'.join([c['slot'],c['nonce'] or 'NEUTRAL',c['alpha']])+'\n' for c in controls)
    mutations=json.loads((E/'mutation-results.json').read_text());rows=[]
    cases=['original','wrong-slot','little-endian-slot','neutral-nonce','wrong-nonce','proof-bit','key-bit','claim-bit']
    require([(r['slot'],r['case']) for r in mutations]==[(h['slot'],c) for h in headers[:2] for c in cases],'mutation identity/order')
    for r in mutations:
        h=next(h for h in headers if h['slot']==r['slot']);s=h['slot'];nonce=h['nonce'];pk=h['pk'];proof=h['proof'];claim=h['claimed'];case=r['case']
        def flip(x):return (bytes([bytes.fromhex(x)[0]^1])+bytes.fromhex(x)[1:]).hex()
        if case=='wrong-slot':s+=1
        if case=='little-endian-slot':s=int.from_bytes(s.to_bytes(8,'little'),'big')
        if case=='neutral-nonce':nonce=''
        if case=='wrong-nonce':nonce=bytes(32).hex()
        if case=='proof-bit':proof=flip(proof)
        if case=='key-bit':pk=flip(pk)
        if case=='claim-bit':claim=flip(claim)
        a=hashlib.blake2b(s.to_bytes(8,'big')+bytes.fromhex(nonce),digest_size=32).hexdigest()
        require((pk,proof,claim,a)==(r['pk'],r['proof'],r['claimed'],r['alpha']),'mutation derivation')
        expected='VERIFIED:'+r['native_output'] if r['certified_ok'] else 'OUTPUT_MISMATCH' if r['native_rc']==0 else 'PROOF_REJECTED'
        require(r['native_rc'] in (0,-1) and r['certified_ok']==(r['native_rc']==0 and r['native_output']==claim),'native evidence shape')
        rows.append('\t'.join([str(h['slot'])+'/'+case,str(s),nonce or 'NEUTRAL',pk,proof,claim,a,expected]))
    require(sum(r['certified_ok'] for r in mutations)==2 and sum(r['native_rc']==0 for r in mutations)==4,'native counts')
    for h in headers[2:]:
        rows.append('\t'.join([str(h['slot'])+'/original',str(h['slot']),h['nonce'],h['pk'],h['proof'],h['claimed'],h['alpha'],'VERIFIED:'+h['native_output']]))
    return {'alpha.tsv':alpha,'certificates.tsv':'\n'.join(rows)+'\n'}

def main():
    pins=json.loads((D/'sha256.json').read_text())
    require(set(pins)=={str(p.relative_to(D)) for p in D.rglob('*') if p.is_file()}-{'sha256.json'},'manifest file set')
    for n,h in pins.items():require(hashlib.sha256((D/n).read_bytes()).hexdigest()==h,'digest '+n)
    for s in json.loads((E/'sources.json').read_text()):require(hashlib.sha256((E/s['file']).read_bytes()).hexdigest()==s['sha256'],'upstream source hash')
    for n,t in projections().items():require((D/n).read_text()==t,'projection '+n)
    print('PASS: 4 archived public certificate positives; 16 native mutations; 15 independent alpha controls; offline source/hash admission')
if __name__=='__main__':main()
