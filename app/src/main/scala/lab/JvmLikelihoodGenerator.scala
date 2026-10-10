// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Resource, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.ledger.{ConwayEpochBoundary, ConwayLikelihoodGeneration, ConwayNativeLikelihood}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest

/** JVM-only calculation and original evidence recorder. Never launches a native executable. */
object JvmLikelihoodGenerator:
  private def sha(bytes: Bytes): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes.value.toArray)
    .map(b => f"${b & 255}%02x")
    .mkString
  def resource[F[_]: Async](
      evidenceDirectory: Path,
      maxInvocations: Int = 128
  ): Resource[F, NativeLikelihoodOracle.Oracle[F]] =
    val F = Async[F]
    for
      _ <- Resource.eval(F.blocking {
        require(
          evidenceDirectory != null && evidenceDirectory.isAbsolute &&
            maxInvocations > 0 && maxInvocations <= 128,
          "JVM generation configuration"
        )
        Files.createDirectory(evidenceDirectory)
      })
      gate <- Resource.eval(Semaphore[F](1))
      counter <- Resource.eval(Ref.of[F, Int](0))
      closed <- Resource.make(Ref.of[F, Boolean](false))(ref =>
        ref.set(true) *> gate.permit.use(_ => F.unit)
      )
    yield new NativeLikelihoodOracle.Oracle[F]:
      def generate(
          frozen: ConwayEpochBoundary.Frozen,
          expectedId: Bytes
      ): F[ConwayNativeLikelihood.Generated] = gate.permit.use { _ =>
        for
          unavailable <- closed.get
          _ <- F.raiseWhen(unavailable)(new IllegalStateException("JVM generator closed"))
          index <- counter.modify(n => if n >= maxInvocations then (n, n) else (n + 1, n))
          _ <- F.raiseWhen(index >= maxInvocations)(
            new IllegalStateException("JVM generation invocation bound")
          )
          generated <- F.fromEither(
            ConwayLikelihoodGeneration
              .generateJvm(frozen, expectedId)
              .leftMap(new IllegalArgumentException(_))
          )
          _ <- F.blocking {
            val directory = evidenceDirectory.resolve(f"generation-$index%04d")
            Files.createDirectory(directory)
            Files.write(
              directory.resolve("request.txt"),
              generated.request.original.value.toArray,
              StandardOpenOption.CREATE_NEW
            )
            Files.write(
              directory.resolve("jvm-result.txt"),
              generated.evidence.value.toArray,
              StandardOpenOption.CREATE_NEW
            )
            val receipt =
              s"jvm-likelihood-execution-v1\n${sha(generated.request.original)}\n${sha(generated.evidence)}\nPureJvm\n${generated.computedRaw32Words} ${generated.computedRaw64Words}\n0 0\n"
            Files.write(
              directory.resolve("execution.txt"),
              receipt.getBytes("US-ASCII"),
              StandardOpenOption.CREATE_NEW
            )
          }
        yield generated
      }
