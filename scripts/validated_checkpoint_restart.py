#!/usr/bin/env python3
"""Offline retained-file restart controller. Execution requires an explicit --execute.

No reference node, peer connection, block production, or power-loss claim is made.
The controller's receipts directory must never be mounted in the child container.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import stat
import uuid

IMAGE = 'eclipse-temurin@sha256:b9142586f9712700c6c9e07adcedfb18608b1a3a056e4001423a3354adfa9d80'
DOCKER = ['docker', '--host', 'unix:///var/run/docker.sock']
MAX_LINE = 4 * 1024 * 1024
MAX_OUTPUT = 64 * 1024 * 1024
HEX = re.compile(r'[0-9a-f]{64}\Z')
DEC = re.compile(r'(0|[1-9][0-9]*)\Z')
SOURCES = dict(genesisSha256='transfer-genesis.md', preTipsSha256='pre-tips.md',
    preProtocolSha256='pre-protocol-state.md', preLedgerSha256='pre-ledger-state.md',
    preParametersSha256='pre-parameters.md', preUtxoSha256='pre-utxo.md',
    preUtxoCborSha256='pre-utxo-cbor.md')

def require(ok, message):
    if not ok:
        raise ValueError(message)

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()

def sha(data):
    return hashlib.sha256(data).hexdigest()

def unique_pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, 'duplicate JSON key')
        result[key] = value
    return result

def parse_record(line):
    require(isinstance(line, bytes) and len(line) <= MAX_LINE and line.endswith(b'\n'), 'record line bound/framing')
    def bad(_):
        raise ValueError('non-finite JSON number')
    value = json.loads(line, object_pairs_hook=unique_pairs, parse_constant=bad)
    require(isinstance(value, dict), 'record object required')
    return value

def fields(value, expected):
    require(isinstance(value, dict) and set(value) == set(expected.split()), 'unexpected record fields')

def decimal(value):
    require(isinstance(value, str) and DEC.fullmatch(value) and int(value) <= 2**63-1, 'canonical nonnegative Long')
    return int(value)

def hex64(value):
    require(isinstance(value, str) and HEX.fullmatch(value), 'lowercase SHA256 required')
    return value

def token_text(state):
    t = state['token']
    return ':'.join(t[k] for k in ('storeId', 'contextId', 'generation', 'digest'))

def retain(path, value):
    """Publish and force an external receipt before any child confirmation."""
    temporary = path.with_suffix('.tmp')
    with temporary.open('xb') as stream:
        stream.write(canonical(value) + b'\n')
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)

def bounded_file(path, limit):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        metadata = os.fstat(fd)
        require(stat.S_ISREG(metadata.st_mode) and metadata.st_size <= limit, 'regular file/size bound')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            data = stream.read(limit+1)
        require(len(data) <= limit, 'file grew beyond bound')
        return data
    finally:
        os.close(fd)

class Protocol:
    """Strict phase automaton; content comparisons deliberately exclude revision/token."""
    def __init__(self, phase, mode, context, originals, anchors=None):
        self.phase, self.mode, self.context = phase, mode, context
        self.originals, self.anchors = originals, anchors if anchors is not None else []
        self.sequence, self.index, self.last = 0, 0, None
        self.holding, self.complete = False, False
        self.script = ([('probe', None)] if phase == 'probe' else
            [('publication', ('anchor', 0))] + [('publication', ('apply', i)) for i in range(1, len(originals)+1)] + [('holding', None)] if phase == 'a' else
            [('restored', 'tip'), ('publication', ('rollback-two', len(originals)-2)),
             ('restored', 'rolled'), ('publication', ('reapply', len(originals)-1)),
             ('publication', ('reapply', len(originals))), ('publication', ('rollback-anchor', 0)), ('restored', 'anchor')])

    def state(self, value, prefix):
        fields(value, 'content contentSha256 revision token capacity')
        decimal(value['revision']); require(value['capacity'] == '8', 'capacity')
        t = value['token']; fields(t, 'storeId contextId generation digest')
        for k in ('storeId', 'contextId', 'digest'): hex64(t[k])
        decimal(t['generation']); require(t['contextId'] == self.context, 'token context')
        c = value['content']
        fields(c, 'profile contextId tupleId anchor tip appliedTip originals certificate nonces eligibility ledger')
        require(c['profile'] == 'conway-pv9-header11-2-derived-nonce-bounded-sequence-v1' and c['contextId'] == self.context, 'content context/profile')
        require(c['originals'] == self.originals[:prefix], 'original-byte commitments')
        require(value['contentSha256'] == sha(canonical(c)), 'content digest')
        # Entire nested projections participate in equality; reject missing/extra dimensions too.
        fields(c['certificate'], 'id contextId tip counters')
        fields(c['certificate']['tip'], 'hash slot block')
        fields(c['nonces'], 'id contextId certificateStateId lastSlot evolving candidate epoch previousEpoch lab lastEpochBlock')
        fields(c['ledger'], 'environmentId checkpointId id utxoHex fees slot')
        hex64(c['tupleId'])
        for point in (c['anchor'], c['tip'], c['appliedTip']):
            if point is not None:
                fields(point, 'slot hash'); decimal(point['slot']); hex64(point['hash'])
        cert = c['certificate']; nonces = c['nonces']; ledger = c['ledger']
        for section, keys in ((cert, ('id', 'contextId')), (nonces, ('id', 'contextId', 'certificateStateId')),
                              (ledger, ('environmentId', 'checkpointId', 'id'))):
            for key in keys: hex64(section[key])
        hex64(cert['tip']['hash']); decimal(cert['tip']['slot']); decimal(cert['tip']['block'])
        require(isinstance(cert['counters'], dict), 'certificate counters')
        for key, count in cert['counters'].items():
            require(re.fullmatch('[0-9a-f]{56}', key), 'pool id'); decimal(count)
        decimal(nonces['lastSlot'])
        for key in ('evolving', 'candidate', 'epoch', 'previousEpoch', 'lab', 'lastEpochBlock'):
            nonce = nonces[key]
            require(isinstance(nonce, dict), 'nonce object')
            if nonce.get('kind') == 'hash': fields(nonce, 'kind hash'); hex64(nonce['hash'])
            else:
                fields(nonce, 'kind')
                require(nonce['kind'] == 'neutral' or key == 'previousEpoch' and nonce['kind'] == 'unknown', 'nonce kind')
        decimal(ledger['fees']); decimal(ledger['slot'])
        require(isinstance(ledger['utxoHex'], str) and re.fullmatch('(?:[0-9a-f]{2})+', ledger['utxoHex']), 'exact UTxO bytes')
        if c['eligibility'] is not None:
            fields(c['eligibility'], 'contextId headers')
            hex64(c['eligibility']['contextId'])
            require(isinstance(c['eligibility']['headers'], list), 'eligibility headers')
            for h in c['eligibility']['headers']:
                fields(h, 'hash leaderValue stakeNumerator stakeDenominator'); hex64(h['hash'])
                for key in ('leaderValue', 'stakeNumerator', 'stakeDenominator'):
                    require(isinstance(h[key], str) and DEC.fullmatch(h[key]), 'eligibility integer')
                require(int(h['stakeDenominator']) > 0, 'stake denominator')
        return value

    def accept(self, record):
        require(not self.complete, 'output after complete')
        require(record.get('phase') == self.phase and type(record.get('sequence')) is int and record['sequence'] == self.sequence, 'phase/sequence')
        self.sequence += 1
        kind = record.get('record')
        common = 'record phase sequence '
        if self.sequence == 1:
            fields(record, common + 'nonce pid')
            require(kind == 'process' and isinstance(record['nonce'], str) and re.fullmatch('[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', record['nonce']), 'process identity')
            require(decimal(record['pid']) > 0, 'process pid')
            return 'start ' + record['nonce']
        if self.index == len(self.script):
            fields(record, common + 'networkContinuation powerLossRecovery')
            require(kind == 'complete' and record['networkContinuation'] is False and record['powerLossRecovery'] is False, 'complete flags')
            require(not (self.phase == 'a' and self.mode == 'kill'), 'kill A must not complete')
            self.complete = True
            return None
        expected, detail = self.script[self.index]
        require(kind == expected, 'unexpected phase transition')
        self.index += 1
        if kind == 'probe':
            fields(record, common + 'passed'); require(record['passed'] is True, 'probe failed')
        elif kind == 'holding':
            fields(record, common + 'mode digest')
            require(record['mode'] == self.mode and record['digest'] == self.last['token']['digest'], 'holding receipt')
            self.holding = True
            return 'release ' + record['digest'] if self.mode == 'graceful' else 'KILL'
        elif kind == 'restored':
            fields(record, common + 'step state'); require(record['step'] == detail, 'restored step')
            expected_state = self.anchors[-1] if detail == 'tip' else self.last
            require(record['state'] == expected_state, 'exact restoration mismatch')
            self.last = expected_state
        elif kind == 'publication':
            fields(record, common + 'operation prefix state')
            require(type(record['prefix']) is int and (record['operation'], record['prefix']) == detail, 'publication operation/prefix')
            value = self.state(record['state'], record['prefix'])
            if self.phase == 'a':
                require(decimal(value['revision']) == record['prefix'] and decimal(value['token']['generation']) == record['prefix'], 'A revision/generation')
                if self.last: require(value['token']['storeId'] == self.last['token']['storeId'], 'store changed')
                self.anchors.append(value)
            else:
                prefix = record['prefix']; op = record['operation']
                require(value['content'] == self.anchors[prefix]['content'], 'rollback/reapply content mismatch')
                delta = 2 if op == 'rollback-two' else len(self.originals) if op == 'rollback-anchor' else 1
                require(decimal(value['revision']) == decimal(self.last['revision']) + delta, 'revision arithmetic')
                require(decimal(value['token']['generation']) == decimal(self.last['token']['generation']) + 1, 'generation arithmetic')
                require(value['token']['storeId'] == self.last['token']['storeId'], 'store changed')
            self.last = value
            return f"retained {record['sequence']} {value['token']['digest']}"
        return None

class Output:
    """Two pipe readers with one combined byte budget and bounded queue."""
    def __init__(self, process):
        self.queue = queue.Queue(maxsize=32)
        self.size, self.lock = 0, threading.Lock()
        self.failed = threading.Event()
        self.error = None
        self.threads = [threading.Thread(target=self.read, args=(name, pipe), daemon=True)
            for name, pipe in [('stdout', process.stdout), ('stderr', process.stderr)]]
        for thread in self.threads: thread.start()

    def read(self, name, pipe):
        try:
            while not self.failed.is_set():
                line = pipe.readline(MAX_LINE+1)
                if not line: break
                with self.lock:
                    self.size += len(line)
                    require(self.size <= MAX_OUTPUT and len(line) <= MAX_LINE, 'output limit')
                self.queue.put((name, line), timeout=1)
            self.queue.put((name, None), timeout=1)
        except Exception as error:
            self.error = error
            self.failed.set()

    def next(self, deadline):
        while time.monotonic() < deadline:
            if self.failed.is_set(): raise self.error
            try: return self.queue.get(timeout=min(.1, max(.001, deadline-time.monotonic())))
            except queue.Empty: pass
        raise TimeoutError('phase output deadline')

class Docker:
    def __init__(self, repo, source, mount, receipts, classpath):
        self.repo, self.source, self.mount, self.receipts, self.classpath = repo, source, mount, receipts, classpath
        self.owned, self.clients, self.owners = [], [], []
        self.case_deadline = time.monotonic()+120

    def command(self, args, timeout=10):
        remaining = self.case_deadline-time.monotonic()
        require(remaining > 0, 'case deadline')
        return subprocess.run(DOCKER+args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=min(timeout, remaining)).stdout

    def inspect(self, cid):
        hex64(cid)
        data = json.loads(self.command(['inspect', cid]))
        require(len(data) == 1 and data[0]['Id'] == cid, 'immutable container identity')
        return data[0]['State']

    def stopped(self, cid, killed):
        s = self.inspect(cid)
        require(s['Running'] is False and s['Pid'] == 0 and s['OOMKilled'] is False and not s.get('Error'), 'container not cleanly stopped')
        require(s['ExitCode'] == (137 if killed else 0) and s['FinishedAt'] != '0001-01-01T00:00:00Z', 'exit state')
        return s

    def phase(self, protocol, expected='-'):
        phase = protocol.phase
        owner = str(uuid.uuid4())
        self.owners.append(owner)  # Registered before create: an uncertain result remains discoverable.
        cid = self.command(['create', '-i', '--label', f'lab.validated-restart.owner={owner}', '--pull', 'never', '--network', 'none', '--cpus', '1',
            '--memory', '1g', '--memory-swap', '1g', '--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m',
            '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--user', f'{os.getuid()}:{os.getgid()}',
            '-v', f'{self.repo}:/work:ro', '-v', f'{self.source}:/input:ro', '-v', f'{self.mount}:/checkpoint:rw',
            '--entrypoint', 'java', IMAGE, '-XX:ActiveProcessorCount=1', '-Xmx512m', '-cp', self.classpath,
            'lab.ValidatedRestartCapture', phase, protocol.mode, '/input', '/checkpoint', protocol.context, expected]).decode().strip()
        hex64(cid); self.owned.append(cid)
        process = subprocess.Popen(DOCKER+['start', '-ai', cid], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.clients.append(process); output = Output(process)
        deadline = min(self.case_deadline, time.monotonic()+45)
        ended, killed, initial_pid = set(), False, None
        with (self.receipts/f'{phase}-stdout.jsonl').open('xb') as out, (self.receipts/f'{phase}-stderr.txt').open('xb') as err:
            while len(ended) != 2:
                stream, line = output.next(deadline)
                if line is None: ended.add(stream); continue
                (out if stream == 'stdout' else err).write(line)
                if stream == 'stderr': continue
                record = parse_record(line); response = protocol.accept(record)
                if record['record'] == 'process':
                    s = self.inspect(cid); require(s['Running'] is True and s['Pid'] > 0, 'process did not start')
                    initial_pid = s['Pid']
                    retain(self.receipts/f'{phase}-identity.json', dict(cid=cid, state=s, record=record))
                if record['record'] == 'publication':
                    checkpoint = bounded_file(self.mount/'state'/'validated.bin', 40*1024*1024)
                    require(32 < len(checkpoint) <= 40*1024*1024 and sha(checkpoint[:-32]) == checkpoint[-32:].hex() == record['state']['token']['digest'], 'published checkpoint digest')
                    retain(self.receipts/f'{phase}-publication-{record["sequence"]:02}.json', dict(record=record, checkpointSha256=sha(checkpoint)))
                if response == 'KILL':
                    before = bounded_file(self.mount/'state'/'validated.bin', 40*1024*1024)
                    require(before == checkpoint, 'checkpoint changed since acknowledged publication')
                    s = self.inspect(cid); require(s['Running'] is True and s['Pid'] == initial_pid, 'kill target changed')
                    self.command(['kill', '--signal=KILL', cid]); killed = True
                elif response is not None:
                    process.stdin.write((response+'\n').encode('ascii')); process.stdin.flush()
            process.wait(timeout=max(.001, deadline-time.monotonic()))
            require(process.returncode == (137 if killed else 0), 'attached process exit')
        stopped = self.stopped(cid, killed)
        require((protocol.holding and killed) or protocol.complete, 'incomplete protocol')
        if killed:
            require(bounded_file(self.mount/'state'/'validated.bin', 40*1024*1024) == before, 'checkpoint changed after kill')
            retain(self.receipts/'a-kill-proof.json', dict(cid=cid, stopped=stopped, checkpointSha256=sha(before)))
        retain(self.receipts/f'{phase}-termination.json', dict(cid=cid, stopped=stopped, killed=killed))

    def cleanup(self):
        errors = []
        # Cleanup gets its own bounded budget even after case timeout.
        self.case_deadline = time.monotonic()+30
        for owner in self.owners:
            try:
                found = self.command(['ps', '-aq', '--no-trunc', '--filter', f'label=lab.validated-restart.owner={owner}']).decode().split()
                for cid in found:
                    hex64(cid)
                    inspected = json.loads(self.command(['inspect', cid]))
                    require(len(inspected) == 1 and inspected[0]['Id'] == cid and inspected[0]['Config']['Labels'].get('lab.validated-restart.owner') == owner, 'cleanup ownership mismatch')
                    if cid not in self.owned: self.owned.append(cid)
            except Exception as error: errors.append(type(error).__name__)
        for cid in self.owned:
            try:
                self.command(['rm', '-f', cid])
                require(not self.command(['ps', '-aq', '--no-trunc', '--filter', f'id={cid}']).strip(), 'container remains')
            except Exception as error: errors.append(type(error).__name__)
        for owner in self.owners:
            try:
                require(not self.command(['ps', '-aq', '--no-trunc', '--filter', f'label=lab.validated-restart.owner={owner}']).strip(), 'owned container remains')
            except Exception as error: errors.append(type(error).__name__)
        for client in self.clients:
            try:
                if client.poll() is None: client.terminate()
                client.wait(timeout=3)
            except Exception as error:
                client.kill(); errors.append(type(error).__name__)
            finally:
                for pipe in (client.stdin, client.stdout, client.stderr): pipe.close()
        require(not errors, 'cleanup failed: '+','.join(errors))

def input_pins(source):
    names = list(SOURCES.values()) + ['coherent-sequence-context.md', 'scala-sequence-capture.md']
    values = {}
    for name in names:
        p = source/name
        values[name] = sha(bounded_file(p, 20*1024*1024))
    context = sha(('coherent-sequence-context-v1\n' + ''.join(k+'='+values[SOURCES[k]]+'\n' for k in sorted(SOURCES))).encode())
    originals = []
    for line in bounded_file(source/'scala-sequence-capture.md', 20*1024*1024).splitlines():
        if line.startswith(b'{'):
            row = parse_record(line+b'\n')
            if row.get('record') == 'transfer-range-block':
                originals.append(dict(headerSha256=sha(bytes.fromhex(row['headerEnvelopeHex'])), blockSha256=sha(bytes.fromhex(row['rawBlockHex']))))
    require(2 <= len(originals) <= 8, 'retained sequence bound')
    return values, context, originals

def run_cases(driver, mode, context, originals, check_pins):
    """B is unreachable until A has returned with verified termination evidence."""
    try:
        driver.phase(Protocol('probe', mode, context, originals)); check_pins()
        a = Protocol('a', mode, context, originals)
        driver.phase(a); check_pins()
        driver.phase(Protocol('b', mode, context, originals, a.anchors), token_text(a.anchors[-1])); check_pins()
    finally:
        driver.cleanup()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ('repo', 'input', 'output'): parser.add_argument('--'+arg, required=True, type=Path)
    parser.add_argument('--mode', required=True, choices=('graceful', 'kill'))
    parser.add_argument('--execute', action='store_true', help='Explicit parent-approved acceptance execution')
    args = parser.parse_args(); require(args.execute, '--execute and parent resource grant required')
    repo, source, output = (p.resolve() for p in (args.repo, args.input, args.output))
    require(not output.is_relative_to(repo) and not output.is_relative_to(source), 'evidence outside repository/input')
    require(not subprocess.check_output(['git', '-C', str(repo), 'status', '--porcelain']).strip(), 'clean committed source required')
    commit = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD']).decode().strip()
    classpath = (repo/'app/target/runtime-classpath.txt').read_text().strip()
    require(classpath and all(p.startswith('/work/') for p in classpath.split(':')), 'isolated resolved classpath required')
    compiled = {}
    for entry in classpath.split(':'):
        host = repo/entry.removeprefix('/work/')
        for p in sorted(host.rglob('*')) if host.is_dir() else [host]:
            if p.is_file(): compiled[str(p.relative_to(repo))] = sha(p.read_bytes())
    require(compiled, 'compiled pin required')
    pins, context, originals = input_pins(source)
    output.mkdir(mode=0o700); receipts = output/'controller'; mount = output/'checkpoint'
    receipts.mkdir(mode=0o700); mount.mkdir(mode=0o700)
    retain(receipts/'pins.json', dict(commit=commit, image=IMAGE, inputs=pins, context=context, compiled=compiled))
    driver = Docker(repo, source, mount, receipts, classpath)
    result = dict(passed=False, mode=args.mode, networkContinuation=False, powerLossRecovery=False)
    def check_pins():
        require(input_pins(source)[0] == pins, 'input changed')
        require(all(sha((repo/p).read_bytes()) == digest for p, digest in compiled.items()), 'compiled artifact changed')
    try:
        run_cases(driver, args.mode, context, originals, check_pins)
        result['passed'] = True
    finally:
        retain(receipts/'result.json', result)

if __name__ == '__main__':
    main()
