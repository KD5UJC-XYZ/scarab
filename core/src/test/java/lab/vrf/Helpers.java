package lab.vrf;
import java.nio.file.*;import java.util.*;
final class Helpers {
 static String evaluate(String line) {
  var hf=HexFormat.of(); {
   var a=line.split("\t",-1);String r;
   try {
    byte[] b=hf.parseHex(a[2]);var p=Ed25519Point.decode(b);
    switch(a[1]) {
     case "d":r=p==null?"INVALID":(p.multiplyByCofactor().equals(Ed25519Point.NEUTRAL)?"SMALL:":"POINT:")+hf.formatHex(p.encode());break;
     case "u":r=hf.formatHex(Draft03Math.uniform(b).encode());break;
     case "e":var q=Ed25519Point.decode(hf.parseHex(a[3]));r=hf.formatHex(Draft03Math.equation(p,q,hf.parseHex(a[4]),hf.parseHex(a[5])).encode());break;
     case "t":
      byte[] pi=hf.parseHex(a[3]),alpha=hf.parseHex(a[4]),gb=Arrays.copyOf(pi,32),c=Arrays.copyOfRange(pi,32,48),s=Arrays.copyOfRange(pi,48,80);var g=Ed25519Point.decode(gb);
      if(p==null||p.multiplyByCofactor().equals(Ed25519Point.NEUTRAL)||g==null||Draft03Math.integer(s).compareTo(Draft03Math.L)>=0){r="INVALID";break;}
      var h=Draft03Math.h2c(p.encode(),alpha);var u=Draft03Math.equation(p,Ed25519Point.BASE_POINT,c,s);var v=Draft03Math.equation(g,h,c,s);
      r=hf.formatHex(h.encode())+hf.formatHex(u.encode())+hf.formatHex(v.encode())+hf.formatHex(Draft03Math.challenge(h,gb,u,v))+hf.formatHex(Draft03Math.output(g));break;
     default:throw new IllegalArgumentException("unknown helper");
    }
   }catch(Exception e){r="INTERNAL:"+e.toString();}
   return r;
  }
 }
}
