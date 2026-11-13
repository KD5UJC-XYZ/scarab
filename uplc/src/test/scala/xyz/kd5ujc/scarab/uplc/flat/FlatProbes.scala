// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.flat.FlatWriter.*

/** The probes of `fixtures/reference/plutus-flat-decode`, written again from their description in
  * that corpus's README with [[FlatWriter]]: the corpus records only the SHA-256 of each script,
  * and the program that wrote them is in the `plutus` tests, which this module cannot see. Each
  * script is checked against its recorded hash before its verdict is compared, so a probe written
  * differently fails rather than being judged against another script's verdict.
  */
object FlatProbes:
  /** A term's bits, given the position it starts at (byte strings need it for their filler). */
  type Tm = Int => String

  final case class Probe(id: String, script: Bytes)

  private def ap(f: Tm, a: Tm): Tm = at =>
    val fb = f(at + 4)
    bits(3, 4) + fb + a(at + 4 + fb.length)
  private def lam(body: Tm): Tm = at => bits(2, 4) + body(at + 4)
  private def delay(body: Tm): Tm = at => bits(1, 4) + body(at + 4)
  private def builtin(tag: Int): Tm = _ => bits(7, 4) + bits(tag, 7)
  private def constant(tags: Seq[Int], value: Int => String): Tm = at =>
    val header = bits(4, 4) + tagList(tags)
    header + value(at + header.length)
  private val unit: Tm = constant(Seq(3), _ => "")
  private def data(cbor: Array[Byte]): Tm = constant(Seq(8), at => byteString(at, cbor))
  private def string(raw: Array[Byte]): Tm = constant(Seq(2), at => byteString(at, raw))
  private def ignore(t: Tm): Tm = ap(lam(unit), t)
  private def varGroups(groupBytes: Seq[Int]): Tm = _ => bits(0, 4) + groups(groupBytes*)

  private def unpadded(version: (BigInt, BigInt, BigInt), t: Tm): String =
    val v = natural(version._1) + natural(version._2) + natural(version._3)
    v + t(v.length)

  private val v100 = (BigInt(1), BigInt(0), BigInt(0))
  private def flatOf(version: (BigInt, BigInt, BigInt), t: Tm): Array[Byte] = padded(
    unpadded(version, t)
  )
  private def scriptOf(version: (BigInt, BigInt, BigInt), t: Tm): Bytes =
    Bytes.wrap(cborBytes(flatOf(version, t)))
  private def probe(id: String, t: Tm): Probe = Probe(id, scriptOf(v100, t))

  private def nestedList(levels: Int): Array[Byte] =
    Array.fill[Byte](levels)(0x81.toByte) :+ 0.toByte
  private def hex(s: String): Array[Byte] =
    Bytes.fromHex(s).fold(e => throw IllegalArgumentException(e.toString), _.toArray)

  private val serialiseData = 51
  private val equalsData = 47

  lazy val probes: Vector[Probe] =
    val depths = Vector(1001, 1003, 16000).flatMap { levels =>
      val deep = data(nestedList(levels))
      Vector(
        probe(s"data-depth-$levels", ignore(deep)),
        probe(s"data-depth-$levels-result", deep),
        probe(s"data-depth-$levels-serialised", ignore(ap(builtin(serialiseData), deep)))
      )
    }
    val misc = Vector(
      probe(
        "data-depth-1003-equal",
        ignore(ap(ap(builtin(equalsData), data(nestedList(1003))), data(nestedList(1003))))
      ),
      probe("data-trailing-zero", ignore(data(hex("0000")))),
      probe("data-trailing-bytes", ignore(data(hex("00ff1c")))),
      probe("data-trailing-truncated-item", ignore(data(hex("0058")))),
      probe("data-truncated", ignore(data(hex("8200")))),
      probe("string-utf8", ignore(string("ok".getBytes("UTF-8")))),
      probe("string-not-utf8", ignore(string(hex("fffe")))),
      probe("string-surrogate", ignore(string(hex("eda080")))),
      probe("builtin-tag-92", ignore(builtin(92))),
      probe("builtin-tag-127", ignore(builtin(127))),
      Probe(
        "version-major-wide",
        scriptOf((BigInt(1) + (BigInt(1) << 32), BigInt(0), BigInt(0)), unit)
      ),
      Probe(
        "version-minor-wide",
        scriptOf((BigInt(1), BigInt(1) + (BigInt(1) << 32), BigInt(0)), unit)
      )
    )
    val words = Vector(
      "var-10-groups-padded" -> (0x81 +: Vector.fill(8)(0x80) :+ 0x00),
      "var-11-groups-padded" -> (0x81 +: Vector.fill(9)(0x80) :+ 0x00),
      "var-10-groups-bit63" -> (Vector.fill(9)(0x80) :+ 0x01),
      "var-10-groups-bit64" -> (Vector.fill(9)(0x80) :+ 0x02)
    ).map((id, gs) => probe(id, delay(varGroups(gs))))
    val tags = Vector(
      "data-tag2-short-head" -> "c24101",
      "data-tag2-wide-head" -> "d8024101",
      "data-tag3-short-head" -> "c34101",
      "data-tag3-wide-head" -> "d8034101",
      "data-tag102-wide64-head" -> "db0000000000000066820080",
      "data-tag102-standard" -> "d866820080",
      "data-tag121-wide4-head" -> "da0000007980"
    ).map((id, cbor) => probe(id, ignore(data(hex(cbor)))))
    val inner = flatOf(v100, ignore(unit))
    val definite = cborBytes(inner)
    val outer = Vector(
      Probe("outer-definite-bytes", Bytes.wrap(definite)),
      Probe(
        "outer-definite-wide-length",
        Bytes.wrap(
          Array(0x5b.toByte) ++ Array.fill[Byte](7)(0) ++ Array(inner.length.toByte) ++ inner
        )
      ),
      Probe(
        "outer-indefinite-bytes",
        Bytes.wrap(Array(0x5f.toByte) ++ definite ++ Array(0xff.toByte))
      )
    )
    val body = unpadded(v100, unit)
    def zeros(b: String): String = b + "0" * ((8 - b.length % 8) % 8)
    def ending(id: String, flat: Array[Byte]): Probe = Probe(id, Bytes.wrap(cborBytes(flat)))
    val minimal = padded(body)
    val fillers = Vector(
      ending("filler-minimal", minimal),
      ending("filler-trailing-zero-byte", minimal :+ 0.toByte),
      ending("filler-trailing-ff-byte", minimal :+ 0xff.toByte),
      ending("filler-longer", packed(zeros(body) + bits(1, 8))),
      ending("filler-without-one", packed(zeros(body))),
      ending("filler-misaligned", packed(zeros(body + "1"))),
      Probe("cbor-remainder", Bytes.wrap(cborBytes(minimal) :+ 0.toByte)),
      Probe(
        "cbor-remainder-and-flat-trailing",
        Bytes.wrap(cborBytes(minimal :+ 0.toByte) :+ 0.toByte)
      )
    )
    depths ++ misc ++ words ++ tags ++ outer ++ fillers
