// SPDX-License-Identifier: Apache-2.0
package lab
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{
  ConwayStake as S,
  ConwayEpochBoundary as B,
  ConwayRewardStart as R,
  ClusterTransition
}
class JvmLikelihoodGeneratorSuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def key(n: Int) = Bytes(Vector.fill(28)(n.toByte))
  private def bytes(n: Int) = Bytes(Vector.fill(32)(n.toByte))
  private def encoded(format: String, values: Vector[BigInt]) = get(
    Cbor.encode(
      V.Arr(
        Vector(Node(V.Text(format), Bytes.empty)) ++ values.map(x => Node(V.UInt(x), Bytes.empty))
      )
    )
  )
  private def fixture(
      stake: BigInt = 1000,
      reserves: BigInt = 3000,
      blocks: BigInt = 4,
      slots: BigInt = 1000,
      supply: BigInt = 6000
  ): B.Frozen =
    val bo = B.owner(); val so = S.owner()
    val credential = S.Credential(false, key(3))
    val pool =
      S.Pool(bytes(5), 0, 0, S.Ratio(0, 1), credential, Set(credential.hash), Set(credential), 0)
    val context = get(
      S.context(
        bytes(7),
        slots,
        Map(credential -> S.Account(0, 0, Some(key(1)))),
        Map(key(1) -> pool)
      )
    )
    val active =
      if stake == 0 then Map.empty[S.Credential, S.Active]
      else Map(credential -> S.Active(stake, key(1)))
    val go = get(S.fromActive(context, active))
    val env = get(
      ClusterTransition.environment(bytes(7), bytes(7), 1082026, 0, 9, 0, 44, 155381, 16384, 4310)
    )
    val ledger = get(
      ClusterTransition.checkpoint(env, get(Cbor.encode(V.Map(Vector.empty))), 50, 110, bytes(7))
    )
    val state = get(S.seed(so, context, ledger, bytes(7), Map.empty, S.Snapshots(go, go, go, 37)))
    val start = get(
      B.context(
        bo,
        so,
        bytes(8),
        state,
        B.Pots(0, reserves, 0, supply),
        Map(key(1) -> blocks),
        Map.empty
      )
    )
    val parameters = get(
      R.decodeParameters(encoded(R.ParameterFormat, Vector[BigInt](9, 0, 0, 1, 0, 1)))
    )
    val globals = get(
      R.decodeGlobals(encoded(R.GlobalFormat, Vector[BigInt](slots, 1, 20, supply)))
    )
    get(B.freezeForAllocation(bo, start, 110, 100, parameters, globals))

  private def root: IO[Path] =
    IO.blocking(Files.createTempDirectory(Path.of("target").toAbsolutePath, "jvm-likelihood-"))
  private def sha(b: Array[Byte]) =
    MessageDigest.getInstance("SHA-256").digest(b).map(x => f"${x & 255}%02x").mkString
  test("JVM recorder retains exact request and output with zero native comparisons") {
    (for
      base <- root
      f = fixture()
      g <- JvmLikelihoodGenerator.resource[IO](base.resolve("evidence")).use(_.generate(f, f.id))
      dir = base.resolve("evidence/generation-0000")
      request <- IO.blocking(Files.readAllBytes(dir.resolve("request.txt")))
      result <- IO.blocking(Files.readAllBytes(dir.resolve("jvm-result.txt")))
      receipt <- IO.blocking(Files.readString(dir.resolve("execution.txt")))
      _ = assertEquals(Bytes.fromArray(request), g.request.original)
      _ = assertEquals(Bytes.fromArray(result), g.evidence)
      _ = assertEquals(
        receipt,
        s"jvm-likelihood-execution-v1\n${sha(request)}\n${sha(result)}\nPureJvm\n100 1\n0 0\n"
      )
      _ = assert(!Files.exists(dir.resolve("response.txt")))
      _ = assert(!g.nativeValidated && !g.diagnosticNativeDependency)
    yield ()).unsafeToFuture()
  }
  test("bounded serialized recorder refuses repeated excess and escaped handle") {
    (for
      base <- root
      f = fixture()
      handle <- JvmLikelihoodGenerator.resource[IO](base.resolve("evidence"), 1).use { h =>
        h.generate(f, f.id) *> List
          .fill(5)(())
          .traverse_(_ => h.generate(f, f.id).attempt.map(x => assert(x.isLeft)))
          .as(h)
      }
      closed <- handle.generate(f, f.id).attempt
      _ = assert(closed.isLeft)
      _ = assert(!Files.exists(base.resolve("evidence/generation-0001")))
    yield ()).unsafeToFuture()
  }
  test("invalid configuration and existing evidence fail without overwrite") {
    (for
      base <- root
      invalid <- JvmLikelihoodGenerator
        .resource[IO](base.resolve("invalid"), 129)
        .use(_ => IO.unit)
        .attempt
      _ = assert(invalid.isLeft)
      existing <- JvmLikelihoodGenerator.resource[IO](base).use(_ => IO.unit).attempt
      _ = assert(existing.isLeft)
      _ = assert(!Files.exists(base.resolve("invalid")))
    yield ()).unsafeToFuture()
  }
  test("wrong frozen identity records no generation") {
    (for
      base <- root
      f = fixture()
      result <- JvmLikelihoodGenerator
        .resource[IO](base.resolve("evidence"))
        .use(_.generate(f, bytes(9)))
        .attempt
      _ = assert(result.isLeft)
      _ = assert(!Files.exists(base.resolve("evidence/generation-0000")))
    yield ()).unsafeToFuture()
  }
