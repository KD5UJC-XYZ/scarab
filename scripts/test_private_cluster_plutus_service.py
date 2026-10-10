#!/usr/bin/env python3
import copy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import private_cluster_plutus_service as c


class ServiceControllerTest(unittest.TestCase):
    def setUp(self):
        self.transfers = [dict(transactionId=str(i+1)*64, envelopeSHA256='a'*64,
            bodySHA256='b'*64, witnessesSHA256='c'*64, spentInput='d'*64+'#'+str(i*2),
            collateralInput='d'*64+'#'+str(i*2+1)) for i in range(2)]
        self.client = dict(schema='plutus-service-client-result-v1', passed=True, profileId=c.fixture.PROFILE,
            fullLedgerValidated=False, staleReceiptSubmitted=False, serviceAliveAfterSecondInclusion=True,
            sameOwner=True, firstInclusionThenSecondSubmission=True, apiAvailableAfterFirst=True,
            apiAvailableAfterSecond=True, transactions=[dict(t, ordinal=i+1, includedBodySHA256=t['bodySHA256'],
                includedWitnessesSHA256=t['witnessesSHA256'], acceptedResponse=dict(code='Accepted'),
                includedResponse=dict(code='Included'), acceptedObservedNanos=i*10+1, includedObservedNanos=i*10+2)
                for i,t in enumerate(self.transfers)])
        self.point = dict(slot=70,blockNo=7,hash='e'*64)
        self.comparison = dict(schema='plutus-service-endpoint-comparison-v1', terminalPoint=self.point,
            sourceJoinId='f'*64, initialManifestSHA256='a'*64, completeUtxoEqual=True,
            collateralPreserved=True, instantaneousStakeEqual=True, endpointSnapshotsUnchanged=True,
            representedProtocolEqual=True, diagnosticOnly=True, fullLedgerValidated=False, restartSupported=False,
            feesBefore=200000, feesAfter=800000,
            transactions=[dict(t,spent=t['spentInput'],collateral=t['collateralInput']) for t in self.transfers])

    def test_service_limit_integer_bounds(self):
        for duration,blocks in ((1,1),(60,128)): c.service_limits(duration,blocks)
        for duration,blocks in ((True,1),(0,1),(61,1),(30,False),(30,0),(30,129)):
            with self.subTest(duration=duration,blocks=blocks),self.assertRaises(ValueError): c.service_limits(duration,blocks)

    def test_exact_compile_dispatch_no_expected_transaction(self):
        result=c.service_cli_tail(['java','lab.NativeLiveBoundaryMain','initial','pin','123','42','exchange'],30,128)
        self.assertEqual(result,['java','lab.Main','plutus-service','--profile',c.fixture.PROFILE,
            '--initial','initial','--manifest-sha256','pin','--port','123','--magic','42','--output','exchange',
            '--duration-seconds','30','--max-blocks','128'])
        with self.assertRaises(ValueError): c.service_cli_tail(['lab.NativeLiveBoundaryMain','incomplete'],30,128)

    def test_actual_epoch_wallclock_margin(self):
        c.epoch_budget(100000,30,67999)
        for now in (68000,99000,100000):
            with self.assertRaises(TimeoutError): c.epoch_budget(100000,30,now)
        with patch.object(c.time,'time_ns',return_value=68000*1000000):
            with self.assertRaises(TimeoutError): c.epoch_budget(100000,30)

    def test_create_enforces_epoch_budget_and_preserves_client(self):
        class Launcher:
            def create(self,phase,tail):return tail
        kind=c.controller_type(SimpleNamespace(Launcher=Launcher))
        obj=object.__new__(kind);obj.args=SimpleNamespace(duration_seconds=30,max_blocks=128);obj.boundary_ms=100000
        tail=['lab.NativeLiveBoundaryMain','initial','pin','1','42','exchange']
        with patch.object(c.time,'time_ns',return_value=68000*1000000):
            with self.assertRaises(TimeoutError):obj.create('scala',tail)
        self.assertEqual(obj.create('ada-client',['lab.PlutusServiceClientMain','1','30','/exchange']),
            ['lab.PlutusServiceClientMain','1','30','/exchange'])

    def test_valid_two_independent_http_flow(self):self.assertIs(c.client_result(self.client,self.transfers),self.client)

    def test_second_before_first_inclusion_rejected(self):
        self.client['transactions'][1]['acceptedObservedNanos']=1
        with self.assertRaises(ValueError):c.client_result(self.client,self.transfers)

    def test_http_identity_and_inclusion_tampering_rejected(self):
        for field in ('transactionId','envelopeSHA256','includedBodySHA256','includedWitnessesSHA256'):
            value=copy.deepcopy(self.client);value['transactions'][1][field]='f'*64
            with self.subTest(field=field),self.assertRaises(ValueError):c.client_result(value,self.transfers)

    def test_stopped_after_first_or_scope_expansion_rejected(self):
        for field,value in (('apiAvailableAfterFirst',False),('apiAvailableAfterSecond',False),
                            ('serviceAliveAfterSecondInclusion',False),('sameOwner',False),
                            ('staleReceiptSubmitted',True),('fullLedgerValidated',True)):
            changed=dict(self.client,**{field:value})
            with self.subTest(field=field),self.assertRaises(ValueError):c.client_result(changed,self.transfers)

    def test_overlapping_collateral_rejected(self):
        self.transfers[1]['collateralInput']=self.transfers[0]['collateralInput']
        with self.assertRaises(ValueError):c.client_result(self.client,self.transfers)

    def test_exact_endpoint_comparison(self):
        self.assertIs(c.comparison_result(self.comparison,self.transfers,self.point,'f'*64,'a'*64),self.comparison)

    def test_endpoint_stale_point_fee_or_scope_rejected(self):
        for field,value in (('terminalPoint',dict(self.point,slot=71)),('feesAfter',800001),
                            ('collateralPreserved',False),('representedProtocolEqual',False),('restartSupported',True)):
            with self.subTest(field=field),self.assertRaises(ValueError):
                c.comparison_result(dict(self.comparison,**{field:value}),self.transfers,self.point,'f'*64,'a'*64)

    def test_endpoint_original_pair_tampering_rejected(self):
        for field in ('bodySHA256','witnessesSHA256','spent','collateral'):
            value=copy.deepcopy(self.comparison);value['transactions'][1][field]='0'*64
            with self.subTest(field=field),self.assertRaises(ValueError):
                c.comparison_result(value,self.transfers,self.point,'f'*64,'a'*64)

    def test_profile_submit_prohibition_inherited(self):
        class Launcher:
            def execute(self,*args,**kwargs):raise AssertionError('unexpected reference submit')
        obj=object.__new__(c.controller_type(SimpleNamespace(Launcher=Launcher)))
        with self.assertRaises(ValueError):obj.execute('cardano-cli','conway','transaction','submit','--tx-file','test.signed')

    def test_aggregate_resources_remain_bounded(self):
        c.base.budget()
        self.assertEqual(sum(x[0] for x in c.base.RESOURCES.values()),4)
        self.assertEqual(sum(x[1] for x in c.base.RESOURCES.values()),7*c.base.GIB)

    def test_compile_classpath_excludes_test_runtime(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'app/target/test-classes').mkdir(parents=True)
            path=root/'cp';path.write_text('/work/app/target/test-classes')
            with self.assertRaises(ValueError):c.checked_classpath(path,root,True)
            self.assertEqual(c.checked_classpath(path,root),'/work/app/target/test-classes')

    def test_client_inspection_failure_still_removes_owned_client(self):
        class Launcher:pass
        obj=object.__new__(c.controller_type(SimpleNamespace(Launcher=Launcher)))
        obj.args=SimpleNamespace(client_classpath_file=Path('/cp'),scala_build_root=Path('/build'),
            java='java',scala_image='image',duration_seconds=30)
        obj.containers={'reference':'r'*64};obj.exchange=Path('/exchange');obj.api_port=1234
        obj.create=lambda phase,args:'c'*64
        obj.owned=lambda cid:dict()
        removed=[];obj.remove_client=lambda:removed.append(True)
        with patch.object(c,'checked_classpath',return_value='/work/test-classes'), \
             patch.object(c.base,'check_resources',side_effect=ValueError('wrong network')):
            with self.assertRaisesRegex(ValueError,'wrong network'):obj.run_client()
        self.assertEqual(removed,[True])

    def test_publication_originals_cross_bind_client_and_final_result(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            refs=[]
            for index,row in enumerate(self.client['transactions']):
                name=f'publication-{index:04d}.json'
                pin=dict(ownerId='a'*64,generation=index+1,point=self.point)
                publication=dict(schema='plutus-service-publication-v1',index=index,profileId=c.fixture.PROFILE,
                    sourceJoinId='f'*64,initialManifestSHA256='a'*64,pin=pin,diagnosticOnly=True,fullLedgerValidated=False,
                    included=[{k:row[k] for k in ('transactionId','bodySHA256','witnessesSHA256')}])
                c.base.write(root/name,publication)
                digest=c.base.sha(root/name)
                refs.append(dict(file=name,sha256=digest,pin=pin))
                row.update(publicationFile=name,publicationSHA256=digest,publication=publication)
                row['includedResponse']['pin']=pin
            result=dict(publications=refs)
            c.publication_bindings(root,result,self.client,'f'*64,'a'*64)
            self.client['transactions'][1]['includedResponse']['pin']=dict(pin,generation=99)
            with self.assertRaises(ValueError):c.publication_bindings(root,result,self.client,'f'*64,'a'*64)
            self.client['transactions'][1]['includedResponse']['pin']=pin
            (root/refs[0]['file']).write_text('{}')
            with self.assertRaises(ValueError):c.publication_bindings(root,result,self.client,'f'*64,'a'*64)

    def test_publication_traversal_and_duplicate_names_rejected(self):
        for name in ('../publication-0000.json','publication-99999.json','other.json'):
            with self.subTest(name=name),self.assertRaises(ValueError):
                c.publication_bindings(Path('/tmp'),dict(publications=[dict(file=name)]),self.client,'f'*64,'a'*64)


if __name__=='__main__':unittest.main()
