// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import scalus.cardano.ledger.CardanoInfo
import scalus.uplc.Program
import supranational.blst.{P1, P2, PT}
import xyz.kd5ujc.scarab.blst.{Blst, BlstProblem, BlstReport, CpuSupport, LibraryBuild}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.bls.*
import xyz.kd5ujc.scarab.plutus.{
  EvalRequest,
  EvalResult,
  ExUnits,
  PlutusData,
  NativeCrypto,
  NativeCryptoProblem,
  NativeCryptoReport,
  PlutusLanguage,
  ScalusEvaluator
}
import cats.mtl.Handle

/** `BlstCheckMain [bench]`: the start-up checks of the BLS12-381 library of Leios certificates (ADR
  * 0012) and of libscalus_crypto, the native library of the Plutus builtins (ADR 0020), the Plutus
  * BLS12-381 builtins through the node's evaluator, and with `bench` the rates of G1 and G2
  * uncompression, the pairing and the verification of a Leios certificate. One JSON object on
  * stdout; exit 0 when both libraries are usable and the builtins evaluate. Run it on a processor
  * without ADX/BMI2 to see both libraries work there.
  */
object BlstCheckMain extends IOApp:
  private val g1Hex =
    "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"
  private val g2Hex =
    "93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8"

  def run(args: List[String]): IO[ExitCode] =
    def refused(message: String): IO[ExitCode] =
      IO.println(Json.obj("ok" -> false.asJson, "refused" -> message.asJson).noSpaces)
        .as(ExitCode(1))
    Handle
      .allow[BlstProblem](
        Blst
          .verify[IO]
          .flatMap(report =>
            Handle
              .allow[NativeCryptoProblem](
                NativeCrypto
                  .verify[IO]
                  .flatMap(crypto => rest(report, crypto, args.contains("bench")))
              )
              .rescue(problem => refused(problem.message))
          )
      )
      .rescue(problem => refused(problem.message))

  private def rest(report: BlstReport, crypto: NativeCryptoReport, bench: Boolean): IO[ExitCode] =
    for
      builtins <- IO.blocking(evaluateBuiltins)
      rates <- if bench then IO.blocking(benchmark).map(Some(_)) else IO.pure(None)
      ok = builtins.forall(_._2)
      _ <- IO.println(
        Json
          .obj(
            "ok" -> ok.asJson,
            "platform" -> report.platform.toString.asJson,
            "cpu" -> (report.cpu match
              case CpuSupport.Known(features) => features.toList.map(_.flag).sorted.asJson
              case CpuSupport.Unknown         => Json.Null),
            "library" -> (report.build match
              case LibraryBuild.Portable      => "portable".asJson
              case LibraryBuild.Other(sha256) => s"other $sha256".asJson
              case LibraryBuild.OnLibraryPath => "library-path".asJson),
            "plutusCrypto" -> Json.obj(
              "platform" -> crypto.platform.directory.asJson,
              "sha256" -> crypto.library.digest.asJson
            ),
            "builtins" -> builtins.toMap.asJson,
            "opsPerSecond" -> rates.map(_.toMap).asJson
          )
          .noSpaces
      )
    yield if ok then ExitCode.Success else ExitCode(1)

  private def program(body: String): Bytes =
    val parsed = Program.parseUplc(s"(program 1.0.0 (lam ctx $body))")
    Bytes.fromArray(parsed.fold(sys.error, identity).cborEncoded)

  private def assertion(cond: String): String =
    s"(force [ (force (builtin ifThenElse)) $cond (delay (con unit ())) (delay (error)) ])"

  /** Scripts with the BLS12-381 builtins of Plutus V3, run by the node's evaluator. */
  private def evaluateBuiltins: List[(String, Boolean)] =
    val g1 = s"[ (builtin bls12_381_G1_uncompress) (con bytestring #$g1Hex) ]"
    val g2 = s"[ (builtin bls12_381_G2_uncompress) (con bytestring #$g2Hex) ]"
    def mul1(k: Int) = s"[ (builtin bls12_381_G1_scalarMul) (con integer $k) g1 ]"
    def mul2(k: Int) = s"[ (builtin bls12_381_G2_scalarMul) (con integer $k) g2 ]"
    val scripts = List(
      "G1 add, scalarMul and equal" ->
        s"[ (lam g1 ${assertion(
            s"[ (builtin bls12_381_G1_equal) [ (builtin bls12_381_G1_add) g1 g1 ] ${mul1(2)} ]"
          )}) $g1 ]",
      "G2 add, scalarMul and equal" ->
        s"[ (lam g2 ${assertion(
            s"[ (builtin bls12_381_G2_equal) [ (builtin bls12_381_G2_add) g2 g2 ] ${mul2(2)} ]"
          )}) $g2 ]",
      "pairing e(3 G1, 5 G2) = e(15 G1, G2)" ->
        s"[ (lam g1 [ (lam g2 ${assertion(
            "[ (builtin bls12_381_finalVerify) " +
              s"[ (builtin bls12_381_millerLoop) ${mul1(3)} ${mul2(5)} ] " +
              s"[ (builtin bls12_381_millerLoop) ${mul1(15)} g2 ] ]"
          )}) $g2 ]) $g1 ]"
    )
    val model = CardanoInfo.mainnet.protocolParams.costModels.models(2).toVector
    val evaluator = ScalusEvaluator.pure
    scripts.map { (name, body) =>
      val result = evaluator.evaluate(
        EvalRequest(
          PlutusLanguage.V3,
          9,
          program(body),
          Vector(PlutusData.unit),
          model,
          ExUnits(10000000, 10000000000L)
        )
      )
      name -> (result match
        case _: EvalResult.Success => true
        case _                     => false)
    }

  private def hex(s: String): Array[Byte] =
    s.grouped(2).map(java.lang.Integer.parseInt(_, 16).toByte).toArray

  /** Operations per second of each, over about two seconds after a one second warm-up. */
  private def benchmark: List[(String, Long)] =
    def rate(name: String)(op: () => Unit): (String, Long) =
      def loop(budgetNanos: Long): Long =
        val start = System.nanoTime()
        var n = 0L
        while System.nanoTime() - start < budgetNanos do
          op()
          n += 1
        (n * 1000000000L) / (System.nanoTime() - start)
      loop(1000000000L)
      name -> loop(2000000000L)
    val g1 = hex(g1Hex)
    val g2 = hex(g2Hex)
    val p1 = new P1(g1).to_affine()
    val p2 = new P2(g2).to_affine()
    val seats = 24
    def key(n: Int) =
      val seed = Seed.fromBytes(Bytes.wrap(Array.tabulate[Byte](32)(i => (n + i * 7).toByte)))
      for
        s <- seed
        sk <- SecretKey.generate(s)
        vk <- VerificationKey.derive(sk)
        pop <- MinSig.provePossession(sk)
      yield (sk, vk, pop)
    val committee = (0 until seats).toVector.traverse(i => key(100 + i))
    val message = RbHash.fromBytes(Bytes.wrap(Array.tabulate[Byte](32)(_.toByte)))
    val certificate = for
      keys <- committee
      hash <- message.toRight(BlsError.InvalidScalar)
      seatsWeighted <- keys.traverse { case (_, vk, pop) =>
        Weight
          .fromNatural(BigInt(10))
          .toRight(BlsError.InvalidScalar)
          .map(w => (w, Some((vk, pop))))
      }
      members <- Committee.fromSeats(seatsWeighted)
      votes <- keys.zipWithIndex.traverse { case ((sk, _, _), i) =>
        for
          seat <- SeatId.fromInt(i)
          sig <- MinSig.sign(sk, hash.bytes)
        yield seat -> sig
      }
      cert <- Certificate.aggregate(members, votes.toMap)
    yield (members, hash, cert)
    List(
      rate("G1 uncompress")(() => new P1(g1).to_affine()),
      rate("G2 uncompress")(() => new P2(g2).to_affine()),
      rate("pairing")(() => PT.finalverify(new PT(p2, p1), new PT(p2, p1)))
    ) ++ certificate.toOption.toList.map { (members, hash, cert) =>
      rate(s"Leios certificate of $seats seats")(() =>
        Certificate
          .verify(members, Weight.zero, hash, cert)
          .fold(e => sys.error(e.toString), _ => ())
      )
    }
