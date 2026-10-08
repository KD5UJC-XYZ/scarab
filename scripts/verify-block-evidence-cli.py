#!/usr/bin/env python3
from private_corpus_gate import require_private_corpus
require_private_corpus()
"""Serial resolved-JVM original-block evidence CLI audit; no network or persisted chain state."""
from pathlib import Path
import hashlib,json,os,subprocess,tempfile
if not __debug__:raise SystemExit('optimized Python is unsupported for verification')
R=Path(__file__).resolve().parents[1];P=R/'fixtures/block-evidence'
CP=(R/'app/target/runtime-classpath.txt').read_text().strip()
CMD=['java','-cp',CP,'lab.Main','block-evidence'];results=[]
rows=[line.split('\t') for line in (P/'expectations.tsv').read_text().splitlines()]
def check(name,args,code,expected=None):
 out=subprocess.run(CMD+list(map(str,args)),cwd=R,capture_output=True,text=True,timeout=30)
 assert out.returncode==code,(name,out.returncode,out.stdout,out.stderr)
 if expected is not None:
  report=json.loads(out.stdout)
  for key,value in expected.items():assert report[key]==value,(name,key,report.get(key),value)
  for key in ['referenceSerializerParity','authorizedIssuer','registeredVrfKeyBinding','opcertCounterAdmissibility','nonceDerivedFromState','leaderEligibility','protocolVersionAdmissibility','ledgerApplied','selectedChain','sourceAuthenticated']:
   assert report[key] is False,(name,key)
  assert report['coverage']=='body_opcert_supplied_timing_sum6'
  assert report['vrf']=='not_checked_missing_nonce'
  assert report['messageEvidence']=='source_profile_shortest_definite_candidate'
 results.append({'case':name,'exit':code});print('PASS:',name,'exit',code)
for r in rows:
 expected=None if r[1]!='checked' else {'originalBlockSha256':r[3],'originalHeaderSha256':r[4],'headerHash':r[5],'parentHash':r[6],'slot':r[7],'messageSha256':r[11],'currentKesPeriod':r[12],'relativeKesPeriod':int(r[13]),'bodySize':int(r[14]),'bodyHash':r[15],'contextSha256':r[20],'receiptId':r[21],'contextEncoding':r[22]}
 check(r[0],[P/'blocks'/r[0],P/'contexts'/(r[0]+'.tsv')],0 if r[1]=='checked' else 1,expected)
with tempfile.TemporaryDirectory(prefix='block-evidence-cli-') as name:
 temp=Path(name);block=temp/'input.cbor';context=temp/'context.tsv'
 for line in (P/'edges.tsv').read_text().splitlines():
  id,status,hexbytes,ctx=line.split('\t');block.write_bytes(bytes.fromhex(hexbytes));context.write_text(ctx.replace('|','\t')+'\n')
  code=0 if status=='checked' else 3 if status.startswith('Unsupported:') else 2 if status.startswith(('Malformed:','ResourceLimit:')) else 1
  check(id,[block,context],code)
 original=P/'blocks/original-08.cbor';supplied=P/'contexts/original-08.cbor.tsv'
 before=hashlib.sha256(original.read_bytes()).hexdigest()
 context.write_text(supplied.read_text()+supplied.read_text());check('two context rows',[original,context],2)
 context.write_bytes(b'x'*4097);check('context oversized',[original,context],2)
 check('wrong owner',[original,P/'contexts/original-09.cbor.tsv'],1)
 link=temp/'context-link';link.symlink_to(supplied);check('context symlink',[original,link],2)
 blocklink=temp/'block-link';blocklink.symlink_to(original);check('block symlink',[blocklink,supplied],2)
 check('context directory',[original,temp],2)
 block.write_bytes(b'');check('empty block',[block,supplied],2)
 block.write_bytes(b'\0'*1048577);check('block oversized',[block,supplied],2)
 block.write_bytes(bytes.fromhex('8204d8184100'));check('wire wrapper unsupported',[block,supplied],3)
 check('missing block',[temp/'missing',supplied],2)
 assert hashlib.sha256(original.read_bytes()).hexdigest()==before
check('help',['--help'],0);check('no args',[],2);check('unknown flag',['--bad'],2)
(R/'docs/cli-block-evidence-verification.json').write_text(json.dumps({'cases':results,'count':len(results),'all_passed':True,'source_authentication':False,'ledger_or_consensus_validation':False},indent=2)+'\n')
print(len(results),'direct JVM CLI cases passed')
