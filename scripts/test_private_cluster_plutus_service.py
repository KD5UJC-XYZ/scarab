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


class SequentialClientTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pin = dict(ownerId='a'*64, profileId=c.fixture.PROFILE, generation=1,
            coherentStateId='b'*64,ledgerStateId='c'*64,environmentId='d'*64,
            point=dict(slot=10,blockNo=1,hash='e'*64),validationSlot=10)
        self.state = dict(code='State',profileId=c.fixture.PROFILE,closed=False,fullLedgerValidated=False,pin=self.pin)
        self.client = dict(transactions=[dict(acceptedResponse=dict(receipt=dict(pin=self.pin)),
            includedResponse=dict(pin=self.pin)) for _ in range(2)],
            betweenStateResponse=dict(pin=self.pin),afterSecondStateResponse=dict(pin=self.pin),
            firstStatusAfterSecondResponse=dict(pin=self.pin))
        c.base.write(self.root/'service-client-result.json',self.client)
        self.report = dict(schema='sequential-devnet-runner-v1',allRequestedPassed=False,
            executedScenariosPassed=True,stop='Exhausted',observationalOnly=True,restoreAuthority=False,rows=[])
        scenarios=('ObserveService','TwoSequentialTransfers','ObserveService','RestartAndRejoin','FollowAcrossEpochs','MultipleNodes')
        requirements=('DurableRestore','RepeatedEpochTransition','MultipleIngressNodes')
        for index,scenario in enumerate(scenarios):
            if index<3:
                raw=(self.root/'service-client-result.json').read_bytes() if index==1 else c.base.json.dumps(self.state).encode()
                (self.root/f'scenario-checkpoint-{index}.json').write_bytes(raw)
                verdict=dict(outcome='completed',checkpointSHA256=c.fixture.digest(raw),checkpointBytes=len(raw),index=index)
            else: verdict=dict(outcome='blocked',missingRequirement=requirements[index-3])
            self.report['rows'].append(dict(scenario=scenario,verdict=verdict))
        self.save_report()

    def save_report(self):
        (self.root/'scenario-runner-result.json').write_text(c.base.json.dumps(self.report))

    def replace_checkpoint(self,index,value):
        raw=c.base.json.dumps(value).encode()
        (self.root/f'scenario-checkpoint-{index}.json').write_bytes(raw)
        self.report['rows'][index]['verdict'].update(checkpointSHA256=c.fixture.digest(raw),checkpointBytes=len(raw))
        self.save_report()

    def test_default_direct_arguments_are_unchanged(self):
        self.assertEqual(c.client_command('direct',1234,30),['lab.PlutusServiceClientMain','1234','30','/exchange'])
        self.assertEqual(c.client_command('sequential',1234,30),['lab.PlutusScenarioRunnerMain','1234','30','/exchange'])

    def test_closed_mode_and_runner_bounds(self):
        for mode,port,duration in (('lab.OtherMain',1234,30),('SEQUENTIAL',1234,30),('sequential',1234,2),
                                  ('sequential',1234,61),('sequential',True,30),('direct',0,30)):
            with self.subTest(mode=mode,port=port,duration=duration),self.assertRaises(ValueError):
                c.client_command(mode,port,duration)
        c.client_command('direct',1234,1)

    def test_typed_report_original_checkpoint_binding(self):
        raw,checkpoints=c.sequential_report(self.root,self.client)
        self.assertEqual(raw,(self.root/'scenario-runner-result.json').read_bytes())
        self.assertEqual(checkpoints[1],(self.root/'service-client-result.json').read_bytes())
        self.assertEqual(len(checkpoints),3)

    def test_all_blocked_cannot_be_a_success(self):
        for row in self.report['rows'][:3]:row['verdict']=dict(outcome='blocked',missingRequirement='DurableRestore')
        self.save_report()
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_success_scope_and_stop_flags_are_strict(self):
        for field,value in (('allRequestedPassed',True),('executedScenariosPassed',False),('restoreAuthority',True),
                            ('observationalOnly',False),('stop','Failed')):
            original=self.report[field];self.report[field]=value;self.save_report()
            with self.subTest(field=field),self.assertRaises(ValueError):c.sequential_report(self.root,self.client)
            self.report[field]=original

    def test_exact_scenario_order_and_blocked_requirements(self):
        self.report['rows'][1]['scenario']='MultipleNodes';self.save_report()
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)
        self.report['rows'][1]['scenario']='TwoSequentialTransfers'
        self.report['rows'][4]['verdict']['missingRequirement']='DurableRestore';self.save_report()
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_checkpoint_index_size_hash_types(self):
        original=copy.deepcopy(self.report['rows'][0]['verdict'])
        for field,value in (('index',True),('index',2),('checkpointBytes',0),('checkpointBytes',65537),
                            ('checkpointSHA256','f'*64),('outcome','blocked')):
            self.report['rows'][0]['verdict']=dict(original,**{field:value});self.save_report()
            with self.subTest(field=field),self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_transfer_checkpoint_cannot_replace_original_client(self):
        self.replace_checkpoint(1,dict(self.client,changed=True))
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_initial_owner_cross_binding_rejects_changed_final_owner(self):
        state=copy.deepcopy(self.state);state['pin']['ownerId']='f'*64
        self.replace_checkpoint(2,state)
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_client_pin_cannot_belong_to_another_owner(self):
        self.client=copy.deepcopy(self.client)
        self.client['transactions'][1]['acceptedResponse']['receipt']['pin']=dict(self.pin,ownerId='f'*64)
        (self.root/'service-client-result.json').write_text(c.base.json.dumps(self.client))
        self.replace_checkpoint(1,self.client)
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_aggregate_checkpoint_budget(self):
        self.replace_checkpoint(0,dict(self.state,padding='x'*33000))
        self.replace_checkpoint(2,dict(self.state,padding='y'*33000))
        with self.assertRaisesRegex(ValueError,'aggregate'):c.sequential_report(self.root,self.client)

    def test_report_and_checkpoint_files_reject_symlinks(self):
        path=self.root/'scenario-checkpoint-0.json'
        target=self.root/'preserved.json';target.write_bytes(path.read_bytes())
        path.unlink();path.symlink_to(target)
        with self.assertRaisesRegex(ValueError,'symlink'):c.sequential_report(self.root,self.client)

    def test_initial_observation_cannot_follow_first_admission(self):
        state=copy.deepcopy(self.state);state['pin']['generation']=2
        self.replace_checkpoint(0,state)
        self.replace_checkpoint(2,state)
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_closed_or_stale_final_observation_rejected(self):
        self.replace_checkpoint(2,dict(self.state,closed=True))
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)
        state=copy.deepcopy(self.state);state['pin']['generation']=0
        self.replace_checkpoint(2,state)
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)


if __name__=='__main__':unittest.main()
