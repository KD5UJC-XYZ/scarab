// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.chain.CardanoBlockIndex
import lab.header.PraosCertificateState as Certificate
import lab.network.ChainSync
import scala.util.control.NonFatal

/** Supplied original-byte linkage only. The NtN envelope is derived, not wire evidence. No
  * signature, consensus, ledger, acquisition authenticity or runtime admission claim.
  */
private[lab] object NativeLinkedBlocks:
  val MaxBlocks = 128
  val MaxBlockBytes = 1048576
  val MaxTotalBytes = 64L * 1024 * 1024
  final class Checked private[NativeLinkedBlocks] (
      val anchor: Certificate.Point,
      val terminal: Certificate.Point,
      val epochLength: BigInt,
      val sourceJoinId: Bytes,
      val originals: Vector[Bytes],
      val sourcePins: Vector[Bytes],
      val blocks: Vector[SequenceInput.Block],
      val id: Bytes
  ):
    val derivedHeaderEnvelope = true
    val authenticatedSnapshot = false
    val actualAcquisitionVerified = false
    val signaturesChecked = false
    val fullLedgerValidated = false
    val nativeConformance = false
    val runtimeImport = false
    val rewardSeedAdmission = false

  private def get[A](result: Either[?, A]): A =
    result.fold(e => throw new IllegalArgumentException(e.toString), value => value)
  private def validBytes(b: Bytes, size: Int): Boolean =
    b != null && b.value != null && b.size == size
  private def point(p: Certificate.Point): Boolean =
    p != null && validBytes(p.hash, 32) && p.slot >= 0 &&
      p.slot <= ChainSync.UInt64.Max && p.blockNo >= 0 && p.blockNo <= ChainSync.UInt64.Max
  private def node(v: Value): Node = Node(v, Bytes.empty)

  def bind(
      anchor: Certificate.Point,
      terminal: Certificate.Point,
      epochLength: BigInt,
      sourceJoinId: Bytes,
      originals: Vector[Bytes],
      expectedPins: Vector[Bytes]
  ): Either[String, Checked] =
    try
      require(point(anchor) && point(terminal), "bounded full points required")
      require(epochLength > 0 && epochLength <= ChainSync.UInt64.Max, "bounded epoch length")
      require(
        anchor.slot / epochLength == 0 && terminal.slot / epochLength == 1,
        "epoch-zero anchor and epoch-one terminal required"
      )
      require(validBytes(sourceJoinId, 32), "source join identity required")
      require(
        originals != null && expectedPins != null &&
          originals.nonEmpty && originals.size <= MaxBlocks &&
          originals.size == expectedPins.size,
        "one to 128 originals and exact pins required"
      )
      require(
        originals.forall(b =>
          b != null && b.value != null &&
            b.size > 0 && b.size <= MaxBlockBytes
        ),
        "one MiB original block bound"
      )
      require(originals.map(_.size.toLong).sum <= MaxTotalBytes, "64 MiB aggregate bound")
      require(expectedPins.forall(validBytes(_, 32)), "SHA256 pins required")
      require(
        originals.map(ClusterHeaderObservation.sha256) == expectedPins,
        "original block digest mismatch"
      )
      var previous = anchor
      val blocks = originals.map { raw =>
        val indexed = get(CardanoBlockIndex.inspect(raw))
        require(indexed.era == "conway", "Conway originals required")
        val envelope = get(
          Cbor.encode(
            Value.Arr(
              Vector(
                node(Value.UInt(6)),
                node(Value.Tag(24, node(Value.ByteString(indexed.headerBytes))))
              )
            )
          )
        )
        val block = get(SequenceInput.block(BoundedChainFollower.Original(envelope, raw)))
        val h = block.header
        require(h.blockNo == previous.blockNo + 1, "consecutive block numbers required")
        require(h.slot / epochLength <= 1, "second epoch boundary unsupported")
        val predecessor =
          ChainSync.Point.Block(get(ChainSync.UInt64.from(previous.slot)), previous.hash)
        get(ReferenceCaptureCommand.compare(h, raw, predecessor))
        previous = Certificate.Point(h.hash, h.slot, h.blockNo)
        block
      }
      require(previous == terminal, "exact full terminal point required")
      def rendered(p: Certificate.Point) = s"${p.slot}:${p.hash.hex}:${p.blockNo}"
      val recipe = Vector(
        "native-linked-blocks-v1",
        sourceJoinId.hex,
        epochLength.toString,
        rendered(anchor),
        rendered(terminal)
      ) ++ expectedPins.map(_.hex)
      val id = ClusterHeaderObservation.sha256(
        Bytes.fromArray((recipe.mkString("\n") + "\n").getBytes("UTF-8"))
      )
      Right(
        new Checked(
          anchor,
          terminal,
          epochLength,
          sourceJoinId,
          originals,
          expectedPins,
          blocks,
          id
        )
      )
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
