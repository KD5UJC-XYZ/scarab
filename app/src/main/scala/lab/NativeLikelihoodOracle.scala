// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.ledger.{ConwayEpochBoundary, ConwayNativeLikelihood}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

/** Explicit pinned local native dependency for diagnostic epochs, never a network oracle. */
object NativeLikelihoodOracle:
  final case class Config(
      executable: Path,
      executableSHA256: Bytes,
      evidenceDirectory: Path,
      timeout: FiniteDuration = 5.seconds,
      maxInvocations: Int = 128,
      mode: ConwayNativeLikelihood.Mode = ConwayNativeLikelihood.Mode.CheckedJvm
  )
  trait Oracle[F[_]]:
    def generate(
        frozen: ConwayEpochBoundary.Frozen,
        expectedId: Bytes
    ): F[ConwayNativeLikelihood.Generated]
  private def sha(bytes: Array[Byte]): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(bytes))
  def resource[F[_]: Async](config: Config): Resource[F, Oracle[F]] =
    val F = Async[F]
    for
      _ <- Resource.eval(F.blocking {
        require(
          config != null && config.executable.isAbsolute &&
            config.evidenceDirectory.isAbsolute && config.executableSHA256 != null &&
            config.executableSHA256.size == 32 && config.timeout > Duration.Zero &&
            config.timeout <= 30.seconds && config.maxInvocations > 0 &&
            config.maxInvocations <= 128 && config.mode != null &&
            config.mode != ConwayNativeLikelihood.Mode.PureJvm,
          "native oracle configuration"
        )
        require(
          Files.isRegularFile(config.executable) && Files.isExecutable(config.executable),
          "native oracle executable required"
        )
        require(Files.size(config.executable) <= 268435456, "native executable size bound")
        require(
          sha(Files.readAllBytes(config.executable)) == config.executableSHA256,
          "native executable pin mismatch"
        )
        Files.createDirectory(config.evidenceDirectory)
      })
      gate <- Resource.eval(Semaphore[F](1))
      counter <- Resource.eval(cats.effect.Ref.of[F, Int](0))
      closed <- Resource.make(cats.effect.Ref.of[F, Boolean](false))(ref =>
        ref.set(true) *> gate.permit.use(_ => F.unit)
      )
    yield new Oracle[F]:
      def generate(
          frozen: ConwayEpochBoundary.Frozen,
          expectedId: Bytes
      ): F[ConwayNativeLikelihood.Generated] = gate.permit.use { _ =>
        for
          unavailable <- closed.get
          _ <- F.raiseWhen(unavailable)(new IllegalStateException("native oracle closed"))
          request <- F.fromEither(
            ConwayNativeLikelihood
              .request(frozen, expectedId)
              .leftMap(new IllegalArgumentException(_))
          )
          index <- counter.modify(n => if n >= config.maxInvocations then (n, n) else (n + 1, n))
          _ <- F.raiseWhen(index >= config.maxInvocations)(
            new IllegalStateException("native oracle invocation bound")
          )
          result <- {
            val directory = config.evidenceDirectory.resolve(f"generation-$index%04d")
            val input = directory.resolve("request.txt")
            val output = directory.resolve("response.txt")
            val errors = directory.resolve("stderr.txt")
            val acquire = F.blocking {
              // Revalidate immediately before each launch; executable directory is trusted local configuration.
              require(Files.size(config.executable) <= 268435456, "native executable size bound")
              require(
                sha(Files.readAllBytes(config.executable)) == config.executableSHA256,
                "native executable pin changed"
              )
              Files.createDirectory(directory)
              Files.write(input, request.original.value.toArray, StandardOpenOption.CREATE_NEW)
              Files.createFile(output); Files.createFile(errors)
              new ProcessBuilder(config.executable.toString, input.toString)
                .redirectOutput(output.toFile)
                .redirectError(errors.toFile)
                .start()
            }
            def stop(process: Process): F[Unit] = F.blocking {
              if process.isAlive then process.destroyForcibly()
              require(process.waitFor(2, TimeUnit.SECONDS), "native child did not terminate")
              process.getInputStream.close(); process.getErrorStream.close();
              process.getOutputStream.close()
            }
            Resource.make(acquire)(stop).use { process =>
              def await: F[Unit] = F
                .blocking {
                  require(
                    Files.size(output) <= ConwayNativeLikelihood.MaxResponseBytes &&
                      Files.size(errors) <= 8192,
                    "native output bound"
                  )
                  process.isAlive
                }
                .flatMap(alive => if alive then F.sleep(10.millis) *> await else F.unit)
              for
                _ <- F.timeout(await, config.timeout)
                response <- F.blocking {
                  require(process.exitValue() == 0, "native oracle failed")
                  require(
                    Files.size(output) <= ConwayNativeLikelihood.MaxResponseBytes &&
                      Files.size(errors) <= 8192,
                    "native output bound"
                  )
                  Bytes.fromArray(Files.readAllBytes(output))
                }
                observed <- F.fromEither(
                  ConwayNativeLikelihood
                    .acceptTrustedNative(
                      request,
                      response,
                      ConwayNativeLikelihood.Mode.AssistedNative
                    )
                    .leftMap(new IllegalArgumentException(_))
                )
                _ <- F.blocking {
                  val evidence =
                    s"native-likelihood-execution-v1\n${config.executableSHA256.hex}\n${sha(request.original.value.toArray).hex}\n${sha(response.value.toArray).hex}\n${config.mode}\n${observed.raw32Comparisons} ${observed.raw32Mismatches}\n${observed.raw64Comparisons} ${observed.raw64Mismatches}\n"
                  Files.write(
                    directory.resolve("execution.txt"),
                    evidence.getBytes("US-ASCII"),
                    StandardOpenOption.CREATE_NEW
                  )
                }
                generated <- F.fromEither(
                  ConwayNativeLikelihood
                    .acceptTrustedNative(request, response, config.mode)
                    .leftMap(new IllegalArgumentException(_))
                )
              yield generated
            }
          }
        yield result
      }
