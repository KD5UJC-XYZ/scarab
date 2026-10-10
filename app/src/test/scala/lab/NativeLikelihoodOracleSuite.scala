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
class NativeLikelihoodOracleSuite extends munit.FunSuite:
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

  private def setup(body: String): IO[NativeLikelihoodOracle.Config] = IO.blocking {
    val root =
      Files.createTempDirectory(Path.of("target").toAbsolutePath, "native-likelihood-test-")
    val executable = root.resolve("oracle")
    Files.writeString(executable, "#!/usr/bin/python3\n" + body)
    assert(executable.toFile.setExecutable(true))
    NativeLikelihoodOracle.Config(
      executable,
      Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable))),
      root.resolve("evidence"),
      500.millis,
      2
    )
  }
  test("pin mismatch refuses resource before creating evidence or launching") {
    (for
      config <- setup("raise Exception('must not run')\n")
      result <- NativeLikelihoodOracle
        .resource[IO](config.copy(executableSHA256 = bytes(7)))
        .use(_ => IO.unit)
        .attempt
      _ = assert(result.isLeft)
      _ = assert(!Files.exists(config.evidenceDirectory))
    yield ()).unsafeToFuture()
  }
  test("successful bounded child retains exact evidence and stops at invocation limit") {
    val script =
      "import sys\ns=open(sys.argv[1]).read()\np=s.splitlines()[3].split()[0]\nprint(s+'--native--\n'+p+' 0000000000000000 '+'00000000'*100)\n"
        .replace("'--native--\n'", "'--native--\\n'")
    (for
      config <- setup(script)
      f = fixture(stake = 0, blocks = 0)
      _ <- NativeLikelihoodOracle.resource[IO](config).use { oracle =>
        for
          first <- oracle.generate(f, f.id)
          _ = assertEquals(first.jvmMismatchWords, 0)
          _ <- oracle.generate(f, f.id)
          denied <- oracle.generate(f, f.id).attempt
          _ = assert(denied.isLeft)
        yield ()
      }
      _ = assert(
        Files.isRegularFile(config.evidenceDirectory.resolve("generation-0000/execution.txt"))
      )
    yield ()).unsafeToFuture()
  }
  test("timeout cancels and reaps the owned subprocess") {
    (for
      config <- setup(
        "import os,time\nopen(__file__+'.pid','w').write(str(os.getpid()))\ntime.sleep(60)\n"
      )
      f = fixture()
      result <- NativeLikelihoodOracle.resource[IO](config).use(_.generate(f, f.id)).attempt
      _ = assert(result.isLeft)
      pid <- IO.blocking(Files.readString(Path.of(config.executable.toString + ".pid")).toLong)
      _ = assert(!ProcessHandle.of(pid).isPresent || !ProcessHandle.of(pid).get().isAlive)
    yield ()).unsafeToFuture()
  }
  test("cancellation reaps child and does not publish completion receipt") {
    (for
      config <- setup(
        "import os,time\nopen(__file__+'.pid','w').write(str(os.getpid()))\ntime.sleep(60)\n"
      )
      f = fixture()
      fiber <- NativeLikelihoodOracle
        .resource[IO](config.copy(timeout = 10.seconds))
        .use(_.generate(f, f.id))
        .start
      _ <- {
        def ready: IO[Unit] = IO
          .blocking(Files.exists(Path.of(config.executable.toString + ".pid")))
          .flatMap(found => if found then IO.unit else IO.sleep(10.millis) *> ready)
        ready.timeout(3.seconds)
      }
      _ <- fiber.cancel
      pid <- IO.blocking(Files.readString(Path.of(config.executable.toString + ".pid")).toLong)
      _ = assert(!ProcessHandle.of(pid).isPresent || !ProcessHandle.of(pid).get().isAlive)
      _ = assert(!Files.exists(config.evidenceDirectory.resolve("generation-0000/execution.txt")))
    yield ()).unsafeToFuture()
  }

  test("escaped oracle refuses after resource release without creating invocation") {
    (for
      config <- setup("raise Exception('must not execute')\n")
      handle <- NativeLikelihoodOracle.resource[IO](config).use(IO.pure)
      f = fixture()
      result <- handle.generate(f, f.id).attempt
      _ = assert(result.isLeft)
      _ = assert(!Files.exists(config.evidenceDirectory.resolve("generation-0000")))
    yield ()).unsafeToFuture()
  }
  test("oversized child output refuses completion and reaps child") {
    (for
      config <- setup(
        "import os,sys,time\nopen(__file__+'.pid','w').write(str(os.getpid()))\nsys.stdout.write('x'*200000);sys.stdout.flush()\ntime.sleep(60)\n"
      )
      f = fixture()
      result <- NativeLikelihoodOracle.resource[IO](config).use(_.generate(f, f.id)).attempt
      _ = assert(result.isLeft)
      pid <- IO.blocking(Files.readString(Path.of(config.executable.toString + ".pid")).toLong)
      _ = assert(!ProcessHandle.of(pid).isPresent || !ProcessHandle.of(pid).get().isAlive)
      _ = assert(!Files.exists(config.evidenceDirectory.resolve("generation-0000/execution.txt")))
    yield ()).unsafeToFuture()
  }
  sys.env.get("NATIVE_LIKELIHOOD_EXECUTABLE").foreach { path =>
    test(
      "retained pinned native executable validates actual dynamic frozen inputs through adapter"
    ) {
      val pin = get(
        Bytes.fromHex(
          sys.env.getOrElse("NATIVE_LIKELIHOOD_SHA256", fail("native binary pin required"))
        )
      )
      (for
        root <- IO.blocking(
          Files.createTempDirectory(Path.of("target").toAbsolutePath, "native-likelihood-real-")
        )
        config = NativeLikelihoodOracle.Config(Path.of(path), pin, root.resolve("evidence"))
        _ <- NativeLikelihoodOracle.resource[IO](config).use { oracle =>
          Vector(
            (BigInt(1000), BigInt(3000), BigInt(4)),
            (BigInt(1000), BigInt(2999), BigInt(7)),
            (BigInt(0), BigInt(3000), BigInt(0))
          ).traverse_ { (stake, reserves, blocks) =>
            val f = fixture(stake = stake, reserves = reserves, blocks = blocks)
            oracle.generate(f, f.id).map { g =>
              assertEquals(g.mode, lab.ledger.ConwayNativeLikelihood.Mode.CheckedJvm)
              assertEquals(g.raw32Comparisons, 100); assertEquals(g.raw64Comparisons, 1)
              assertEquals(g.jvmMismatchWords, 0)
              assert(!g.nativeValuesAuthoritative)
              assert(g.forFrozen(f, f.id).isRight)
            }
          }
        }
      yield ()).unsafeToFuture()
    }
  }
