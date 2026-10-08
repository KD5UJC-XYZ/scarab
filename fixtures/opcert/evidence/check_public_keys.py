"""Public-only fixture inspection; no signer, keygen, network or repository build."""
import pathlib,json,hashlib,ctypes
D=pathlib.Path(__file__).resolve().parent; P=D.parent/'praos-header-probe'
pins={x['file']:x['sha256'] for x in json.loads((P/'sources.json').read_text())}
def decode(b):
 i=0; spans={}
 def go(path=()):
  nonlocal i
  start=i;h=b[i];i+=1;m=h>>5;n=h&31
  if n>=24:
   k={24:1,25:2,26:4,27:8}[n];n=int.from_bytes(b[i:i+k],'big');i+=k
  if m==0:r=n
  elif m==2:r=b[i:i+n];i+=n
  elif m==4:r=[go(path+(j,)) for j in range(n)]
  elif m==7 and n==22:r=None
  else:raise ValueError((m,n))
  spans[path]=(start,i);return r
 r=go();assert i==len(b);return r,spans
lib=D.parent/'cardano-sodium-oracle/build/src/libsodium/.libs/libsodium.so.23.3.0'
assert hashlib.sha256(lib.read_bytes()).hexdigest()=='76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f'
f=ctypes.CDLL(str(lib)).crypto_sign_ed25519_verify_detached
f.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_ulonglong,ctypes.c_void_p];f.restype=ctypes.c_int
def verify(pk,sig,msg):
 assert len(pk)==32 and len(sig)==64
 rc=f(sig,msg,len(msg),pk);assert rc in [0,-1];return rc

def kes(pk,sig,msg,t):
 assert len(sig)==448 and len(pk)==32 and 0<=t<64
 checks=[]
 for depth in range(6,0,-1):
  offset=64+(depth-1)*64;left=sig[offset:offset+32];right=sig[offset+32:offset+64]
  good=hashlib.blake2b(left+right,digest_size=32).digest()==pk;checks.append(good)
  if not good:return False,checks,None
  half=1<<(depth-1)
  if t<half:pk=left
  else:pk=right;t-=half
 rc=verify(pk,sig[:64],msg)
 return rc==0,checks,pk.hex()
rows=[]
for slot in [70070331,70070379,70070426,70070464]:
 p=P/f'preprod_{slot}.cbor';raw=p.read_bytes();assert hashlib.sha256(raw).hexdigest()==pins[p.name]
 (body,sig),spans=decode(raw);assert len(body)==10 and len(sig)==448
 cold=body[3];vrf=body[4];hot,n,c0,tau=body[8];msg=hot+n.to_bytes(8,'big')+c0.to_bytes(8,'big');assert len(msg)==48
 start,end=spans[(0,)];bodybytes=raw[start:end]
 kp=slot//129600;t=kp-c0;assert 0<=t<62
 ok,checks,leaf=kes(hot,sig,bodybytes,t)
 cases={'original':verify(cold,tau,msg),'hot-bit':verify(cold,tau,bytes([msg[0]^1])+msg[1:]),'counter+1':verify(cold,tau,hot+(n+1).to_bytes(8,'big')+c0.to_bytes(8,'big')),'start+1':verify(cold,tau,hot+n.to_bytes(8,'big')+(c0+1).to_bytes(8,'big')),'little-endian':verify(cold,tau,hot+n.to_bytes(8,'little')+c0.to_bytes(8,'little')),'signature-bit':verify(cold,bytes([tau[0]^1])+tau[1:],msg),'cold-key-bit':verify(bytes([cold[0]^1])+cold[1:],tau,msg)}
 row=dict(file=p.name,sha256=pins[p.name],slot=slot,cold_key=cold.hex(),pool_id=hashlib.blake2b(cold,digest_size=28).hexdigest(),vrf_key=vrf.hex(),vrf_hash=hashlib.blake2b(vrf,digest_size=32).hexdigest(),hot_key=hot.hex(),counter=n,start_period=c0,opcert_signature=tau.hex(),opcert_message=msg.hex(),opcert_native_cases=cases,header_protocol_version=body[9],body_start=start,body_end=end,body_bytes=bodybytes.hex(),kes_signature=sig.hex(),public_test_slots_per_kes_period=129600,public_test_max_evolutions=62,kp=kp,relative_period=t,kes_tree_checks=checks,kes_leaf_public_key=leaf,kes_public_prototype_ok=ok,kes_wrong_period_ok=kes(hot,sig,bodybytes,(t+1)%64)[0],kes_body_bit_ok=kes(hot,sig,bytes([bodybytes[0]^1])+bodybytes[1:],t)[0])
 assert cases['original']==0 and all(v==-1 for k,v in cases.items() if k!='original');assert ok and not row['kes_wrong_period_ok'] and not row['kes_body_bit_ok'];rows.append(row)
(D/'public-key-results.json').write_text(json.dumps(rows,indent=2)+'\n');print([(r['slot'],r['counter'],r['start_period'],r['kp'],r['relative_period'],r['kes_public_prototype_ok']) for r in rows])
