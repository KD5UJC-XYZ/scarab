#!/usr/bin/env python3
"""Portable bounded offline admission/projector; never loads native code or fetches sources.
Reconstructs exact archive projections, deterministic mutations and tree work traces.
Leaf outcomes remain pinned historical observations; this is not a full KES oracle.
"""
from pathlib import Path
import hashlib,json
ROOT=Path(__file__).resolve().parent.parent
D=ROOT/'fixtures/sum6'; E=D/'evidence'
MAX_BYTES=1024*1024
MANIFEST_PIN='e1ce8b40da45a655bc95df4f5d773b3c7fd589ce8ddd8df2d3622b9069107dbb'
def require(ok,msg):
    if not ok: raise ValueError(msg)
def read(path):
    with path.open('rb') as f: data=f.read(MAX_BYTES+1)
    require(len(data)<=MAX_BYTES,'file exceeds 1 MiB')
    return data

def decode(raw):
    i=0; spans={}
    def take(n):
        nonlocal i
        require(0<=n<=len(raw)-i,'truncated CBOR')
        data=raw[i:i+n];i+=n;return data
    def go(path=(),depth=0):
        require(depth<8,'fixture depth')
        start=i; h=take(1)[0];major=h>>5;n=h&31
        if n>=24:
            require(n in (24,25,26,27),'unsupported argument')
            n=int.from_bytes(take(1<<(n-24)),'big')
        if major==0: value=n
        elif major==2:value=take(n)
        elif major==4:
            require(n<=20,'fixture array bound')
            value=[go(path+(j,),depth+1) for j in range(n)]
        else:raise ValueError('unsupported fixture type')
        spans[path]=(start,i);return value
    value=go();require(i==len(raw),'trailing bytes');return value,spans

def cases(root,sig,msg,period):
    yield 'original',root,sig,msg,period
    for p in range(64):
        if p!=period:yield f'period/{p}',root,sig,msg,p
    def flip(b,i,bit):return b[:i]+bytes([b[i]^(1<<bit)])+b[i+1:]
    for field,data in [('root',root),('signature',sig)]:
        for i in range(len(data)):
            for bit in range(8):
                mutated=flip(data,i,bit)
                yield f'{field}/xor/{i}/{bit}',mutated if field=='root' else root,mutated if field=='signature' else sig,msg,period
    for i in range(len(msg)):
        yield f'message/xor/{i}/0',root,sig,flip(msg,i,0),period
    for i in (0,len(msg)-1):
        for bit in range(1,8):yield f'message/xor/{i}/{bit}',root,sig,flip(msg,i,bit),period
    for d in range(1,7):
        o=64+64*(d-1)
        yield f'signature/swap-pair/{d}',root,sig[:o]+sig[o+32:o+64]+sig[o:o+32]+sig[o+64:],msg,period
    for name,m in [('empty',b''),('truncate-last',msg[:-1]),('append-zero',msg+b'\0')]:
        yield 'message/'+name,root,sig,m,period

def trace(root,sig,period):
    for depth in range(6,0,-1):
        o=64+64*(depth-1);pair=sig[o:o+64]
        if hashlib.blake2b(pair,digest_size=32).digest()!=root:return 7-depth,0
        half=1<<(depth-1);selected=0 if period<half else 32
        if selected:period-=half
        root=pair[selected:selected+32]
    require(period==0,'period invariant')
    return 6,1

def projections():
    sources=json.loads(read(E/'sources.json'))
    headers=[s for s in sources if 'slot' in s]
    require([s['slot'] for s in headers]==[70070331,70070379,70070426,70070464],'archive identities')
    originals=[];observations=[]
    for s in headers:
        raw=read(E/s['file']);require(hashlib.sha256(raw).hexdigest()==s['sha256'],'archive digest')
        (body,sig),spans=decode(raw)
        require(len(body)==10 and len(sig)==448 and len(body[8])==4,'archive shape')
        start,end=spans[(0,)];msg=raw[start:end];root=body[8][0];period=s['relative_period']
        require((start,end)==(1,408) and len(root)==32,'exact admitted spans and root')
        require(body[1]==s['slot'] and body[8][2]==s['start_period'],'context extraction')
        require(period==s['slot']//129600-s['start_period'] and 0<=period<62,'archived period context')
        originals.append('\t'.join([str(s['slot']),root.hex(),str(period),msg.hex(),sig.hex()]))
        for name,r,signature,message,p in cases(root,sig,msg,period):
            require(len(r)==32 and len(signature)==448 and len(message)<=65536 and 0<=p<64,'mutation shape')
            checks,leaves=trace(r,signature,p)
            observations.append('\t'.join([str(s['slot']),name,'VERIFIED' if name=='original' else 'REJECTED',str(checks),str(leaves)]))
    require(len(observations)==17336,'case count')
    return {'originals.tsv':'\n'.join(originals)+'\n','observations.tsv':'\n'.join(observations)+'\n'}

def main():
    raw=read(D/'sha256.json');require(hashlib.sha256(raw).hexdigest()==MANIFEST_PIN,'embedded manifest digest')
    pins=json.loads(raw)
    require(set(pins)=={str(p.relative_to(D)) for p in D.rglob('*') if p.is_file()}-{'sha256.json'},'manifest file set')
    for name,pin in pins.items():require(hashlib.sha256(read(D/name)).hexdigest()==pin,'digest '+name)
    for s in json.loads(read(E/'sources.json')):
        require(hashlib.sha256(read(E/s['file'])).hexdigest()==s['sha256'],'source '+s['file'])
    for name,value in projections().items():require(read(D/name).decode()==value,'projection '+name)
    print('PASS: 4 public originals and 17332 synthetic controls; offline immutable admission, exact projection and tree traces. Historical leaf outcomes only; no independent full KES oracle.')
if __name__=='__main__':main()
