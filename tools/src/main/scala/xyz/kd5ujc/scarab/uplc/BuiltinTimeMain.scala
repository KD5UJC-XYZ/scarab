// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import org.bouncycastle.crypto.ec.CustomNamedCurves
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.cost.{DefaultCostModels, ExBudget}
import xyz.kd5ujc.scarab.uplc.ledger.Unavailability
import xyz.kd5ujc.scarab.uplc.oracle.{ScriptWriter, Verdict}
import xyz.kd5ujc.scarab.uplc.text.UplcParser

/** Development tool: the wall-clock time of loops that call one builtin until a transaction's
  * budget (10^10 CPU, 14 * 10^6 memory) is spent, through the production path
  * ([[Verdict.evaluate]], as the node evaluates a script). Each loop spends the reference's budget;
  * what this measures is how long the evaluator takes for it, against the same loop around
  * `sha2_256` of 32 bytes. Not a test: the time depends on the host and its load, so it is read by
  * hand, on a quiet host, before and after a change to a builtin.
  *
  * `tools/runMain xyz.kd5ujc.scarab.uplc.BuiltinTimeMain [rounds] [shape ...]`
  *
  * Each shape prints its CPU and memory spent, the median time of `rounds` runs after two warm-up
  * runs, that time per 10^10 CPU, and its ratio to the baseline's time per 10^10 CPU. The timed
  * region reads `System.nanoTime` directly (FUN-2 (e)): `Clock[IO]` would add its allocation to
  * what is timed.
  */
object BuiltinTimeMain extends IOApp:
  final case class Shape(name: String, language: PlutusLanguage, protocol: Int, body: String)

  private def b(name: String): String = s"(builtin $name)"
  private def ap(f: String, args: String*): String = args.foldLeft(f)((acc, a) => s"[$acc $a]")
  private def bs(hex: String): String = s"(con bytestring #$hex)"
  private def int(i: BigInt): String = s"(con integer $i)"
  private def bool(v: Boolean): String = s"(con bool ${if v then "True" else "False"})"
  private def ints(is: Seq[Int]): String = s"(con (list integer) [${is.mkString(", ")}])"
  private def let(name: String, value: String)(body: String): String = s"[(lam $name $body) $value]"

  /** `op` evaluated over and over until the budget is spent. */
  private def loop(op: String): String =
    s"[(lam self [self self]) (lam self [(lam x [self self]) $op])]"

  private def dataMap(n: Int): String =
    s"(con data (Map [${Vector.fill(n)("(I 0, I 0)").mkString(", ")}]))"
  private def dataList(n: Int): String =
    s"(con data (List [${Vector.fill(n)("I 0").mkString(", ")}]))"

  /** A value of `currencies` currencies of `tokens` tokens each, as the data `unValueData` reads.
    */
  private def valueData(currencies: Int, tokens: Int): String =
    def key(i: Int) = f"B #$i%04x"
    val entries = (0 until currencies).map(c =>
      s"(${key(c)}, Map [${(0 until tokens).map(t => s"(${key(t)}, I 1)").mkString(", ")}])"
    )
    s"(con data (Map [${entries.mkString(", ")}]))"

  private val G1 =
    "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"
  private val G2 =
    "93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e" +
      "024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8"

  /** secp256k1's generator, compressed and x-only: a public point for the signature shapes. */
  private val SecpGenerator: String =
    Bytes.wrap(CustomNamedCurves.getByName("secp256k1").getG.getEncoded(true)).hex
  private val SchnorrGenerator: String = SecpGenerator.drop(2)
  private val Signature = "22" * 31 + "23" + "00" * 31 + "07"

  /** The names the BLS shapes bind: a point of each group and a Miller loop of the two. */
  private def points(body: String): String =
    let("g1", ap(b("bls12_381_G1_uncompress"), bs(G1)))(
      let("g2", ap(b("bls12_381_G2_uncompress"), bs(G2)))(
        let("ml", ap(b("bls12_381_millerLoop"), "g1", "g2"))(body)
      )
    )

  /** The byte strings the bitwise shapes bind: 8,192 and 4,096 bytes of `0x5a`, 64 KB of zeros. */
  private def strings(body: String): String =
    val zeros64k = (0 until 4).foldLeft(ap(b("replicateByte"), int(4096), int(0)))((s, _) =>
      ap(b("appendByteString"), s, s)
    )
    let("big", ap(b("replicateByte"), int(8192), int(0x5a)))(
      let("big4", ap(b("replicateByte"), int(4096), int(0x5a)))(
        let("zeros", zeros64k)(body)
      )
    )

  private def v3(name: String, op: String, protocol: Int = 11): Shape =
    Shape(name, PlutusLanguage.V3, protocol, loop(op))

  val baseline: Shape = v3("sha2_256-32", ap(b("sha2_256"), bs("00" * 32)))

  val shapes: Vector[Shape] = Vector(
    baseline,
    v3("addInteger", ap(b("addInteger"), int(1), int(2))),
    Shape(
      "equalsData-map8000-I",
      PlutusLanguage.V3,
      11,
      let("d", dataMap(8000))(loop(ap(b("equalsData"), "d", "(con data (I 9))")))
    ),
    Shape(
      "equalsData-I-map8000",
      PlutusLanguage.V3,
      11,
      let("d", dataMap(8000))(loop(ap(b("equalsData"), "(con data (I 9))", "d")))
    ),
    Shape(
      "equalsData-map8000-emptyMap",
      PlutusLanguage.V3,
      11,
      let("d", dataMap(8000))(loop(ap(b("equalsData"), "d", "(con data (Map []))")))
    ),
    Shape(
      "equalsData-map1000-I",
      PlutusLanguage.V3,
      11,
      let("d", dataMap(1000))(loop(ap(b("equalsData"), "d", "(con data (I 9))")))
    ),
    Shape(
      "equalsData-list8000-I",
      PlutusLanguage.V3,
      11,
      let("d", dataList(8000))(loop(ap(b("equalsData"), "d", "(con data (I 9))")))
    ),
    Shape(
      "writeBits-4096-empty",
      PlutusLanguage.V3,
      11,
      strings(loop(ap(b("writeBits"), "big4", ints(Nil), bool(true))))
    ),
    Shape(
      "writeBits-4096-one",
      PlutusLanguage.V3,
      11,
      strings(loop(ap(b("writeBits"), "big4", ints(Vector(0)), bool(true))))
    ),
    Shape(
      "andByteString-8192",
      PlutusLanguage.V3,
      11,
      strings(loop(ap(b("andByteString"), bool(true), "big", "big")))
    ),
    Shape(
      "orByteString-8192",
      PlutusLanguage.V3,
      11,
      strings(loop(ap(b("orByteString"), bool(false), "big", "big")))
    ),
    Shape(
      "xorByteString-8192",
      PlutusLanguage.V3,
      11,
      strings(loop(ap(b("xorByteString"), bool(true), "big", "big")))
    ),
    Shape(
      "complementByteString-8192",
      PlutusLanguage.V3,
      11,
      strings(loop(ap(b("complementByteString"), "big")))
    ),
    Shape(
      "complementByteString-64k",
      PlutusLanguage.V3,
      12,
      strings(loop(ap(b("complementByteString"), "zeros")))
    ),
    v3("replicateByte-8192", ap(b("replicateByte"), int(8192), int(1))),
    Shape(
      "decodeUtf8-64k",
      PlutusLanguage.V3,
      12,
      strings(loop(ap(b("decodeUtf8"), "zeros")))
    ),
    Shape(
      "encodeUtf8-64k",
      PlutusLanguage.V3,
      12,
      strings(let("t", ap(b("decodeUtf8"), "zeros"))(loop(ap(b("encodeUtf8"), "t"))))
    ),
    Shape(
      "insertCoin-1x1",
      PlutusLanguage.V3,
      12,
      let("v", ap(b("unValueData"), valueData(1, 1)))(
        loop(ap(b("insertCoin"), bs("0000"), bs("0000"), int(5), "v"))
      )
    ),
    Shape(
      "insertCoin-1x3000",
      PlutusLanguage.V3,
      12,
      let("v", ap(b("unValueData"), valueData(1, 3000)))(
        loop(ap(b("insertCoin"), bs("0000"), bs("0000"), int(5), "v"))
      )
    ),
    Shape(
      "insertCoin-1400x1",
      PlutusLanguage.V3,
      12,
      let("v", ap(b("unValueData"), valueData(1400, 1)))(
        loop(ap(b("insertCoin"), bs("0000"), bs("0000"), int(5), "v"))
      )
    ),
    Shape("G1_add", PlutusLanguage.V3, 11, points(loop(ap(b("bls12_381_G1_add"), "g1", "g1")))),
    Shape("G1_neg", PlutusLanguage.V3, 11, points(loop(ap(b("bls12_381_G1_neg"), "g1")))),
    Shape("G1_equal", PlutusLanguage.V3, 11, points(loop(ap(b("bls12_381_G1_equal"), "g1", "g1")))),
    Shape("G1_compress", PlutusLanguage.V3, 11, points(loop(ap(b("bls12_381_G1_compress"), "g1")))),
    v3("G1_uncompress", ap(b("bls12_381_G1_uncompress"), bs(G1))),
    Shape(
      "G1_scalarMul-3",
      PlutusLanguage.V3,
      11,
      points(loop(ap(b("bls12_381_G1_scalarMul"), int(3), "g1")))
    ),
    v3("G1_hashToGroup", ap(b("bls12_381_G1_hashToGroup"), bs("00"), bs("00"))),
    Shape("G2_add", PlutusLanguage.V3, 11, points(loop(ap(b("bls12_381_G2_add"), "g2", "g2")))),
    Shape("G2_neg", PlutusLanguage.V3, 11, points(loop(ap(b("bls12_381_G2_neg"), "g2")))),
    Shape("G2_equal", PlutusLanguage.V3, 11, points(loop(ap(b("bls12_381_G2_equal"), "g2", "g2")))),
    Shape("G2_compress", PlutusLanguage.V3, 11, points(loop(ap(b("bls12_381_G2_compress"), "g2")))),
    v3("G2_uncompress", ap(b("bls12_381_G2_uncompress"), bs(G2))),
    Shape(
      "G2_scalarMul-3",
      PlutusLanguage.V3,
      11,
      points(loop(ap(b("bls12_381_G2_scalarMul"), int(3), "g2")))
    ),
    v3("G2_hashToGroup", ap(b("bls12_381_G2_hashToGroup"), bs("00"), bs("00"))),
    Shape(
      "millerLoop",
      PlutusLanguage.V3,
      11,
      points(loop(ap(b("bls12_381_millerLoop"), "g1", "g2")))
    ),
    Shape(
      "mulMlResult",
      PlutusLanguage.V3,
      11,
      points(loop(ap(b("bls12_381_mulMlResult"), "ml", "ml")))
    ),
    v3(
      "verifyEcdsaSecp256k1Signature",
      ap(b("verifyEcdsaSecp256k1Signature"), bs(SecpGenerator), bs("11" * 32), bs(Signature))
    ),
    v3(
      "verifySchnorrSecp256k1Signature",
      ap(b("verifySchnorrSecp256k1Signature"), bs(SchnorrGenerator), bs(""), bs(Signature))
    ),
    v3(
      "verifyEd25519Signature",
      ap(b("verifyEd25519Signature"), bs("11" * 32), bs(""), bs("22" * 64))
    )
  )

  final case class Measured(shape: Shape, verdict: String, cpu: Long, memory: Long, nanos: Long):
    /** The time per 10^10 CPU. */
    def perBudget: Double = if cpu <= 0 then 0.0 else nanos.toDouble * 1e10 / cpu.toDouble / 1e6

  private val budget = ExBudget.of(10_000_000_000L, 14_000_000L)

  /** The median of `rounds` timed runs of `shape`, after two warm-up runs; `rounds` is positive. */
  def measure(shape: Shape, rounds: Int): IO[Measured] = IO.blocking {
    val protocol = ProtocolMajor(shape.protocol)
    val variant = SemanticsVariant.of(shape.language, protocol)
    val profile =
      Verdict.context(shape.language, protocol, DefaultCostModels.list(shape.language, variant))
    val program = UplcParser
      .program(s"(program 1.0.0 ${shape.body})")
      .fold(e => sys.error(s"${shape.name}: $e"), identity)
    val script = ScriptWriter.script(program)
    def once(): (Long, Either[Unavailability, Verdict]) =
      val start = System.nanoTime()
      val verdict = Verdict.evaluate(profile, script, Vector.empty, budget)
      (System.nanoTime() - start, verdict)
    (1 to 2).foreach(_ => once())
    val runs = Vector.fill(rounds)(once())
    val nanos = runs.map(_._1).sorted.apply(rounds / 2)
    runs.headOption.map(_._2) match
      case Some(Right(v @ Verdict.Ran(_, spent, _))) =>
        Measured(shape, v.stage, spent.cpu.toLong, spent.memory.toLong, nanos)
      case other => Measured(shape, other.toString.take(120), 0, 0, nanos)
  }

  private def line(m: Measured, base: Measured): String =
    val ratio = if base.perBudget == 0 then 0.0 else m.perBudget / base.perBudget
    f"METRIC ${m.shape.name}%-34s ${m.verdict}%-8s cpu=${m.cpu}%d mem=${m.memory}%d " +
      f"ms=${m.nanos / 1e6}%.1f ms_per_1e10_cpu=${m.perBudget}%.1f ratio_to_sha2=${ratio}%.1f"

  def run(args: List[String]): IO[ExitCode] =
    args.headOption.fold(Option(5))(_.toIntOption).filter(_ > 0) match
      case None =>
        IO.println("usage: BuiltinTimeMain [rounds, a positive integer] [shape ...]")
          .as(ExitCode(2))
      case Some(rounds) =>
        val wanted = args.drop(1).toSet
        for
          base <- measure(baseline, rounds)
          _ <- IO.println(line(base, base))
          _ <- shapes
            .filter(s => s != baseline && (wanted.isEmpty || wanted.contains(s.name)))
            .traverse_(shape => measure(shape, rounds).flatMap(m => IO.println(line(m, base))))
        yield ExitCode.Success
