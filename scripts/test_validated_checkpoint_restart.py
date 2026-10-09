#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import copy
import io
import json
import os
import subprocess
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
import validated_checkpoint_restart as m

H = 'ab'*32
ORIGINALS = [dict(headerSha256='01'*32, blockSha256='02'*32), dict(headerSha256='03'*32, blockSha256='04'*32)]

def state(prefix, revision=None, generation=None):
    c = dict(profile='conway-pv9-header11-2-derived-nonce-bounded-sequence-v1', contextId=H,
        tupleId=f'{prefix:064x}', anchor=None, tip=None, appliedTip=None, originals=ORIGINALS[:prefix],
        certificate=dict(id=H, contextId=H, tip=dict(hash=H, slot='0', block='0'), counters={}),
        nonces=dict(id=H, contextId=H, certificateStateId=H, lastSlot='0',
            **{k:dict(kind='neutral') for k in ('evolving','candidate','epoch','previousEpoch','lab','lastEpochBlock')}),
        eligibility=None, ledger=dict(environmentId=H, checkpointId=H, id=H, utxoHex='a0', fees='0', slot='0'))
    return dict(content=c, contentSha256=m.sha(m.canonical(c)), revision=str(prefix if revision is None else revision),
        token=dict(storeId=H, contextId=H, generation=str(prefix if generation is None else generation), digest=H), capacity='8')

def record(p, kind, **values):
    return dict(record=kind, phase=p.phase, sequence=p.sequence, **values)

def start(p):
    return p.accept(record(p, 'process', nonce='12345678-1234-1234-1234-123456789abc', pid='1'))

def pub(p, operation, prefix, value):
    return p.accept(record(p, 'publication', operation=operation, prefix=prefix, state=value))

class ProtocolTests(unittest.TestCase):
    def test_strict_json(self):
        for line in (b'{"x":1,"x":2}\n', b'{"x":NaN}\n', b'{}', b'[]\n', b'{"x":{"a":1,"a":2}}\n'):
            with self.subTest(line=line), self.assertRaises(ValueError): m.parse_record(line)

    def test_record_bounds(self):
        with patch.object(m, 'MAX_LINE', 4), self.assertRaises(ValueError): m.parse_record(b'{"x":1}\n')

    def test_A_and_B_complete_prefix_projection_and_arithmetic(self):
        a = m.Protocol('a', 'graceful', H, ORIGINALS); self.assertTrue(start(a).startswith('start '))
        for i in range(3): self.assertTrue(pub(a, 'anchor' if i == 0 else 'apply', i, state(i)).startswith('retained '))
        self.assertEqual(a.accept(record(a, 'holding', mode='graceful', digest=H)), 'release '+H)
        a.accept(record(a, 'complete', networkContinuation=False, powerLossRecovery=False))
        b = m.Protocol('b', 'graceful', H, ORIGINALS, a.anchors); start(b)
        b.accept(record(b, 'restored', step='tip', state=state(2)))
        pub(b, 'rollback-two', 0, state(0,4,3))
        b.accept(record(b, 'restored', step='rolled', state=state(0,4,3)))
        pub(b, 'reapply', 1, state(1,5,4)); pub(b, 'reapply', 2, state(2,6,5))
        pub(b, 'rollback-anchor', 0, state(0,8,6))
        b.accept(record(b, 'restored', step='anchor', state=state(0,8,6)))
        b.accept(record(b, 'complete', networkContinuation=False, powerLossRecovery=False))
        self.assertTrue(b.complete)

    def test_exact_resume_checks_revision_and_token(self):
        for change in ('revision', 'generation', 'digest'):
            b=m.Protocol('b','graceful',H,ORIGINALS,[state(i) for i in range(3)]); start(b)
            altered=state(2)
            if change == 'revision': altered[change]='3'
            else: altered['token'][change]='3' if change == 'generation' else 'ff'*32
            with self.subTest(change=change), self.assertRaisesRegex(ValueError,'exact restoration'):
                b.accept(record(b,'restored',step='tip',state=altered))

    def test_rollback_rejects_wrong_arithmetic_or_content(self):
        for value in (state(0,3,3), state(0,4,4), state(1,4,3)):
            b=m.Protocol('b','graceful',H,ORIGINALS,[state(i) for i in range(3)]); start(b)
            b.accept(record(b,'restored',step='tip',state=state(2)))
            with self.assertRaises(ValueError): pub(b,'rollback-two',0,value)

    def test_strict_sequence_types_fields_and_projection(self):
        for mutate in (lambda r:r.update(sequence=True), lambda r:r.update(extra=1),
                       lambda r:r['state']['content'].pop('nonces'),
                       lambda r:r['state']['content']['nonces'].update(extra='bad'),
                       lambda r:r['state']['token'].update(generation='00')):
            p=m.Protocol('a','graceful',H,ORIGINALS); start(p)
            r=record(p,'publication',operation='anchor',prefix=0,state=state(0)); mutate(r)
            with self.assertRaises(ValueError): p.accept(r)

    def test_kill_A_must_not_complete(self):
        p=m.Protocol('a','kill',H,ORIGINALS); start(p)
        for i in range(3): pub(p,'anchor' if i==0 else 'apply',i,state(i))
        self.assertEqual(p.accept(record(p,'holding',mode='kill',digest=H)), 'KILL')
        with self.assertRaises(ValueError): p.accept(record(p,'complete',networkContinuation=False,powerLossRecovery=False))

class FakeProcess:
    def __init__(self, lines, code=0):
        self.stdin=io.BytesIO(); self.stdout=io.BytesIO(b''.join(m.canonical(r)+b'\n' for r in lines)); self.stderr=io.BytesIO()
        self.returncode=code
    def wait(self, timeout): return self.returncode
    def poll(self): return self.returncode
    def terminate(self): self.returncode=-15
    def kill(self): self.returncode=-9

class DriverTests(unittest.TestCase):
    def test_uncertain_create_recovers_owned_CID_and_removes_only_that_container(self):
        d=m.Docker(Path('/repo'),Path('/input'),Path('/mount'),Path('/receipts'),'cp')
        calls=[]; removed=False
        def command(args,timeout=10):
            nonlocal removed
            calls.append(args)
            if args[0]=='create':
                self.assertEqual(len(d.owners),1)
                self.assertIn('lab.validated-restart.owner='+d.owners[0],args)
                raise subprocess.TimeoutExpired('docker create',1)
            if args[0]=='ps': return b'' if removed else (H+'\n').encode()
            if args[0]=='inspect': return json.dumps([dict(Id=H,Config=dict(Labels={'lab.validated-restart.owner':d.owners[0]}))]).encode()
            if args[0]=='rm':
                self.assertEqual(args,['rm','-f',H]); removed=True; return b''
            raise AssertionError(args)
        with patch.object(d,'command',side_effect=command):
            with self.assertRaises(subprocess.TimeoutExpired): d.phase(m.Protocol('a','kill',H,ORIGINALS))
            d.cleanup()
        self.assertTrue(removed); self.assertEqual(d.owned,[H])

    def test_uncertain_create_cleanup_rejects_wrong_label(self):
        d=m.Docker(Path('/repo'),Path('/input'),Path('/mount'),Path('/receipts'),'cp'); d.owners=['our-label']; commands=[]
        def command(args,timeout=10):
            commands.append(args)
            if args[0]=='ps': return (H+'\n').encode()
            return json.dumps([dict(Id=H,Config=dict(Labels={'lab.validated-restart.owner':'someone-else'}))]).encode()
        with patch.object(d,'command',side_effect=command), self.assertRaisesRegex(ValueError,'cleanup failed'): d.cleanup()
        self.assertFalse(any(args[0]=='rm' for args in commands))

    def test_bounded_file_rejects_oversize_symlink_FIFO_and_directory(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root); regular=root/'regular'; regular.write_bytes(b'abcd')
            self.assertEqual(m.bounded_file(regular,4),b'abcd')
            with self.assertRaises(ValueError):m.bounded_file(regular,3)
            link=root/'link'; link.symlink_to(regular)
            with self.assertRaises(OSError):m.bounded_file(link,4)
            fifo=root/'fifo'; os.mkfifo(fifo)
            with self.assertRaises(ValueError):m.bounded_file(fifo,4)
            with self.assertRaises(ValueError):m.bounded_file(root,4)

    def test_B_never_starts_after_A_failure_and_cleanup_runs(self):
        class Driver:
            calls=[]
            def phase(self,p,*args):
                self.calls.append(p.phase)
                if p.phase=='a': raise ValueError('A still alive')
            def cleanup(self): self.calls.append('cleanup')
        d=Driver()
        with self.assertRaisesRegex(ValueError,'still alive'): m.run_cases(d,'kill',H,ORIGINALS,lambda:None)
        self.assertEqual(d.calls,['probe','a','cleanup'])

    def test_B_requires_A_return_and_external_token(self):
        events=[]
        class Driver:
            def phase(self,p,token='-'):
                events.append((p.phase,token))
                if p.phase=='a': p.anchors.extend(state(i) for i in range(3)); events.append(('A verified stopped','-'))
            def cleanup(self): events.append(('cleanup','-'))
        m.run_cases(Driver(),'graceful',H,ORIGINALS,lambda:None)
        self.assertEqual([x[0] for x in events],['probe','a','A verified stopped','b','cleanup'])
        self.assertEqual(events[3][1],m.token_text(state(2)))

    def test_cleanup_failure_cannot_pass(self):
        class Driver:
            def phase(self,p,*args):
                if p.phase=='a': p.anchors.extend(state(i) for i in range(3))
            def cleanup(self): raise ValueError('cleanup failed')
        with self.assertRaisesRegex(ValueError,'cleanup failed'): m.run_cases(Driver(),'graceful',H,ORIGINALS,lambda:None)

    def test_immutable_container_identity(self):
        d=m.Docker(Path('/repo'),Path('/input'),Path('/mount'),Path('/receipts'),'cp')
        with patch.object(d,'command',return_value=json.dumps([dict(Id='cd'*32,State={})]).encode()), self.assertRaisesRegex(ValueError,'immutable'):
            d.inspect(H)
        with self.assertRaises(ValueError): d.inspect('mutable-name')

    def test_actual_kill_exit_and_OOM_checked(self):
        d=m.Docker(Path('/repo'),Path('/input'),Path('/mount'),Path('/receipts'),'cp')
        good=dict(Running=False,Pid=0,OOMKilled=False,Error='',ExitCode=137,FinishedAt='2026-10-09')
        with patch.object(d,'inspect',return_value=good): self.assertEqual(d.stopped(H,True),good)
        for key,value in [('Running',True),('Pid',1),('OOMKilled',True),('ExitCode',0),('FinishedAt','0001-01-01T00:00:00Z')]:
            with patch.object(d,'inspect',return_value=dict(good,**{key:value})), self.assertRaises(ValueError): d.stopped(H,True)

    def test_receipt_write_failure_prevents_publication_confirmation(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root); (root/'state').mkdir(); receipts=root/'receipts'; receipts.mkdir()
            payload=b'checkpoint'; digest=m.sha(payload); (root/'state'/'validated.bin').write_bytes(payload+bytes.fromhex(digest))
            p=m.Protocol('a','graceful',H,ORIGINALS); value=state(0); value['token']['digest']=digest
            rows=[dict(record='process',phase='a',sequence=0,nonce='12345678-1234-1234-1234-123456789abc',pid='1'),
                dict(record='publication',phase='a',sequence=1,operation='anchor',prefix=0,state=value)]
            child=FakeProcess(rows); d=m.Docker(root,root,root,receipts,'cp')
            def receipt(path,value):
                if 'publication' in path.name: raise OSError('receipt disk full')
            with patch.object(d,'command',return_value=(H+'\n').encode()), patch.object(d,'inspect',return_value=dict(Running=True,Pid=12)), patch.object(m.subprocess,'Popen',return_value=child), patch.object(m,'retain',side_effect=receipt), self.assertRaisesRegex(OSError,'disk full'):
                d.phase(p)
            self.assertEqual(child.stdin.getvalue(),b'start 12345678-1234-1234-1234-123456789abc\n')
            self.assertEqual(d.owned,[H])

    def test_receipt_force_failure_is_propagated(self):
        with tempfile.TemporaryDirectory() as root, patch.object(m.os,'fsync',side_effect=OSError('force failed')):
            with self.assertRaises(OSError): m.retain(Path(root)/'receipt.json',dict(x=1))
            self.assertFalse((Path(root)/'receipt.json').exists())

    def run_scripted_A(self, root, mode, changed_after_kill=False, receipt_failure=False):
        mount=root/'checkpoint'; mount.mkdir(); (mount/'state').mkdir()
        receipts=root/'controller'; receipts.mkdir(); checkpoint=mount/'state'/'validated.bin'
        p=m.Protocol('a',mode,H,ORIGINALS)
        rows=[dict(record='process',phase='a',sequence=0,nonce='12345678-1234-1234-1234-123456789abc',pid='1')]
        images=[]
        for i in range(3):
            payload=f'checkpoint-{i}'.encode(); digest=m.sha(payload); images.append(payload+bytes.fromhex(digest))
            value=state(i); value['token']['digest']=digest
            rows.append(dict(record='publication',phase='a',sequence=i+1,operation='anchor' if i==0 else 'apply',prefix=i,state=value))
        rows.append(dict(record='holding',phase='a',sequence=4,mode=mode,digest=m.sha(b'checkpoint-2')))
        if mode=='graceful': rows.append(dict(record='complete',phase='a',sequence=5,networkContinuation=False,powerLossRecovery=False))
        child=FakeProcess(rows,137 if mode=='kill' else 0); events=[]
        class Input(io.BytesIO):
            def write(self,data):
                if data.startswith(b'start '): checkpoint.write_bytes(images[0])
                if data.startswith(b'retained '):
                    sequence=int(data.split()[1]); events.append(('confirmed',sequence))
                    if sequence<3: checkpoint.write_bytes(images[sequence])
                return super().write(data)
        child.stdin=Input(); d=m.Docker(root,root,mount,receipts,'cp')
        def command(args,timeout=10):
            events.append(('command',args))
            if args[0]=='kill':
                self.assertEqual(args,['kill','--signal=KILL',H])
                if changed_after_kill: checkpoint.write_bytes(b'changed')
            return (H+'\n').encode()
        running=dict(Running=True,Pid=12)
        stopped=dict(Running=False,Pid=0,OOMKilled=False,Error='',ExitCode=child.returncode,FinishedAt='2026-10-09')
        states=[running,running,stopped] if mode=='kill' else [running,stopped]
        def receipt(path,value):
            if 'publication' in path.name:
                events.append(('retained',value['record']['sequence']))
                if receipt_failure: raise OSError('receipt failed')
            m.retain_original(path,value)
        with patch.object(d,'command',side_effect=command), patch.object(d,'inspect',side_effect=states), patch.object(m.subprocess,'Popen',return_value=child), patch.object(m,'retain_original',m.retain,create=True), patch.object(m,'retain',side_effect=receipt):
            d.phase(p)
        return events,receipts

    def test_scripted_kill_retains_before_confirmation_and_proves_postkill_bytes(self):
        with tempfile.TemporaryDirectory() as root:
            events,receipts=self.run_scripted_A(Path(root),'kill')
            for i in range(1,4): self.assertLess(events.index(('retained',i)),events.index(('confirmed',i)))
            proof=json.loads((receipts/'a-kill-proof.json').read_text())
            self.assertEqual(proof['cid'],H); self.assertEqual(proof['stopped']['ExitCode'],137)

    def test_scripted_kill_rejects_changed_checkpoint_after_death(self):
        with tempfile.TemporaryDirectory() as root, self.assertRaisesRegex(ValueError,'changed after kill'):
            self.run_scripted_A(Path(root),'kill',changed_after_kill=True)

    def test_scripted_graceful_has_verified_termination_receipt(self):
        with tempfile.TemporaryDirectory() as root:
            _,receipts=self.run_scripted_A(Path(root),'graceful')
            self.assertEqual(json.loads((receipts/'a-termination.json').read_text())['stopped']['ExitCode'],0)

    def test_output_limits_both_streams_and_deadline(self):
        child=FakeProcess([]); child.stderr=io.BytesIO(b'x'*32+b'\n')
        with patch.object(m,'MAX_LINE',16):
            output=m.Output(child)
            for t in output.threads:t.join(1)
            with self.assertRaisesRegex(ValueError,'output limit'): output.next(time.monotonic()+1)
        child=FakeProcess([]); child.stdout=io.BytesIO(b'1234\n'*4)
        with patch.object(m,'MAX_OUTPUT',10):
            output=m.Output(child)
            for t in output.threads:t.join(1)
            with self.assertRaises(ValueError): output.next(time.monotonic()+1)
        with self.assertRaises(TimeoutError): output.next(time.monotonic()-1)

if __name__=='__main__': unittest.main()
