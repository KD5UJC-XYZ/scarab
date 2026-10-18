# SPDX-License-Identifier: Apache-2.0
"""Prepare explicitly scoped, original-byte context API inputs; derivations are never signed admission evidence."""
import argparse
from copy import deepcopy
import hashlib
import itertools
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "scripts"))
from devnet.cbor import enc, Tag, Raw
from devnet.scenarios.v1context import wrapped, script_hash, FLAT_OK, FLAT_FAIL
from devnet.ntcquery import decode, decode_all


def raw_body(case):
    transaction = bytes.fromhex(case["transaction"])
    if transaction[0] != 0x84:
        raise ValueError("expected a four-field Conway transaction")
    _, end = decode(transaction, 1)
    return transaction[1:end]


def derived_cases(original):
    by_name={a['name']:a for a in original}
    rows=[]
    def derive(base,name,body_change=lambda b:None,wits_change=lambda w:None,models_change=lambda m:None):
        row=deepcopy(by_name[base]); body,wits,valid,aux=decode_all(bytes.fromhex(row['transaction']))
        body_change(body);wits_change(wits);models_change(row['costModels'])
        row['name']=name;row['transaction']=enc([body,wits,valid,aux]).hex()
        row['provenance']={'kind':'api-only-derived-transaction','sourceCase':base,'sourceTransactionSha256':hashlib.sha256(bytes.fromhex(by_name[base]['transaction'])).hexdigest(),'signaturesRevalidated':False,'ledgerAdmissionClaim':False}
        rows.append(row);return row

    vote={(2,b'\x11'*28):{(b'\x22'*32,0):[1,['https://example.invalid/vote',b'\x33'*32]]}}
    proposal=[[100_000_000_000,b'\xe0'+b'\x44'*28,[6],['https://example.invalid/proposal',b'\x55'*32]]]
    fields={19:vote,20:proposal,22:12345,21:98765}
    names={19:'votes-with-anchor',20:'proposals',22:'donation',21:'current-treasury'}
    for lang,base in [(1,'conway-pv10-v1-plain'),(2,'conway-pv10-v2-refinput-inline-datum'),(3,'conway-pv10-v3-refinput-inline-datum')]:
        for key,value in fields.items():
            derive(base,f'api-v{lang}-{names[key]}',lambda b,k=key,v=value:b.update({k:v}))
        derive(base,f'api-v{lang}-all-governance-fields',lambda b:b.update(fields))
        for a,b in itertools.combinations(fields,2):
            derive(base,f'api-v{lang}-{names[a]}-before-{names[b]}',lambda body,a=a,b=b:body.update({a:fields[a],b:fields[b]}))

    # Distinct scripts are hashed from their unchanged flat bytes; collection never evaluates them.
    contents=[(1,wrapped(FLAT_OK)),(1,wrapped(FLAT_FAIL)),(2,wrapped(FLAT_OK)),(2,wrapped(FLAT_FAIL))]
    policies=sorted((script_hash(lang,code),lang,code) for lang,code in contents)
    base='conway-pv10-v1-plain'
    all_models={}
    for a in original:all_models.update(a['costModels'])
    def prepare_multi(b,w,provided,inline=False):
        b[9]={policy:{b't':1} for policy,_,_ in policies}
        if inline:b[1][0]={0:b[1][0][0],1:b[1][0][1],2:[1,Tag(24,enc(42))]}
        w[3]=[code for _,lang,code in policies if lang==1]
        w[6]=[code for _,lang,code in policies if lang==2]
        w[5]={(1,i):[0,[200_000,100_000_000]] for i in provided}
        if not provided:w.pop(5)
    for provided in [(),(0,),(1,),(2,),(3,),(0,1,2),(1,2,3),(0,1,2,3)]:
        for inline in (False,True):
            row=deepcopy(by_name[base]);b,w,v,a=decode_all(bytes.fromhex(row['transaction']))
            prepare_multi(b,w,provided,inline)
            row['name']='api-collect-'+('inline-' if inline else '')+('none' if not provided else '-'.join(map(str,provided)))
            row['transaction']=enc([b,w,v,a]).hex();row['costModels']=all_models
            row['provenance']={'kind':'api-only-derived-transaction','sourceCase':base,'sourceTransactionSha256':hashlib.sha256(bytes.fromhex(by_name[base]['transaction'])).hexdigest(),'signaturesRevalidated':False,'ledgerAdmissionClaim':False}
            rows.append(row)
    for lang,base in [(2,'conway-pv10-v2-refinput-inline-datum'),(3,'conway-pv10-v3-refinput-inline-datum')]:
        derive(base,f'api-v{lang}-extra-redeemer-pointer',wits_change=lambda w:w[5].update({(1,99):[0,[200_000,100_000_000]]}))
    # Resolve the cost model only when this purpose is prepared; missing redeemers can skip it.
    collection_rows={a['name']:a for a in rows}
    for source in ['api-collect-0-1-2-3','api-collect-inline-0-1-2-3','api-collect-inline-0-1-2','api-collect-inline-1-2-3']:
        for removed in [('0',),('1',),('0','1')]:
            row=deepcopy(collection_rows[source]);row['name']=source+'-without-models-'+''.join(removed)
            row['costModels']={k:v for k,v in row['costModels'].items() if k not in removed};rows.append(row)
    derive('conway-pv10-v1-plain','api-missing-script-witness-ignored-by-collector',wits_change=lambda w:w.pop(3))
    # Every reachable Conway certificate payload and all six required-purpose variants.
    cred=[0,b'\x66'*28];rep=[2];anchor=['https://example.invalid/certificate',b'\x77'*32]
    certs={9:[9,cred,rep],10:[10,cred,b'\x88'*28,rep],11:[11,cred,b'\x88'*28,123],12:[12,cred,rep,123],13:[13,cred,b'\x88'*28,rep,123],14:[14,cred,cred],15:[15,cred,anchor],16:[16,cred,123,anchor],17:[17,cred,123],18:[18,cred,anchor]}
    for tag,cert in certs.items():
        derive('conway-pv10-v1-plain',f'api-certificate-payload-{tag}',lambda b,c=cert:b.update({4:[c]}))
    base='conway-pv10-v3-refinput-inline-datum'
    row=deepcopy(by_name[base]);body,wits,valid,aux=decode_all(bytes.fromhex(row['transaction']))
    policy=script_hash(3,wits[7][0]);reward=b'\xf0'+policy
    body.update({9:{policy:{b't':1}},5:{reward:1},4:[[16,[1,policy],123,anchor]],19:{(3,policy):{(b'\x22'*32,0):[1,anchor]}},20:[[100000000000,reward,[0,None,{},policy],anchor]]})
    spent=decode_all(bytes.fromhex(row['utxo'][0]['output']));spent[0]=b'\x70'+policy
    row['utxo'][0]['output']=enc(spent).hex()
    for provided in [(),(0,1,2,3,4,5),(0,2,4)]:
        witness=deepcopy(wits)
        if provided:witness[5]={(tag,0):[0,[200000,100000000]] for tag in provided}
        else:witness.pop(5,None)
        probe=deepcopy(row);probe['name']='api-all-six-purposes-'+('none' if not provided else '-'.join(map(str,provided)))
        probe['transaction']=enc([body,witness,valid,aux]).hex()
        probe['provenance']={'kind':'api-only-derived-transaction','sourceCase':base,'sourceTransactionSha256':hashlib.sha256(bytes.fromhex(by_name[base]['transaction'])).hexdigest(),'signaturesRevalidated':False,'ledgerAdmissionClaim':False}
        rows.append(probe)
    relay_host=b'relay.invalid';metadata_url=b'https://example.invalid/pool'
    relay_raw=bytes.fromhex('9801980318011900bb7900')+bytes([len(relay_host)])+relay_host
    metadata_raw=bytes.fromhex('98027900')+bytes([len(metadata_url)])+metadata_url+bytes.fromhex('590020')+b'\x99'*32
    pool_cert=[3,b'\x66'*28,b'\x66'*32,1000,340000000,Tag(30,[1,2]),b'\xe0'+b'\x66'*28,Tag(258,[b'\x66'*28]),Raw(relay_raw),Raw(metadata_raw)]
    derive('conway-pv10-v1-plain','api-pool-noncanonical-payload',lambda b:b.update({4:[pool_cert]}))
    update=Raw(b'\xbf'+enc(9)+enc(Tag(30,[2,4]))+enc(0)+b'\x18\x01\xff')
    noncanonical_proposal=[[100000000000,b'\xe0'+b'\x44'*28,[0,None,update,None],['https://example.invalid/proposal',b'\x55'*32]]]
    derive('conway-pv10-v1-plain','api-proposal-noncanonical-params',lambda b:b.update({20:noncanonical_proposal}))
    for row in rows:
        provenance = row["provenance"]
        provenance["bodyMatchesOriginal"] = raw_body(row) == raw_body(by_name[provenance["sourceCase"]])
    return rows


def original_case(case, name, source):
    entries = []
    for entry in case.get("spent", []) + case.get("references", []) + case.get("collateral", []):
        txid, index = entry["in"].split("#")
        entries.append({"input": enc([bytes.fromhex(txid), int(index)]).hex(), "output": entry["out"]})
    transaction = enc([Raw(bytes.fromhex(case["body"])), Raw(bytes.fromhex(case["witnesses"])),
                       case["isValid"], Raw(bytes.fromhex(case["auxiliary"])) if case.get("auxiliary") else None]).hex()
    if "contextCase" in case:
        assert transaction == case["contextCase"]["transaction"]
    return {"name": name, "transaction": transaction, "utxo": entries,
            "costModels": case["costModels"], "protocolMajor": case["protocolMajor"],
            "provenance": {"kind": "retained-original-reference-transaction",
                           "sourcePath": str(source.relative_to(ROOT)),
                           "sourceFileSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                           "sourceCase": case["name"],
                           "transactionSha256": hashlib.sha256(bytes.fromhex(transaction)).hexdigest(),
                           "hasSeparateSubmissionEvidence": True, "ledgerAdmissionClaim": False}}


def prepare(submissions, legacy):
    submissions, legacy = Path(submissions).resolve(), Path(legacy).resolve()
    original = [original_case(case, case["name"], submissions)
                for case in json.loads(submissions.read_text())]
    previous = []
    for path in sorted(legacy.glob("*.json")):
        values = json.loads(path.read_text())
        if not isinstance(values, list):
            continue
        previous.extend(original_case(case, "legacy-" + path.stem + "-" + case["name"], path)
                        for case in values if case["protocolMajor"] >= 9)
    result = original + previous + derived_cases(original)
    assert len({case["name"] for case in result}) == len(result)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--submissions", type=Path, default=ROOT / "fixtures/reference/context-errors/submissions/conway-pv10.json")
    parser.add_argument("--legacy", type=Path, default=ROOT / "fixtures/reference/v1-context")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    cases = prepare(args.submissions, args.legacy)
    args.out.write_text(json.dumps(cases, indent=1) + "\n")
    print(f"Prepared {len(cases)} context API cases; derived cases make no ledger-admission claim")
