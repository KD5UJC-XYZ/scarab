#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Post-run exact likelihood differential. Never an in-service authority or fallback."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import struct
import subprocess
import time

HEX = re.compile(r"[0-9a-f]{64}\Z")
ROW = re.compile(r"([0-9a-f]{56}) ([0-9a-f]{16}) ([0-9a-f]{800})\n")
MAX_BYTES = 131072
PLUTUS_PROFILE = "isolated-conway-pv9-plutus-v3-spend-v1"

def require(condition, reason):
    if not condition:
        raise ValueError(reason)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def read(path, maximum=MAX_BYTES):
    require(path.is_file() and not path.is_symlink(), "regular original file required: " + str(path))
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    require(len(data) <= maximum, "original size bound: " + str(path))
    return data

def unique_object(pairs):
    result = {}
    for k, v in pairs:
        require(k not in result, "duplicate JSON key")
        result[k] = v
    return result

def document(data):
    return json.loads(data, object_pairs_hook=unique_object)

def write(path, data):
    with path.open("xb") as stream:
        stream.write(data)

def json_bytes(value):
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()

def request_rows(data):
    text = data.decode("ascii")
    lines = text.splitlines(keepends=True)
    require(3 <= len(lines) <= 67, "request row count")
    require(lines[0] == "conway-native-likelihood-v1\n" and
            HEX.fullmatch(lines[1][:-1]) and lines[1].endswith("\n") and
            lines[2] == "1000 1 20 0 1\n", "request header/geometry")
    pools = []
    for line in lines[3:]:
        match = re.fullmatch(r"([0-9a-f]{56}) (0|[1-9][0-9]{0,19}) ([1-9][0-9]{0,19}) (0|[1-9][0-9]{0,3})\n", line)
        require(match is not None, "canonical request row")
        pool, stake, circulation, blocks = match.groups()
        require(0 <= int(stake) <= int(circulation) <= 2**64-1 and int(blocks) <= 1000,
                "request numeric bounds")
        pools.append(pool)
    require(pools == sorted(set(pools)), "request ordered unique full pool domain")
    return lines[1][:-1], pools

def output_rows(data, prefix, pools):
    require(data.startswith(prefix), "output must echo exact original request")
    text = data[len(prefix):].decode("ascii")
    matches = list(ROW.finditer(text))
    require("".join(m.group(0) for m in matches) == text, "canonical output row")
    require([m.group(1) for m in matches] == pools, "output full pool domain/order")
    result = []
    for match in matches:
        pool, probability, raw = match.groups()
        require(math.isfinite(struct.unpack(">d", bytes.fromhex(probability))[0]), "nonfinite probability")
        words = [raw[i:i+8] for i in range(0,800,8)]
        require(all(math.isfinite(struct.unpack(">f", bytes.fromhex(w))[0]) for w in words), "nonfinite likelihood")
        result.append((pool, probability, words))
    return result

def compare(request, jvm, native):
    _, pools = request_rows(request)
    actual = output_rows(jvm, b"conway-jvm-likelihood-result-v1\n"+request+b"--jvm--\n", pools)
    expected = output_rows(native, request+b"--native--\n", pools)
    differences = []
    mismatch32 = mismatch64 = 0
    for (pool, probability, words), (_, native_probability, native_words) in zip(actual, expected):
        if probability != native_probability:
            mismatch64 += 1
            differences.append(dict(pool=pool, primitive="leaderProbability", raw64Jvm=probability, raw64Native=native_probability))
        for index, (word, native_word) in enumerate(zip(words, native_words)):
            if word != native_word:
                mismatch32 += 1
                differences.append(dict(pool=pool, sample=index, raw32Jvm=word, raw32Native=native_word))
    return dict(raw32Comparisons=len(pools)*100, raw64Comparisons=len(pools),
                raw32Mismatches=mismatch32, raw64Mismatches=mismatch64, differences=differences[:256],differencesOmitted=max(0,len(differences)-256))

def full_point(point):
    require(isinstance(point,dict) and set(point)=={"slot","blockNo","hash"} and
            all(type(point[k]) is int and 0<=point[k]<2**64 for k in ("slot","blockNo")) and
            isinstance(point["hash"],str) and HEX.fullmatch(point["hash"]),"canonical full point")
    return point

def full_pin(pin):
    require(isinstance(pin,dict) and set(pin)=={"ownerId","generation","point","coherentStateId","ledgerStateId","environmentId","validationSlot","profileId"},"canonical full pin fields")
    require(all(isinstance(pin[k],str) and HEX.fullmatch(pin[k]) for k in ("ownerId","coherentStateId","ledgerStateId","environmentId")),"full pin hash fields")
    require(all(type(pin[k]) is int and 0<=pin[k]<2**64 for k in ("generation","validationSlot")) and
            pin["profileId"]==PLUTUS_PROFILE,"full pin values")
    full_point(pin["point"])
    require(pin["validationSlot"]==pin["point"]["slot"],"pin validation slot equals point")
    return pin

def inspect_service(service, generations):
    result_bytes = read(service/"result.json", 4*1024*1024)
    result = document(result_bytes)
    require(result.get("schema") == "plutus-service-result-v1" and result.get("status") == "stopped" and
            result.get("resourcesFinalized") is True and result.get("fullLedgerValidated") is False and result.get("epochMode")=="repeated-jvm-v1", "completed service resource finalization required")
    require(type(result.get("transportOpens")) is int and result["transportOpens"] > 0 and
            result.get("transportCloses") == result["transportOpens"], "service transport finalization")
    ready_bytes=read(service/"bootstrap-ready.json")
    active_bytes=read(service/"service-active.json")
    ready=document(ready_bytes);active=document(active_bytes)
    require(ready.get("schema")=="plutus-service-ready-v1" and active.get("schema")=="plutus-service-active-v1" and
            ready.get("epochMode")==active.get("epochMode")=="repeated-jvm-v1","pure JVM startup originals")
    for key in ("sourceJoinId","initialManifestSHA256"):
        require(isinstance(result.get(key),str) and HEX.fullmatch(result[key]) and ready.get(key)==result[key],"startup/result source identity")
    require(isinstance(result.get("profileId"),str) and ready.get("profileId")==result["profileId"],"startup/result profile")
    initial_pin=full_pin(active.get("pin"));final_pin=full_pin(result.get("finalPin"))
    require(initial_pin["profileId"]==final_pin["profileId"]==result["profileId"] and
            initial_pin["ownerId"]==final_pin["ownerId"],"one service owner/profile")
    require(full_point(result.get("initialPoint"))==ready.get("initialPoint")==initial_pin["point"],"initial fullpoint binding")
    refs = result.get("publications")
    require(isinstance(refs,list) and 1 <= len(refs) <= 512, "bounded publications required")
    bindings = {}
    originals = {"result.json": result_bytes,"bootstrap-ready.json":ready_bytes,"service-active.json":active_bytes}
    aggregate_bytes=sum(map(len,originals.values()))
    previous_pin=initial_pin
    seen_files = set()
    for index,ref in enumerate(refs):
        name = ref.get("file", "")
        require(re.fullmatch(r"publication-[0-9]{4}\.json", name) and name not in seen_files, "publication filename/duplicate")
        seen_files.add(name)
        require(name==f"publication-{index:04d}.json","publication contiguous index")
        raw = read(service/name)
        aggregate_bytes+=len(raw)
        require(aggregate_bytes<=16*1024*1024,"aggregate service original bound")
        require(digest(raw) == ref.get("sha256"), "publication original hash")
        originals[name] = raw
        publication = document(raw)
        require(publication.get("schema")=="plutus-service-publication-v1", "publication schema")
        require(publication.get("pin") == ref.get("pin") and isinstance(ref.get("pin"),dict), "publication full pin")
        pin=full_pin(publication["pin"])
        require(publication.get("diagnosticOnly") is True and publication.get("fullLedgerValidated") is False and
                type(publication.get("index")) is int and publication["index"]==index and pin["ownerId"]==initial_pin["ownerId"] and
                pin["profileId"]==initial_pin["profileId"] and pin["generation"]==previous_pin["generation"]+1 and
                pin["point"]["slot"]>previous_pin["point"]["slot"] and pin["point"]["blockNo"]==previous_pin["point"]["blockNo"]+1,
                "publication ordered owner/generation/fullpoint")
        previous_pin=pin
        require(all(publication.get(k)==result[k] for k in ("sourceJoinId","initialManifestSHA256","profileId")),"publication service source/profile")
        repeated=publication.get("repeatedEpoch")
        require(isinstance(repeated,dict) and all(isinstance(repeated.get(k),str) and HEX.fullmatch(repeated[k]) for k in ("componentId","nonMyopicId")),"repeated component identities")
        require(type(repeated.get("epoch")) is int and repeated["epoch"]==pin["point"]["slot"]//1000,"repeated publication epoch")
        metadata=repeated.get("checkedLikelihood")
        if metadata is None:
            require("checkedLikelihood" in repeated and repeated.get("frozenId") is None and repeated.get("allocationId") is None,"no generation component pairing")
            continue
        require(isinstance(metadata,dict) and repeated.get("frozenId")==metadata.get("frozenId") and
                isinstance(repeated.get("allocationId"),str) and HEX.fullmatch(repeated["allocationId"]),"selected frozen/allocation identities")
        require(metadata.get("mode") == "pure-jvm" and "nativeResponseSHA256" in metadata and metadata["nativeResponseSHA256"] is None and
                metadata.get("nativeValidated") is False and metadata.get("diagnosticNativeDependency") is False and
                metadata.get("raw32Comparisons")==0 and metadata.get("raw64Comparisons")==0 and metadata.get("jvmMismatchWords")==0,
                "only explicit pure JVM publications accepted")
        for key in ("frozenId","requestSHA256","evidenceSHA256"):
            require(isinstance(metadata.get(key),str) and HEX.fullmatch(metadata[key]), "publication identity/hash")
        require(HEX.fullmatch(metadata.get("preTickTupleId","")) and
                all(type(metadata.get(k)) is int and metadata[k]>=0 for k in
                    ("applicationEpoch","observedSlot","computedRaw32Words","computedRaw64Words")), "captured metadata types")
        require(metadata["observedSlot"]<=pin["point"]["slot"] and metadata["applicationEpoch"]==repeated["epoch"]==metadata["observedSlot"]//1000,"captured observation/application epoch binding")
        key = (metadata["requestSHA256"],metadata["evidenceSHA256"])
        if key in bindings:
            require(bindings[key][0]["metadata"]==metadata,"same generation metadata must remain identical")
        binding = dict(file=name,sha256=digest(raw),pin=ref["pin"],metadata=metadata)
        bindings.setdefault(key,[]).append(binding)
    require(previous_pin==final_pin,"final pin equals last published full pin")
    require(bindings, "no published pure JVM generation")
    directories = sorted(generations.iterdir())
    require(1 <= len(directories) <= 128 and all(p.is_dir() and not p.is_symlink() and
            re.fullmatch(r"generation-[0-9]{4}",p.name) for p in directories), "bounded generation directory set")
    records = []
    matched = set()
    unreferenced = []
    for directory in directories:
        # Failed or stale drafts may leave partial evidence; never promote them by directory index.
        if not all((directory/f).is_file() for f in ("request.txt","jvm-result.txt","execution.txt")):
            unreferenced.append(dict(directory=directory.name, complete=False))
            continue
        request=read(directory/"request.txt"); jvm=read(directory/"jvm-result.txt")
        frozen,pools=request_rows(request)
        output_rows(jvm,b"conway-jvm-likelihood-result-v1\n"+request+b"--jvm--\n",pools)
        receipt=read(directory/"execution.txt",4096)
        expected=f"jvm-likelihood-execution-v1\n{digest(request)}\n{digest(jvm)}\nPureJvm\n{len(pools)*100} {len(pools)}\n0 0\n".encode()
        require(receipt == expected,"canonical JVM execution receipt")
        key=(digest(request),digest(jvm))
        if key not in bindings:
            unreferenced.append(dict(directory=directory.name,complete=True,requestSHA256=key[0],evidenceSHA256=key[1]))
            continue
        require(key not in matched,"duplicate recorded published generation")
        matched.add(key)
        for binding in bindings[key]:
            meta=binding["metadata"]
            require(meta["frozenId"]==frozen and meta.get("computedRaw32Words")==len(pools)*100 and
                    meta.get("computedRaw64Words")==len(pools),"publication captured source/computed count binding")
        records.append(dict(directory=directory.name, request=request,jvm=jvm,receipt=receipt,bindings=bindings[key],frozenId=frozen))
    require(matched == set(bindings),"every published generation must have complete original evidence")
    return dict(resultSHA256=digest(result_bytes),stopReason=result.get("stopReason"),records=records,unreferenced=unreferenced,originals=originals)

def pin_file(entry, maximum):
    require(isinstance(entry,dict) and set(entry)=={"path","sha256"} and HEX.fullmatch(entry.get("sha256","")),"explicit file pin")
    path=Path(entry["path"])
    require(path.is_absolute(),"absolute pinned path")
    data=read(path,maximum)
    require(digest(data)==entry["sha256"],"file pin mismatch: "+str(path))
    return data

def prepare_native(config_path, config_pin, output):
    raw=read(config_path,65536)
    require(HEX.fullmatch(config_pin) and digest(raw)==config_pin,"native config hash")
    config=document(raw)
    require(set(config)=={"schema","image","executable","libraries","sources","timeoutSeconds"} and
            config["schema"]=="likelihood-postrun-native-config-v1","native config schema")
    require(re.fullmatch(r"sha256:[0-9a-f]{64}",config["image"]),"immutable image ID")
    require(type(config["timeoutSeconds"]) is int and 1<=config["timeoutSeconds"]<=30,"helper timeout bound")
    libs=config["libraries"]; sources=config["sources"]
    require(isinstance(libs,dict) and 1<=len(libs)<=32 and isinstance(sources,dict) and 1<=len(sources)<=32,"explicit library/source pin domain")
    bundle=output/"native";bundle.mkdir()
    write(bundle/"helper",pin_file(config["executable"],256*1024*1024));(bundle/"helper").chmod(0o555)
    library_directory=bundle/"lib";library_directory.mkdir()
    for name,entry in libs.items():
        require(re.fullmatch(r"lib[A-Za-z0-9_.+-]+\.so(?:\.[0-9]+)*",name),"library loader name")
        write(library_directory/name,pin_file(entry,16*1024*1024))
    for name,entry in sources.items():
        require(re.fullmatch(r"[A-Za-z0-9_.-]+",name),"source label")
        pin_file(entry,8*1024*1024)
    write(output/"native-config.json",raw)
    return config,bundle

def native_run(config,bundle,directory):
    name="likelihood-postrun-"+secrets.token_hex(12)
    owner=secrets.token_hex(24)
    command=["docker","run","--rm","--name",name,"--label","cardano.likelihood.postrun="+owner,
             "--pull=never","--network=none","--cpus=1","--memory=2g","--memory-swap=2g","--pids-limit=64",
             "--cap-drop=ALL","--security-opt=no-new-privileges","--read-only","--user",f"{os.getuid()}:{os.getgid()}",
             "--tmpfs","/tmp:size=16m","-e","LD_LIBRARY_PATH=/native/lib",
             "-v",str(bundle)+":/native:ro","-v",str(directory)+":/input:ro",
             "--entrypoint=/native/helper",config["image"],"/input/request.txt"]
    write(directory/"command.json",json_bytes(command))
    began=time.monotonic()
    process=None
    try:
        with (directory/"native-result.txt").open("xb") as stdout,(directory/"stderr.txt").open("xb") as stderr:
            process=subprocess.Popen(command,stdout=stdout,stderr=stderr)
            while process.poll() is None:
                require(time.monotonic()-began <= config["timeoutSeconds"],"native helper timeout")
                require((directory/"native-result.txt").stat().st_size<=MAX_BYTES and
                        (directory/"stderr.txt").stat().st_size<=8192,"native helper output bound")
                time.sleep(0.02)
            require(process.returncode==0,"native helper failed")
        response=read(directory/"native-result.txt")
        read(directory/"stderr.txt",8192)
        return response
    finally:
        try:
            probe=subprocess.run(["docker","inspect",name],capture_output=True,timeout=10)
            if probe.returncode==0:
                entries=json.loads(probe.stdout)
                require(len(entries)==1 and entries[0]["Config"]["Labels"].get("cardano.likelihood.postrun")==owner,"owned container identity")
                subprocess.run(["docker","rm","-f",entries[0]["Id"]],check=True,capture_output=True,timeout=10)
            else:
                # A missing owned container is expected after docker run --rm; daemon errors are not.
                require(b"No such object" in probe.stderr or b"No such container" in probe.stderr,"container cleanup unconfirmed")
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill();process.wait(timeout=5)

def verify(service,generations,config_path,config_pin,output):
    service=service.resolve(strict=True); generations=generations.resolve(strict=True)
    captured=inspect_service(service,generations)
    require(output.is_absolute(),"absolute fresh output directory")
    output.mkdir()
    original_directory=output/"service-originals";original_directory.mkdir()
    for name,raw in captured["originals"].items():
        write(original_directory/name,raw)
    config,bundle=prepare_native(config_path,config_pin,output)
    report=dict(schema="likelihood-postrun-differential-v1",runtimeMode="pure-jvm",postRunOnly=True,
                runtimeNativeDependency=False,valuesReplaced=False,generalJvmParityValidated=False,
                serviceResultSHA256=captured["resultSHA256"],serviceStopReason=captured["stopReason"],
                nativeConfigSHA256=config_pin,nativePins=config,unreferencedRecords=captured["unreferenced"],generations=[])
    try:
        for record in captured["records"]:
            directory=output/record["directory"];directory.mkdir()
            write(directory/"request.txt",record["request"])
            write(directory/"jvm-result.txt",record["jvm"])
            write(directory/"jvm-execution.txt",record["receipt"])
            native=native_run(config,bundle,directory)
            compared=compare(record["request"],record["jvm"],native)
            row=dict(directory=record["directory"],frozenId=record["frozenId"],publications=record["bindings"],
                     requestSHA256=digest(record["request"]),evidenceSHA256=digest(record["jvm"]),
                     nativeResponseSHA256=digest(native),**compared)
            report["generations"].append(row)
        for key in ("raw32Comparisons","raw64Comparisons","raw32Mismatches","raw64Mismatches"):
            report[key]=sum(row[key] for row in report["generations"])
        report["exactMatch"]=report["raw32Mismatches"]+report["raw64Mismatches"]==0
        report["status"]="matched" if report["exactMatch"] else "mismatch"
    except BaseException as error:
        report["status"]="failed"
        report["failureType"]=type(error).__name__
        report["failure"]=str(error)
        write(output/"result.json",json_bytes(report))
        raise
    write(output/"result.json",json_bytes(report))
    return report

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service-output",type=Path,required=True)
    parser.add_argument("--generation-directory",type=Path,required=True)
    parser.add_argument("--native-config",type=Path,required=True)
    parser.add_argument("--native-config-sha256",required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    report=verify(args.service_output,args.generation_directory,args.native_config,args.native_config_sha256,args.output)
    print(json.dumps({k:report[k] for k in ("status","raw32Comparisons","raw64Comparisons","raw32Mismatches","raw64Mismatches")}))
    return 0 if report["exactMatch"] else 1

if __name__=="__main__":
    raise SystemExit(main())
