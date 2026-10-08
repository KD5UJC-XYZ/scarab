import java.nio.file.{Files,Paths}
import java.util.{HexFormat,Arrays}
import scala.collection.JavaConverters._
import org.bouncycastle.math.ec.rfc8032.Ed25519
object Verify extends App {
 for(line <- Files.readAllLines(Paths.get(args(0))).asScala) {
 val a=line.split("\t",-1); val pk=HexFormat.of.parseHex(a(1));val sig=HexFormat.of.parseHex(a(2));val m=HexFormat.of.parseHex(a(3))
 if(pk.length!=32||sig.length!=64) println(a(0)+"\tMalformedLength")
 else println(a(0)+"\t"+Ed25519.verify(sig,0,pk,0,m,0,m.length)+"\t"+Ed25519.validatePublicKeyFull(pk,0)+"\t"+Ed25519.validatePublicKeyFull(Arrays.copyOf(sig,32),0))
 }
}
