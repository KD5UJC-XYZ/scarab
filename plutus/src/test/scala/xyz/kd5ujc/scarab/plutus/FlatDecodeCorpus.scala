// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes

import FlatProgram.*

/** Original-byte probes of the flat decoder's reach, run through the pinned reference API
  * (`scripts/oracle/plutus`) and recorded in `fixtures/reference/plutus-flat-decode`: `data`
  * constants deeper than 1000 levels and up to the size of a transaction, bytes after a `data`
  * constant's CBOR item, `string` constants that are not UTF-8, builtin tags without a builtin and
  * version components beyond 32 bits.
  */
object FlatDecodeCorpus:
  final case class Probe(id: String, script: Bytes, protocols: Vector[Int] = Vector(9, 10, 11))

  val Directory = "fixtures/reference/plutus-flat-decode"
  val budget: ExUnits = ExUnits(BigInt(14000000), BigInt(10000000000L))

  private val v100 = (1, 0, 0)

  /** `[[...[0]...]]`: `levels` definite lists, each holding the next, around the integer 0. */
  def nestedList(levels: Int): Array[Byte] =
    Array.fill[Byte](levels)(0x81.toByte) :+ 0x00.toByte

  /** The fewest levels Scalus 1.3.0's `Data` decoder refuses (borer's 1000 nesting levels, counted
    * from the outermost list after its first two).
    */
  val BeyondBorer = 1003

  /** Mainnet's `maxTxSize` is 16,384 bytes; a script of this depth is a little smaller. */
  val NearSizeBound = 16000

  private def ignore(term: Tm): Tm = Ap(Lam(unit), term)
  private val serialiseData = 51
  private val equalsData = 47
  private def probe(id: String, term: Tm, version: (Int, Int, Int) = v100): Probe =
    Probe(id, script(version, term))

  val probes: Vector[Probe] =
    Vector(1001, BeyondBorer, NearSizeBound).flatMap { levels =>
      val deep = data(nestedList(levels))
      Vector(
        probe(s"data-depth-$levels", ignore(deep)),
        probe(s"data-depth-$levels-result", deep),
        probe(s"data-depth-$levels-serialised", ignore(Ap(Builtin(serialiseData), deep)))
      )
    } ++ Vector(
      probe(
        s"data-depth-$BeyondBorer-equal",
        ignore(
          Ap(Ap(Builtin(equalsData), data(nestedList(BeyondBorer))), data(nestedList(BeyondBorer)))
        )
      )
    ) ++ Vector(
      probe("data-trailing-zero", ignore(data(Array[Byte](0x00, 0x00)))),
      probe("data-trailing-bytes", ignore(data(Array[Byte](0x00, 0xff.toByte, 0x1c)))),
      probe("data-trailing-truncated-item", ignore(data(Array[Byte](0x00, 0x58)))),
      probe("data-truncated", ignore(data(Array[Byte](0x82.toByte, 0x00)))),
      probe("string-utf8", ignore(string("ok".getBytes("UTF-8")))),
      probe("string-not-utf8", ignore(string(Array[Byte](0xff.toByte, 0xfe.toByte)))),
      probe("string-surrogate", ignore(string(Array[Byte](0xed.toByte, 0xa0.toByte, 0x80.toByte)))),
      probe("builtin-tag-92", ignore(Builtin(92))),
      probe("builtin-tag-127", ignore(Builtin(127))),
      Probe(
        "version-major-wide",
        Bytes.fromArray(
          cborByteString(flatOf((BigInt(1) + (BigInt(1) << 32), BigInt(0), BigInt(0)), unit))
        )
      ),
      Probe(
        "version-minor-wide",
        Bytes.fromArray(
          cborByteString(flatOf((BigInt(1), BigInt(1) + (BigInt(1) << 32), BigInt(0)), unit))
        )
      )
    ) ++ wordProbes ++ tagProbes ++ outerProbes

  /** Padded and extreme `Word64` indices: `dWord64` reads at most ten groups of seven bits and the
    * tenth holds bit 63 alone.
    */
  private lazy val wordProbes: Vector[Probe] =
    def index(id: String, groups: Vector[Int]): Probe = probe(id, Delay(VarGroups(groups)))
    Vector(
      index("var-10-groups-padded", 0x81 +: Vector.fill(8)(0x80) :+ 0x00),
      index("var-11-groups-padded", 0x81 +: Vector.fill(9)(0x80) :+ 0x00),
      index("var-10-groups-bit63", Vector.fill(9)(0x80) :+ 0x01),
      index("var-10-groups-bit64", Vector.fill(9)(0x80) :+ 0x02)
    )

  /** `data` constants with a tag in a head wider than the shortest: big numbers (tags 2 and 3) need
    * `0xc2` and `0xc3` exactly, the constructor tags do not.
    */
  private lazy val tagProbes: Vector[Probe] =
    def tagged(id: String, hex: String): Probe =
      probe(id, ignore(data(Bytes.fromHex(hex).fold(e => sys.error(e.toString), _.toArray))))
    Vector(
      tagged("data-tag2-short-head", "c24101"),
      tagged("data-tag2-wide-head", "d8024101"),
      tagged("data-tag3-short-head", "c34101"),
      tagged("data-tag3-wide-head", "d8034101"),
      tagged("data-tag102-wide64-head", "db0000000000000066820080"),
      tagged("data-tag102-standard", "d866820080"),
      tagged("data-tag121-wide4-head", "da0000007980")
    )

  /** The outer byte string of the script: `deserialiseScript` asks cborg for definite bytes. */
  private lazy val outerProbes: Vector[Probe] =
    val inner = flat(v100, ignore(unit))
    val definite = cborByteString(inner)
    Vector(
      Probe("outer-definite-bytes", Bytes.fromArray(definite)),
      Probe(
        "outer-definite-wide-length",
        Bytes.fromArray(
          Array(0x5b.toByte) ++ Array.fill[Byte](7)(0) ++ Array(inner.length.toByte) ++ inner
        )
      ),
      Probe(
        "outer-indefinite-bytes",
        Bytes.fromArray(Array(0x5f.toByte) ++ definite ++ Array(0xff.toByte))
      )
    )

  def costModel(language: PlutusLanguage, protocol: Int): Vector[Long] =
    PlutusBoundaryCorpus.costModel(language, protocol)

  /** The language/protocol pairs the probe runs at: every language the protocol has. */
  def cells(probe: Probe): Vector[(PlutusLanguage, Int)] =
    for
      protocol <- probe.protocols
      language <- PlutusLanguage.values.toVector
      if language != PlutusLanguage.V3 || protocol >= 9
      if language != PlutusLanguage.V2 || protocol >= 7
    yield (language, protocol)

  /** Writes the oracle's requests (`scripts/oracle/plutus/Main.hs` reads them). */
  def main(args: Array[String]): Unit =
    val out = Path.of(args.headOption.getOrElse("/oracle/flat-decode-requests.jsonl"))
    val rows = for
      probe <- probes
      (language, protocol) <- cells(probe)
    yield Json.obj(
      "id" -> Json.fromString(probe.id),
      "language" -> Json.fromInt(language.id + 1),
      "protocol" -> Json.fromInt(protocol),
      "script" -> Json.fromString(probe.script.hex),
      "costModel" -> Json.arr(costModel(language, protocol).map(Json.fromLong)*)
    )
    Files.writeString(out, rows.map(_.noSpaces).mkString("\n") + "\n")
