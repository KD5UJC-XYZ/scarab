"""Bounded Linux process/file primitives adapted from reviewed exporter b9cd1759."""
import hashlib,os,pathlib,selectors,signal,stat,subprocess,tempfile,time
INPUT_LIMIT=8*1024*1024
OUTPUT_LIMIT=64*1024*1024
ERROR_LIMIT=128*1024
def read_regular(path,limit):
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    try:
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_size>limit:
            raise ValueError('expected bounded regular file')
        with os.fdopen(os.dup(fd),'rb') as f:data=f.read(limit+1)
        if len(data)>limit:raise ValueError('file exceeds limit')
        return data
    finally:os.close(fd)

def sha(data):return hashlib.sha256(data).hexdigest()

def bounded_process(command,data,timeout=30,output_limit=OUTPUT_LIMIT,error_limit=ERROR_LIMIT,pass_fds=(),env=None,own_group=True):
    # A bounded regular stdin snapshot avoids blocked writers when the child stops reading.
    with tempfile.TemporaryFile() as source:
        source.write(data);source.seek(0)
        p=subprocess.Popen(command,stdin=source,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                           start_new_session=own_group,pass_fds=pass_fds,env=env)
        chunks={p.stdout:bytearray(),p.stderr:bytearray()}
        limits={p.stdout:output_limit,p.stderr:error_limit};end=time.monotonic()+timeout
        try:
            with selectors.DefaultSelector() as selector:
                for stream in chunks:selector.register(stream,selectors.EVENT_READ)
                while selector.get_map():
                    left=end-time.monotonic()
                    if left<=0:raise ValueError('helper timeout')
                    for key,_ in selector.select(min(left,0.1)):
                        stream=key.fileobj;part=os.read(stream.fileno(),65536)
                        if not part:selector.unregister(stream)
                        elif len(chunks[stream])+len(part)>limits[stream]:raise ValueError('helper output limit')
                        else:chunks[stream].extend(part)
                left=end-time.monotonic()
                if left<=0:raise ValueError('helper timeout')
                try:code=p.wait(timeout=left)
                except subprocess.TimeoutExpired:raise ValueError('helper timeout')
                if code:raise ValueError('helper failed: '+bytes(chunks[p.stderr]).decode(errors='replace')[:4096])
                return bytes(chunks[p.stdout])
        finally:
            # Always clean the process group, including descendants retaining or closing pipes.
            try:
                if own_group:os.killpg(p.pid,signal.SIGKILL)
                else:p.kill()
            except ProcessLookupError:pass
            p.wait(timeout=2);p.stdout.close();p.stderr.close()


def supervise(command, *, timeout=45, env=None):
    """External wall-clock/process-group boundary, including worker publication.

    Worker helpers MUST inherit this group. Cleanup also runs after successful
    exit, so pipe-closing descendants cannot survive normal completion.
    """
    child_env=dict(os.environ if env is None else env)
    child_env['EPOCH_PACKET_DEADLINE']=str(time.monotonic()+timeout)
    child_env['EPOCH_PACKET_WORKER']='1'
    return bounded_process(command,b'',timeout=timeout,env=child_env)
