"""Bounded executable invocation contract; no live use before source/build review.
Compiled native adapter identities and synthetic evidence are in CONFORMANCE.json.
"""
import argparse,fcntl,json,os,pathlib,sys,time,signal
from bounded import bounded_process,read_regular,sha,supervise
MAX_NATIVE=8*1024*1024
MAX_ENTRIES=4096
TOTAL_SECONDS=20
CAPTURE_FIELDS={'schema','kind','requestedPoint','acquiredPoint','finalPoint','blockNo','finalBlockNo','ntcVersion','acquireCount','reacquireCount','release','queryEncoding','epochHex','utxoHex','protocolHex','parametersHex','projection'}
VERIFIER_FIELDS={'schema','kind','epochInputSHA256','utxoInputSHA256','epochFullConsumption','utxoFullConsumption','epochRoundTripEqual','utxoRoundTripEqual','derivedRoundTripEqual','onlyUtxoReplaced','derivedSeedHex','runtimeImport','monetaryParity','wholeUTxOEntries','rewardSeedAdmission','admissionChecks'}

def strict_json(data):
    def pairs(items):
        result={}
        for k,v in items:
            if k in result:raise ValueError('duplicate JSON key: '+k)
            result[k]=v
        return result
    def constant(_):raise ValueError('nonfinite JSON constant')
    # No fractional JSON number belongs to this protocol. Also rejects 1e999.
    def floating(_):raise ValueError('noninteger JSON number')
    try:return json.loads(data,object_pairs_hook=pairs,parse_constant=constant,parse_float=floating)
    except (UnicodeError,RecursionError) as e:raise ValueError('invalid JSON encoding/depth') from e

def remaining(deadline):
    left=deadline-time.monotonic()
    if left<=0:raise ValueError('total packet deadline exceeded')
    return left


def point(value):
    if not isinstance(value,dict) or set(value)!={'slot','hash'}:raise ValueError('invalid point fields')
    if type(value['slot']) is not int or not 0<=value['slot']<2**64:raise ValueError('invalid slot')
    h=value['hash']
    if not isinstance(h,str) or len(h)!=64 or any(c not in '0123456789abcdef' for c in h):raise ValueError('invalid point hash')
    return value

def request(value):
    if not isinstance(value,dict) or set(value)!={'schema','socket','point','networkMagic','byronEpochSlots','ntcVersion','producerBinarySHA256','producerImage'}:raise ValueError('invalid request fields')
    if type(value['schema']) is not int or value['schema']!=1:raise ValueError('unsupported schema')
    point(value['point'])
    for key,limit in [('networkMagic',2**32),('byronEpochSlots',2**64),('ntcVersion',2**16)]:
        if type(value[key]) is not int or not 0<value[key]<limit:raise ValueError('invalid '+key)
    if not isinstance(value['socket'],str) or not value['socket'].startswith('/') or '\x00' in value['socket'] or len(value['socket'].encode('utf-8'))>100:raise ValueError('invalid Unix socket path')
    for key,prefix in [('producerBinarySHA256',''),('producerImage','sha256:')]:
        v=value[key]
        if not isinstance(v,str) or not v.startswith(prefix) or len(v)!=len(prefix)+64 or any(c not in '0123456789abcdef' for c in v[len(prefix):]):raise ValueError('invalid producer identity')
    return value

def native_hex(value):
    if not isinstance(value,str) or not 0<len(value)<=2*MAX_NATIVE or len(value)%2 or any(c not in '0123456789abcdef' for c in value):raise ValueError('invalid bounded native payload')
    return bytes.fromhex(value)

def check_capture(req,capture):
    if not isinstance(capture,dict) or set(capture)!=CAPTURE_FIELDS or type(capture.get('schema')) is not int or capture.get('schema')!=1 or capture.get('kind')!='single-acquire-sustained-payloads':raise ValueError('invalid capture envelope')
    if point(capture.get('requestedPoint'))!=req['point'] or point(capture.get('acquiredPoint'))!=req['point'] or point(capture.get('finalPoint'))!=req['point']:raise ValueError('point mismatch')
    if type(capture.get('blockNo')) is not int or not 0<=capture['blockNo']<2**64 or type(capture.get('finalBlockNo')) is not int or capture.get('finalBlockNo')!=capture['blockNo']:raise ValueError('block mismatch')
    if type(capture.get('ntcVersion')) is not int or capture.get('ntcVersion')!=req['ntcVersion']:raise ValueError('negotiated version mismatch')
    if type(capture.get('acquireCount')) is not int or type(capture.get('reacquireCount')) is not int or capture.get('acquireCount')!=1 or capture.get('reacquireCount')!=0 or capture.get('release')!='sent-no-ack':raise ValueError('invalid acquisition lifecycle')
    if capture.get('queryEncoding')!='GetCBOR-server-maxBound':raise ValueError('encoding attribution missing')
    return native_hex(capture.get('epochHex')),native_hex(capture.get('utxoHex'))

def check_verification(epoch,utxo,verified):
    if not isinstance(verified,dict) or set(verified)!=VERIFIER_FIELDS or type(verified.get('schema')) is not int or verified.get('schema')!=1 or verified.get('kind')!='derived-native-full-epoch-seed':raise ValueError('invalid verifier envelope')
    if verified.get('epochInputSHA256')!=sha(epoch) or verified.get('utxoInputSHA256')!=sha(utxo):raise ValueError('verifier input mismatch')
    for key in ['epochFullConsumption','utxoFullConsumption','epochRoundTripEqual','utxoRoundTripEqual','derivedRoundTripEqual','onlyUtxoReplaced']:
        if verified.get(key) is not True:raise ValueError('native verification failed: '+key)
    if verified.get('runtimeImport') is not False or verified.get('monetaryParity') is not False:raise ValueError('unsupported acceptance claim')
    if type(verified['wholeUTxOEntries']) is not int or not 0<=verified['wholeUTxOEntries']<=MAX_ENTRIES:raise ValueError('whole UTxO entry bound')
    if verified['rewardSeedAdmission'] is not False or verified['admissionChecks']!='not-performed':raise ValueError('unsupported reward seed admission')
    return native_hex(verified.get('derivedSeedHex'))

def sealed_run(binary,expected,data,args=(),deadline=None):
    if deadline is None:deadline=time.monotonic()+TOTAL_SECONDS
    remaining(deadline)
    payload=read_regular(binary,256*1024*1024)
    if sha(payload)!=expected:raise ValueError('executable digest mismatch')
    fd=os.memfd_create('epoch-query',os.MFD_ALLOW_SEALING)
    try:
        with os.fdopen(os.dup(fd),'wb') as f:f.write(payload)
        del payload
        fcntl.fcntl(fd,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
        return bounded_process(['/proc/self/fd/'+str(fd),*args,'+RTS','-M768m','-K16m','-N1','-RTS'],data,pass_fds=(fd,),timeout=min(30,remaining(deadline)),own_group=os.environ.get('EPOCH_PACKET_WORKER')!='1')
    finally:os.close(fd)

def collect(query_binary,query_sha,request_bytes,destination,deadline=None):
    if deadline is None:deadline=time.monotonic()+TOTAL_SECONDS
    remaining(deadline)
    if len(request_bytes)>16384:raise ValueError('request exceeds limit')
    req=request(strict_json(request_bytes))
    captured=sealed_run(query_binary,query_sha,json.dumps(req).encode(),deadline=deadline)
    capture=strict_json(captured);epoch,utxo=check_capture(req,capture)
    protocol=native_hex(capture['protocolHex']);parameters=native_hex(capture['parametersHex'])
    projection=capture['projection']
    expected={'kind','ledgerJsonHex','utxoJsonHex','parametersJsonHex','protocolJsonHex','utxoEntries','nativeSemanticRoundTrips','fullLedgerValidation','monetaryParity'}
    if not isinstance(projection,dict) or set(projection)!=expected:raise ValueError('invalid projection fields')
    if projection['kind']!='derived-native-supported-state' or projection['nativeSemanticRoundTrips'] is not True or projection['fullLedgerValidation'] is not False or projection['monetaryParity'] is not False:raise ValueError('invalid projection claims')
    if type(projection['utxoEntries']) is not int or not 0<=projection['utxoEntries']<=4096:raise ValueError('UTxO entry cap')
    files={'request.json':request_bytes,'capture.json':captured,'original-debug-epoch.cbor':epoch,'original-whole-utxo.cbor':utxo,'original-protocol.cbor':protocol,'original-parameters.cbor':parameters}
    for key,name in [('ledgerJsonHex','derived-ledger.json'),('utxoJsonHex','derived-utxo.json'),('parametersJsonHex','derived-parameters.json'),('protocolJsonHex','derived-protocol.json')]:
        raw=native_hex(projection[key])
        # Validate UTF-8 JSON but retain the original native encoder bytes exactly.
        import decimal
        value=json.loads(raw,parse_float=decimal.Decimal)
        if not isinstance(value,dict):raise ValueError('derived projection must be object')
        files[name]=raw
    remaining(deadline)
    out=pathlib.Path(destination);out.mkdir(mode=0o700,parents=False,exist_ok=False)
    for name,data in files.items():
        remaining(deadline)
        with (out/name).open('xb') as f:f.write(data)
    receipt={'schema':1,'kind':'single-acquire-supported-state-oracle','point':req['point'],'blockNo':capture['blockNo'],'queryHelperSHA256':query_sha,'producerIdentitySource':'caller-supplied-review-pin-not-peer-attestation','fileSHA256':{name:sha(data) for name,data in files.items()},'runtimeImport':False,'monetaryParity':False,'rewardSeedAdmission':False,'fullLedgerValidation':False,'admissionChecks':'not-performed','utxoEntries':projection['utxoEntries'],'acquireCount':1,'reacquireCount':0,'release':'sent-no-ack'}
    remaining(deadline)
    with (out/'receipt.pending').open('x') as f:json.dump(receipt,f,indent=2)
    remaining(deadline);(out/'receipt.pending').rename(out/'receipt.json')
    return receipt

def main():
    def cancelled(signum,_frame):raise SystemExit(128+signum)
    signal.signal(signal.SIGTERM,cancelled)
    if len(sys.argv)<2 or sys.argv[1]!='--supervised-worker':
        try:
            output=supervise([sys.executable,str(pathlib.Path(__file__).resolve()),'--supervised-worker',*sys.argv[1:]],timeout=TOTAL_SECONDS)
            sys.stdout.buffer.write(output);return
        except (ValueError,OSError) as e:raise SystemExit(str(e))
    if os.environ.get('EPOCH_PACKET_WORKER')!='1':raise SystemExit('private worker requires supervisor')
    ap=argparse.ArgumentParser(description=__doc__)
    for arg in ['query-helper','query-sha256','request','output']:ap.add_argument('--'+arg,required=True)
    a=ap.parse_args(sys.argv[2:])
    try:
        deadline=float(os.environ['EPOCH_PACKET_DEADLINE'])
        print(json.dumps(collect(a.query_helper,a.query_sha256,read_regular(a.request,16384),a.output,deadline=deadline)))
    except (ValueError,OSError,KeyError,TypeError) as err:ap.exit(1,str(err)+'\n')

if __name__=='__main__':main()
