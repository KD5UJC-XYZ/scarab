package lab.vrf;
// Public-input-only adaptation of CCL VrfUtil at 45906b783973b4c44ac6883253af26bdd07bc1b4.
// MIT: see fixtures/vrf/licenses/CCL-LICENSE. Variable-time; public inputs only.
// Native profile: sodium dbb48cce5429cb6585c9034f002568964f1ce567.
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
final class Draft03Math {
 public static final BigInteger P=BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
 public static final BigInteger L=BigInteger.TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
 static final BigInteger A=BigInteger.valueOf(486662);
 public static BigInteger integer(byte[] b) {byte[] r=b.clone();for(int i=0;i<b.length;i++)r[b.length-1-i]=b[i];return new BigInteger(1,r);}
 public static byte[] scalar(BigInteger x) {if(x.signum()<0||x.bitLength()>256)throw new IllegalArgumentException("scalar range");byte[] b=x.toByteArray(),r=new byte[32];for(int i=0;i<Math.min(32,b.length);i++)r[i]=b[b.length-1-i];return r;}
 public static byte[] hash(byte[]... pieces) {try {var d=MessageDigest.getInstance("SHA-512");for(byte[] b:pieces)d.update(b);return d.digest();}catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-512 unavailable",e);}}
 public static Ed25519Point uniform(byte[] input) {
  if(input.length!=32)throw new IllegalArgumentException("uniform length");
  byte[] rb=input.clone();int sign=(rb[31]&255)>>>7;rb[31]&=127;
  BigInteger r=integer(rb).mod(P), den=BigInteger.ONE.add(BigInteger.TWO.multiply(r.multiply(r))).mod(P);
  // Field inversion maps zero to zero, as native fe25519_invert does.
  BigInteger u=A.negate().multiply(den.modPow(P.subtract(BigInteger.TWO),P)).mod(P);
  BigInteger w=u.multiply(u.multiply(u).add(A.multiply(u)).add(BigInteger.ONE)).mod(P);
  if(w.modPow(P.subtract(BigInteger.ONE).shiftRight(1),P).equals(P.subtract(BigInteger.ONE)))u=u.negate().subtract(A).mod(P);
  BigInteger w2=u.multiply(u.multiply(u).add(A.multiply(u)).add(BigInteger.ONE)).mod(P);
  BigInteger up=u.add(BigInteger.ONE).mod(P);
  // mont_to_ed returns identity if (u+1)*montgomery_y == 0.
  BigInteger y=up.signum()==0||w2.signum()==0?BigInteger.ONE:u.subtract(BigInteger.ONE).multiply(up.modInverse(P)).mod(P);
  byte[] encoded=scalar(y);encoded[31]|=(byte)(sign<<7);
  Ed25519Point h=Ed25519Point.decode(encoded);
  if(h==null)throw new IllegalStateException("Elligator map produced undecodable point");
  return h.multiplyByCofactor();
 }
 public static Ed25519Point h2c(byte[] y,byte[] alpha) {byte[] r=Arrays.copyOf(hash(new byte[]{4,1},y,alpha),32);r[31]&=127;return uniform(r);}
 public static byte[] challenge(Ed25519Point h,byte[] originalGamma,Ed25519Point u,Ed25519Point v) {return Arrays.copyOf(hash(new byte[]{4,2},h.encode(),originalGamma,u.encode(),v.encode()),16);}
 public static byte[] output(Ed25519Point gamma) {return hash(new byte[]{4,3},gamma.multiplyByCofactor().encode());}
 public static Ed25519Point equation(Ed25519Point p,Ed25519Point q,byte[] c,byte[] s) {return p.scalarMultiply(scalar(integer(c).negate().mod(L))).add(q.scalarMultiply(s));}
}
