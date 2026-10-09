// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.ledger.{ClusterNativeTransfer, NativeSpending, ClusterTransition as Ledger}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Local reference observation and separate offline diagnostics. Acquisition is not validated
  * state. Funding is prior reference setup, outside this spending comparison.
  */
object NativeSpendingCommand:
  enum Failure:
    case Unsupported(stage: String, detail: String)
    case Rejected(stage: String, predicate: String, detail: String)
  private final case class Stop(failure: Failure) extends RuntimeException
  private[lab] val sources: Map[String, String] = Map(
    "genesisSha256" -> "transfer-genesis.md",
    "parametersSha256" -> "pre-parameters.md",
    "preTipsSha256" -> "pre-tips.md",
    "preLedgerSha256" -> "pre-ledger-state.md",
    "preUtxoCborSha256" -> "pre-utxo-cbor.md"
  )
  final class Pre private[NativeSpendingCommand] (
      val state: Ledger.State,
      val epochLength: BigInt,
      val manifestDigest: Bytes
  )
  private def text(raw: Bytes): String =
    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw.toArray)).toString
  private def sha(raw: Bytes): Bytes = ClusterHeaderObservation.sha256(raw)
  private def read(path: Path, max: Int): Bytes =
    val in = Files.newInputStream(path)
    val bytes =
      try in.readNBytes(max + 1)
      finally in.close()
    require(bytes.nonEmpty && bytes.length <= max, "nonempty bounded source required")
    Bytes.fromArray(bytes)
  private def get[A](value: Either[String, A]): A =
    value.fold(e => throw new IllegalArgumentException(e), identity)
  private def hash(s: String, size: Int): Bytes =
    val bytes = get(Bytes.fromHex(s))
    require(bytes.size == size && bytes.hex == s, "canonical hash required")
    bytes
  private def supported(condition: Boolean, why: String): Unit =
    if !condition then throw Stop(Failure.Unsupported("pre-context", why))
  private def protect[A](stage: String)(body: => A): Either[Failure, A] =
    try Right(body)
    catch
      case Stop(failure) => Left(failure)
      case NonFatal(e) =>
        Left(
          Failure.Rejected(
            stage,
            "MalformedInput",
            Option(e.getMessage).getOrElse(e.getClass.getName)
          )
        )
  private[lab] def nativeFailure(error: NativeSpending.Error): Failure = error match
    case NativeSpending.Error.UnsupportedProfile(detail) => Failure.Unsupported("native", detail)
    case NativeSpending.Error.UnsupportedInput(input, detail) =>
      Failure.Unsupported("native", s"$input: $detail")
    case NativeSpending.Error.WitnessProfileRejected(detail) =>
      Failure.Unsupported("native-witness", detail)
    case other => Failure.Rejected("native", other.productPrefix, other.toString)
  private def ledgerFailure(error: Ledger.Failure): Failure = error match
    case Ledger.Failure.Unsupported(detail) => Failure.Unsupported("ledger", detail)
    case Ledger.Failure.Rejected(predicate) => nativeFailure(predicate)
    case other => Failure.Rejected("ledger", other.productPrefix, other.toString)
  private def ledger[A](result: Ledger.Checked[A]): A =
    result.fold(e => throw Stop(ledgerFailure(e)), identity)
  private def lift[A](result: Either[Failure, A]): IO[A] =
    IO.fromEither(result.left.map(Stop.apply))

  /** Only five pre sources are accepted; no post-state path is consulted. */
  private[lab] def bindPre(manifest: Bytes, originals: Map[String, Bytes]): Either[Failure, Pre] =
    protect("pre-input") {
      require(manifest.size > 0 && manifest.size <= 8192, "manifest bound")
      val rows = text(manifest).linesIterator.map { line =>
        val columns = line.split("\t", -1)
        require(columns.length == 2 && columns.forall(_.nonEmpty), "strict manifest TSV required")
        columns(0) -> columns(1)
      }.toVector
      require(rows.map(_._1).distinct.size == rows.size, "duplicate manifest key")
      val fields = rows.toMap
      require(fields.keySet == sources.keySet + "format", "exact pre manifest fields required")
      require(fields("format") == "native-spending-pre-v1", "native pre format required")
      require(originals.keySet == sources.values.toSet, "exact pre sources required")
      val pins = sources.map { (key, file) =>
        val bytes = originals(file)
        require(bytes.size > 0 && bytes.size <= 4194304, "pre source bound")
        val pin = hash(fields(key), 32)
        require(sha(bytes) == pin, "pre source digest mismatch: " + file)
        key -> pin
      }
      import ReferenceJson.{parse, field, uint, string, array}
      val genesis = parse(originals("transfer-genesis.md"))
      val params = parse(originals("pre-parameters.md"))
      val ledgerJson = parse(originals("pre-ledger-state.md"))
      supported(
        string(field(genesis, "networkId")) == "Testnet" && uint(
          field(genesis, "networkMagic")
        ) == 1082026,
        "isolated testnet magic 1082026 required"
      )
      supported(
        uint(field(params, "protocolVersion", "major")) == 9 && uint(
          field(params, "protocolVersion", "minor")
        ) == 0,
        "Conway PV9.0 required"
      )
      val epochLength = uint(field(genesis, "epochLength"))
      require(epochLength > 0, "positive epoch length required")
      val tips = array(parse(originals("pre-tips.md")))
      require(tips.size >= 2 && tips.size <= 32, "bounded stable tip bracket required")
      val points = tips.map { tip =>
        supported(string(field(tip, "era")) == "Conway", "Conway tip required")
        (
          hash(string(field(tip, "hash")), 32),
          uint(field(tip, "slot")),
          uint(field(tip, "epoch")),
          uint(field(tip, "block"))
        )
      }
      require(points.distinct.size == 1, "unstable pre tip bracket")
      val (_, slot, epoch, blockNo) = points.head
      require(
        slot <= lab.network.ChainSync.UInt64.Max && blockNo <= lab.network.ChainSync.UInt64.Max,
        "point Word64 bound"
      )
      supported(slot / epochLength == epoch, "fixed supplied epoch translation required")
      require(uint(field(ledgerJson, "lastEpoch")) == epoch, "pre ledger epoch mismatch")
      val env = ledger(
        Ledger.environment(
          pins("genesisSha256"),
          pins("parametersSha256"),
          1082026L,
          epoch,
          9,
          0,
          uint(field(params, "txFeePerByte")),
          uint(field(params, "txFeeFixed")),
          uint(field(params, "maxTxSize")),
          uint(field(params, "utxoCostPerByte"))
        )
      )
      val pre = get(Bytes.fromHex(text(originals("pre-utxo-cbor.md")).trim))
      val digest = sha(manifest)
      val state = ledger(
        Ledger.checkpoint(
          env,
          pre,
          uint(field(ledgerJson, "stateBefore", "esLState", "utxoState", "fees")),
          slot,
          digest
        )
      )
      new Pre(state, epochLength, digest)
    }
  private[lab] def loadPre(directory: Path): Either[Failure, Pre] = protect("pre-input") {
    bindPre(
      read(directory.resolve("native-spending-pre.md"), 8192),
      sources.values.map(n => n -> read(directory.resolve(n), 4194304)).toMap
    )
      .fold(f => throw Stop(f), identity)
  }

  /** Supplied slot is diagnostic context, never an inclusion claim. */
  private[lab] def diagnose(
      pre: Pre,
      transaction: Bytes,
      slot: BigInt
  ): Either[Failure, Ledger.Applied] =
    protect("diagnostic") {
      require(
        transaction.size > 0 && transaction.size <= 65536,
        "bounded original transaction required"
      )
      require(
        slot >= pre.state.slot && slot <= lab.network.ChainSync.UInt64.Max,
        "candidate slot must be Word64 at or after pre tip"
      )
      supported(
        slot / pre.epochLength == pre.state.environment.epoch,
        "candidate must stay in supplied epoch"
      )
      val applied = ledger(Ledger.applyTransaction(pre.state, transaction, slot))
      supported(
        applied.candidate.nativeAdmission.nonEmpty,
        "at least one native-script input required"
      )
      applied
    }

  private def quote(s: String): String = "\"" + s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""
  private[lab] def renderFailure(f: Failure, scope: String, txSha: Option[Bytes] = None): String =
    val (outcome, stage, predicate, detail) = f match
      case Failure.Unsupported(s, d) => ("Unsupported", s, "Unsupported", d)
      case Failure.Rejected(s, p, d) => ("Rejected", s, p, d)
    s"""{"scope":${quote(scope)},"passed":false,"outcome":${quote(outcome)},"stage":${quote(
        stage
      )},"predicate":${quote(predicate)},"detail":${quote(
        detail
      )},"originalTransactionSha256":${txSha.fold("null")(h =>
        quote(h.hex)
      )},"fullLedgerValidated":false,"consensusValidated":false,"referenceSnapshotAtomic":false}"""

  private def observe(port: String, directory: Path): IO[ExitCode] =
    for
      in <- IO.blocking(ClusterTransferCommand.load(directory))
      _ <- IO.raiseUnless(in.context.networkMagic == 1082026)(
        Stop(Failure.Unsupported("context", "isolated testnet magic 1082026 required"))
      )
      _ <- IO.blocking {
        val genesisBytes = read(directory.resolve("transfer-genesis.md"), 4194304)
        require(sha(genesisBytes) == in.context.genesisDigest, "genesis changed after loading")
        val epochLength =
          ReferenceJson.uint(ReferenceJson.field(ReferenceJson.parse(genesisBytes), "epochLength"))
        require(epochLength > 0, "positive epoch length required")
        supported(
          in.context.preSlot / epochLength == in.context.epoch && in.context.postSlot / epochLength == in.context.epoch,
          "captured range must stay within supplied fixed epoch"
        )
      }
      peer <- IO.fromEither(
        ReferenceHandshakeCommand
          .options(List(port, in.context.networkMagic.toString))
          .left
          .map(e => Stop(Failure.Rejected("acquisition", "Configuration", e)))
      )
      headers <- ReferenceCaptureCommand.headersThrough(
        peer._1,
        peer._2,
        in.prePoint,
        Some(in.postPoint),
        8
      )
      blocks <- headers.traverse(h => ReferenceCaptureCommand.exactBlock(peer._1, peer._2, h))
      _ <- headers.zip(blocks).zipWithIndex.traverse_ { case ((header, block), index) =>
        val previous =
          if index == 0 then in.prePoint
          else
            lab.network.ChainSync.Point.Block(
              get(lab.network.ChainSync.UInt64.from(headers(index - 1).slot)),
              headers(index - 1).hash
            )
        IO.fromEither(
          ReferenceCaptureCommand
            .compare(header, block, previous)
            .left
            .map(e => Stop(Failure.Rejected("acquisition", "OriginalMismatch", e)))
        ).flatMap(IO.println) *>
          IO.println(
            s"""{"record":"transfer-range-block","headerEnvelopeHex":"${header.envelope.hex}","rawBlockHex":"${block.hex}"}"""
          )
      }
      // Pure comparison derives before touching the supplied reference post-state oracle.
      receipt <- lift(
        ClusterNativeTransfer
          .compare(in.context, in.minimumOutputParameters, in.pre, in.post, in.tx, blocks)
          .left
          .map(nativeFailure)
      )
      _ <- IO.println(MinimumOutputCommand.render(receipt.minimum))
      _ <- IO.println(
        s"""{"scope":"cluster-native-transfer-observation","profile":"${receipt.profileId}","passed":true,"scopedSuccess":true,"credentialBound":true,"independentStateDerived":true,"referencePostStateMatched":true,"transactionId":"${receipt.transactionId.hex}","originalTransactionSha256":"${sha(
            in.tx
          ).hex}","containingBlockHash":"${receipt.bound.blockHash.hex}","containingBlockSha256":"${receipt.bound.blockRawSha256.hex}","slot":${receipt.bound.interval.slot},"slotSource":"containing-block","fee":${receipt.fee},"observedFeePotDelta":${in.context.feesAfter - in.context.feesBefore},"untouchedUtxoEntries":${receipt.untouchedEntries},"capturedBlocks":${blocks.size},"contextSha256":"${in.manifestDigest.hex}","requiredScriptCount":${receipt.admission.requiredScripts.size},"requiredKeyCount":${receipt.admission.requiredKeys.size},"originalTransactionInclusionMatched":true,"witnessSignaturesChecked":true,"minimumOutputsChecked":true,"validityIntervalChecked":true,"headerSignaturesChecked":false,"fundingSetupValidated":false,"acquisitionIsValidatedState":false,"referenceSnapshotAtomic":false,"fullLedgerValidated":false,"consensusValidated":false}"""
      )
    yield ExitCode.Success

  private def diagnostic(
      directory: Path,
      transactionFile: Path,
      suppliedSlot: String
  ): IO[ExitCode] =
    for
      pre <- IO.blocking(loadPre(directory)).flatMap(lift)
      slot <- IO {
        require(
          suppliedSlot.matches("0|[1-9][0-9]{0,19}"),
          "canonical bounded candidate slot required"
        )
        BigInt(suppliedSlot)
      }
      tx <- IO.blocking {
        val bytes = get(Bytes.fromHex(text(read(transactionFile, 131074)).trim))
        require(bytes.size <= 65536, "transaction bound")
        bytes
      }
      result <- IO(diagnose(pre, tx, slot))
      code <- result match
        case Left(error) =>
          IO.println(renderFailure(error, "native-spending-diagnostic", Some(sha(tx))))
            .as(ExitCode(2))
        case Right(applied) =>
          IO.println(
            s"""{"scope":"native-spending-diagnostic","profile":"${Ledger.ProfileId}","outcome":"ScopedDerived","passed":true,"credentialBound":true,"independentStateDerived":true,"transactionId":"${applied.candidate.transactionId.hex}","originalTransactionSha256":"${sha(
                tx
              ).hex}","contextSha256":"${pre.manifestDigest.hex}","slot":$slot,"slotSource":"caller-supplied","referencePostStateMatched":false,"originalTransactionInclusionMatched":false,"fullLedgerValidated":false,"consensusValidated":false,"referenceSnapshotAtomic":false}"""
          ).as(ExitCode.Success)
    yield code

  def run(args: List[String]): IO[ExitCode] =
    val scope =
      if args.headOption.contains("diagnose") then "native-spending-diagnostic"
      else "cluster-native-transfer-observation"
    val work = args match
      case List("observe", port, directory) => observe(port, Path.of(directory))
      case List("diagnose", directory, transaction, slot) =>
        diagnostic(Path.of(directory), Path.of(transaction), slot)
      case _ =>
        IO.raiseError[ExitCode](
          Stop(
            Failure.Rejected(
              "arguments",
              "Usage",
              "native-spending observe PORT EVIDENCE_DIRECTORY | native-spending diagnose PRE_DIRECTORY TRANSACTION_CBOR_FILE SLOT"
            )
          )
        )
    work.timeout(60.seconds).handleErrorWith { e =>
      val failure = e match
        case Stop(f) => f
        case other =>
          Failure.Rejected(
            "command",
            "ExecutionFailure",
            Option(other.getMessage).getOrElse(other.getClass.getName)
          )
      IO.println(renderFailure(failure, scope)).as(ExitCode(2))
    }
