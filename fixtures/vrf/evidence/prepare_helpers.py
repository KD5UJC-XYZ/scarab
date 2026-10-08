import pathlib,ctypes,hashlib,json
D=pathlib.Path(__file__).resolve().parent
lib=D.parent/'cardano-sodium-oracle/build/src/libsodium/.libs/libsodium.so.23.3.0'
assert hashlib.sha256(lib.read_bytes()).hexdigest()=='76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f'
n=ctypes.CDLL(str(D/'helper.so'));so=ctypes.CDLL(str(lib));cases=[]
for name,k in [('probe_decode',2),('probe_uniform',2),('probe_equation',5)]:getattr(n,name).argtypes=[ctypes.c_void_p]*k;getattr(n,name).restype=ctypes.c_int if name!='probe_uniform' else None
n.probe_trace.argtypes=[ctypes.c_void_p]*4+[ctypes.c_ulonglong];n.probe_trace.restype=ctypes.c_int
so.crypto_core_ed25519_add.argtypes=[ctypes.c_void_p]*3;so.crypto_core_ed25519_add.restype=ctypes.c_int
P=2**255-19;L=2**252+27742317777372353535851937790883648493
B=bytes.fromhex('58'+'66'*31)
def add(id,op,*args):
 out=ctypes.create_string_buffer(176)
 if op=='d':
  rc=n.probe_decode(out,*args);assert rc in [-1,0,1];res='INVALID' if rc==-1 else ('SMALL:' if rc==1 else 'POINT:')+out.raw[:32].hex()
 elif op=='u':n.probe_uniform(out,*args);res=out.raw[:32].hex()
 elif op=='e':
  p,q,c,s=args;rc=n.probe_equation(out,p,q,c.ljust(32,b'\0'),s);assert rc==0;res=out.raw[:32].hex()
 else:
  pk,pi,a=args;rc=n.probe_trace(out,pk,pi,a,len(a));assert rc in [0,-1];res='INVALID' if rc else out.raw.hex()
 cases.append(dict(id=id,op=op,args=[a.hex() for a in args],expected=res))
points=[]
for y in list(range(20))+[P-i for i in range(-2,20)]:
 for sign in [0,1]:
  x=(y|(sign<<255)).to_bytes(32,'little');add(f'd-{y}-{sign}','d',x)
for i in range(512):add('d-hash-'+str(i),'d',hashlib.sha256(('public-decode-'+str(i)).encode()).digest())
for i,r in enumerate([0,1,2,P-1,P,P+1,(1<<255)-1]+[int.from_bytes(hashlib.sha256(('public-uniform-'+str(j)).encode()).digest(),'little') for j in range(512)]):add('u-'+str(i),'u',r.to_bytes(32,'little'))
# All eight torsion points, from repeated addition of public order-8 point.
t=bytes.fromhex('26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05');point=(1).to_bytes(32,'little')
for j in range(8):
 add(f'torsion-{j}','d',point);out=ctypes.create_string_buffer(32);assert so.crypto_core_ed25519_add(out,B,point)==0;mixed=out.raw
 add(f'mixed-{j}','d',mixed);points.extend([point,mixed]);assert so.crypto_core_ed25519_add(out,point,t)==0;point=out.raw
for j,p in enumerate(points):
 for k,c in enumerate([0,1,2,17,2**128-1]):
  for l,s in enumerate([0,1,L-1]):add(f'e-{j}-{k}-{l}','e',p,B,c.to_bytes(16,'little'),s.to_bytes(32,'little'))
rows=json.loads((D.parent/'vrf-slice-probe/vectors.json').read_text())
for row in rows:
 if row['native03']!='EXCLUDED':add('t-'+row['id'],'t',bytes.fromhex(row['pk']),bytes.fromhex(row['proof']),bytes.fromhex(row['alpha']))
base=rows[0];pk=bytes.fromhex(base['pk']);pi=bytes.fromhex(base['proof'])
for j,p in enumerate(points):
 add('t-mixed-y-'+str(j),'t',p,pi,b'')
 add('t-mixed-gamma-'+str(j),'t',pk,p+pi[32:],b'')
for x in [1,P-1]:add('t-negative-zero-'+str(x),'t',pk,(x+(1<<255)).to_bytes(32,'little')+pi[32:],b'')
(D/'helpers.json').write_text(json.dumps(cases,indent=2)+'\n');(D/'helpers.tsv').write_text(''.join('\t'.join([r['id'],r['op']]+r['args'])+'\n' for r in cases))
print('native helper rows',len(cases))
# Direct verifier calls, in addition to instrumented traces; no proof creation.
so.crypto_vrf_ietfdraft03_verify.argtypes=[ctypes.c_void_p]*4+[ctypes.c_ulonglong];so.crypto_vrf_ietfdraft03_verify.restype=ctypes.c_int
additional=[]
def verify(id,pk,pi,a):
 out=ctypes.create_string_buffer(64);rc=so.crypto_vrf_ietfdraft03_verify(out,pk,pi,a,len(a));assert rc in [0,-1]
 additional.append(dict(id=id,pk=pk.hex(),proof=pi.hex(),alpha=a.hex(),expected='INVALID' if rc else 'VALID:'+out.raw.hex()))
for row in rows:
 if row['native03']!='EXCLUDED':
  verify('rerun-'+row['id'],*(bytes.fromhex(row[k]) for k in ['pk','proof','alpha']));assert additional[-1]['expected']==row['native03']
for case in cases:
 if case['op']=='t' and ('mixed' in case['id'] or 'negative-zero' in case['id']):verify(case['id'],*(bytes.fromhex(x) for x in case['args']))
for i in range(256):
 b=hashlib.shake_256(('public-only-unstructured-'+str(i)).encode()).digest(144)
 verify('unstructured-'+str(i),b[:32],b[32:112],b[112:])
for row in [r for r in rows if r['kind']=='published-positive']:
 for s in [0,1,2**252-1,L-1,L,L+1,2**256-1]:
  verify(row['id']+'-scalar-boundary-'+str(s),bytes.fromhex(row['pk']),bytes.fromhex(row['proof'])[:48]+s.to_bytes(32,'little'),bytes.fromhex(row['alpha']))
(D/'additional.json').write_text(json.dumps(additional,indent=2)+'\n');(D/'additional.tsv').write_text(''.join('\t'.join(r[k] for k in ['id','pk','proof','alpha'])+'\n' for r in additional))
