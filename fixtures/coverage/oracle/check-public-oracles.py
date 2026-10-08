"""Public inputs only. No key generation, signing, network or node commands."""
import ctypes,hashlib,json,pathlib,subprocess,sys
if sys.flags.optimize:raise RuntimeError('Do not disable validation with -O')
P=pathlib.Path(__file__).resolve().parent
libpath=P.parent/'cardano-sodium-oracle/build/src/libsodium/.libs/libsodium.so.23.3.0'
assert hashlib.sha256(libpath.read_bytes()).hexdigest()=='76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f'
lib=ctypes.CDLL(str(libpath));verify=lib.crypto_sign_ed25519_verify_detached
verify.argtypes=[ctypes.c_char_p,ctypes.c_char_p,ctypes.c_ulonglong,ctypes.c_char_p];verify.restype=ctypes.c_int
rows=json.loads((P/'projection.json').read_text());results=[]
exe=P.parent/'reference-runtime/offline/cardano-cli'
assert hashlib.sha256(exe.read_bytes()).hexdigest()=='0ac45e874599fac4ee6ca4fd9602c0ddb9854a62be5f365dfa425eff9f4bd0ed'
for row in rows:
 for i,w in enumerate(row['provided']):
  v=P/f'event-{row["event"]}-witness-{i}.vkey';v.write_text(json.dumps({'type':'PaymentVerificationKeyShelley_ed25519','description':'Published Haskell fixture public verification key','cborHex':'5820'+w['vkey']},indent=2)+'\n')
  args=[str(exe),'address','key-hash','--payment-verification-key-file',str(v)];r=subprocess.run(args,capture_output=True,text=True,timeout=20,env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8'},close_fds=True,cwd=P)
  pk,sig,msg=[bytes.fromhex(x) for x in (w['vkey'],w['signature'],row['body_hash'])];assert len(pk)==32 and len(sig)==64 and len(msg)==32
  rc=verify(sig,msg,len(msg),pk);assert rc in (0,-1)
  results.append({'event':row['event'],'args':args,'exit_code':r.returncode,'stdout':r.stdout,'stderr':r.stderr,'expected_blake2b224':w['key_hash'],'matches':r.returncode==0 and r.stdout.strip()==w['key_hash'],'pinned_sodium_rc':rc,'signature_verified':rc==0})
assert all(r['matches'] and r['signature_verified'] for r in results)
(P/'public-oracle-results.json').write_text(json.dumps(results,indent=2)+'\n')
print('PASS: 2 CLI public-key hash checks; 2 pinned-source public signature checks')
