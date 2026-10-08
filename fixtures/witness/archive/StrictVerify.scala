import java.nio.file.{Files,Paths}
import java.util.{HexFormat,Arrays}
import java.security.MessageDigest
import scala.collection.JavaConverters._
import com.weavechain.curve25519._
object StrictVerify extends App {
 def verify(pk:Array[Byte],sig:Array[Byte],m:Array[Byte]):Boolean = {
  try {
   val rbytes=Arrays.copyOf(sig,32)
   val a=new CompressedEdwardsY(pk).decompress();val r=new CompressedEdwardsY(rbytes).decompress()
   if(!Arrays.equals(a.compress().toByteArray,pk)|| !Arrays.equals(r.compress().toByteArray,rbytes)||a.isSmallOrder||r.isSmallOrder) return false
   val s=Scalar.fromCanonicalBytes(Arrays.copyOfRange(sig,32,64))
   val digest=MessageDigest.getInstance("SHA-512");digest.update(rbytes);digest.update(pk);digest.update(m)
   val h=Scalar.fromBytesModOrderWide(digest.digest())
   val calc=Constants.ED25519_BASEPOINT.multiply(s).subtract(a.multiply(h))
   Arrays.equals(calc.compress().toByteArray,rbytes)
  } catch {case _:InvalidEncodingException => false;case _:IllegalArgumentException => false}
 }
 for(line <- Files.readAllLines(Paths.get(args(0))).asScala) {
  val a=line.split("\t",-1); val pk=HexFormat.of.parseHex(a(1));val sig=HexFormat.of.parseHex(a(2));val m=HexFormat.of.parseHex(a(3))
  if(pk.length!=32||sig.length!=64) println(a(0)+"\tMalformedLength")
  else println(a(0)+"\t"+verify(pk,sig,m))
 }
}
