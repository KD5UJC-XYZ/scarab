"""Draft offline orchestrator; no acquisition or submission operations.
Run only with an independently reviewed binary, packet, and Docker hard limits.
Usage: python verify_packet.py PACKET_DIRECTORY EXECUTABLE_PATH
"""
import hashlib,json,os,signal,subprocess,sys,threading,time,stat,contextlib
from pathlib import Path

def unique_object(pairs):
    result={}
    for key,value in pairs:
        if key in result: raise ValueError('duplicate JSON key: '+key)
        result[key]=value
    return result

PROFILE='synthetic-conway-pv9-v3-draft'
LIMIT=8*1024*1024

def digest(data): return hashlib.sha256(data).hexdigest()
def reject(message): raise ValueError(message)
@contextlib.contextmanager
def regular_file(path,cap):
    # NONBLOCK avoids a FIFO open hanging before fstat; NOFOLLOW rejects links.
    fd=os.open(path,os.O_RDONLY|os.O_NONBLOCK|os.O_NOFOLLOW)
    try:
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode): reject('not a regular file: '+path.name)
        if info.st_size>cap: reject('file exceeds cap: '+path.name)
        with os.fdopen(fd,'rb',closefd=False) as f: yield f
    finally: os.close(fd)

def limited(path,cap):
    with regular_file(path,cap) as f: data=f.read(cap+1)
    if len(data)>cap: reject('file exceeds cap: '+path.name)
    return data

def file_entry(root,entry,cap):
    name=entry['file']
    if not isinstance(name,str) or Path(name).name!=name or name in ('.','..'):
        reject('only packet-local filenames allowed')
    p=root/name
    if p.is_symlink() or p.resolve().parent!=root: reject('file escapes packet directory')
    raw=limited(p,cap)
    if digest(raw)!=entry['sha256']: reject('SHA-256 mismatch: '+name)
    return raw

def prepare(root,executable):
    manifest_raw=limited(root/'manifest.json',65536)
    m=json.loads(manifest_raw,object_pairs_hook=unique_object)
    if m['schema']!=1 or m['profile']!=PROFILE or m['historicalChainAnchor'] is not None:
        reject('requires explicit synthetic schema/profile and null historical anchor')
    # The manifest itself must be independently reviewed; a self-supplied digest
    # establishes integrity, not authority or correctness.
    with regular_file(executable,512*1024*1024) as f: binary_digest=hashlib.file_digest(f,'sha256').hexdigest()
    if binary_digest!=m['helperSha256']: reject('helper binary digest mismatch')
    file_entry(root,m['referenceBuildManifest'],1048576)
    tx=file_entry(root,m['transaction'],65536)
    pp=file_entry(root,m['parameters'],65536)
    expected_script=file_entry(root,m['reviewedScript'],65536)
    if len(m['utxo'])>256: reject('too many pre-state entries')
    pairs=[]; total=0
    for e in m['utxo']:
        a=file_entry(root,e['input'],128); b=file_entry(root,e['output'],65536)
        total+=len(a)+len(b)
        if total>1048576: reject('pre-state exceeds 1 MiB')
        pairs.append(dict(inputCborHex=a.hex(),outputCborHex=b.hex()))
    data=json.dumps(dict(profile=PROFILE,transactionCborHex=tx.hex(),parametersCborHex=pp.hex(),reviewedScriptHex=expected_script.hex(),utxo=pairs),separators=(',',':')).encode()
    if len(data)>3*1024*1024: reject('encoded input exceeds cap')
    return m,manifest_raw,expected_script,data

def run_bounded(executable,data,wall_seconds=35):
    p=subprocess.Popen([str(executable)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
    outputs=[bytearray(),bytearray()]; overflow=threading.Event(); failures=[]
    def read(stream,dest):
        try:
            while True:
                chunk=stream.read(65536)
                if not chunk: break
                if len(dest)+len(chunk)>LIMIT: overflow.set(); break
                dest.extend(chunk)
        except Exception as e: failures.append(str(e)); overflow.set()
    def write():
        try: p.stdin.write(data); p.stdin.close()
        except BrokenPipeError: pass
        except Exception as e: failures.append(str(e)); overflow.set()
    threads=[threading.Thread(target=read,args=(p.stdout,outputs[0]),daemon=True),threading.Thread(target=read,args=(p.stderr,outputs[1]),daemon=True),threading.Thread(target=write,daemon=True)]
    try:
        for t in threads:t.start()
        deadline=time.monotonic()+wall_seconds
        while p.poll() is None:
            if overflow.is_set(): reject('helper output cap or pipe failure')
            if time.monotonic()>=deadline: reject('helper wall-clock deadline')
            time.sleep(.05)
        for t in threads:t.join(timeout=2)
        if overflow.is_set() or failures or any(t.is_alive() for t in threads):reject('helper pipe did not complete')
        if p.returncode!=0:reject('helper failed: '+bytes(outputs[1]).decode(errors='replace')[:8192])
        return bytes(outputs[0])
    finally:
        # Always clean the group, including successful leaders whose surviving
        # descendants closed inherited pipes. Leader exit is not group exit.
        try: os.killpg(p.pid,signal.SIGTERM)
        except ProcessLookupError: pass
        try: p.wait(timeout=2)
        except subprocess.TimeoutExpired: pass
        try: os.killpg(p.pid,signal.SIGKILL)
        except ProcessLookupError: pass
        p.wait(timeout=2)
        for stream in (p.stdin,p.stdout,p.stderr):
            try: stream.close()
            except (OSError,ValueError): pass

def require_readonly(path):
    if not (os.statvfs(path).f_flag & os.ST_RDONLY):
        reject('reviewed packet and executable must be on read-only mounts')

def main():
    if len(sys.argv)!=3: reject('usage: verify_packet.py PACKET_DIRECTORY EXECUTABLE_PATH')
    root=Path(sys.argv[1]).resolve(); executable=Path(sys.argv[2]).resolve()
    require_readonly(root); require_readonly(executable)
    def preparation_timeout(signum,frame): raise TimeoutError('packet preparation deadline')
    previous=signal.signal(signal.SIGALRM,preparation_timeout)
    signal.alarm(10)
    try: m,manifest_raw,expected_script,data=prepare(root,executable)
    finally:
        signal.alarm(0); signal.signal(signal.SIGALRM,previous)
    raw=run_bounded(executable,data); result=json.loads(raw,object_pairs_hook=unique_object)
    if result.get('status')=='rejected': reject('kernel rejected: '+str(result.get('error',''))[:8192])
    if result.get('profile')!=PROFILE or result.get('historicalChainAnchor') is not False:
        reject('helper did not emit expected synthetic result')
    if bytes.fromhex(result['scriptHex'])!=expected_script: reject('script differs from reviewed packet')
    context=bytes.fromhex(result['contextDataCborHex'])
    result['contextDataCborSha256']=digest(context)
    result['inputManifestSha256']=digest(manifest_raw)
    result['helperSha256']=m['helperSha256']
    result['referenceBuildManifestSha256']=m['referenceBuildManifest']['sha256']
    # A complete result is emitted only after every check; caller stores it as
    # an immutable artifact. No packet or shared build file is overwritten.
    sys.stdout.write(json.dumps(result,sort_keys=True,separators=(',',':'))+'\n')

if __name__=='__main__':
    try: main()
    except (ValueError,KeyError,TypeError,OSError,subprocess.SubprocessError) as e:
        print('rejected: '+str(e),file=sys.stderr); sys.exit(1)
