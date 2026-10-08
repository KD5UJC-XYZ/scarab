"""Public detached verification only; malformed lengths never cross the native ABI."""
import sys
if sys.flags.optimize:
 raise RuntimeError("Run without Python optimization; required provenance and ABI assertions must remain enabled")
import ctypes, hashlib, json, pathlib
P=pathlib.Path(__file__).resolve().parent
W=P.parent/'witness-slice-probe'
expected={
 W/'vectors.json':'c9cbe16bc930c541fa45df129d5ae99606a0095c045b9d2c7974aab9abffb23e',
 W/'results.json':'3bc0fab952d45108f11f569edb4dda3b3ebf0368505105236e21207470c6bf3f',
 W/'strict-results.tsv':'8a56009372dbf3efeb7ae0c042c81b58fe55e3a9506921214668063750af4430',
 P/'source.tar.gz':'e4f29ae3c16037e484bb69e3fa22a5565c42adf497f8f88e61ff8d9486ab863e',
 P/'build/src/libsodium/.libs/libsodium.so.23.3.0':'76b41125c867878a1b0cfe64dde580872f61ca4cc6fae06223e902e3e803b24f'}
for path,digest in expected.items():
 assert hashlib.sha256(path.read_bytes()).hexdigest()==digest, str(path)
lib=ctypes.CDLL(str(P/'build/src/libsodium/.libs/libsodium.so.23.3.0'))
lib.sodium_version_string.argtypes=[]
lib.sodium_version_string.restype=ctypes.c_char_p
verify=lib.crypto_sign_ed25519_verify_detached
verify.argtypes=[ctypes.c_char_p,ctypes.c_char_p,ctypes.c_ulonglong,ctypes.c_char_p]
verify.restype=ctypes.c_int
rows=json.loads((W/'vectors.json').read_text())
recorded={r['id']:r for r in json.loads((W/'results.json').read_text())}
strict=dict(line.split('\t') for line in (W/'strict-results.tsv').read_text().splitlines())
assert len(rows)==2219 and len(set(r['id'] for r in rows))==2219
results=[]
for row in rows:
 pk,sig,msg=[bytes.fromhex(row[k]) for k in ('pk','sig','msg')]
 valid=len(pk)==32 and len(sig)==64
 assert row['lengths_valid']==valid
 result=dict(id=row['id'],public_key_bytes=len(pk),signature_bytes=len(sig),message_bytes=len(msg),native_called=valid)
 if valid:
  rc=verify(sig,msg,len(msg),pk)
  assert rc in (0,-1)
  result.update(native_rc=rc,pinned_sodium=rc==0,system_sodium=row['system_sodium'],strict_prototype=strict[row['id']]=='true',bc=recorded[row['id']]['bc']=='true')
  assert recorded[row['id']]['system_sodium']==row['system_sodium']
 else:
  result.update(native_rc=None,pinned_sodium=None,system_sodium=None,strict_prototype=None,bc=None,excluded_reason='wrong public-key or signature length; no native invocation or padding')
 results.append(result)
fixed=[r for r in results if r['native_called']]
ledger=[r for r in fixed if r['id'].startswith('amaru-')]
summary=dict(total=len(results),native_calls=len(fixed),malformed_excluded=len(results)-len(fixed),ledger_witnesses=len(ledger),ledger_verified=sum(r['pinned_sodium'] for r in ledger),version=lib.sodium_version_string().decode(),system_divergences=[r['id'] for r in fixed if r['pinned_sodium']!=r['system_sodium']],strict_divergences=[r['id'] for r in fixed if r['pinned_sodium']!=r['strict_prototype']],bc_divergences=[r['id'] for r in fixed if r['pinned_sodium']!=r['bc']],accepted=sum(r['pinned_sodium'] for r in fixed),rejected=sum(not r['pinned_sodium'] for r in fixed))
assert len(fixed)==2207 and len(ledger)==8
(P/'results.json').write_text(json.dumps(results,indent=2)+'\n')
(P/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
