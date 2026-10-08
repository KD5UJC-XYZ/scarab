#!/usr/bin/env python3
"""Bounded offline, hash-gated public fixture projection; never invokes a native oracle."""
from pathlib import Path
import hashlib,json
ROOT=Path(__file__).resolve().parent.parent
D=ROOT/'fixtures/opcert'; E=D/'evidence'
MAX_BYTES=64*1024
MANIFEST_PIN='78dcb5d8872f477e974742c415cfc33ce58e0d4f46a5ede0cf473fe5f838fdc1'
CASES=['original','hot-bit','counter+1','start+1','little-endian','signature-bit','cold-key-bit']
def require(ok,msg):
    if not ok: raise ValueError(msg)
def read(path):
    with path.open('rb') as f: data=f.read(MAX_BYTES+1)
    require(len(data)<=MAX_BYTES,'fixture exceeds 64 KiB')
    return data
def decode(raw):
    require(len(raw)<=MAX_BYTES,'fixture exceeds 64 KiB')
    i=0
    def take(n):
        nonlocal i
        require(0<=n<=len(raw)-i,'truncated CBOR')
        b=raw[i:i+n];i+=n;return b
    def go(depth=0):
        require(depth<8,'fixture nesting limit')
        h=take(1)[0];m=h>>5;n=h&31
        if n>=24:
            require(n in (24,25,26,27),'unsupported CBOR argument')
            n=int.from_bytes(take(1<<(n-24)),'big')
        if m==0:return n
        if m==2:return take(n)
        if m==4:
            require(n<=20,'fixture array limit')
            return [go(depth+1) for _ in range(n)]
        raise ValueError('unsupported fixture CBOR type')
    v=go();require(i==len(raw),'trailing CBOR');return v

def projections():
    headers=json.loads(read(E/'public-key-results.json'))
    require([h['slot'] for h in headers]==[70070331,70070379,70070426,70070464],'header identity/order')
    rows=[]
    for h in headers:
        raw=read(E/h['file'])
        require(hashlib.sha256(raw).hexdigest()==h['sha256'],'header hash')
        v=decode(raw)
        require(isinstance(v,list) and len(v)==2 and isinstance(v[0],list) and len(v[0])==10,'fixed header shape')
        b=v[0];cert=b[8]
        require(isinstance(cert,list) and len(cert)==4,'fixed certificate shape')
        hot,n,start,sig=cert;cold=b[3]
        require(all(isinstance(x,bytes) for x in [hot,sig,cold]) and len(hot)==32 and len(sig)==64 and len(cold)==32,'certificate envelope')
        require(type(n)==int and type(start)==int and 0<=n<2**64 and 0<=start<2**64,'unsigned integers')
        msg=hot+n.to_bytes(8,'big')+start.to_bytes(8,'big')
        require((b[1],cold.hex(),hot.hex(),n,start,sig.hex(),msg.hex())==(h['slot'],h['cold_key'],h['hot_key'],h['counter'],h['start_period'],h['opcert_signature'],h['opcert_message']),'certificate extraction')
        require(list(h['opcert_native_cases'])==CASES,'native case identity/order')
        for case in CASES:
            k=cold;key=hot;c=n;s=start;signature=sig
            def flip(x):return bytes([x[0]^1])+x[1:]
            if case=='hot-bit':key=flip(key)
            if case=='counter+1':c+=1
            if case=='start+1':s+=1
            if case=='little-endian':
                c=int.from_bytes(c.to_bytes(8,'little'),'big');s=int.from_bytes(s.to_bytes(8,'little'),'big')
            if case=='signature-bit':signature=flip(signature)
            if case=='cold-key-bit':k=flip(k)
            require(h['opcert_native_cases'][case]==(0 if case=='original' else -1),'native result')
            message=key+c.to_bytes(8,'big')+s.to_bytes(8,'big')
            rows.append('\t'.join([str(h['slot'])+'/'+case,k.hex(),key.hex(),str(c),str(s),signature.hex(),message.hex(),'VERIFIED' if case=='original' else 'REJECTED']))
    controls=json.loads(read(E/'serialization-controls.json'))
    domain=[(str(n),str(s)) for n in [0,1,2**63-1,2**63,2**64-1] for s in [0,1,2**63-1,2**63,2**64-1]]
    require([(c['counter'],c['start_period']) for c in controls]==domain,'serialization control identity/order')
    for c in controls:
        require(c['hot_key']==bytes(range(32)).hex(),'control hot key')
        require(c['message']==(bytes(range(32))+int(c['counter']).to_bytes(8,'big')+int(c['start_period']).to_bytes(8,'big')).hex(),'independent serialization')
    return {'certificates.tsv':'\n'.join(rows)+'\n','serialization.tsv':''.join('\t'.join([c['hot_key'],c['counter'],c['start_period'],c['message']])+'\n' for c in controls)}

def main():
    manifest=read(D/'sha256.json')
    require(hashlib.sha256(manifest).hexdigest()==MANIFEST_PIN,'embedded manifest digest')
    pins=json.loads(manifest)
    require(set(pins)=={str(p.relative_to(D)) for p in D.rglob('*') if p.is_file()}-{'sha256.json'},'manifest file set')
    for n,h in pins.items():require(hashlib.sha256(read(D/n)).hexdigest()==h,'digest '+n)
    for s in json.loads(read(E/'sources.json')):require(hashlib.sha256(read(E/s['file'])).hexdigest()==s['sha256'],'upstream source hash')
    for n,t in projections().items():require(read(D/n).decode()==t,'projection '+n)
    print('PASS: 4 archived public opcert signatures; 24 native-rejected mutations; 25 independent uint64 serialization controls; offline hash admission')
if __name__=='__main__':main()
