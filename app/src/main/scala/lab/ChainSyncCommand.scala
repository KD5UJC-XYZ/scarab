// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.network.{ChainSync, ChainSyncFixtures, FixtureChainModel}
import ChainSync.*

/** Offline pure checks only. No socket, ByteTransport, handshake or reference runtime is started.
  */
object ChainSyncCommand:
  val Payloads: Vector[(String, String, Boolean)] = Vector(
    (
      "ntn-header-conway.cbor",
      "e0f58468d294102df59a77bf13631ed14c3f92e91b6f610a40f5d9729a7eb932",
      true
    ),
    (
      "ntn-placeholder-header.cbor",
      "10f389fcd2771f1f3033ddae41b3fda1c22a36f88b63bb53670855c828dd6a4b",
      true
    ),
    (
      "ntc-block-conway.cbor",
      "eb451bb8134942ff8afbcd1e87cedd282197b865d8a3637e41cf158ad7e34548",
      false
    ),
    (
      "ntc-placeholder-block.cbor",
      "eed2bfd687a284ffe2637f788193be37b504214a4e1a61c27f199e2a73885be9",
      false
    )
  )
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  def checkPayload(
      name: String,
      bytes: Bytes,
      expectedHash: String,
      ntn: Boolean
  ): Either[String, Int] =
    if bytes.size > 65535 then return Left("fixture byte limit exceeded")
    val actual = Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(bytes.toArray)).hex
    if actual != expectedHash then Left(s"$name checksum mismatch")
    else
      val checked =
        if ntn then ChainSyncFixtures.ntnHeader.decode(bytes).map(_.bytes)
        else ChainSyncFixtures.ntcBlock.decode(bytes).map(_.bytes)
      checked.flatMap { exact =>
        // Derived envelope plus upstream payload and synthetic Origin tip, NOT a runtime transcript.
        val wire = Bytes(
          Vector(0x83.toByte, 2.toByte) ++ exact.value ++ Vector(0x82.toByte, 0x80.toByte, 0.toByte)
        )
        decodePrefix(State.NextCanAwait, Role.Server, wire, ChainSyncFixtures.rawItem) match
          case DecodeResult.Decoded(Message.RollForward(raw, tip), consumed)
              if raw == bytes && tip == Tip.Origin && consumed == wire.size =>
            encode(
              State.NextCanAwait,
              Role.Server,
              Message.RollForward(raw, tip),
              ChainSyncFixtures.rawItem
            )
              .flatMap(encoded => Either.cond(encoded == wire, wire.size, "payload bytes changed"))
          case _ => Left("derived envelope mismatch")
      }
  def verify(directory: Path): String =
    val byteCount = Payloads.map { case (name, hash, ntn) =>
      val path = directory.resolve(name)
      if Files.size(path) > 65535 then
        throw new IllegalArgumentException("fixture byte limit exceeded")
      get(checkPayload(name, Bytes.fromArray(Files.readAllBytes(path)), hash, ntn))
    }.sum
    val model = get(FixtureChainModel.demo())
    if model.state != State.Done || model.trace.size != 14 then
      throw new IllegalArgumentException("deterministic model did not terminate")
    s"""PASS pure ChainSync envelope/state checks and deterministic synthetic fork model
       |networkPin=c45735a56c567fa977969173d18943bac6bb3821
       |consensusPin=82ecba329d7d054340bf707d44fe6e9ac27cec40
       |fixtureProfiles=NtN14/protocol2/CardanoNodeToNodeVersion2;NtC16/protocol5/CardanoNodeToClientVersion12
       |payloadFixtures=4 typedSerializationExamples=2 placeholderEnvelopeGoldens=2 derivedEnvelopeBytes=$byteCount
       |modelTransitions=${model.trace.size} finalState=${model.state} transportExchanges=0
       |referenceRuntimeChecked=false cardanoHeaderValidated=false cardanoBlockValidated=false ledgerRollbackImplemented=false livePeerChecked=false
       |This pure mode starts no CE connection/session. Synthetic points do not describe a validated Cardano chain.""".stripMargin
  def run: IO[ExitCode] =
    IO.blocking(verify(Path.of("fixtures/chain-sync")))
      .flatMap(IO.println)
      .as(ExitCode.Success)
      .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
