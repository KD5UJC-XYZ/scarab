import hashlib,json,pathlib
D=pathlib.Path(__file__).resolve().parent
P=2**255-19; L=2**252+27742317777372353535851937790883648493; d=-121665*pow(121666,-1,P)%P
O=(0,1)
def decode(b):
 y=int.from_bytes(b,'little')&((1<<255)-1); sign=b[31]>>7
 if y>=P:return None
 u=(y*y-1)*pow(d*y*y+1,-1,P)%P
 x=pow(u,(P+3)//8,P)
 if x*x%P!=u:x=x*pow(2,(P-1)//4,P)%P
 if x*x%P!=u:return None
 if x%2!=sign:x=-x%P
 return x,y
def enc(p):
 x,y=p;return (y|((x%2)<<255)).to_bytes(32,'little').hex()
def add(p,q):
 x,y=p;u,v=q;t=d*x*u*y*v%P
 return ((x*v+y*u)*pow(1+t,-1,P)%P,(y*v+x*u)*pow(1-t,-1,P)%P)
def mul(p,n):
 r=O
 while n:
  if n&1:r=add(r,p)
  p=add(p,p);n>>=1
 return r
pts=[]
for i in range(512):
 b=hashlib.sha256(f'independent-affine-vrf-review-{i}'.encode()).digest();p=decode(b)
 if p is not None:pts.append(p)
 if len(pts)==64:break
rows=[]; expected={}
for i in range(64):
 c=int.from_bytes(hashlib.sha256(f'public-c-{i}'.encode()).digest()[:16],'little');s=int.from_bytes(hashlib.sha256(f'public-s-{i}'.encode()).digest(),'little')%L
 p,q=pts[i],pts[(i+17)%64]; ident=f'affine-equation-{i}'
 rows.append('\t'.join((ident,'e',enc(p),enc(q),c.to_bytes(16,'little').hex(),s.to_bytes(32,'little').hex())))
 expected[ident]=enc(add(mul(p,-c%L),mul(q,s)))
for y in range(P,2**255):
 for sign in (0,1):
  ident=f'noncanonical-y-{y-P}-{sign}';rows.append('\t'.join((ident,'d',(y|(sign<<255)).to_bytes(32,'little').hex())));expected[ident]='INVALID'
for y in (1,P-1):
 ident=f'negative-zero-{y}';rows.append('\t'.join((ident,'d',(y|(1<<255)).to_bytes(32,'little').hex())));expected[ident]='SMALL:'+y.to_bytes(32,'little').hex()
(D/'affine-inputs.tsv').write_text('\n'.join(rows)+'\n');(D/'affine-expected.json').write_text(json.dumps(expected,indent=2)+'\n');print(len(rows))
