#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Isolated peer-selected fork acceptance candidate. No execution without --execute and reviewed pins."""
import argparse
import copy
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import time
from private_cluster import Runner, JDK, BINARY_HASHES, profile
from private_cluster_coherent import CoherentRunner, FIXTURE
from private_cluster_sequence import PRE_PINS, ORACLE_PINS
from private_cluster_durable_node import acknowledged_receipt, checkpoint_binding
from private_cluster_restart import RestartRunner, PIDFD
from validated_checkpoint_restart import Output, bounded_file, timestamp

LABEL = 'lab.cardano-fork.owner'
CAPACITY = 8
PORTS = {1: 5301, 2: 5302}
EPOCH_SLOTS = 1000
FORGING_FLAGS = ('--shelley-kes-key','--shelley-vrf-key','--shelley-operational-certificate',
                 '--delegation-certificate','--signing-key')
HEX = re.compile('[0-9a-f]{64}\\Z')

def require(ok, why):
    if not ok: raise ValueError(why)

def sha(raw): return hashlib.sha256(raw).hexdigest()

def strict_json(raw):
    def pairs(items):
        out={}
        for key,value in items:
            require(key not in out,'duplicate JSON field'); out[key]=value
        return out
    def bad(value): raise ValueError('nonfinite JSON')
    return json.loads(raw,object_pairs_hook=pairs,parse_constant=bad)

def point(tip): return dict(hash=tip['hash'],slot=tip['slot'])

def same_tip(a,b):
    return all(a.get(k)==b.get(k) for k in ('hash','slot','block','epoch','era'))

def retained_receipts(directory, requirements):
    originals={}
    for item in requirements:
        path=Path(item['path'])
        require(str(path)==item['path'] and len(path.parts)==3 and path.parts[1] in ('receipts-a','receipts-b','resume') and path.name not in ('.','..'),'owned receipt mount required')
        require(path.parts[1]!='resume' or path.name=='phase-a-acknowledged.json','known resume receipt required')
        host=directory/path.parts[1]/path.name
        require(host.resolve().is_relative_to(directory.resolve()) and not host.parent.is_symlink(),'receipt escapes evidence')
        raw=bounded_file(host,4096); require(sha(raw)==item['sha256'],'original receipt hash mismatch')
        originals[item['sha256']]=raw
    return originals

def bounded_run(command, timeout, data=None, limit=4*1024*1024):
    child=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    output=Output(child); end=time.monotonic()+timeout; done=set(); chunks={'stdout':[],'stderr':[]}; size=0
    try:
        if data is not None: child.stdin.write(data.encode() if isinstance(data,str) else data)
        child.stdin.close()
        while len(done)!=2:
            stream,line=output.next(end)
            if line is None: done.add(stream); continue
            size+=len(line); require(size<=limit,'command output limit'); chunks[stream].append(line)
        code=child.wait(timeout=max(.001,end-time.monotonic()))
        return subprocess.CompletedProcess(command,code,*[b''.join(chunks[s]).decode('utf-8',errors='strict') for s in ('stdout','stderr')])
    finally:
        if child.poll() is None:
            child.terminate()
            try: child.wait(timeout=1)
            except subprocess.TimeoutExpired: child.kill(); child.wait(timeout=1)
        for pipe in (child.stdin,child.stdout,child.stderr): pipe.close()

def isolated_topology():
    return dict(bootstrapPeers=None,localRoots=[],publicRoots=[],useLedgerAfterSlot=-1)

def topology_for_peer(template, port):
    value=copy.deepcopy(template)
    require(value.get('localRoots'),'generated local-root template required')
    root=copy.deepcopy(value['localRoots'][0]); root['advertise']=False
    root['accessPoints']=[dict(address='127.0.0.1',port=port)]
    for key in ('valency','hotValency','warmValency'):
        if key in root: root[key]=1
    value.update(bootstrapPeers=None,localRoots=[root],publicRoots=[],useLedgerAfterSlot=-1)
    return value

def node_arguments(node, forging):
    require(type(node) is int and node in PORTS and type(forging) is bool,'owned node role required')
    args=['/opt/reference/bin/cardano-node','run','--config','/work/env/configuration.yaml',
          '--topology',f'/work/env/node-data/node{node}/topology.json','--database-path',f'/work/env/node-data/node{node}/db',
          '--socket-path',f'/work/env/socket/node{node}/sock','--host-addr','127.0.0.1','--port',str(PORTS[node])]
    if forging:
        base=f'/work/env/pools-keys/pool{node}/'
        for flag,file in zip(FORGING_FLAGS,('kes.skey','vrf.skey','opcert.cert','byron-delegation.cert','byron-delegate.key')):
            args.extend((flag,base+file))
    return args+['+RTS','-N1','-M512m','-RTS']

def process_identity(pid, stat, argv, expected):
    require(type(pid) is int and pid>1,'owned child PID required')
    require(stat.split(' ',1)[0]==str(pid) and ') ' in stat,'process stat PID mismatch')
    fields=stat.rsplit(') ',1)[1].split()
    require(len(fields)>=20 and fields[0] not in ('Z','X') and fields[19].isdigit(),'live process start identity required')
    require(argv==expected,'actual process arguments differ')
    return dict(pid=pid,startTicks=fields[19],argv=argv)

def frozen_role(identity,node):
    require(identity['argv']==node_arguments(node,False),'frozen endpoint has forging/foreign arguments')
    require(not any(flag in identity['argv'] for flag in FORGING_FLAGS),'forging credentials present')
    require(not any('/pools-keys/' in word for word in identity['argv']),'credential path present')

def epoch_headroom(genesis, anchor, utc_before, utc_after, container_utc, minimum=18):
    require(genesis.get('epochLength')==EPOCH_SLOTS and genesis.get('slotLength')==0.1 and genesis.get('securityParam')==5 and genesis.get('activeSlotsCoeff')==0.05,'fixed epoch geometry')
    require(all(isinstance(t,(int,float)) and not isinstance(t,bool) and math.isfinite(t) for t in (utc_before,utc_after,container_utc)),'finite UTC clocks')
    require(utc_before<=utc_after and utc_after-utc_before<=3 and utc_before-1<=container_utc<=utc_after+1,'UTC bracket/skew')
    start=datetime.datetime.fromisoformat(genesis['systemStart'].replace('Z','+00:00'))
    require(start.tzinfo is not None,'UTC systemStart timezone required')
    current=max(utc_after,container_utc)+1
    slot=math.floor((current-start.timestamp())/0.1)
    require(slot>=0 and slot//EPOCH_SLOTS==anchor['epoch']==anchor['slot']//EPOCH_SLOTS,'current UTC differs from supplied epoch')
    remaining=start.timestamp()+(anchor['epoch']+1)*EPOCH_SLOTS*0.1-current
    require(remaining>=minimum,'insufficient real UTC epoch headroom')
    return dict(currentSlot=slot,epoch=anchor['epoch'],secondsRemaining=remaining,epochEndUtc=start.timestamp()+(anchor['epoch']+1)*EPOCH_SLOTS*0.1,clockMarginSeconds=1)

def branch_bounds(anchor, tip, branch, n_a=None):
    require(branch in ('a','b'),'known branch required')
    require(tip.get('era')=='Conway' and tip.get('epoch')==anchor['epoch'] and tip['slot']//EPOCH_SLOTS==anchor['epoch'],'same-epoch branch required')
    count=tip['block']-anchor['block']; require(tip['slot']>anchor['slot'] and tip['hash']!=anchor['hash'],'nonempty branch required')
    require(1<=count<=2 if branch=='a' else n_a is not None and 1<=n_a<=2 and n_a<count<=4,'fork suffix bounds')
    return count

def phase_arguments(phase, port, context, target, receipt=None):
    require(phase in ('a','b') and port in PORTS.values() and HEX.fullmatch(context),'phase endpoint/context')
    require(type(target) is int and (1<=target<=2 if phase=='a' else 2<=target<=4),'phase target')
    args=['node','--profile','conway-pv9-header11-2-derived-nonce-bounded-sequence-v1','--bootstrap','/evidence',
          '--port',str(port),'--mode','bounded-durable','--store-action','create' if phase=='a' else 'resume',
          '--store','/checkpoint','--receipts','/receipts-'+phase,'--expected-context',context,'--blocks',str(target),
          '--seconds','45','--events','64','--bytes','16777216','--reconnects','0','--audit','true']
    # Ordinary bounded-durable owns a fixed capacity of eight; no rollback-capacity flag.
    if phase=='b':
        require(isinstance(receipt,str) and HEX.fullmatch(receipt),'externally retained acknowledged receipt required')
        args += ['--resume-receipt','/resume/phase-a-acknowledged.json','--resume-sha256',receipt]
    else: require(receipt is None,'create cannot resume')
    return args

def manifest(directory,name,kind,pins):
    text='format\t'+kind+'\n'+''.join(key+'\t'+sha(bounded_file(directory/file,4*1024*1024))+'\n' for key,file in sorted(pins.items()))
    (directory/name).write_text(text)

def verify_build(repo,pin_path,digest):
    raw=bounded_file(pin_path,4*1024*1024); require(HEX.fullmatch(digest) and sha(raw)==digest,'reviewed source pin digest')
    pin=strict_json(raw); require(Path(pin['repo']).resolve()==repo,'pin repository')
    require(subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD'],text=True).strip()==pin['commit'],'commit differs')
    require(not subprocess.check_output(['git','-C',str(repo),'status','--porcelain']).strip(),'clean integrated checkout required')
    needed={'scripts/private_cluster_fork.py','scripts/private_cluster_fork_audit.py','app/src/main/scala/lab/ForkAuditCommand.scala','app/src/main/scala/lab/NodeCommand.scala'}
    require(needed<=set(pin['sourceHashes']) and pin['compiled'] and pin['image']==JDK,'reviewed fork sources/classes/image required')
    require((repo/'app/target/runtime-classpath.txt').read_text().strip()==pin['classpath'] and all(p.startswith('/work/') for p in pin['classpath'].split(':')),'resolved classpath differs')
    for group in ('sourceHashes','compiled'):
        for name,wanted in pin[group].items():
            path=repo/name
            require(path.resolve().is_relative_to(repo) and not path.is_symlink(),'pin escapes repository')
            require(sha(bounded_file(path,64*1024*1024))==wanted,'pinned file changed: '+name)
    return pin

class ForkRunner(CoherentRunner):
    def __init__(self,args):
        super().__init__(args); self.out.chmod(0o700)
        self.owner=self.name; self.containers={}; self.active={}; self.serial=0; self.topologies={}
        self.endpoint='unix:///var/run/docker.sock'; self.started=time.monotonic(); self.deadline=self.started+240
        self.repo=Path(args.scala_repo).resolve(); self.network_attempted=False

    def docker(self,*args,data=None,check=True,timeout=10,limit=4*1024*1024):
        remaining=self.deadline-time.monotonic(); require(remaining>0,'case deadline')
        command=['docker','--host',self.endpoint,*args]
        with self.commands.open('a') as stream: stream.write(json.dumps(command)+'\n')
        result=bounded_run(command,min(timeout,remaining),data,limit)
        if check: require(result.returncode==0,'Docker command failed: '+result.stderr[:512])
        return result

    def execute(self,*args,**kwargs): return self.docker('exec',self.containers['reference'],*args,**kwargs)
    def hashes(self,paths):
        return {path:self.execute('sha256sum',path).stdout.split()[0] for path in paths}
    def write_json(self,path,obj):
        require(path in ('byron-genesis.json','shelley-genesis.json','configuration.yaml','node-data/node1/topology.json','node-data/node2/topology.json'),'owned fixture path required')
        return self.docker('exec','-i',self.containers['reference'],'/bin/sh','-c','cat > '+shlex.quote('/work/env/'+path),data=json.dumps(obj))
    def read(self,path): return self.execute('head','-c','4194305','--','/work/env/'+path).stdout
    def query_node(self,node,kind,*options):
        return self.execute('cardano-cli','conway','query',kind,'--testnet-magic','1082026',
                            '--socket-path',f'/work/env/socket/node{node}/sock',*options,timeout=4).stdout
    def tip(self,node): return strict_json(self.query_node(node,'tip'))

    def create_container(self,key,args):
        # Ownership label is registered before create; cleanup resolves uncertain creates by label.
        result=self.docker('create','--name',self.name+'-'+key,'--label',LABEL+'='+self.owner,*args)
        cid=result.stdout.strip(); require(HEX.fullmatch(cid),'immutable CID required'); self.containers[key]=cid
        self.docker('start',cid); return cid

    def inspect(self,cid):
        value=strict_json(self.docker('inspect',cid).stdout)
        require(len(value)==1 and value[0]['Id']==cid and value[0]['Config']['Labels'].get(LABEL)==self.owner,'owned immutable container identity')
        return value[0]

    def process(self,node):
        saved=self.active[node]; pid=saved['pid']
        stat=self.execute('cat',f'/proc/{pid}/stat').stdout
        argv=self.execute('cat',f'/proc/{pid}/cmdline').stdout.rstrip('\0').split('\0')
        identity=process_identity(pid,stat,argv,saved['argv'])
        require(identity==saved['identity'],'PID/start identity changed'); return identity

    def node_inventory(self):
        command='for d in /proc/[0-9]*; do read -r n < "$d/comm" 2>/dev/null || continue; if [ "$n" = cardano-node ]; then printf "%s\\n" "${d##*/}"; fi; done; true'
        observed={int(p) for p in self.execute('/bin/sh','-c',command).stdout.split()}
        require(observed=={p['pid'] for p in self.active.values()},'unexpected reference node/relay process')

    def start_node(self,node,forging):
        require(node not in self.active,'duplicate active node')
        self.serial+=1; base=f'/work/process-{node}-{self.serial}'; args=node_arguments(node,forging)
        socket=f'/work/env/socket/node{node}/sock'
        command='umask 077; mkdir -p '+shlex.quote(str(Path(socket).parent))+'; rm -f -- '+shlex.quote(socket)+'; cd /work/env; '+shlex.join(args)+' > '+base+'.log 2>&1 & p=$!; printf "%s" "$p" > '+base+'.pid; wait "$p"; r=$?; printf "%s" "$r" > '+base+'.exit'
        self.docker('exec','-d',self.containers['reference'],'/bin/sh','-c',command)
        until=min(self.deadline,time.monotonic()+10)
        while time.monotonic()<until:
            result=self.execute('cat',base+'.pid',check=False)
            if result.returncode==0 and result.stdout.isdigit(): break
            time.sleep(.05)
        else: raise TimeoutError('owned process PID readiness')
        pid=int(result.stdout)
        while time.monotonic()<until:
            stat=self.execute('cat',f'/proc/{pid}/stat',check=False)
            cmd=self.execute('cat',f'/proc/{pid}/cmdline',check=False)
            try: identity=process_identity(pid,stat.stdout,cmd.stdout.rstrip('\0').split('\0'),args); break
            except ValueError: time.sleep(.05)
        else: raise TimeoutError('owned executable readiness')
        self.active[node]=dict(pid=pid,argv=args,identity=identity,base=base,forging=forging)
        self.save(f'process-{node}-{self.serial}-start.json',dict(identity=identity,forging=forging,container=self.containers['reference']))
        while time.monotonic()<until:
            try:
                tip=self.tip(node)
                if tip.get('era')=='Conway': break
            except ValueError: pass
            time.sleep(.1)
        else: raise TimeoutError('node socket readiness')
        self.node_inventory()
        if not forging: frozen_role(self.process(node),node)

    def stop_node(self,node):
        saved=self.active[node]; identity=self.process(node)
        require(getattr(self,'pidfd_ready',False),'verified process-bound helper required')
        sent=self.execute(PIDFD,str(saved['pid']),identity['startTicks'],check=False,timeout=5)
        self.save(Path(saved['base']).name+'-signal.json',dict(returnCode=sent.returncode,stdout=sent.stdout,stderr=sent.stderr))
        require(sent.returncode==0,'process-bound signal failed; no numeric PID fallback')
        require(strict_json(sent.stdout)==dict(method='pidfd_send_signal',pid=saved['pid'],startTicks=int(identity['startTicks']),signal='TERM',sent=True),'process-bound signal receipt differs')
        until=min(self.deadline,time.monotonic()+7)
        while time.monotonic()<until:
            result=self.execute('cat',saved['base']+'.exit',check=False)
            if result.returncode==0: break
            time.sleep(.05)
        else: raise TimeoutError('graceful reference stop')
        require(result.stdout=='0','reference shutdown must be clean')
        log=self.execute('head','-c','4194305',saved['base']+'.log').stdout
        self.save(Path(saved['base']).name+'-stdout.txt',log)
        self.save(Path(saved['base']).name+'-stop.json',dict(identity=identity,exitCode=0,verifiedBeforeSignal=True))
        del self.active[node]; self.node_inventory()

    def roles(self):
        self.node_inventory()
        require(self.read('configuration.yaml')==self.configuration,'reference config changed')
        values={}
        for node in self.active:
            values[str(node)]=self.process(node); frozen_role(values[str(node)],node)
            require(strict_json(self.read(f'node-data/node{node}/topology.json'))==self.topologies[node],'topology changed')
        return values

    def snapshot(self,node,directory,prefix):
        before_roles=self.roles(); tips=[]; raw_tips=[]
        def observe():
            raw=self.query_node(node,'tip'); raw_tips.append(raw); tips.append(strict_json(raw))
        observe(); observe()
        for name,kind,options in [('utxo','utxo',['--whole-utxo','--output-json']),('utxo-cbor','utxo',['--whole-utxo','--output-cbor-hex']),
                                 ('ledger-state','ledger-state',['--output-json']),('protocol-state','protocol-state',['--output-json']),('parameters','protocol-parameters',[])]:
            (directory/(prefix+'-'+name+'.md')).write_text(self.query_node(node,kind,*options)); observe()
        after_roles=self.roles(); require(before_roles==after_roles,'frozen endpoint role changed')
        keys=('hash','slot','block','epoch','era')
        require(tips[0].get('era')=='Conway' and all(tuple(t.get(k) for k in keys)==tuple(tips[0].get(k) for k in keys) for t in tips),'state acquisition tips changed')
        (directory/(prefix+'-tips.md')).write_text('['+','.join(raw_tips)+']')
        (directory/(prefix+'-binding.json')).write_text(json.dumps(dict(point=tips[0],beforeRoles=before_roles,afterRoles=after_roles,singleAcquiredSnapshot=False)))
        return tips[0]

    def setup(self):
        require(not os.environ.get('DOCKER_HOST') and not os.environ.get('DOCKER_CONTEXT'),'clear endpoint overrides')
        self.pin=verify_build(self.repo,Path(self.args.source_pin),self.args.source_pin_sha256)
        self.save('source-pin.json',self.pin)
        self.image=self.docker('image','inspect',self.args.reference_image,'--format','{{.Id}}').stdout.strip()
        require(re.fullmatch('sha256:[0-9a-f]{64}',self.image),'pinned reference image required')
        self.network_attempted=True
        self.docker('network','create','--internal','--label',LABEL+'='+self.owner,self.name)
        self.create_container('reference',['--pull','never','--network',self.name,'--cpus','2','--memory','2g','--memory-swap','2g','--pids-limit','256',
            '--cap-drop','ALL','--security-opt','no-new-privileges','--user','1000:1000','--read-only',
            '--tmpfs','/work:rw,nosuid,nodev,noexec,size=1g,uid=1000,gid=1000,mode=0700','--tmpfs','/tmp:rw,nosuid,nodev,noexec,size=128m',
            '-e','CARDANO_CLI=/opt/reference/bin/cardano-cli','-e','CARDANO_NODE=/opt/reference/bin/cardano-node',
            '--entrypoint','/bin/sh',self.image,'-c','umask 077; sleep 270'])
        for name,expected in BINARY_HASHES.items():
            require(self.execute('sha256sum','/opt/reference/bin/'+name).stdout.split()[0]==expected,'reference binary pin')
        RestartRunner.verify_pidfd_helper(self)
        self.execute('cardano-testnet','create-env','--nodes','spo,spo,relay','--testnet-magic','1082026','--output','/work/env',timeout=40)
        self.prepare_genesis()
        genesis,config,topologies=profile(strict_json(self.read('shelley-genesis.json')),strict_json(self.read('configuration.yaml')),
                                           [strict_json(self.read(f'node-data/node{i}/topology.json')) for i in (1,2,3)])
        genesis['epochLength']=EPOCH_SLOTS
        self.write_json('shelley-genesis.json',genesis); self.write_json('configuration.yaml',config)
        expected={name:strict_json(raw) for name,raw in self.generated_genesis.items()}
        expected['byron-genesis.json']['nonAvvmBalances']={}
        expected['shelley-genesis.json']['protocolParams']['protocolVersion']={'major':9,'minor':0}
        expected['shelley-genesis.json']['epochLength']=EPOCH_SLOTS
        effective={name:self.read(name) for name in expected}
        require({name:strict_json(raw) for name,raw in effective.items()}==expected,'unexpected fresh genesis mutation')
        for node in (1,2,3): self.execute('test','!','-e',f'/work/env/node-data/node{node}/db')
        for name,raw in effective.items(): self.save('effective-'+name+'.md',raw)
        self.save('fork-fixture.json',dict(epochLength=EPOCH_SLOTS,slotLength=0.1,securityParam=5,activeSlotsCoeff=0.05,randomnessStabilizationWindow=400,allDatabasesAbsentAtMutation=True,generatedSha256={n:sha(v.encode()) for n,v in self.generated_genesis.items()},effectiveSha256={n:sha(v.encode()) for n,v in effective.items()}))
        self.genesis=genesis; self.configuration=self.read('configuration.yaml')
        self.genesis_pins={name:sha(self.read(name).encode()) for name in ('byron-genesis.json','shelley-genesis.json','alonzo-genesis.json','conway-genesis.json')}
        for node in (1,2):
            value=topology_for_peer(topologies[node-1],PORTS[3-node]); self.topologies[node]=value
            self.write_json(f'node-data/node{node}/topology.json',value)
        self.save('resource-profile.json',dict(referenceCpus=2,referenceMemoryGiB=2,maxReferenceProcesses=2,nodeRtsCapabilities=1,nodeHeapMiB=512,scalaCpus=1,scalaMemoryGiB=1,totalSeconds=240,cleanupSeconds=30,discovery=False,relayStarted=False))

    def clock(self,minimum):
        before=time.time(); raw=self.execute('date','-u','+%s.%N').stdout.strip(); after=time.time()
        require(re.fullmatch(r'[0-9]+\.[0-9]{9}',raw),'container UTC clock precision required')
        proof=epoch_headroom(self.genesis,self.anchor,before,after,float(raw),minimum)
        self.save('utc-headroom-'+str(self.serial)+'-'+str(minimum)+'.json',proof)
        self.forge_deadline=min(self.deadline,time.monotonic()+proof['secondsRemaining']-3)

    def common_anchor(self):
        self.start_node(1,True); self.start_node(2,True)
        until=min(self.deadline,time.monotonic()+125)
        while time.monotonic()<until:
            a,b=self.tip(1),self.tip(2)
            if a.get('hash')==b.get('hash') and a.get('block',0)>0 and a.get('epoch',0)>=1 and 1<=a.get('slotInEpoch',501)<=25: break
            time.sleep(.1)
        else: raise TimeoutError('early common chain readiness')
        self.stop_node(1); self.stop_node(2); self.start_node(1,False); self.start_node(2,False)
        until=min(self.deadline,time.monotonic()+8)
        while time.monotonic()<until:
            a,b=self.tip(1),self.tip(2)
            if a.get('hash')==b.get('hash'): break
            time.sleep(.1)
        else: raise TimeoutError('frozen common anchor convergence')
        self.anchor=self.snapshot(1,self.out,'pre')
        require(same_tip(self.tip(2),self.anchor),'both databases must hold exact common anchor')
        (self.out/'transfer-genesis.md').write_text(self.read('shelley-genesis.json'))
        manifest(self.out,'coherent-sequence-context.md','coherent-sequence-context-v1',PRE_PINS)
        recipe='coherent-sequence-context-v1\n'+''.join(k+'='+sha((self.out/n).read_bytes())+'\n' for k,n in sorted(PRE_PINS.items()))
        self.context_id=sha(recipe.encode())

    def transactions(self):
        utxo=strict_json((self.out/'pre-utxo.md').read_text())
        addresses=[self.execute('cardano-cli','address','build','--payment-verification-key-file',f'/work/env/utxo-keys/utxo{i}/utxo.vkey','--testnet-magic','1082026').stdout.strip() for i in (1,2)]
        sources=[(k,v) for k,v in utxo.items() if v['address']==addresses[0]]
        require(len(sources)==1 and set(sources[0][1]['value'])=={'lovelace'},'one complete disposable ADA source')
        txin,source=sources[0]; self.transactions_by_branch={}
        for index,branch in enumerate(('a','b')):
            amount=10000000+index*1000000; fee=200000+index*10000; change=source['value']['lovelace']-amount-fee
            require(change>amount,'adequate disposable source value')
            base='/work/fork-'+branch
            self.execute('cardano-cli','conway','transaction','build-raw','--tx-in',txin,'--tx-out',addresses[1]+'+'+str(amount),'--tx-out',addresses[0]+'+'+str(change),'--fee',str(fee),'--out-file',base+'.body')
            self.execute('cardano-cli','conway','transaction','sign','--tx-body-file',base+'.body','--signing-key-file','/work/env/utxo-keys/utxo1/utxo.skey','--testnet-magic','1082026','--out-file',base+'.signed')
            txid=self.execute('cardano-cli','conway','transaction','txid','--tx-file',base+'.signed','--output-text').stdout.strip()
            require(HEX.fullmatch(txid),'canonical transaction ID')
            envelope=strict_json(self.execute('cat',base+'.signed').stdout)['cborHex']
            (self.out/f'signed-transaction-{index}-cbor.md').write_text(envelope)
            self.transactions_by_branch[branch]=dict(input=txin,transactionId=txid,fee=fee,signedPath=base+'.signed')
        require(self.transactions_by_branch['a']['transactionId']!=self.transactions_by_branch['b']['transactionId'],'distinct branch spends')
        self.save('branch-transactions.json',self.transactions_by_branch)

    def fork(self):
        self.clock(26)
        self.stop_node(1); self.stop_node(2)
        for node in (1,2):
            self.topologies[node]=isolated_topology(); self.write_json(f'node-data/node{node}/topology.json',self.topologies[node])
        self.endpoints={}
        for branch,node in (('a',1),('b',2)):
            self.clock(18 if branch=='a' else 9)
            self.start_node(node,True)
            tx=self.transactions_by_branch[branch]
            submitted=self.execute('cardano-cli','conway','transaction','submit','--tx-file',tx['signedPath'],'--testnet-magic','1082026','--socket-path',f'/work/env/socket/node{node}/sock',timeout=3,check=False)
            self.save(branch+'-submission.json',dict(returnCode=submitted.returncode,stdout=submitted.stdout,stderr=submitted.stderr,transactionId=tx['transactionId']))
            require(submitted.returncode==0,'reference branch submission rejected; exact result retained')
            minimum=1 if branch=='a' else self.n_a+1
            while time.monotonic()<self.forge_deadline:
                tip=self.tip(node); count=tip.get('block',0)-self.anchor['block']
                require(tip.get('epoch')==self.anchor['epoch'] and count<=(2 if branch=='a' else 4),'branch grew outside bounds')
                if count>=minimum:
                    utxo=strict_json(self.query_node(node,'utxo','--whole-utxo','--output-json'))
                    if tx['input'] not in utxo and any(k.startswith(tx['transactionId']+'#') for k in utxo): break
                time.sleep(.08)
            else: raise TimeoutError('same-epoch branch inclusion deadline')
            self.stop_node(node); self.start_node(node,False)
            frozen=self.tip(node); count=branch_bounds(self.anchor,frozen,branch,getattr(self,'n_a',None))
            if branch=='a': self.n_a=count
            else: self.n_b=count
            self.endpoints[branch]=frozen
        for branch,node in (('a',1),('b',2)):
            directory=self.out/('branch-'+branch); directory.mkdir(mode=0o700)
            observed=self.snapshot(node,directory,'post'); require(same_tip(observed,self.endpoints[branch]),'frozen branch endpoint moved')
            for index in (0,1): (directory/f'signed-transaction-{index}-cbor.md').write_bytes((self.out/f'signed-transaction-{index}-cbor.md').read_bytes())
        self.save('branch-endpoints.json',dict(anchor=self.anchor,a=self.endpoints['a'],b=self.endpoints['b'],nA=self.n_a,nB=self.n_b,capacity=8,independentChainSelection=False))

    def scala_phase(self,phase,receipt=None):
        node=1 if phase=='a' else 2; target=self.n_a if phase=='a' else self.n_b
        self.roles(); require(same_tip(self.tip(node),self.endpoints[phase]),'endpoint changed before Scala')
        arguments=phase_arguments(phase,PORTS[node],self.context_id,target,receipt)
        cid=self.create_container(phase,['--pull','never','--network','container:'+self.containers['reference'],'--cpus','1','--memory','1g','--memory-swap','1g',
            '--pids-limit','128','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user','1000:1000','--tmpfs','/tmp:size=64m',
            '--log-driver','json-file','--log-opt','max-size=32m','--log-opt','max-file=1',
            '-v',str(self.repo)+':/work:ro','-v',str(self.out)+':/evidence:ro','-v',str(self.out/'checkpoint')+':/checkpoint:rw',
            '-v',str(self.out/('receipts-'+phase))+':/receipts-'+phase+':rw','-v',str(self.out/'resume')+':/resume:ro',
            '--entrypoint','java',JDK,'-XX:ActiveProcessorCount=1','-Xmx512m','-cp',self.pin['classpath'],'lab.Main',*arguments])
        started=self.inspect(cid); self.save(phase+'-process-start.json',started)
        if phase=='b':
            require(cid!=self.phase_a_finished['Id'] and timestamp(self.phase_a_finished['State']['FinishedAt'])<timestamp(started['State']['StartedAt']),'distinct sequential Scala processes required')
        until=min(self.deadline,time.monotonic()+45)
        while time.monotonic()<until:
            ended=self.inspect(cid)
            if not ended['State']['Running']: break
            time.sleep(.1)
        else: raise TimeoutError('Scala phase deadline')
        logs=self.docker('logs',cid,limit=32*1024*1024)
        (self.out/(phase+'-stdout.jsonl')).write_text(logs.stdout); (self.out/(phase+'-stderr.txt')).write_text(logs.stderr)
        self.save(phase+'-process-finish.json',ended)
        require(ended['State']['ExitCode']==0 and ended['State']['OOMKilled'] is False and ended['State']['Pid']==0,'Scala graceful exit required')
        rows=[strict_json(line) for line in logs.stdout.splitlines() if line]
        outcomes=[r for r in rows if r.get('scope')=='bounded-node-outcome']
        require(len(outcomes)==1,'unique ordinary node outcome required'); outcome=outcomes[0]
        require(outcome.get('typedStop')=='TargetReached' and outcome.get('confirmation')=='acknowledged' and outcome.get('potentiallyOlderThanDisk') is False and outcome.get('externalReceiptStale') is False and outcome.get('cleanupFailure') is None and outcome.get('peerResourcesFinalized') is True,'clean acknowledged outcome required')
        require(outcome.get('scopedAppliedTip')==point(self.endpoints[phase]),'Scala phase tip differs from branch endpoint')
        path=Path(outcome['receiptPath']); require(path.parent==Path('/receipts-'+phase),'phase receipt path')
        raw=bounded_file(self.out/('receipts-'+phase)/path.name,4096)
        token=acknowledged_receipt(raw,outcome['receiptSha256'],self.context_id)
        require(int(token['generation'])==outcome['confirmedGeneration'],'outcome token generation')
        image=bounded_file(self.out/'checkpoint/validated.bin',40*1024*1024); binding=checkpoint_binding(image,token['digest'])
        destination=self.out/'resume'/('phase-'+phase+'-acknowledged.json')
        with destination.open('xb') as stream: stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        fd=os.open(destination.parent,os.O_RDONLY|os.O_DIRECTORY)
        try: os.fsync(fd)
        finally: os.close(fd)
        (self.out/(phase+'-validated.bin')).write_bytes(image)
        self.save(phase+'-external-receipt.json',dict(receiptSha256=outcome['receiptSha256'],token=token,**binding,pendingUsed=False))
        directory=self.out/('branch-'+phase)
        captures=[line for line in logs.stdout.splitlines() if strict_json(line).get('record')=='transfer-range-block']
        (directory/'scala-sequence-capture.md').write_text('\n'.join(captures)+'\n')
        manifest(directory,'coherent-sequence-oracle.md','coherent-sequence-oracle-v1',ORACLE_PINS)
        self.roles(); require(same_tip(self.tip(node),self.endpoints[phase]),'endpoint changed during Scala')
        if phase=='a': self.phase_a_finished=ended
        return rows,outcome

    def audit(self,a,b):
        cid=self.create_container('audit',['--pull','never','--network','none','--cpus','1','--memory','1g','--memory-swap','1g','--pids-limit','128',
            '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--user','1000:1000','--tmpfs','/tmp:size=64m',
            '-v',str(self.repo)+':/work:ro','-v',str(self.out)+':/evidence:ro','--entrypoint','java',JDK,'-XX:ActiveProcessorCount=1','-Xmx512m','-cp',self.pin['classpath'],
            'lab.ForkAuditCommand','/evidence','/evidence/branch-a','/evidence/branch-b','/evidence/a-stdout.jsonl','/evidence/b-stdout.jsonl'])
        until=min(self.deadline,time.monotonic()+45)
        while time.monotonic()<until:
            info=self.inspect(cid)
            if not info['State']['Running']: break
            time.sleep(.1)
        else: raise TimeoutError('fork audit deadline')
        result=self.docker('logs',cid); self.save('audit-stdout.jsonl',result.stdout); self.save('audit-stderr.txt',result.stderr)
        require(info['State']['ExitCode']==0 and info['State']['OOMKilled'] is False,'checked fork replay failed')
        reports=[strict_json(line) for line in result.stdout.splitlines() if line]
        require(len(reports)==1,'single standalone fork audit report required')
        from private_cluster_fork_audit import audit_evidence, audit_receipts
        summary=audit_evidence(a,b,reports[0])
        return audit_receipts(summary,retained_receipts(self.out,summary['requiredReceipts']))

    def cleanup(self):
        self.deadline=time.monotonic()+30; errors=[]; removed=[]; collection_errors=[]
        for kind in ('container','network'):
            try:
                args=['ps','-aq','--no-trunc'] if kind=='container' else ['network','ls','-q','--no-trunc']
                found=self.docker(*args,'--filter','label='+LABEL+'='+self.owner).stdout.split()
                for identity in found:
                    info=strict_json(self.docker(*(['inspect'] if kind=='container' else ['network','inspect']),identity).stdout)[0]
                    labels=info['Config']['Labels'] if kind=='container' else info['Labels']
                    require(info['Id']==identity and labels.get(LABEL)==self.owner,'cleanup ownership changed')
                    if kind=='container' and hasattr(self,'containers'):
                        key=next((key for key,cid in self.containers.items() if cid==identity),None)
                        try:
                            if key=='reference':
                                for node,saved in self.active.items():
                                    log=self.execute('head','-c','4194304',saved['base']+'.log',timeout=2)
                                    self.save(Path(saved['base']).name+'-cleanup-stdout.txt',log.stdout)
                            elif key:
                                log=self.docker('logs',identity,timeout=2,limit=32*1024*1024)
                                self.save(key+'-cleanup-stdout.txt',log.stdout); self.save(key+'-cleanup-stderr.txt',log.stderr)
                            if key: self.save(key+'-cleanup-inspect.json',info)
                        except BaseException as error: collection_errors.append(type(error).__name__+': '+str(error))
                    self.docker(*(['rm','-f',identity] if kind=='container' else ['network','rm',identity]))
                    removed.append(dict(kind=kind,id=identity))
                require(not self.docker(*args,'--filter','label='+LABEL+'='+self.owner).stdout.strip(),'owned resources remain')
            except BaseException as error: errors.append(type(error).__name__+': '+str(error))
        self.save('cleanup.json',dict(removed=removed,errors=errors,evidenceCollectionErrors=collection_errors,verifiedAbsent=not errors))
        require(not errors,'cleanup incomplete')

    def run(self):
        result=dict(passed=False,peerSelectedReplacement=True,independentChainSelection=False,powerLossRecovery=False,singleAcquiredSnapshot=False)
        try:
            self.setup(); self.common_anchor(); self.transactions(); self.fork()
            for name in ('checkpoint','receipts-a','receipts-b','resume'): (self.out/name).mkdir(mode=0o700)
            a,first=self.scala_phase('a'); b,last=self.scala_phase('b',first['receiptSha256'])
            require(first['events']+last['events']<=128 and first['returnedBytes']+last['returnedBytes']<=33554432,'combined Scala resource counters')
            result['audit']=self.audit(a,b)
            require(result['audit'].get('passed') is True and result['audit'].get('receiptBytesVerified') is True,'fork evidence and original receipts must pass')
            require({name:sha(self.read(name).encode()) for name in self.genesis_pins}==self.genesis_pins,'genesis changed')
            verify_build(self.repo,Path(self.args.source_pin),self.args.source_pin_sha256)
            require(time.monotonic()<=self.deadline,'case deadline exceeded')
        except BaseException as error:
            result['error']=type(error).__name__+': '+str(error)
            raise
        else: result['passed']=True
        finally:
            try: self.cleanup()
            except BaseException:
                result['passed']=False; result['cleanupFailed']=True; raise
            finally:
                result['elapsedSeconds']=time.monotonic()-self.started; self.save('result.json',result)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('reference-image','pidfd-helper-sha256','scala-repo','source-pin','source-pin-sha256','output'): parser.add_argument('--'+name,required=True)
    parser.add_argument('--fixture-profile',required=True,choices=[FIXTURE]); parser.add_argument('--execute',action='store_true')
    args=parser.parse_args(); require(args.execute,'explicit reviewed execution grant and --execute required')
    args.capture=False
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    ForkRunner(args).run()

if __name__=='__main__': main()
