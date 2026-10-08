// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.network.{ChainSyncFixtures, ConnectionSession, FixturePeer}

/** Separate from the pure v0.10 self-test: finite scripted byte transports, never endpoints. */
object ChainSyncSessionCommand:
  private def checked[A](value: Either[String, A]): IO[A] =
    IO.fromEither(value.leftMap(new IllegalArgumentException(_)))
  private def load(directory: Path, name: String): IO[Bytes] = IO.blocking {
    val path = directory.resolve(name)
    // Bound the actual read as well as checking the size; do not rely on a size/read race.
    val in = Files.newInputStream(path)
    try
      val bytes = in.readNBytes(65536)
      if bytes.length > 65535 then throw new IllegalArgumentException("fixture byte limit exceeded")
      Bytes.fromArray(bytes)
    finally in.close()
  }
  def verify(directory: Path): IO[String] =
    for
      files <- ChainSyncCommand.Payloads
        .traverse { case (name, hash, ntn) =>
          for
            bytes <- load(directory, name)
            _ <- checked(ChainSyncCommand.checkPayload(name, bytes, hash, ntn))
          yield name -> bytes
        }
        .map(_.toMap)
      header <- checked(ChainSyncFixtures.ntnHeader.decode(files("ntn-header-conway.cbor")))
      block <- checked(ChainSyncFixtures.ntcBlock.decode(files("ntc-block-conway.cbor")))
      ntn <- FixturePeer.run(ConnectionSession.NtN14, header)
      ntc <- FixturePeer.run(ConnectionSession.NtC16, block)
      ntnBoundary <- FixturePeer.responderBoundary(ConnectionSession.NtN14)
      ntcBoundary <- FixturePeer.responderBoundary(ConnectionSession.NtC16)
      reports = Vector(ntn, ntc)
      counts = reports.map(_.counts) ++ Vector(ntnBoundary, ntcBoundary)
    yield
      val profiles = reports
        .map(r =>
          s"${r.profile}:transitions=${r.transitions},finalState=${r.finalState},doneObserved=${r.doneObserved}"
        )
        .mkString(";")
      s"""PASS finite in-memory ChainSync fixture sessions
         |networkPin=c45735a56c567fa977969173d18943bac6bb3821
         |consensusPin=82ecba329d7d054340bf707d44fe6e9ac27cec40
         |fixtureProfiles=$profiles
         |payloadFixtures=4 typedSerializationExamples=2 placeholderEnvelopeGoldens=2
         |clientForkScenarios=2 responderBoundaryScenarios=2 handshakeApplicationCoalescingChecked=true
         |transportReads=${counts
          .map(_.reads)
          .sum} transportWrites=${counts.map(_.writes).sum} transportReadBytes=${counts
          .map(_.readBytes)
          .sum} transportWrittenBytes=${counts.map(_.writtenBytes).sum}
         |wirePeer=independent-scripted-envelopes payloadPreservationChecked=true doneConsumedBeforeClose=true
         |referenceRuntimeChecked=false cardanoHeaderValidated=false cardanoBlockValidated=false ledgerRollbackImplemented=false livePeerChecked=false
         |Synthetic points describe only a deterministic fixture history. No ledger-valid chain is asserted.""".stripMargin
  def run: IO[ExitCode] =
    verify(Path.of("fixtures/chain-sync"))
      .flatMap(IO.println)
      .as(ExitCode.Success)
      .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
