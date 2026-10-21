#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Observe actual exported native tentative-header criteria for every supported era."""
import argparse, hashlib, json, pathlib, subprocess, shutil, sys
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/"scripts"))
from devnet.cborspan import children
PIN="82ecba329d7d054340bf707d44fe6e9ac27cec40"
HERE=pathlib.Path(__file__).resolve().parent
FILES=[
"ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Block/SupportsDiffusionPipelining.hs",
"ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/ChainDB/API/Types/InvalidBlockPunishment.hs",
"ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/HardFork/Combinator/Node/DiffusionPipelining.hs",
"ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Protocol/PBFT.hs",
"ouroboros-consensus-cardano/src/byron/Ouroboros/Consensus/Byron/Node.hs",
"ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Node/DiffusionPipelining.hs",
"ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/MiniProtocol/BlockFetch/ClientInterface.hs",
"ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/ChainDB/Impl/ChainSel.hs"]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('--source',type=pathlib.Path,required=True);p.add_argument('--probe-dir',type=pathlib.Path,required=True);p.add_argument('--output-dir',type=pathlib.Path,required=True);p.add_argument('--image',default='scarab-33-genesis-reference-cache:local');p.add_argument('--allow-download',action='store_true');p.add_argument('--container',default='scarab-33-genesis-punishment-native');a=p.parse_args()
 probe=a.probe_dir.resolve();probe.mkdir(parents=True,exist_ok=False);commands=[]
 def run(argv):
  log=probe/f'command-{len(commands)}.log'
  with log.open('w') as out:r=subprocess.run(argv,stdout=out,stderr=subprocess.STDOUT,text=True)
  commands.append({'argv':argv,'exitCode':r.returncode,'logSha256':sha(log)});(probe/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
  if r.returncode:raise RuntimeError(f'command failed: {log}')
  return log.read_text().strip()
 if run(['git','-C',str(a.source),'rev-parse','HEAD'])!=PIN:raise ValueError('source pin differs')
 archive=probe/'source.tar';run(['git','-C',str(a.source),'archive','--format=tar','-o',str(archive),PIN]);source=probe/'source';source.mkdir();run(['tar','-xf',str(archive),'-C',str(source)]);archive.unlink()
 target=source/'scarab-punishment-observer';target.mkdir();shutil.copyfile(HERE/'Main.hs',target/'Main.hs');shutil.copyfile(HERE/'MainClassification.hs',target/'MainClassification.hs')
 original=ROOT/"fixtures/preview/conway.hex";raw=bytes.fromhex(original.read_text().splitlines()[0]);parts=children(raw,children(raw,0)[1][0]);start,end=parts[0];(probe/"original-header.cbor").write_bytes(raw[start:end])
 with (source/'ouroboros-consensus.cabal').open('a') as out:out.write('''
executable scarab-punishment-observer
  import: common-test
  hs-source-dirs: scarab-punishment-observer
  main-is: Main.hs
  ld-options: -Wl,--whole-archive /usr/local/lib/libsodium.a -Wl,--no-whole-archive
  build-depends:
    base, aeson, bytestring, containers, time, cborg, cardano-ledger-conway, ouroboros-network:api, cardano-crypto-class,
    cardano-ledger-core, cardano-ledger-shelley, cardano-protocol-tpraos, sop-core, sop-extras, strict-sop-core,
    ouroboros-consensus:{ouroboros-consensus,cardano,protocol}

executable scarab-punishment-classification
  import: common-test
  hs-source-dirs: scarab-punishment-observer
  main-is: MainClassification.hs
  ld-options: -Wl,--whole-archive /usr/local/lib/libsodium.a -Wl,--no-whole-archive
  build-depends:
    base, aeson, bytestring, resource-registry, cardano-ledger-core,
    ouroboros-consensus:{ouroboros-consensus,unstable-consensus-testlib}
''')
 created=False
 try:
  run(['docker','create','--network='+('bridge' if a.allow_download else 'none'),'--name',a.container,'--entrypoint','tail','-v',f'{probe}:/probe','-w','/probe/source',a.image,'-f','/dev/null']);created=True;run(['docker','start',a.container]);base=['docker','exec',a.container]
  run(base+['sh','-c',"sed 's/packages: harness/packages: ./' /oracle/project/cabal.project > cabal.project; printf '\\npackage blockio\\n  flags: +serialblockio\\n' >> cabal.project"])
  run(base+['cabal','build',*([] if a.allow_download else ['--offline']),'exe:scarab-punishment-observer','exe:scarab-punishment-classification','--builddir=/probe/builddir','--jobs=2'])
  exe=run(base+['cabal','list-bin','exe:scarab-punishment-observer','--builddir=/probe/builddir']).splitlines()[-1];run(base+[exe,'/probe/punishment.jsonl','/probe/original-header.cbor','/probe/projection.json']);classification=run(base+['cabal','list-bin','exe:scarab-punishment-classification','--builddir=/probe/builddir']).splitlines()[-1];run(base+[classification,'/probe/classification.jsonl']);a.output_dir.mkdir(parents=True,exist_ok=True);dest=a.output_dir/'punishment.jsonl';shutil.copyfile(probe/'punishment.jsonl',dest)
  projection=json.loads((probe/'projection.json').read_text());projection['source']={'path':'fixtures/preview/conway.hex','line':1,'blockSha256':hashlib.sha256(raw).hexdigest()};(a.output_dir/'projection.json').write_text(json.dumps(projection,indent=2)+'\n');shutil.copyfile(probe/'classification.jsonl',a.output_dir/'classification.jsonl')
  rows=[json.loads(x) for x in dest.read_text().splitlines()]
  manifest={'schema':'scarab-native-diffusion-punishment-v1','sourceCommit':PIN,'sourceFiles':{f:sha(source/f) for f in FILES},'observerSha256':sha(HERE/'Main.hs'),'classificationObserverSha256':sha(HERE/'MainClassification.hs'),'projectionSha256':sha(a.output_dir/'projection.json'),'classificationSha256':sha(a.output_dir/'classification.jsonl'),'originalBlockFixtureSha256':sha(original),'exporterSha256':sha(HERE/'export.py'),'corpusSha256':sha(dest),'traces':len(rows),'steps':sum(len(r['steps']) for r in rows),'domain':'Byron through Conway, the existing supported eras; future Dijkstra is not an exported input','image':run(['docker','inspect',a.container,'--format','{{.Image}}']),'executableSha256':run(base+['sha256sum',exe]).split()[0],'classificationExecutableSha256':run(base+['sha256sum',classification]).split()[0],'planSha256':sha(probe/'builddir/cache/plan.json')};(a.output_dir/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
 finally:
  if created:run(['docker','rm','-f',a.container])
if __name__=='__main__':main()
