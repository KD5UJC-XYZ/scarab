"""Offline native projection equivalence; never a new acquired capture packet."""
import argparse,hashlib,json,pathlib,subprocess,time
from fractions import Fraction
from bounded import bounded_process

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--offline',required=True);ap.add_argument('--evidence',required=True);a=ap.parse_args()
 root=pathlib.Path(a.evidence);names=['pre-ledger-state.cbor','pre-ledger-state.md','pre-utxo-cbor.md']
 originals={name:(root/name).read_bytes() for name in names}
 epoch=originals['pre-ledger-state.cbor'];utxo=bytes.fromhex(originals['pre-utxo-cbor.md'].decode().strip())
 began=time.monotonic()
 raw=bounded_process([a.offline,'project-retained','+RTS','-M768m','-K16m','-N1','-RTS'],json.dumps({'epochHex':epoch.hex(),'utxoHex':utxo.hex()}).encode(),timeout=20)
 projected=json.loads(raw);ledger_bytes=bytes.fromhex(projected['ledgerJsonHex']);ledger=json.loads(ledger_bytes);reference=json.loads(originals['pre-ledger-state.md'])
 assert ledger['lastEpoch']==reference['lastEpoch']
 assert ledger['stateBefore']['esLState']['utxoState']['fees']==reference['stateBefore']['esLState']['utxoState']['fees']
 actual=ledger['stakeDistrib'];expected=reference['stakeDistrib'];assert actual==expected
 pools=actual['unPoolDistr'];assert len(pools)>0
 checked={}
 for key,row in pools.items():
  assert len(key)==56 and bytes.fromhex(key)
  vrf=row['individualPoolStakeVrf'];assert len(vrf)==64 and bytes.fromhex(vrf)
  share=row['individualPoolStake'];rational=Fraction(share['numerator'],share['denominator'])
  assert 0<rational<=1
  checked[key]={'vrf':vrf,'numerator':rational.numerator,'denominator':rational.denominator,'totalStake':row['individualTotalPoolStake']}
 for name,data in originals.items():assert (root/name).read_bytes()==data
 (root/'derived-ledger.json').write_bytes(ledger_bytes)
 report={'schema':1,'success':True,'kind':'retained-native-ledger-projection-compatibility','inputSHA256':{n:hashlib.sha256(d).hexdigest() for n,d in originals.items()},'derivedLedgerSHA256':hashlib.sha256(ledger_bytes).hexdigest(),'nativeHelperSHA256':hashlib.sha256(pathlib.Path(a.offline).read_bytes()).hexdigest(),'nonemptyPoolCount':len(pools),'poolChecks':checked,'completeStakeDistributionEqual':True,'epochEqual':True,'feePotEqual':True,'epoch':ledger['lastEpoch'],'fees':ledger['stateBefore']['esLState']['utxoState']['fees'],'utxoEntries':projected['utxoEntries'],'seconds':time.monotonic()-began,'newAcquiredCapture':False,'protocolAndParametersInHarness':'synthetic-only-not-compared','monetaryParity':False,'fullLedgerValidation':False}
 (root/'retained-comparison.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()
