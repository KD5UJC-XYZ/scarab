"""Gated binary tests: run only inside the approved 2CPU/2GiB offline container.
No real node, keys, downloads or ledger admission. The fake peer uses native
fixture query/result bytes and only hand-encodes documented mux/handshake framing.
Usage: python3 test_native.py --capture PATH --verify PATH --offline PATH
"""
import argparse,json,os,pathlib,signal,socket,struct,subprocess,sys,tempfile,threading,time,unittest
from bounded import bounded_process,sha
strict_json=json.loads
REQ=dict(schema=1,socket='/capture/node.sock',point=dict(slot=1505,hash='1'*64),networkMagic=1082026,byronEpochSlots=21600,ntcVersion=16,producerBinarySHA256='2'*64,producerImage='sha256:'+'3'*64)
RTS=['+RTS','-M768m','-K16m','-N1','-RTS']
BIN={};FIX={}
class More(Exception):pass

def cbor(value):
    def head(major,n):
        if n<24:return bytes([major*32+n])
        for tag,width in [(24,1),(25,2),(26,4),(27,8)]:
            if n<1<<(width*8):return bytes([major*32+tag])+n.to_bytes(width,'big')
        raise ValueError('integer overflow')
    if value is False:return b'\xf4'
    if value is True:return b'\xf5'
    if type(value) is int and value>=0:return head(0,value)
    if isinstance(value,bytes):return head(2,len(value))+value
    if isinstance(value,list):return head(4,len(value))+b''.join(map(cbor,value))
    if isinstance(value,dict):return head(5,len(value))+b''.join(cbor(k)+cbor(v) for k,v in value.items())
    raise ValueError('unsupported test CBOR')

def uncbor(data,start=0,depth=0):
    if depth>16:raise ValueError('test CBOR depth')
    if start>=len(data):raise More()
    tag=data[start];start+=1;major=tag>>5;n=tag&31
    if tag in (244,245):return tag==245,start
    if n>=24:
        if n not in (24,25,26,27):raise ValueError('indefinite test CBOR forbidden')
        width=1<<(n-24)
        if start+width>len(data):raise More()
        n=int.from_bytes(data[start:start+width],'big');start+=width
    if major==0:return n,start
    if major==2:
        if start+n>len(data):raise More()
        return data[start:start+n],start+n
    if major in (4,5):
        if n>100:raise ValueError('test collection limit')
        values=[]
        for _ in range(n*(2 if major==5 else 1)):
            value,start=uncbor(data,start,depth+1);values.append(value)
        return (dict(zip(values[::2],values[1::2])) if major==5 else values),start
    raise ValueError('unsupported incoming test CBOR')

class Peer:
    def __init__(self,path,mode='good',fixtures=None):
        self.path=path;self.mode=mode;self.fixtures=fixtures or FIX
        self.trace=[];self.failure=None;self.eof=False;self.eof_time=None;self.public_tip=1505
        self.ready=threading.Event();self.stop=threading.Event();self.listener=None;self.conn=None;self.pending={}
    def __enter__(self):
        self.thread=threading.Thread(target=self.serve,daemon=True);self.thread.start()
        if not self.ready.wait(2):raise AssertionError('peer failed to bind')
        if self.failure:raise self.failure
        return self
    def __exit__(self,*_):
        self.stop.set()
        for sock in (self.conn,self.listener):
            if sock:
                try:sock.shutdown(socket.SHUT_RDWR)
                except OSError:pass
                sock.close()
        self.thread.join(2)
        if self.thread.is_alive():raise AssertionError('fake peer thread leaked')
        if pathlib.Path(self.path).exists():pathlib.Path(self.path).unlink()
    def exact(self,n):
        result=b''
        while len(result)<n:
            chunk=self.conn.recv(n-len(result))
            if not chunk:self.eof=True;self.eof_time=time.monotonic();raise EOFError()
            result+=chunk
        return result
    def receive(self,protocol):
        while True:
            data=self.pending.get(protocol,b'')
            try:
                value,end=uncbor(data)
                self.pending[protocol]=data[end:]
                return value,data[:end]
            except More:pass
            _,pid,size=struct.unpack('>IHH',self.exact(8))
            if pid&0x8000 or (pid&0x7fff)!=protocol or size==0:raise AssertionError('unexpected mux frame')
            payload=self.exact(size)
            self.pending[protocol]=data+payload
            if len(self.pending[protocol])>1048576:raise AssertionError('client query size')
    def send(self,protocol,payload):
        # Pinned network-mux encodeSDU maps ResponderDir to the high bit.
        for i in range(0,len(payload),16000):
            part=payload[i:i+16000]
            self.conn.sendall(struct.pack('>IHH',0,protocol|0x8000,len(part))+part)
    def wait_eof(self):
        try:
            while self.conn.recv(4096):pass
            self.eof=True;self.eof_time=time.monotonic()
        except (ConnectionResetError,BrokenPipeError):self.eof=True;self.eof_time=time.monotonic()
    def serve(self):
        try:
            self.listener=socket.socket(socket.AF_UNIX);self.listener.settimeout(5)
            self.listener.bind(self.path);self.listener.listen(1);self.ready.set()
            self.conn,_=self.listener.accept();self.conn.settimeout(5)
            offered,_=self.receive(0);self.trace.append('handshake')
            # NodeToClientV16 sets bit15 in the version number, not its Enum index.
            wire=32768+16
            if offered!=[0,{wire:[REQ['networkMagic'],False]}]:raise AssertionError('unexpected version/magic/query handshake')
            if self.mode=='handshake-refuse':self.send(0,cbor([2,[0,[]]]));self.wait_eof();return
            if self.mode=='bad-handshake':self.send(0,b'\xff');self.wait_eof();return
            if self.mode=='wrong-version':self.send(0,cbor([1,wire+1,[REQ['networkMagic'],False]]));self.wait_eof();return
            self.send(0,cbor([1,wire,[REQ['networkMagic'],False]]))
            acquired,_=self.receive(7);self.trace.append('acquire')
            if acquired!=[0,[1505,bytes.fromhex(REQ['point']['hash'])]]:raise AssertionError('not exact requested acquire')
            if self.mode in ('too-old','not-on-chain'):
                self.send(7,cbor([2,0 if self.mode=='too-old' else 1]))
                done,_=self.receive(7)
                if done!=[7]:raise AssertionError('expected done after acquire refusal')
                self.trace.append('done');self.wait_eof();return
            self.send(7,cbor([1]))
            for index,(expected,reply) in enumerate(zip(self.fixtures['queries'],self.fixtures['replies'])):
                _,raw=self.receive(7);self.trace.append('query');self.public_tip+=1
                if raw.hex()!=expected:raise AssertionError('native query bytes differ at '+str(index))
                if index==2 and self.mode=='disconnect':self.conn.close();return
                if index==2 and self.mode=='cancel':self.wait_eof();return
                if index==2 and self.mode=='malformed-reply':self.send(7,b'\xff');self.wait_eof();return
                failure=False
                replacements={('wrong-first',0):'wrongPoint',('wrong-last',6):'wrongPoint',('wrong-block',7):'wrongBlock',('epoch-era',2):'epochMismatch',('utxo-era',3):'utxoMismatch',('protocol-era',4):'protocolMismatch',('parameters-era',5):'parametersMismatch',('big-epoch',2):'oversizedEpochReply',('big-utxo',3):'oversizedUTxOReply'}
                if (self.mode,index) in replacements:reply=self.fixtures[replacements[self.mode,index]];failure=True
                self.send(7,bytes.fromhex(reply))
                if failure:break
            released,_=self.receive(7)
            if released!=[5]:raise AssertionError('expected exactly one release')
            self.trace.append('release')
            done,_=self.receive(7)
            if done!=[7]:raise AssertionError('expected done')
            self.trace.append('done');self.wait_eof()
        except (OSError,EOFError) as err:
            if not self.stop.is_set():self.failure=err
        except BaseException as err:self.failure=err
        finally:self.ready.set()

def invoke(binary,data,arguments=()):
    return bounded_process([binary,*arguments,*RTS],data,timeout=8)

def run_capture(mode,runner=None):
    with tempfile.TemporaryDirectory(prefix='epoch-native-') as directory:
        path=str(pathlib.Path(directory)/'fake.sock');req=dict(REQ,socket=path)
        with Peer(path,mode) as peer:
            if runner is None:
                try:out=invoke(BIN['capture'],json.dumps(req).encode());ok=True
                except ValueError:out=b'';ok=False
            else:out,ok=runner(req,peer,directory)
            peer.thread.join(2)
            if peer.failure:raise AssertionError('fake peer failure '+mode+': '+repr(peer.failure))
            trace=list(peer.trace);eof=peer.eof;tip=peer.public_tip
        if pathlib.Path(path).exists():raise AssertionError('socket path leaked')
        return out,ok,trace,eof,tip

class NativeTests(unittest.TestCase):
 def test_encoded_success_and_acquired_view(self):
  out,ok,trace,eof,tip=run_capture('good')
  self.assertTrue(ok);self.assertTrue(eof);self.assertEqual(tip,1513)
  self.assertEqual(trace,['handshake','acquire']+['query']*8+['release','done'])
  value=strict_json(out)
  self.assertEqual(value['epochHex'],FIX['epochHex']);self.assertEqual(value['utxoHex'],FIX['utxoHex'])
  self.assertEqual(value['acquiredPoint'],REQ['point']);self.assertEqual(value['finalPoint'],REQ['point'])
  self.assertEqual(value['protocolHex'],FIX['protocolHex']);self.assertEqual(value['parametersHex'],FIX['parametersHex'])
  protocol=json.loads(bytes.fromhex(value['projection']['protocolJsonHex']))
  self.assertEqual(protocol['lastSlot'],1505);self.assertEqual(protocol['oCertCounters'],{'05'*28:7})
  self.assertEqual(len(protocol['evolvingNonce']),64);self.assertIsNone(protocol['candidateNonce'])
  params=json.loads(bytes.fromhex(value['projection']['parametersJsonHex']))
  for key in ['txFeePerByte','txFeeFixed','maxTxSize','utxoCostPerByte','protocolVersion']:self.assertIn(key,params)
  ledger=json.loads(bytes.fromhex(value['projection']['ledgerJsonHex']))
  self.assertEqual(ledger['stateBefore']['esLState']['utxoState']['fees'],0)
  self.assertIn('unPoolDistr',ledger['stakeDistrib'])
  self.assertIsInstance(json.loads(bytes.fromhex(value['projection']['utxoJsonHex'])),dict)
  self.assertTrue(value['projection']['nativeSemanticRoundTrips']);self.assertFalse(value['projection']['fullLedgerValidation'])
 def test_encoded_handshake_and_acquire_failures(self):
  for mode in ['handshake-refuse','bad-handshake','wrong-version','too-old','not-on-chain']:
   with self.subTest(mode=mode):
    out,ok,trace,eof,_=run_capture(mode);self.assertFalse(ok);self.assertEqual(out,b'');self.assertTrue(eof)
    self.assertNotIn('release',trace)
    self.assertEqual(trace.count('acquire'),0 if mode in ('handshake-refuse','bad-handshake','wrong-version') else 1)
 def test_encoded_queries_fail_closed(self):
  for mode in ['wrong-first','wrong-last','wrong-block','epoch-era','utxo-era','protocol-era','parameters-era','big-epoch','big-utxo','malformed-reply','disconnect']:
   with self.subTest(mode=mode):
    out,ok,trace,eof,_=run_capture(mode);self.assertFalse(ok);self.assertEqual(out,b'')
    self.assertEqual(trace.count('acquire'),1)
    self.assertEqual(trace.count('release'),0 if mode in ('malformed-reply','disconnect') else 1)
    if mode!='disconnect':self.assertTrue(eof)
 def test_actual_transport_async_cancellation(self):
  def cancel(req,peer,_directory):
   with subprocess.Popen([BIN['offline'],'capture-cancel',*RTS],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True) as p:
    try:
     p.stdin.write(json.dumps(req).encode());p.stdin.close()
     deadline=time.monotonic()+4
     while not peer.eof and time.monotonic()<deadline:time.sleep(.01)
     self.assertTrue(peer.eof,'no EOF after captureAt timeout');self.assertIsNone(p.poll(),'EOF observed only after process exit')
     p.wait(timeout=4);out=p.stdout.read();err=p.stderr.read()
     self.assertEqual(p.returncode,0,err.decode(errors='replace'))
     self.assertEqual(strict_json(out),{'cancelled':True,'capturePublished':False})
     return out,True
    finally:
     try:os.killpg(p.pid,signal.SIGKILL)
     except ProcessLookupError:pass
  _,ok,trace,eof,_=run_capture('cancel',cancel)
  self.assertTrue(ok);self.assertTrue(eof);self.assertNotIn('release',trace)

 def test_packet_publication_and_exact_native_json_bytes(self):
  from packet import collect
  def package(req,peer,directory):
   target=pathlib.Path(directory)/'packet'
   request_file=pathlib.Path(directory)/'request.json';request_file.write_text(json.dumps(req))
   command=[sys.executable,str(pathlib.Path(__file__).with_name('packet.py')),'--query-helper',BIN['capture'],'--query-sha256',sha(pathlib.Path(BIN['capture']).read_bytes()),'--request',str(request_file),'--output',str(target)]
   receipt=json.loads(bounded_process(command,b'',timeout=8))
   self.assertFalse(receipt['rewardSeedAdmission']);self.assertFalse(receipt['runtimeImport'])
   cap=json.loads((target/'capture.json').read_bytes())
   for key,name in [('ledgerJsonHex','derived-ledger.json'),('protocolJsonHex','derived-protocol.json'),('parametersJsonHex','derived-parameters.json'),('utxoJsonHex','derived-utxo.json')]:self.assertEqual((target/name).read_bytes(),bytes.fromhex(cap['projection'][key]))
   return b'',True
  run_capture('good',package)

 def test_public_wrapper_cancel_does_not_publish(self):
  def cancelled(req,peer,directory):
   request_file=pathlib.Path(directory)/'request.json';request_file.write_text(json.dumps(req));target=pathlib.Path(directory)/'packet'
   command=[sys.executable,str(pathlib.Path(__file__).with_name('packet.py')),'--query-helper',BIN['capture'],'--query-sha256',sha(pathlib.Path(BIN['capture']).read_bytes()),'--request',str(request_file),'--output',str(target)]
   process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
   try:
    deadline=time.monotonic()+3
    while peer.trace.count('query')<3 and time.monotonic()<deadline:time.sleep(.01)
    self.assertGreaterEqual(peer.trace.count('query'),3)
    process.send_signal(self.cancel_signal);out,err=process.communicate(timeout=4)
    self.assertNotEqual(process.returncode,0);self.assertEqual(out,b'');self.assertFalse((target/'receipt.json').exists())
    peer.thread.join(2);self.assertTrue(peer.eof)
    return out,True
   finally:
    if process.poll() is None:process.kill();process.wait(timeout=2)
  for cancellation in (signal.SIGTERM,signal.SIGINT):
   self.cancel_signal=cancellation
   run_capture('cancel',cancelled)

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--capture',required=True);parser.add_argument('--offline',required=True);args=parser.parse_args()
 BIN.update(capture=args.capture,offline=args.offline)
 FIX.update(json.loads(invoke(BIN['offline'],b'',('fixtures',))))
 began=time.monotonic()
 outcome=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(NativeTests))
 print(json.dumps({'elapsedSeconds':time.monotonic()-began,'testGroups':outcome.testsRun,'success':outcome.wasSuccessful()}))
 sys.exit(0 if outcome.wasSuccessful() else 1)
