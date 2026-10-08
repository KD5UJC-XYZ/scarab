// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.network.{BlockFetch, CardanoBlockFetch, ChainSync}

/** Entirely offline codec and bounded batch checks, not a connection/session. */
object BlockFetchCommand:
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  def verify(directory: Path): String =
    import BlockFetch.{State, Role, Message}
    import CardanoBlockFetch.*
    val path = directory.resolve("conway-block-source-derived.hex")
    if Files.size(path) > 16000 then throw new IllegalArgumentException("fixture file too large")
    val wire = get(Bytes.fromHex(Files.readString(path).trim))
    val digest = Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(wire.toArray)).hex
    require(
      digest == "ccf88f406ce7085c3425e8cb63f94c59697c386d8920650b8f8f9b042f4b0d43",
      "fixture checksum mismatch"
    )
    val codec = payloadCodec()
    val decoded = get(BlockFetch.decode(State.Streaming, Role.Server, wire, codec))
    require(
      get(BlockFetch.encode(State.Streaming, Role.Server, decoded, codec)) == wire,
      "wire bytes changed"
    )
    val point = get(
      SpecificPoint.from(
        ChainSync.Point.Block(ChainSync.UInt64.Zero, Bytes(Vector.fill(32)(0.toByte)))
      )
    )
    val plan = get(FetchPlan.from(Vector(point)))
    val batch = Batch.begin(plan)
    val stream = get(batch.accept(Message.StartBatch)(_ => Right(point)))
    require(stream.accept(Message.BatchDone)(_ => Right(point)).isLeft, "empty batch accepted")
    // Synthetic identity observation exercises the pure correlation model only.
    val received = get(stream.accept(decoded)(_ => Right(point)))
    require(received.endOfInput.isLeft, "partial result completed")
    require(received.accept(decoded)(_ => Right(point)).isLeft, "extra block accepted")
    val complete = get(received.accept(Message.BatchDone)(_ => Right(point)))
    require(complete.endOfInput.isRight, "batch failed")
    require(
      get(batch.accept(Message.NoBlocks)(_ => Right(point))).result == BatchResult.Unavailable
    )
    s"""PASS pure BlockFetch codec and bounded single-point batch checks
       |miniProtocol=3 defaultNtNVersion=14 cardanoCodecVersion=2 fixtureWireBytes=${wire.size}
       |sourceDerivedEnvelope=true originalRawBytesPreserved=true batchCompleteOnlyAfterBatchDone=true
       |transportExchanges=0 transportSessionImplemented=false referenceRuntimeChecked=false livePeerChecked=false
       |cardanoHeaderValidated=false cardanoBlockValidated=false ledgerValidated=false blockIdentityVerified=false
       |Synthetic point observations test correlation only; no network, header identity, parent-link or ledger validation was run.""".stripMargin
  def run: IO[ExitCode] =
    IO.blocking(verify(Path.of("fixtures/network/block-fetch")))
      .flatMap(IO.println)
      .as(ExitCode.Success)
      .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
