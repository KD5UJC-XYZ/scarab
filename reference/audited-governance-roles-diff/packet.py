#!/usr/bin/env python3
"""Prepare/compare finite external fixtures only. Never runs Docker or native code."""
import argparse
import hashlib
import json
from pathlib import Path

PIN = "19bff89f7dfd13f54312ba3cb6dfcad46ebb8b69d347221df0d2965948b49dc7"
PINS = {"previousParameters": "0f6d70064c39fb492af2f456aa87e3ce44195bb791da4a74a54d507bb01899ef",
        "currentParameters": "75146abbc571a0a633ea1b62dc34d4e0c85164f29a1492ecf20f1929070d9600"}
def digest(raw): return hashlib.sha256(raw).hexdigest()
def strict_json(raw):
    def unique(pairs):
        result={}
        for k,v in pairs:
            if k in result: raise ValueError("duplicate JSON key")
            result[k]=v
        return result
    def bad_constant(value): raise ValueError("nonfinite JSON constant")
    return json.loads(raw,object_pairs_hook=unique,parse_constant=bad_constant)
def load(path, cap):
    with path.open("rb") as f: raw = f.read(cap+1)
    if len(raw)>cap: raise ValueError("input bound")
    return raw
def items(raw):
    """Bounded CBOR framing, retaining original native indefinite cost-model arrays."""
    count=0
    def read(pos,depth=0):
        nonlocal count
        count+=1
        if depth>16 or count>8192 or pos>=len(raw): raise ValueError("CBOR bound")
        start=pos; h=raw[pos]; pos+=1; major=h>>5; ai=h&31
        if ai==31 and major in (4,5):
            children=[]
            while pos<len(raw) and raw[pos]!=255:
                child,pos=read(pos,depth+1); children.append(child)
            if pos>=len(raw) or (major==5 and len(children)%2): raise ValueError("indefinite CBOR")
            pos+=1
            return (major,len(children)//(2 if major==5 else 1),raw[start:pos],children),pos
        if ai<24: size=ai
        elif ai in (24,25,26,27):
            n=1<<(ai-24)
            if pos+n>len(raw): raise ValueError("CBOR truncated")
            size=int.from_bytes(raw[pos:pos+n],"big"); pos+=n
        else: raise ValueError("definite CBOR required")
        children=[]
        if major in (2,3): pos+=size
        elif major in (4,5,6):
            for _ in range(1 if major==6 else size*(2 if major==5 else 1)):
                child,pos=read(pos,depth+1); children.append(child)
        elif major not in (0,1,7): raise ValueError("CBOR major")
        if pos>len(raw): raise ValueError("CBOR truncated")
        return (major,size,raw[start:pos],children),pos
    value,end=read(0)
    if end!=len(raw): raise ValueError("trailing CBOR")
    return value
def extract(bundle):
    raw=load(bundle/"native-projection.json",4*1024*1024)
    if digest(raw)!=PIN: raise ValueError("audited native projection pin mismatch")
    document=strict_json(raw)
    result={}
    for role,pin in PINS.items():
        component=document["components"][role]
        if component["encoding"]!="native-encCBOR-pv9": raise ValueError("component encoding")
        value=bytes.fromhex(component["cborHex"])
        if len(value)>65536 or digest(value)!=pin or component["sha256"]!=pin: raise ValueError("component pin")
        parsed=items(value)
        if parsed[0:2]!=(4,31): raise ValueError("31-field PParams")
        result[role]=(value,parsed[3])
    a,b=(result[k][1] for k in PINS)
    if [i for i in range(31) if a[i][2]!=b[i][2]]!=[15]: raise ValueError("cost-model-only difference required")
    for fields,languages in [(a,[0,2]),(b,[0,1,2])]:
        cost=fields[15]
        if cost[0]!=5 or [x[1] for x in cost[3][::2]]!=languages: raise ValueError("language keys")
        for language,model in zip(cost[3][::2],cost[3][1::2]):
            if language[0]!=0 or model[0]!=4 or model[1]!={0:166,1:175,2:251}[language[1]]:
                raise ValueError("complete native cost-model length")
            for value in model[3]:
                if value[0] not in (0,1) or value[1] >= 1<<63: raise ValueError("signed64 model bound")
    return {k:v[0] for k,v in result.items()}
def expected(values):
    previous=values["previousParameters"].hex(); current=values["currentParameters"].hex()
    def roles(oc,op,ec,ep): return dict(outerCurrent=oc,outerPrevious=op,completedCurrent=ec,completedPrevious=ep)
    cases=[]
    for name,completed in [("exact-current",current),("previous-costs-only",previous)]:
        cases.append(dict(id=name,status="accepted",nativeSTSExecuted=True,
            before=roles(current,previous,completed,previous),after=roles(current,current,current,current),
            afterFuture="PotentialNone",wholeParameterEqualityChecked=True))
    cases.append(dict(id="reject-non-cost-change",status="profile-rejected",
        reason="non-cost-parameter-difference",nativeSTSExecuted=False))
    return dict(schema="audited-governance-roles-result-v1",producer="native-newepoch-normalized-governance",cases=cases)
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",choices=["inspect","prepare","compare"])
    parser.add_argument("--bundle",type=Path,required=True)
    parser.add_argument("--output",type=Path)
    parser.add_argument("--result",type=Path)
    args=parser.parse_args(); values=extract(args.bundle)
    if args.action=="inspect":
        print(json.dumps({"sourcePinsChecked":True,"differentFields":[15],"previousLanguages":["V1","V3"],"currentLanguages":["V1","V2","V3"],"completeModelLengths":{"V1":166,"V2":175,"V3":251},"signed64BoundsChecked":True,"nativeExecuted":False}))
    elif args.action=="prepare":
        if args.output is None: parser.error("--output required")
        target=args.output.resolve(); repo=Path(__file__).resolve().parents[2]
        if target==repo or repo in target.parents: raise ValueError("disposable fixtures must remain outside Git")
        target.mkdir(parents=True,exist_ok=False)
        for role,name in [("previousParameters","previous"),("currentParameters","current")]:
            (target/(name+"-parameters.cbor")).write_bytes(values[role])
        (target/"expected.json").write_text(json.dumps(expected(values),sort_keys=True)+"\n")
        print(json.dumps({"prepared":str(target),"nativeExecuted":False}))
    else:
        if args.result is None: parser.error("--result required")
        actual=strict_json(load(args.result,256*1024))
        if actual!=expected(values): raise ValueError("native result differs from independently expected finite roles")
        print(json.dumps({"match":True,"cases":3,"nativeInvokedByComparator":False}))
if __name__=="__main__": main()
