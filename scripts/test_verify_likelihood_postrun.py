# SPDX-License-Identifier: Apache-2.0
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import verify_likelihood_postrun as v

class PostRunTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.service=self.root/"service";self.service.mkdir()
        self.generations=self.service/"jvm-likelihood";self.generations.mkdir()
        self.directory=self.generations/"generation-0000";self.directory.mkdir()
        self.request=("conway-native-likelihood-v1\n"+"01"*32+"\n1000 1 20 0 1\n"+"02"*28+" 0 3000 0\n").encode()
        self.row=("02"*28+" 0000000000000000 "+"00000000"*100+"\n").encode()
        self.jvm=b"conway-jvm-likelihood-result-v1\n"+self.request+b"--jvm--\n"+self.row
        self.native=self.request+b"--native--\n"+self.row
        self.record(self.request,self.jvm)
        self.meta=dict(mode="pure-jvm",frozenId="01"*32,requestSHA256=v.digest(self.request),evidenceSHA256=v.digest(self.jvm),
                       nativeResponseSHA256=None,computedRaw32Words=100,computedRaw64Words=1,
                       applicationEpoch=0,observedSlot=110,preTickTupleId="04"*32,nativeValidated=False,diagnosticNativeDependency=False,raw32Comparisons=0,raw64Comparisons=0,jvmMismatchWords=0)
        self.pin=dict(ownerId="05"*32,generation=1,point=dict(slot=110,blockNo=2,hash="06"*32),coherentStateId="07"*32,ledgerStateId="08"*32,environmentId="09"*32,validationSlot=110,profileId=v.PLUTUS_PROFILE)
        self.repeated=dict(epoch=0,componentId="0a"*32,nonMyopicId="0b"*32,frozenId="01"*32,allocationId="0c"*32,checkedLikelihood=self.meta)
        self.publication=dict(schema="plutus-service-publication-v1",index=0,diagnosticOnly=True,fullLedgerValidated=False,pin=self.pin,repeatedEpoch=self.repeated,profileId=v.PLUTUS_PROFILE,sourceJoinId="0d"*32,initialManifestSHA256="0e"*32)
        self.result=dict(schema="plutus-service-result-v1",epochMode="repeated-jvm-v1",status="stopped",stopReason="durationLimit",
                         resourcesFinalized=True,fullLedgerValidated=False,transportOpens=1,transportCloses=1,profileId=v.PLUTUS_PROFILE,sourceJoinId="0d"*32,initialManifestSHA256="0e"*32,finalPin=self.pin,initialPoint=dict(slot=100,blockNo=1,hash="0f"*32))
        initial=copy.deepcopy(self.pin);initial["generation"]=0;initial["point"]=self.result["initialPoint"];initial["validationSlot"]=100
        (self.service/"bootstrap-ready.json").write_bytes(v.json_bytes(dict(schema="plutus-service-ready-v1",epochMode="repeated-jvm-v1",profileId=v.PLUTUS_PROFILE,sourceJoinId="0d"*32,initialManifestSHA256="0e"*32,initialPoint=self.result["initialPoint"])))
        (self.service/"service-active.json").write_bytes(v.json_bytes(dict(schema="plutus-service-active-v1",epochMode="repeated-jvm-v1",pin=initial)))
        self.publish()

    def record(self,request,jvm):
        (self.directory/"request.txt").write_bytes(request)
        (self.directory/"jvm-result.txt").write_bytes(jvm)
        (self.directory/"execution.txt").write_bytes(f"jvm-likelihood-execution-v1\n{v.digest(request)}\n{v.digest(jvm)}\nPureJvm\n100 1\n0 0\n".encode())

    def publish(self):
        raw=v.json_bytes(self.publication)
        (self.service/"publication-0000.json").write_bytes(raw)
        self.result["publications"]=[dict(file="publication-0000.json",sha256=v.digest(raw),pin=self.publication["pin"])]
        (self.service/"result.json").write_bytes(v.json_bytes(self.result))

    def inspect(self):
        return v.inspect_service(self.service,self.generations)

    def test_originals_bind_and_compare_all_words(self):
        self.assertEqual(len(self.inspect()["records"]),1)
        result=v.compare(self.request,self.jvm,self.native)
        self.assertEqual((result["raw32Comparisons"],result["raw64Comparisons"]),(100,1))
        self.assertEqual((result["raw32Mismatches"],result["raw64Mismatches"]),(0,0))

    def test_exact_one_bit_mismatches_never_tolerance_pass(self):
        changed=self.native[:-801]+b"00000001"+self.native[-793:]
        result=v.compare(self.request,self.jvm,changed)
        self.assertEqual(result["raw32Mismatches"],1)
        self.assertEqual(result["differences"][0]["sample"],0)
        probability=self.native.replace(b" 0000000000000000 ",b" 0000000000000001 ")
        self.assertEqual(v.compare(self.request,self.jvm,probability)["raw64Mismatches"],1)

    def test_canonical_request_rejects_bounds_order_and_geometry(self):
        for changed in (self.request.replace(b"1000 1 20",b"1001 1 20"),self.request.replace(b" 0 3000 0",b" 00 3000 0"),
                        self.request.replace(b" 0 3000 0",b" 3001 3000 0"),self.request.replace(b" 0 3000 0",b" 0 3000 1001"),
                        self.request+self.request.splitlines(keepends=True)[3]):
            with self.subTest(changed=changed), self.assertRaises(ValueError):v.request_rows(changed)

    def test_native_original_echo_domain_and_finite_words_required(self):
        for changed in (self.native.replace(b"01",b"03",1),self.native+self.row,self.native[:-1],
                        self.native.replace(b" 0000000000000000 ",b" 7ff0000000000000 "),self.native[:-801]+b"7fc00000"+self.native[-793:]):
            with self.subTest(), self.assertRaises(ValueError):v.compare(self.request,self.jvm,changed)

    def test_published_frozen_and_computed_counts_are_bound(self):
        for key,value in (("frozenId","03"*32),("computedRaw32Words",99),("computedRaw64Words",0),
                          ("mode","checked-jvm"),("nativeValidated",True),("raw32Comparisons",100)):
            original=self.meta[key];self.meta[key]=value;self.publish()
            with self.subTest(key=key),self.assertRaises(ValueError):self.inspect()
            self.meta[key]=original

    def test_unfinalized_or_native_service_refused(self):
        for key,value in (("resourcesFinalized",False),("transportCloses",0),("epochMode","repeated-native-checked-jvm-v1")):
            original=self.result[key];self.result[key]=value;self.publish()
            with self.subTest(key=key),self.assertRaises(ValueError):self.inspect()
            self.result[key]=original

    def test_publication_original_hash_and_pin_refused(self):
        (self.service/"publication-0000.json").write_bytes(b"{}")
        with self.assertRaises(ValueError):self.inspect()
        self.publish();self.result["publications"][0]["pin"]={"generation":2}
        (self.service/"result.json").write_bytes(v.json_bytes(self.result))
        with self.assertRaises(ValueError):self.inspect()

    def test_partial_published_refused_unpublished_partial_retained(self):
        unused=self.generations/"generation-0001";unused.mkdir();(unused/"request.txt").write_bytes(b"partial")
        self.assertEqual(self.inspect()["unreferenced"],[dict(directory="generation-0001",complete=False)])
        (self.directory/"execution.txt").unlink()
        with self.assertRaises(ValueError):self.inspect()

    def test_duplicate_record_and_receipt_refused(self):
        import shutil
        shutil.copytree(self.directory,self.generations/"generation-0001")
        with self.assertRaises(ValueError):self.inspect()
        shutil.rmtree(self.generations/"generation-0001")
        (self.directory/"execution.txt").write_text("claimed parity")
        with self.assertRaises(ValueError):self.inspect()

    def test_empty_domain_has_zero_comparisons_without_invented_rows(self):
        request=b"".join(self.request.splitlines(keepends=True)[:3])
        result=v.compare(request,b"conway-jvm-likelihood-result-v1\n"+request+b"--jvm--\n",request+b"--native--\n")
        self.assertEqual(result["raw32Comparisons"],0)

    def test_duplicate_json_and_traversal_refused(self):
        with self.assertRaises(ValueError):v.document(b'{"a":1,"a":2}')
        self.result["publications"][0]["file"]="../publication-0000.json"
        (self.service/"result.json").write_bytes(v.json_bytes(self.result))
        with self.assertRaises(ValueError):self.inspect()

    def test_pin_read_requires_exact_original_and_no_symlink(self):
        path=self.root/"original";path.write_bytes(b"value")
        entry=dict(path=str(path),sha256=v.digest(b"value"))
        self.assertEqual(v.pin_file(entry,5),b"value")
        with self.assertRaises(ValueError):v.pin_file(entry,4)
        entry["sha256"]="00"*32
        with self.assertRaises(ValueError):v.pin_file(entry,5)
        alias=self.root/"alias";alias.symlink_to(path)
        with self.assertRaises(ValueError):v.read(alias)

    def test_foreign_source_and_malformed_fullpin_refused(self):
        for key,value in (("sourceJoinId","99"*32),("initialManifestSHA256","99"*32),("profileId","foreign"),("index",False),("diagnosticOnly",False),("fullLedgerValidated",True)):
            old=self.publication[key];self.publication[key]=value;self.publish()
            with self.subTest(key=key),self.assertRaises(ValueError):self.inspect()
            self.publication[key]=old
        self.pin["ownerId"]="99"*32;self.publish()
        with self.assertRaises(ValueError):self.inspect()
        self.pin["ownerId"]="05"*32;self.pin.pop("environmentId");self.publish()
        with self.assertRaises(ValueError):self.inspect()

    def test_cleanup_probe_failure_always_reaps_local_cli(self):
        from unittest.mock import MagicMock
        import subprocess
        child=MagicMock();child.poll.return_value=None
        config=dict(image="sha256:"+"00"*32,timeoutSeconds=1)
        directory=self.root/"run";directory.mkdir()
        with patch.object(v.subprocess,"Popen",return_value=child),patch.object(v.time,"sleep",side_effect=RuntimeError("stop")),patch.object(v.subprocess,"run",side_effect=subprocess.TimeoutExpired("inspect",10)):
            with self.assertRaises(subprocess.TimeoutExpired):v.native_run(config,self.root,directory)
        child.terminate.assert_called_once();child.wait.assert_called_once_with(timeout=5)

if __name__=="__main__":unittest.main()
