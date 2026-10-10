#!/usr/bin/env python3
import copy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import private_cluster_plutus_two_service as c


def state_pin(owner, generation=0):
    return dict(ownerId=owner, generation=generation,
                point=dict(slot=200+generation, blockNo=3+generation, hash='e'*64),
                coherentStateId='a'*64, ledgerStateId='b'*64, environmentId='c'*64,
                validationSlot=200+generation, profileId=c.fixture.PROFILE)


def state(owner, generation):
    return dict(code='State', closed=False, volatile=True, fullLedgerValidated=False,
                profileId=c.fixture.PROFILE, pin=state_pin(owner,generation))


def example():
    transfers=[dict(transactionId=str(i+1)*64, envelopeSHA256='a'*64, bodySHA256='b'*64,
                    witnessesSHA256='c'*64, spentInput='d'*64+'#'+str(i*2),
                    collateralInput='d'*64+'#'+str(i*2+1)) for i in range(2)]
    endpoints=[]
    for i,tx in enumerate(transfers):
        owner=str(i+3)*64
        observed=[]
        for j,t in enumerate(transfers):
            identity={k:t[k] for k in ('transactionId','bodySHA256','witnessesSHA256')}
            pub=dict(schema='plutus-service-publication-v1', profileId=c.fixture.PROFILE,
                     pin=state_pin(owner,j+1), included=[identity], sourceJoinId='f'*64,
                     initialManifestSHA256='a'*64, diagnosticOnly=True, fullLedgerValidated=False)
            observed.append(dict(identity, publicationFile=f'publication-{j:04d}.json', publicationSHA256='f'*64, publication=pub))
        accepted=dict(code='Accepted', volatile=True, fullLedgerValidated=False, profileId=c.fixture.PROFILE,
                      receipt=dict(transactionId=tx['transactionId'],envelopeSHA256=tx['envelopeSHA256'],pin=state_pin(owner,0)))
        included=dict(code='Included', volatile=True, fullLedgerValidated=False, profileId=c.fixture.PROFILE,
                      transactionId=tx['transactionId'], submittedEnvelopeByteEqualityVerified=False, pin=state_pin(owner,i+1))
        endpoints.append(dict(tx,endpointId=c.SERVICE_PHASES[i],port=31001+i,observedOwnerId=owner,
                              acceptedResponse=accepted,includedResponse=included,initialState=state(owner,0),
                              finalState=state(owner,2),observedTransactions=observed,
                              **{k:observed[i][k] for k in ('publicationFile','publicationSHA256','publication')}))
    return dict(schema='multi-endpoint-client-result-v1',passed=True,diagnosticOnly=True,resourcesFinalized=True,
                fullLedgerValidated=False,submittedEnvelopeByteEqualityVerified=False,endpoints=endpoints),transfers


class TwoServiceTest(unittest.TestCase):
    def test_receipt_published_during_stopped_inspection_is_observed(self):
        kind=c.controller_type(SimpleNamespace(Launcher=object))
        for name in ('bootstrap-ready.json','result.json'):
            with self.subTest(name=name),tempfile.TemporaryDirectory() as directory:
                obj=object.__new__(kind);obj.exchange=Path(directory)
                root=obj.exchange/'service-1';root.mkdir()
                obj.containers={'service-1':'owned'};obj.deadline=c.time.monotonic()+10
                def owned(cid):
                    self.assertEqual(cid,'owned')
                    (root/name).write_text('{"observed":true}')
                    return {'State':{'Running':False}}
                obj.owned=owned
                self.assertEqual(obj.wait_service('service-1',name,1),{'observed':True})

    def test_stopped_inspection_keeps_missing_failure_and_symlink_rejections(self):
        kind=c.controller_type(SimpleNamespace(Launcher=object))
        for mode in ('missing','failure','symlink','invalid'):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as directory:
                obj=object.__new__(kind);obj.exchange=Path(directory)
                root=obj.exchange/'service-1';root.mkdir()
                obj.containers={'service-1':'owned'};obj.deadline=c.time.monotonic()+10
                def owned(cid):
                    path=root/'result.json'
                    if mode=='failure':
                        path.write_text('{}');(root/'failure.json').write_text('{}')
                    elif mode=='symlink':
                        target=obj.exchange/'foreign.json';target.write_text('{}');path.symlink_to(target)
                    elif mode=='invalid':path.write_text('not json')
                    return {'State':{'Running':False}}
                obj.owned=owned
                with self.assertRaises(ValueError):obj.wait_service('service-1','result.json',1)

    def test_exact_aggregate_and_overbudget_rejected(self):
        c.budget()
        self.assertEqual(sum(v[0] for v in c.RESOURCES.values()),4_000_000_000)
        self.assertEqual(sum(v[1] for v in c.RESOURCES.values()),7*c.base.GIB)
        for role,delta in (('service-1',(1,0)),('helper',(0,1))):
            changed=dict(c.RESOURCES); old=changed[role]; changed[role]=(old[0]+delta[0],old[1]+delta[1])
            with self.assertRaises(ValueError):c.budget(changed)

    def test_compile_runtime_readonly_source_isolated_outputs_and_jvm_headroom(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            args=SimpleNamespace(duration_seconds=30,max_blocks=128,scala_build_root=root/'build',java='java',scala_image='sha256:'+'a'*64)
            first=c.service_args(args,root/'service-1',root/'initial','/work/app.jar','f'*64,3001,42,'b'*64)
            second=c.service_args(args,root/'service-2',root/'initial','/work/app.jar','f'*64,3001,42,'b'*64)
            for command in (first,second):
                self.assertIn('--cpus=0.5',command);self.assertIn('--memory=1g',command)
                self.assertIn('--memory-swap=1g',command);self.assertIn('-Xmx512m',command)
                self.assertIn('-XX:ActiveProcessorCount=1',command)
                self.assertIn('type=bind,src='+str(root/'initial')+',dst=/initial,readonly',command)
                self.assertIn('type=bind,src='+str(root/'build')+',dst=/work,readonly',command)
                self.assertEqual(command[command.index('lab.Main')+1],'plutus-service')
                self.assertNotIn('lab.PlutusServiceClientMain',command)
            self.assertNotEqual(first,second)
            with self.assertRaises(ValueError):c.service_args(args,root/'initial',root/'initial','cp','f'*64,3001,42,'b'*64)

    def test_actual_inspection_rejects_memory_cpu_writable_source_or_namespace_drift(self):
        mounts={('/build','/work',False),('/initial','/initial',False),('/service-1','/exchange',True)}
        obj=dict(Image='image',Config=dict(User='1000:1000'),HostConfig=dict(NetworkMode='container:reference',NanoCpus=500000000,
                 Memory=c.base.GIB,MemorySwap=c.base.GIB,PidsLimit=256,ReadonlyRootfs=True,CapDrop=['ALL'],SecurityOpt=['no-new-privileges']),
                 Mounts=[dict(Type='bind',Source=s,Destination=d,RW=w) for s,d,w in mounts])
        c.check_service(obj,'service-1','image','container:reference',mounts)
        for key,value in (('NanoCpus',1000000000),('Memory',2*c.base.GIB),('MemorySwap',2*c.base.GIB),('NetworkMode','host'),('ReadonlyRootfs',False)):
            changed=copy.deepcopy(obj);changed['HostConfig'][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):c.check_service(changed,'service-1','image','container:reference',mounts)
        changed=copy.deepcopy(obj)
        for m in changed['Mounts']:
            if m['Destination']=='/initial':m['RW']=True
        with self.assertRaises(ValueError):c.check_service(changed,'service-1','image','container:reference',mounts)

    def test_real_client_receipt_two_designated_owners_and_originals(self):
        value,txs=example()
        self.assertIs(c.client_result(value,txs,[31001,31002]),value)

    def test_same_owner_or_port_never_counts_as_two_ingresses(self):
        value,txs=example();value['endpoints'][1]['observedOwnerId']='3'*64
        with self.assertRaises(ValueError):c.client_result(value,txs,[31001,31002])
        value,txs=example()
        with self.assertRaises(ValueError):c.client_result(value,txs,[31001,31001])

    def test_accepted_envelope_and_included_body_witness_substitution(self):
        for mutation in ('envelope','body','witness','pin','point'):
            value,txs=example();row=value['endpoints'][1]
            if mutation=='envelope':row['acceptedResponse']['receipt']['envelopeSHA256']='f'*64
            elif mutation=='body':row['observedTransactions'][0]['bodySHA256']='f'*64
            elif mutation=='witness':row['publication']['included'][0]['witnessesSHA256']='f'*64
            elif mutation=='pin':row['includedResponse']['pin']['ownerId']='3'*64
            else:
                row['includedResponse']['pin']['point']=copy.deepcopy(row['acceptedResponse']['receipt']['pin']['point'])
                row['includedResponse']['pin']['validationSlot']=200
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):c.client_result(value,txs,[31001,31002])

    def test_missing_cross_endpoint_original_or_overlap_cannot_pass(self):
        value,txs=example();value['endpoints'][0]['observedTransactions'].pop()
        with self.assertRaises(ValueError):c.client_result(value,txs,[31001,31002])
        value,txs=example();txs[1]['collateralInput']=txs[0]['collateralInput']
        with self.assertRaises(ValueError):c.client_result(value,txs,[31001,31002])

    def test_false_success_or_unfinalized_client_cannot_pass(self):
        for key,bad in (('passed',False),('resourcesFinalized',False),('fullLedgerValidated',True),('submittedEnvelopeByteEqualityVerified',True)):
            value,txs=example();value[key]=bad
            with self.subTest(key=key),self.assertRaises(ValueError):c.client_result(value,txs,[31001,31002])

    def test_cleanup_recovers_unregistered_second_create_and_removes_consumers_first(self):
        class Launcher:pass
        kind=c.controller_type(SimpleNamespace(Launcher=Launcher,LABEL='owned'))
        with tempfile.TemporaryDirectory() as directory:
            obj=object.__new__(kind);obj.out=Path(directory);obj.token='token';obj.cleaned=False
            obj.cleanup_deadline=None;obj.containers={'service-1':'id1'}
            existing={'service-1':'id1','service-2':'id2'};events=[]
            def docker(*args,**kwargs):
                if args[0]=='ps':
                    phase=next((p for p in c.SERVICE_PHASES if 'label=lab.zero-live.phase='+p in args),None)
                    if phase:return SimpleNamespace(stdout=existing.get(phase,''))
                    identity=next(a[3:] for a in args if a.startswith('id='))
                    return SimpleNamespace(stdout=identity if identity in existing.values() else '')
                if args[0]=='logs':return SimpleNamespace(stdout='bounded log',stderr='')
                if args[0]=='rm':
                    cid=args[-1];events.append('remove:'+cid)
                    for phase,value in list(existing.items()):
                        if value==cid:del existing[phase]
                    return SimpleNamespace(stdout='')
                raise AssertionError(args)
            obj.docker=docker;obj.owned=lambda cid:events.append('owned:'+cid)
            obj.remove_client=lambda:events.append('client-cleanup')
            obj.diagnostic_write=lambda *args:None
            parent=kind.__bases__[0]
            with patch.object(c.signal,'alarm'),patch.object(parent,'cleanup',lambda self:events.append('reference-cleanup')):
                obj.cleanup()
            self.assertEqual(events,['client-cleanup','owned:id2','remove:id2','owned:id1','remove:id1','reference-cleanup'])
            self.assertEqual(existing,{})
            self.assertTrue((obj.out/'service-2-cleanup.json').exists())

    def test_cleanup_refuses_foreign_immutable_id(self):
        kind=c.controller_type(SimpleNamespace(Launcher=object,LABEL='owned'))
        obj=object.__new__(kind);obj.token='token';obj.containers={'service-1':'expected'}
        obj.docker=lambda *a,**kw:SimpleNamespace(stdout='foreign')
        with self.assertRaises(ValueError):obj.remove_service('service-1')

    def test_second_service_launch_rechecks_epoch_window_and_keeps_first_for_cleanup(self):
        kind=c.controller_type(SimpleNamespace(Launcher=object,process=SimpleNamespace(PORTS={1:3001})))
        with tempfile.TemporaryDirectory() as directory:
            obj=object.__new__(kind);obj.exchange=Path(directory)/'exchange';obj.exchange.mkdir()
            (obj.exchange/'submission').mkdir();(obj.exchange/'initial').mkdir()
            for n in (1,2):(obj.exchange/'submission'/f'transaction-{n}.cbor').write_bytes(bytes([n]))
            obj.args=SimpleNamespace(duration_seconds=30,max_blocks=128,scala_build_root=Path(directory)/'build',java='java',scala_image='image')
            obj.out=Path(directory)/'evidence';obj.out.mkdir();obj.magic=42;obj.boundary_ms=100000
            obj.classpath='/work/main.jar';obj.containers={'reference':'reference'};obj.construct_transfer=lambda:None
            events=[]
            def create(phase,args):obj.containers[phase]=phase;events.append(('create',phase));return phase
            obj.create=create;obj.owned=lambda cid:{};obj.docker=lambda *a:events.append(a)
            with patch.object(c.single,'epoch_budget',side_effect=[None,TimeoutError('missed window')]),patch.object(c,'check_service'):
                with self.assertRaises(TimeoutError):obj.start_services('a'*64)
            self.assertEqual(events,[('create','service-1'),('start','service-1')])
            self.assertIn('service-1',obj.containers)

    def test_existing_epoch_margins_and_slot_guards_unchanged(self):
        with self.assertRaises(TimeoutError):c.single.epoch_budget(100000,30,68000)
        for slot in (0,100,300):
            tip=dict(slot=slot,block=1,hash='a'*64,era='Conway',epoch=0)
            with self.assertRaises((ValueError,TimeoutError)):c.single.prefunding_point(tip)
        c.single.prefunding_point(dict(slot=99,block=0,hash='a'*64,era='Conway',epoch=0))


    def test_same_generation_state_substitution_or_final_before_other_publication_rejected(self):
        value,txs=example();value['endpoints'][0]['initialState']['pin']['ledgerStateId']='f'*64
        with self.assertRaises(ValueError):c.client_result(value,txs,[31001,31002])
        value,txs=example();value['endpoints'][0]['finalState']=state('3'*64,1)
        with self.assertRaises(ValueError):c.client_result(value,txs,[31001,31002])

    def test_cancel_or_failure_at_second_create_inspect_start_retains_owned_cleanup(self):
        for failure_stage in ('create','inspect','start'):
            class Launcher:pass
            live=SimpleNamespace(Launcher=Launcher,LABEL='owned',process=SimpleNamespace(PORTS={1:3001}))
            kind=c.controller_type(live)
            with self.subTest(failure_stage=failure_stage),tempfile.TemporaryDirectory() as directory:
                obj=object.__new__(kind);obj.exchange=Path(directory)/'exchange';obj.exchange.mkdir()
                (obj.exchange/'submission').mkdir();(obj.exchange/'initial').mkdir()
                for n in (1,2):(obj.exchange/'submission'/f'transaction-{n}.cbor').write_bytes(bytes([n]))
                obj.args=SimpleNamespace(duration_seconds=30,max_blocks=128,scala_build_root=Path(directory)/'build',java='java',scala_image='image')
                obj.out=Path(directory)/'evidence';obj.out.mkdir();obj.magic=42;obj.boundary_ms=100000
                obj.classpath='/work/main.jar';obj.containers={'reference':'reference'};obj.construct_transfer=lambda:None
                obj.token='token';obj.cleaned=False;obj.cleanup_deadline=None
                existing={};events=[]
                def create(phase,args):
                    existing[phase]=phase
                    if phase=='service-2' and failure_stage=='create':raise KeyboardInterrupt()
                    obj.containers[phase]=phase
                    return phase
                def owned(cid):
                    if cid=='service-2' and failure_stage=='inspect' and not getattr(obj,'in_cleanup',False):raise ValueError('inspection')
                    return {}
                def docker(*args,**kwargs):
                    if args[0]=='start':
                        if args[-1]=='service-2' and failure_stage=='start':raise TimeoutError('start')
                        return SimpleNamespace(stdout='')
                    if args[0]=='ps':
                        phase=next((p for p in c.SERVICE_PHASES if 'label=lab.zero-live.phase='+p in args),None)
                        if phase:return SimpleNamespace(stdout=existing.get(phase,''))
                        identity=next(a[3:] for a in args if a.startswith('id='))
                        return SimpleNamespace(stdout=identity if identity in existing.values() else '')
                    if args[0]=='logs':return SimpleNamespace(stdout='private bounded log',stderr='')
                    if args[0]=='rm':
                        events.append(args[-1]);existing.pop(args[-1],None);return SimpleNamespace(stdout='')
                    raise AssertionError(args)
                obj.create=create;obj.owned=owned;obj.docker=docker
                obj.remove_client=lambda:events.append('client')
                obj.diagnostic_write=lambda *args:None
                parent=kind.__bases__[0]
                with patch.object(c.single,'epoch_budget'),patch.object(c,'check_service'),patch.object(c.signal,'alarm'),patch.object(parent,'cleanup',lambda self:events.append('reference')):
                    with self.assertRaises((KeyboardInterrupt,ValueError,TimeoutError)):
                        try:obj.start_services('a'*64)
                        finally:obj.cleanup()
                self.assertEqual(events,['client','service-2','service-1','reference'])
                self.assertEqual(existing,{})


if __name__=='__main__':unittest.main()
