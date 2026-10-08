// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.ledger.{ClusterTransfer, Coverage, MinimumOutput}
import lab.network.ChainSync
import scala.concurrent.duration.*

/** One integrated block-range and explicit-context transfer observation. */
object ClusterTransferCommand:
  val ContextFormat = "conway-pv9-cluster-context-v2"
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
      manifestDigest: Bytes,
      minimumOutputParameters: MinimumOutput.Parameters
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
      "binding",
      "preTipsSha256",
      "postTipsSha256",
      "preLedgerSha256",
      "postLedgerSha256"
    )
    require(
      fields.keySet == expected && pairs.size == expected.size,
      "missing/duplicate context fields"
    )
    require(
      fields("format") == ContextFormat && fields("binding") == "paused-producer-tip-brackets",
      "unsupported context profile or binding"
    )
    def n(key: String): BigInt =
      val text = fields(key)
      require(text.matches("0|[1-9][0-9]{0,38}"), "bounded unsigned context integer required")
      BigInt(text)
    def b(key: String): Bytes = get(Bytes.fromHex(fields(key)))
    def source(name: String, digest: String): ReferenceJson.Json =
      val raw = read(dir.resolve(name))
      require(sha(raw) == b(digest), "reference source digest mismatch: " + name)
      ReferenceJson.parse(raw)
    val genesis = source("transfer-genesis.md", "genesisSha256")
    val params = source("pre-parameters.md", "parametersSha256")
    source("post-parameters.md", "parametersSha256")
    val preTips = source("pre-tips.md", "preTipsSha256")
    val postTips = source("post-tips.md", "postTipsSha256")
    val preLedger = source("pre-ledger-state.md", "preLedgerSha256")
    val postLedger = source("post-ledger-state.md", "postLedgerSha256")
    import ReferenceJson.{field, uint, string}
    def number(root: ReferenceJson.Json, path: String*): BigInt =
      val value = uint(field(root, path*))
      require(value <= (BigInt(1) << 64) - 1, "reference integer exceeds uint64")
      value
    def equal(key: String, value: BigInt): Unit =
      require(n(key) == value, "context/source numeric mismatch: " + key)
    equal("networkMagic", number(genesis, "networkMagic"))
    require(string(field(genesis, "networkId")) == "Testnet", "testnet genesis required")
    equal("major", number(params, "protocolVersion", "major"))
    equal("minor", number(params, "protocolVersion", "minor"))
    equal("feePerByte", number(params, "txFeePerByte"))
    equal("feeFixed", number(params, "txFeeFixed"))
    equal("maxTxSize", number(params, "maxTxSize"))
    def point(prefix: String, tips: ReferenceJson.Json, ledger: ReferenceJson.Json): Unit =
      val observations = ReferenceJson.array(tips)
      require(observations.size >= 2 && observations.size <= 32, "bounded tip brackets required")
      val observed = observations.map { tip =>
        require(string(field(tip, "era")) == "Conway", "Conway tip required")
        (
          string(field(tip, "hash")),
          number(tip, "slot"),
          number(tip, "epoch"),
          number(tip, "block")
        )
      }
      require(observed.distinct.size == 1, "reference tip bracket changed")
      val (hash, slot, epoch, _) = observed.head
      require(get(Bytes.fromHex(hash)) == b(prefix + "Hash"), "context/source point hash mismatch")
      equal(prefix + "Slot", slot)
      equal(prefix + "Epoch", epoch)
      require(number(ledger, "lastEpoch") == epoch, "ledger epoch differs from tip bracket")
      equal(
        if prefix == "pre" then "feesBefore" else "feesAfter",
        number(ledger, "stateBefore", "esLState", "utxoState", "fees")
      )
    point("pre", preTips, preLedger)
    point("post", postTips, postLedger)
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
      sha(manifest),
      MinimumOutputCommand.parameters(params)
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
        minimum <- checked(MinimumOutput.check(in.minimumOutputParameters, in.tx))
        _ <- checked(Either.cond(minimum.satisfied, (), "minimum output predicate failed"))
        _ <- IO.println(MinimumOutputCommand.render(minimum))
        receipt <- checked(ClusterTransfer.compare(in.context, in.pre, in.post, in.tx))
        _ <- IO.println(
          s"""{"scope":"cluster-transfer-observation","profile":"${receipt.profileId}","passed":true,"transactionId":"${receipt.transactionId.hex}","fee":${receipt.fee},"observedFeePotDelta":${receipt.observedFeePotDelta},"untouchedUtxoEntries":${receipt.untouchedEntries},"capturedBlocks":${blocks.size},"contextSha256":"${in.manifestDigest.hex}","ledgerProtocolVersion":"9.0","referenceSnapshotAtomic":false,"originalTransactionInclusionMatched":true,"witnessSignaturesChecked":true,"minimumOutputsChecked":true,"headerSignaturesChecked":false,"fullLedgerValidated":false}"""
        )
      yield ExitCode.Success)
        .timeout(60.seconds)
        .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
    case _ => IO.println("usage: cluster-transfer PORT PUBLIC_EVIDENCE_DIRECTORY").as(ExitCode(2))
