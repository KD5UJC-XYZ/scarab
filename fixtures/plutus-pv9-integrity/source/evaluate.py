# SPDX-License-Identifier: Apache-2.0
import sys,json,hashlib,copy
from pathlib import Path
sys.path.insert(0,'/release');import verify_packet as v
sys.path.insert(0,'/source');import mutations
root=Path('/source');v.require_readonly(root);v.require_readonly(Path('/release'))
m=json.loads(v.limited(root/'run-manifest.json',65536),object_pairs_hook=v.unique_object)
def digest(x):return hashlib.sha256(x).hexdigest()
raw=v.limited(root/'inputs.json',2*1024*1024);assert digest(raw)==m['inputSha256'];assert digest(v.limited(root/'integrity-reference',512*1024*1024))==m['helperSha256'];assert digest(v.limited(Path('/release/verify_packet.py'),65536))==m['verifierSha256']
inputs=json.loads(raw,object_pairs_hook=v.unique_object);rows=[];rejections=[]
renames={'redeemerOriginalHex':'redeemerBytesHex','datumDomainHex':'datumBytesHex','integrityPreimageHex':'preimageHex','computedCommitmentHex':'computedHashHex','suppliedCommitmentHex':'suppliedHashHex'}
def probe(row,malformed=False):
 p=row['packet'];assert digest(bytes.fromhex(p['parametersCborHex']))=='9626e5907d905440a4c5fd119c57b8aa8524f2d0b8e8fe32ef5f33543db2f67b';assert digest(bytes.fromhex(p['reviewedScriptHex']))=='57fb50f08ffc1222cbe2b652db3dcfed0f714da98f8170cb104aee2bde4070f6'
 assert len(bytes.fromhex(p['transactionCborHex']))<=65536 and len(p['utxo'])<=3
 total=0
 for e in p['utxo']:
  a=bytes.fromhex(e['inputCborHex']);b=bytes.fromhex(e['outputCborHex']);assert len(a)<=128 and len(b)<=4096;total+=len(a)+len(b)
 assert total<=16384
 data=json.dumps(dict(p,profile='synthetic-conway-pv9-v3-draft'),separators=(',',':')).encode();assert len(data)<=3*1024*1024
 out=v.run_bounded(root/'integrity-reference',data);result=json.loads(out,object_pairs_hook=v.unique_object)
 Path('/results/'+row['name']+'.json').write_bytes(out)
 if malformed:
  assert result.get('status')=='rejected',result;assert 'DecoderError' in result['error'],result
  rejections.append(dict(row,reference=result));return
 assert result.get('status')!='rejected',result
 result={renames.get(k,k):x for k,x in result.items()}
 assert result['usedLanguages']==['PlutusV3'];assert result['datumCount']==0 and result['datumBytesHex']==''
 assert result['redeemerBytesHex']==mutations.redeemer_raw(p).hex()
 assert digest(bytes.fromhex(result['languageViewHex']))=='ffe1bc3154b74e81cf05b3482361a11b0e23bdc158ac38d64eb62e942969ca1d'
 assert len(bytes.fromhex(result['languageViewHex']))==690
 assert result['preimageHex']==result['redeemerBytesHex']+result['datumBytesHex']+result['languageViewHex']
 assert hashlib.blake2b(bytes.fromhex(result['preimageHex']),digest_size=32).hexdigest()==result['computedHashHex']
 assert result['matches']==row['expectedMatch'],(row['name'],result)
 assert result['scriptEvaluationPerformed'] is False and result['fullLedgerValidation'] is False
 if result['matches']:assert result['referenceCheckDiagnostic']=='Success ()',result['referenceCheckDiagnostic']
 else:assert 'PPViewHashesDontMatch' in result['referenceCheckDiagnostic'],result['referenceCheckDiagnostic']
 rows.append(dict(row,reference=result));return result
for row in inputs['rows']:probe(row)
for name in ['redeemer-eight-stale','redeemer-indefinite-stale']:
 old=next(x for x in rows if x['name']==name);p=copy.deepcopy(old['packet']);p['transactionCborHex']=old['reference']['repairedTransactionCborHex'];assert mutations.redeemer_raw(p).hex()==old['reference']['redeemerBytesHex']
 probe(dict(name=name.replace('-stale','-repaired'),packet=p,expectedMatch=True,kind='reference-body-commitment-repair'))
for row in inputs['malformed']:probe(row,True)
base=rows[0]['reference'];assert len(set(x['reference']['preimageHex'] for x in rows[:18]))==3
for name in ['commitment-missing','commitment-zero','commitment-flip','outer-witness-map-reversed','body-fee-plus-one','inline-minimum-only']:
 x=next(x for x in rows if x['name']==name);assert x['reference']['preimageHex']==base['preimageHex'],name
indef=next(x for x in rows if x['name']=='redeemer-indefinite-stale');assert indef['reference']['redeemerBytesHex'].startswith('bf') and indef['reference']['redeemerBytesHex'].endswith('ff');assert indef['reference']['computedHashHex']!=base['computedHashHex']
Path('/results/vectors.json').write_text(json.dumps(dict(schema=1,classification='original synthetic integrity-only reference evidence',vectors=rows,rejections=rejections),separators=(',',':'))+'\n')
Path('/results/receipt.json').write_text(json.dumps(dict(observedRows=len(rows),malformedRejected=len(rejections),matches=sum(x['reference']['matches'] for x in rows),baseline18DistinctPreimages=3,peakMemoryBytes=int(Path('/sys/fs/cgroup/memory.peak').read_text()),memoryEvents=Path('/sys/fs/cgroup/memory.events').read_text()),indent=2))
