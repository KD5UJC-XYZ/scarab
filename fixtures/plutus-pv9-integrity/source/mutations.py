# SPDX-License-Identifier: Apache-2.0
# Small raw-slice mutation tool for original synthetic CBOR only, not a ledger decoder.
from dataclasses import dataclass
import copy
@dataclass
class N:
 raw:bytes
 major:int
 value:object
def head(m,n):
 if n<24:return bytes([(m<<5)|n])
 for width,ai in [(1,24),(2,25),(4,26),(8,27)]:
  if n<1<<(8*width):return bytes([(m<<5)|ai])+n.to_bytes(width,'big')
 raise ValueError('too large')
def parse(raw):
 assert len(raw)<=65536
 pos=0;count=0
 def go(depth=0):
  nonlocal pos,count
  count+=1;assert depth<=32 and count<=4096
  start=pos;h=raw[pos];pos+=1;m=h>>5;ai=h&31
  if ai<24:n=ai
  elif ai in (24,25,26,27):
   width=1<<(ai-24);n=int.from_bytes(raw[pos:pos+width],'big');pos+=width
  elif ai==31:n=None
  else:raise ValueError('reserved')
  if m in (0,1):assert n is not None;v=n if m==0 else -1-n
  elif m==2:assert n is not None and n<=4096;v=raw[pos:pos+n];pos+=n
  elif m in (4,5):
   v=[]
   while (len(v)<n if n is not None else raw[pos]!=255):
    v.append((go(depth+1),go(depth+1)) if m==5 else go(depth+1))
   if n is None:pos+=1
  elif m==6:assert n is not None;v=(n,go(depth+1))
  elif m==7:assert ai in (20,21,22);v=ai
  else:raise ValueError('unsupported fixture type')
  assert pos<=len(raw);return N(raw[start:pos],m,v)
 result=go();assert pos==len(raw);return result
def arr(xs):return parse(head(4,len(xs))+b''.join(x.raw for x in xs))
def mp(xs,indef=False):return parse((b'\xbf' if indef else head(5,len(xs)))+b''.join(k.raw+v.raw for k,v in xs)+(b'\xff' if indef else b''))
def uint(n):return parse(head(0,n))
def bs(x):return parse(head(2,len(x))+x)
def field(n,k):return next(v for key,v in n.value if key.value==k)
def change(n,k,value):
 pairs=[(key,(value if key.value==k else val)) for key,val in n.value if key.value!=k or value is not None]
 if value is not None and not any(key.value==k for key,_ in n.value):pairs.append((uint(k),value))
 return mp(pairs)
def transaction(row,body=None,wits=None):
 p=copy.deepcopy(row['packet']);tx=parse(bytes.fromhex(p['transactionCborHex']));xs=tx.value[:]
 if body is not None:xs[0]=body(xs[0])
 if wits is not None:xs[1]=wits(xs[1])
 p['transactionCborHex']=arr(xs).raw.hex();return p
def redeemer_raw(packet):return field(parse(bytes.fromhex(packet['transactionCborHex'])).value[1],5).raw

def create(base):
 rows=[]
 def add(name,p,expected=True):rows.append(dict(name=name,packet=p,expectedMatch=expected,kind='original-synthetic-mutation'))
 add('commitment-missing',transaction(base,body=lambda b:change(b,11,None)),False)
 add('commitment-zero',transaction(base,body=lambda b:change(b,11,bs(bytes(32)))),False)
 def flip(b):
  x=bytearray(field(b,11).value);x[0]^=1;return change(b,11,bs(x))
 add('commitment-flip',transaction(base,body=flip),False)
 def tamper(w):
  r=field(w,5);k,payload=r.value[0];return change(w,5,mp([(k,arr([uint(8),payload.value[1]]))]))
 add('redeemer-eight-stale',transaction(base,wits=tamper),False)
 add('redeemer-indefinite-stale',transaction(base,wits=lambda w:change(w,5,mp(field(w,5).value,True))),False)
 add('outer-witness-map-reversed',transaction(base,wits=lambda w:mp(list(reversed(w.value)))))
 add('body-fee-plus-one',transaction(base,body=lambda b:change(b,2,uint(field(b,2).value+1))))
 p=copy.deepcopy(base['packet']);o=parse(bytes.fromhex(p['utxo'][0]['outputCborHex']));inline=field(o,2);tag=inline.value[1];dat=parse(tag.value[1].value);fields=dat.value[1].value
 newdat=parse(head(6,121)+arr([fields[0],uint(1000000)]).raw)
 wrapped=parse(head(6,24)+bs(newdat.raw).raw);o=change(o,2,arr([inline.value[0],wrapped]));p['utxo'][0]['outputCborHex']=o.raw.hex();add('inline-minimum-only',p)
 malformed=[dict(name='commitment-width31',packet=transaction(base,body=lambda b:change(b,11,bs(bytes(31))))),dict(name='commitment-null',packet=transaction(base,body=lambda b:change(b,11,parse(b'\xf6'))))]
 return rows,malformed
