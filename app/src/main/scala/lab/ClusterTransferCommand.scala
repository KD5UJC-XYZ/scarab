// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.ledger.{ClusterTransfer, Coverage}
import lab.network.ChainSync
import scala.concurrent.duration.*

/** One integrated block-range and explicit-context transfer observation. */
object ClusterTransferCommand:
  private def checked[A](e: Either[String, A]): IO[A] =
    IO.fromEither(e.leftMap(new IllegalArgumentException(_)))
  private def sha(raw: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(raw.toArray))
  private def read(path: Path, max: Long = 4194304): Bytes =
    require(Files.size(path) <= max, "input file exceeds local bound")
    Bytes.fromArray(Files.readAllBytes(path))
  private def hex(path: Path): Bytes = Bytes
    .fromHex(new String(read(path).toArray, "UTF-8").trim)
    .fold(e => throw new IllegalArgumentException(e), identity)
  private def array(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case _             => throw new IllegalArgumentException("array required")
  private def get[A](e: Either[String, A]): A =
    e.fold(x => throw new IllegalArgumentException(x), identity)

  final case class Input(
      context: ClusterTransfer.Context,
      pre: Bytes,
      post: Bytes,
      tx: Bytes,
      prePoint: ChainSync.Point,
      postPoint: ChainSync.Point,
      manifestDigest: Bytes
  )
  def load(dir: Path): Input =
    val manifest = read(dir.resolve("transfer-context.md"), 8192)
    val pairs = new String(manifest.toArray, "UTF-8").linesIterator
      .filter(_.nonEmpty)
      .map { line =>
        line.split("\t", -1).toList match
          case key :: value :: Nil => key -> value
          case _                   => throw new IllegalArgumentException("invalid context field")
      }
      .toVector
    val fields = pairs.toMap
    val expected = Set(
      "format",
      "genesisSha256",
      "parametersSha256",
      "networkMagic",
      "major",
      "minor",
      "preHash",
      "postHash",
      "preSlot",
      "postSlot",
      "preEpoch",
      "postEpoch",
      "feePerByte",
      "feeFixed",
      "maxTxSize",
      "feesBefore",
      "feesAfter",
      "binding"
    )
    require(
      fields.keySet == expected && pairs.size == expected.size,
      "missing/duplicate context fields"
    )
    require(
      fields("format") == ClusterTransfer.ProfileId && fields(
        "binding"
      ) == "paused-producer-tip-brackets",
      "unsupported context profile or binding"
    )
    def n(key: String): BigInt =
      val text = fields(key)
      require(text.matches("0|[1-9][0-9]{0,38}"), "bounded unsigned context integer required")
      BigInt(text)
    def b(key: String): Bytes = get(Bytes.fromHex(fields(key)))
    require(
      sha(read(dir.resolve("transfer-genesis.md"))) == b("genesisSha256"),
      "genesis attribution mismatch"
    )
    require(
      sha(read(dir.resolve("pre-parameters.md"))) == b("parametersSha256") &&
        sha(read(dir.resolve("post-parameters.md"))) == b("parametersSha256"),
      "parameter attribution mismatch"
    )
    require(
      n("networkMagic").isValidLong && n("major").isValidInt && n("minor").isValidInt,
      "context integer overflow"
    )
    val context = get(
      ClusterTransfer.Context.checked(
        b("genesisSha256"),
        b("parametersSha256"),
        n("networkMagic").toLong,
        b("preHash"),
        b("postHash"),
        n("preSlot"),
        n("postSlot"),
        n("preEpoch"),
        n("postEpoch"),
        n("major").toInt,
        n("minor").toInt,
        n("feePerByte"),
        n("feeFixed"),
        n("maxTxSize"),
        n("feesBefore"),
        n("feesAfter")
      )
    )
    Input(
      context,
      hex(dir.resolve("pre-utxo-cbor.md")),
      hex(dir.resolve("post-utxo-cbor.md")),
      hex(dir.resolve("signed-transaction-cbor.md")),
      ChainSync.Point.Block(get(ChainSync.UInt64.from(context.preSlot)), context.preHash),
      ChainSync.Point.Block(get(ChainSync.UInt64.from(context.postSlot)), context.postHash),
      sha(manifest)
    )

  def checkInclusion(original: Bytes, blocks: Vector[Bytes]): Unit =
    val tx = get(Coverage.decode(original).left.map(_.toString))
    val target = array(get(Cbor.decode(original)))
    val all = blocks.flatMap { raw =>
      val outer = array(get(Cbor.decode(raw)))
      val block = array(outer(1))
      require(
        block.size == 5 && array(block(4)).isEmpty,
        "invalid transaction indices unsupported"
      )
      block(3).value match
        case Value.Map(xs) => require(xs.isEmpty, "auxiliary data unsupported")
        case _             => throw new IllegalArgumentException("auxiliary map required")
      val bodies = array(block(1)); val witnesses = array(block(2))
      require(bodies.size == witnesses.size, "body/witness count mismatch")
      bodies.zip(witnesses)
    }
    require(
      all.size == 1,
      "closed transfer scenario requires exactly one transaction in captured range"
    )
    require(
      all.head._1.original == target(0).original && all.head._2.original == target(
        1
      ).original,
      "submitted original body/witness bytes differ from inclusion block"
    )
    require(
      Blake2b.hash256.hash(all.head._1.original) == tx.body.hash.bytes,
      "included transaction ID mismatch"
    )

  def run(args: List[String]): IO[ExitCode] = args match
    case List(port, directory) =>
      (for
        in <- IO.blocking(load(Path.of(directory)))
        parsed <- checked(
          ReferenceHandshakeCommand.options(List(port, in.context.networkMagic.toString))
        )
        headers <- ReferenceCaptureCommand.headersThrough(
          parsed._1,
          parsed._2,
          in.prePoint,
          Some(in.postPoint)
        )
        blocks <- headers.traverse(h => ReferenceCaptureCommand.exactBlock(parsed._1, parsed._2, h))
        _ <- headers.zip(blocks).zipWithIndex.traverse_ { case ((h, raw), i) =>
          val previous =
            if i == 0 then in.prePoint
            else
              ChainSync.Point.Block(
                get(ChainSync.UInt64.from(headers(i - 1).slot)),
                headers(i - 1).hash
              )
          checked(ReferenceCaptureCommand.compare(h, raw, previous)).flatMap(IO.println) *>
            IO.println(
              s"""{"record":"transfer-range-block","headerEnvelopeHex":"${h.envelope.hex}","rawBlockHex":"${raw.hex}"}"""
            )
        }
        _ <- IO.blocking {
          checkInclusion(in.tx, blocks)
        }
        receipt <- checked(ClusterTransfer.compare(in.context, in.pre, in.post, in.tx))
        _ <- IO.println(
          s"""{"scope":"cluster-transfer-observation","profile":"${receipt.profileId}","passed":true,"transactionId":"${receipt.transactionId.hex}","fee":${receipt.fee},"observedFeePotDelta":${receipt.observedFeePotDelta},"untouchedUtxoEntries":${receipt.untouchedEntries},"capturedBlocks":${blocks.size},"contextSha256":"${in.manifestDigest.hex}","ledgerProtocolVersion":"9.0","referenceSnapshotAtomic":false,"originalTransactionInclusionMatched":true,"witnessSignaturesChecked":true,"headerSignaturesChecked":false,"fullLedgerValidated":false}"""
        )
      yield ExitCode.Success)
        .timeout(60.seconds)
        .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
    case _ => IO.println("usage: cluster-transfer PORT PUBLIC_EVIDENCE_DIRECTORY").as(ExitCode(2))
