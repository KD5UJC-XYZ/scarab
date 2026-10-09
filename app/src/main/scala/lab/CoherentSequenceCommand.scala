// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.header.PraosCertificateState as Certificate
import lab.ledger.ClusterTransition as Ledger
import lab.network.ChainSync
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Separate byte acquisition and offline observation. Post-state cannot seed a transition. */
object CoherentSequenceCommand:
  val OracleFormat = "coherent-sequence-oracle-v1"
  private[lab] val sources: Map[String, String] = Map(
    "postUtxoCborSha256" -> "post-utxo-cbor.md",
    "postLedgerSha256" -> "post-ledger-state.md",
    "postProtocolSha256" -> "post-protocol-state.md",
    "postTipsSha256" -> "post-tips.md",
    "postParametersSha256" -> "post-parameters.md",
    "transaction0Sha256" -> "signed-transaction-0-cbor.md",
    "transaction1Sha256" -> "signed-transaction-1-cbor.md",
    "captureSha256" -> "scala-sequence-capture.md"
  )
  enum Failure:
    case Unsupported(stage: String, feature: String)
    case Rejected(stage: String, detail: String)
  private final case class Stop(failure: Failure) extends RuntimeException
  final class OwnedOracle private[CoherentSequenceCommand] (
      val originals: Map[String, Bytes],
      val pins: Map[String, Bytes],
      val manifestDigest: Bytes
  )
  private[lab] final case class Grouping(
      blockIndex: Int,
      emptyBlocks: Int,
      transactionIds: Vector[Bytes],
      submissionOrder: Vector[Int]
  )
  final class Report private[CoherentSequenceCommand] (
      val contextId: Bytes,
      val oracleManifestDigest: Bytes,
      val captureDigest: Bytes,
      val initial: CoherentSequence.State,
      val finalState: CoherentSequence.State,
      val capturedBlocks: Int,
      private[CoherentSequenceCommand] val grouping: Grouping,
      val previousEpochNonceCompared: Boolean,
      val finalRevision: BigInt
  )
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes(StandardCharsets.UTF_8))
  private def text(b: Bytes): String =
    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b.toArray)).toString
  private def sha(b: Bytes): Bytes = ClusterHeaderObservation.sha256(b)
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def protect[A](stage: String)(body: => A): Either[Failure, A] =
    try Right(body)
    catch
      case Stop(failure) => Left(failure)
      case NonFatal(e) =>
        Left(Failure.Rejected(stage, Option(e.getMessage).getOrElse(e.getClass.getName)))
  private def supported(condition: Boolean, stage: String, reason: String): Unit =
    if !condition then throw Stop(Failure.Unsupported(stage, reason))
  private def read(path: Path, bound: Int): Bytes =
    val stream = Files.newInputStream(path)
    val bytes =
      try stream.readNBytes(bound + 1)
      finally stream.close()
    require(bytes.nonEmpty && bytes.length <= bound, "nonempty bounded file required")
    Bytes.fromArray(bytes)
  private def limit(name: String): Int =
    if name == "scala-sequence-capture.md" then 20 * 1024 * 1024
    else if name.startsWith("signed-transaction-") then 131074
    else 4194304
  private def hash(s: String, size: Int): Bytes =
    val result = get(Bytes.fromHex(s))
    require(result.size == size && result.hex == s, "canonical hash required")
    result
  private def obj(j: ReferenceJson.Json): Map[String, ReferenceJson.Json] = j match
    case ReferenceJson.Json.Obj(fields) => fields
    case _                              => throw new IllegalArgumentException("object required")
  private def array(n: Node): Vector[Node] = n.value match
    case Value.Arr(values) => values
    case _                 => throw new IllegalArgumentException("array required")
  private def point(p: Certificate.Point): ChainSync.Point =
    ChainSync.Point.Block(get(ChainSync.UInt64.from(p.slot)), p.hash)

  /** Pin and own bytes only. No reference post-state is parsed here. */
  private[lab] def bindOracle(
      manifest: Bytes,
      originals: Map[String, Bytes]
  ): Either[Failure, OwnedOracle] = protect("oracle-input") {
    require(manifest.size > 0 && manifest.size <= 8192, "manifest bound")
    val pairs = text(manifest).linesIterator.map { line =>
      val columns = line.split("\t", -1)
      require(columns.length == 2 && columns.forall(_.nonEmpty), "strict manifest TSV required")
      columns(0) -> columns(1)
    }.toVector
    require(pairs.map(_._1).distinct.size == pairs.size, "duplicate manifest field")
    val fields = pairs.toMap
    require(
      fields.keySet == sources.keySet + "format" && fields("format") == OracleFormat,
      "exact oracle manifest required"
    )
    require(originals.keySet == sources.values.toSet, "exact oracle files required")
    val pins = sources.map { (key, name) =>
      val bytes = originals(name)
      require(bytes.size > 0 && bytes.size <= limit(name), "oracle original bound")
      val pin = hash(fields(key), 32)
      require(sha(bytes) == pin, "digest mismatch: " + name)
      key -> pin
    }
    new OwnedOracle(originals, pins, sha(manifest))
  }
  private[lab] def loadOracle(directory: Path): Either[Failure, OwnedOracle] =
    protect("oracle-input") {
      bindOracle(
        read(directory.resolve("coherent-sequence-oracle.md"), 8192),
        sources.values.map(n => n -> read(directory.resolve(n), limit(n))).toMap
      )
        .fold(f => throw Stop(f), identity)
    }
  private[lab] def captures(
      bytes: Bytes,
      maxBlocks: Int = 8
  ): Either[Failure, Vector[BoundedChainFollower.Original]] =
    capturesWithin(bytes, maxBlocks, 12)
  private[lab] def fencedCaptures(
      bytes: Bytes
  ): Either[Failure, Vector[BoundedChainFollower.Original]] =
    capturesWithin(bytes, 16, 16)
  private def capturesWithin(
      bytes: Bytes,
      maxBlocks: Int,
      ceiling: Int
  ): Either[Failure, Vector[BoundedChainFollower.Original]] =
    protect("capture") {
      require(maxBlocks >= 2 && maxBlocks <= ceiling, s"capture maximum must be 2 through $ceiling")
      require(
        bytes.size > 0 && bytes.size <= limit("scala-sequence-capture.md"),
        "capture byte bound"
      )
      val lines = text(bytes).linesIterator.filter(_.nonEmpty).take(1025).toVector
      require(lines.size <= 1024, "capture record bound")
      val records = lines.flatMap { line =>
        val json = ReferenceJson.parse(raw(line))
        obj(json).get("record") match
          case Some(ReferenceJson.Json.Str("transfer-range-block")) =>
            val envelope = get(
              Bytes.fromHex(ReferenceJson.string(ReferenceJson.field(json, "headerEnvelopeHex")))
            )
            val block =
              get(Bytes.fromHex(ReferenceJson.string(ReferenceJson.field(json, "rawBlockHex"))))
            require(
              envelope.size > 0 && envelope.size <= 65535 && block.size > 0 && block.size <= 1048576,
              "original capture bounds"
            )
            Some(BoundedChainFollower.Original(envelope, block))
          case _ => None
      }
      require(
        records.size >= 2 && records.size <= maxBlocks,
        "complete captured original count exceeds explicit bound"
      )
      records
    }
  private[lab] def endpoint(bytes: Bytes): Either[Failure, (Certificate.Point, BigInt)] =
    protect("endpoint") {
      import ReferenceJson.{parse, field, array, uint, string}
      val observations = ReferenceJson.array(parse(bytes))
      require(
        observations.size >= 2 && observations.size <= 32,
        "bounded stable endpoint brackets required"
      )
      val points = observations.map { tip =>
        supported(string(field(tip, "era")) == "Conway", "endpoint", "Conway endpoint required")
        val slot = uint(field(tip, "slot")); val block = uint(field(tip, "block"))
        require(
          slot <= ChainSync.UInt64.Max && block <= ChainSync.UInt64.Max,
          "endpoint Word64 bound"
        )
        (
          Certificate.Point(hash(string(field(tip, "hash")), 32), slot, block),
          uint(field(tip, "epoch"))
        )
      }
      require(points.distinct.size == 1, "unstable endpoint brackets")
      points.head
    }
  private def submitted(bytes: Bytes): (Bytes, Bytes, Bytes) =
    val transaction = get(Bytes.fromHex(text(bytes).trim))
    require(transaction.size > 0 && transaction.size <= 65536, "submitted transaction bound")
    val fields = array(get(Cbor.decode(transaction, Cbor.Limits(65536, 48, 20000, 65536))))
    require(fields.size == 4, "four-field submitted transaction required")
    supported(
      fields(2).value == Value.Bool(true) && fields(3).value == Value.Null,
      "submitted",
      "phase1 transaction without auxiliary data required"
    )
    (fields(0).original, fields(1).original, get(TransactionId.fromEnvelope(transaction)))

  /** Exact body/witness pairs and their chain order; reconstructed memos are not original
    * envelopes.
    */
  private[lab] def checkGrouping(
      blocks: Vector[SequenceInput.Block],
      submittedFiles: Vector[Bytes],
      maxBlocks: Int = 8
  ): Either[Failure, Grouping] = checkGroupingWithin(blocks, submittedFiles, maxBlocks, 12)
  private[lab] def fencedGrouping(
      blocks: Vector[SequenceInput.Block],
      submittedFiles: Vector[Bytes]
  ): Either[Failure, Grouping] =
    checkGroupingWithin(blocks, submittedFiles, 16, 16)
  private def checkGroupingWithin(
      blocks: Vector[SequenceInput.Block],
      submittedFiles: Vector[Bytes],
      maxBlocks: Int,
      ceiling: Int
  ): Either[Failure, Grouping] = protect("inclusion") {
    require(
      maxBlocks >= 2 && maxBlocks <= ceiling && blocks.size >= 2 && blocks.size <= maxBlocks && submittedFiles.size == 2,
      "bounded two-transaction scenario required"
    )
    val expected = submittedFiles.map(submitted)
    require(expected.map(_._3).distinct.size == 2, "two distinct submitted bodies required")
    val nonempty = blocks.zipWithIndex.filter(_._1.transactionMemos.nonEmpty)
    require(
      nonempty.size == 1 && nonempty.head._1.transactionMemos.size == 2,
      "exactly two transactions together in one block required"
    )
    val selected = nonempty.head
    val actual = selected._1.transactionMemos.map { memo =>
      val fields = array(get(Cbor.decode(memo)))
      val matchIndex =
        expected.indexWhere(e => e._1 == fields(0).original && e._2 == fields(1).original)
      require(matchIndex >= 0, "original body/witness pair does not match submitted transaction")
      matchIndex
    }
    require(actual.sorted == Vector(0, 1), "each submitted transaction must occur exactly once")
    new Grouping(selected._2, blocks.size - 1, actual.map(i => expected(i)._3), actual)
  }
  private def sequence[A](e: CoherentSequence.Result[A]): IO[A] = IO.fromEither(e.left.map {
    case CoherentSequence.Failure.Unsupported(stage, feature) =>
      Stop(Failure.Unsupported(stage, feature))
    case other => Stop(Failure.Rejected("coordinator", other.toString))
  })
  private def lift[A](e: Either[Failure, A]): IO[A] = IO.fromEither(e.left.map(Stop.apply))
  private def input[A](e: Either[SequenceInput.Failure, A]): IO[A] = IO.fromEither(e.left.map {
    case SequenceInput.Failure.Unsupported(feature) => Stop(Failure.Unsupported("input", feature))
    case SequenceInput.Failure.Rejected(stage, reason) => Stop(Failure.Rejected(stage, reason))
  })
  private def sameContent(a: CoherentSequence.State, b: CoherentSequence.State): Boolean =
    a.id == b.id && a.contextId == b.contextId && a.scopedAppliedTip == b.scopedAppliedTip &&
      a.acquisition.anchor == b.acquisition.anchor && a.acquisition.originals == b.acquisition.originals &&
      a.certificates.state.id == b.certificates.state.id && a.certificates.state.tip == b.certificates.state.tip &&
      a.certificates.state.counters == b.certificates.state.counters &&
      a.nonces.id == b.nonces.id && a.nonces.fields == b.nonces.fields &&
      a.nonces.certificateStateId == b.nonces.certificateStateId && a.nonces.lastSlot == b.nonces.lastSlot &&
      a.eligibility.map(e => (e.contextId, e.headers)) == b.eligibility.map(e =>
        (e.contextId, e.headers)
      ) &&
      a.ledger.id == b.ledger.id && a.ledger.outputMap == b.ledger.outputMap && a.ledger.fees == b.ledger.fees && a.ledger.slot == b.ledger.slot
  private[lab] def compareOracle(
      context: SequenceInput.Context,
      receipts: Vector[CoherentSequence.Applied],
      owned: OwnedOracle,
      maxBlocks: Int = 8
  ): Either[Failure, Boolean] = compareOracleWithin(context, receipts, owned, maxBlocks, false)
  private[lab] def compareFencedOracle(
      context: SequenceInput.Context,
      receipts: Vector[CoherentSequence.Applied],
      owned: OwnedOracle
  ): Either[Failure, Boolean] =
    compareOracleWithin(context, receipts, owned, 16, true)
  private def compareOracleWithin(
      context: SequenceInput.Context,
      receipts: Vector[CoherentSequence.Applied],
      owned: OwnedOracle,
      maxBlocks: Int,
      fenced: Boolean
  ): Either[Failure, Boolean] = protect("post-oracle") {
    require(
      maxBlocks >= 1 && maxBlocks <= (if fenced then 16
                                      else 12) && receipts.nonEmpty && receipts.size <= maxBlocks,
      "bounded oracle receipt sequence required"
    )
    val state = receipts.last.state
    val (tip, epoch) = endpoint(owned.originals("post-tips.md")).fold(f => throw Stop(f), identity)
    require(
      tip == state.certificates.state.tip && epoch == context.epoch,
      "complete post endpoint mismatch"
    )
    import ReferenceJson.{parse, field, uint}
    val protocol = parse(owned.originals("post-protocol-state.md"))
    require(uint(field(protocol, "lastSlot")) == tip.slot, "post protocol slot mismatch")
    val counters = obj(field(protocol, "oCertCounters")).map { (id, n) =>
      val number = uint(n)
      require(number <= ChainSync.UInt64.Max, "counter Word64 bound")
      hash(id, 28) -> number
    }
    require(counters == state.certificates.state.counters, "full counter map mismatch")
    val nonces = get(
      PraosNonceSnapshot.parse(
        owned.originals("post-protocol-state.md"),
        owned.pins("postProtocolSha256")
      )
    )
    val previousCompared = get(CoherentBranchCommand.compareNonces(state.nonces, nonces))
    val postLedger = parse(owned.originals("post-ledger-state.md"))
    require(uint(field(postLedger, "lastEpoch")) == context.epoch, "post ledger epoch mismatch")
    val preLedger = parse(context.originals("pre-ledger-state.md"))
    require(
      field(preLedger, "stakeDistrib") == field(postLedger, "stakeDistrib"),
      "supplied stake/registration endpoint view changed"
    )
    parse(owned.originals("post-parameters.md"))
    require(
      context.originals("pre-parameters.md") == owned.originals("post-parameters.md"),
      "parameter endpoint bytes changed"
    )
    val utxo = get(Bytes.fromHex(text(owned.originals("post-utxo-cbor.md")).trim))
    val fees = uint(field(postLedger, "stateBefore", "esLState", "utxoState", "fees"))
    (if fenced then
       Ledger.compareFencedBlockSequenceReference(receipts.map(_.ledgerObservation), utxo, fees)
     else
       Ledger.compareBlockSequenceReference(
         receipts.map(_.ledgerObservation),
         utxo,
         fees,
         maxBlocks
       )
    )
      .fold(f => throw Stop(Failure.Rejected("post-ledger-oracle", f.toString)), identity)
    previousCompared
  }
  private def applyAll(
      runtime: CoherentSequence.Runtime[IO],
      blocks: Vector[SequenceInput.Block]
  ): IO[Vector[CoherentSequence.Applied]] =
    blocks.foldLeft(IO.pure(Vector.empty[CoherentSequence.Applied])) { (done, block) =>
      for
        prior <- done
        candidate <- runtime.prepare(block).flatMap(sequence)
        applied <- runtime.publish(candidate).flatMap(sequence)
      yield prior :+ applied
    }
  def assess(context: SequenceInput.Context, owned: OwnedOracle): IO[Either[Failure, Report]] =
    val work = for
      originals <- lift(captures(owned.originals("scala-sequence-capture.md")))
      blocks <- originals.traverse(o => input(SequenceInput.block(o)))
      grouping <- lift(
        checkGrouping(
          blocks,
          Vector(
            owned.originals("signed-transaction-0-cbor.md"),
            owned.originals("signed-transaction-1-cbor.md")
          )
        )
      )
      runtime <- CoherentSequence.create[IO](context).flatMap(sequence)
      initial <- runtime.snapshot
      applied <- applyAll(runtime, blocks)
      _ <- IO {
        require(
          initial.state.contextId == context.id && initial.state.ledger.id == context.ledger.id &&
            initial.state.ledger.checkpointId == context.ledger.checkpointId && initial.state.ledger.environment.id == context.ledger.environment.id &&
            initial.state.revision == 0 && initial.state.acquisition.size == 0,
          "exact supplied ledger anchor required"
        )
        require(applied.size == originals.size, "one receipt per original required")
        applied.zip(blocks).zipWithIndex.foreach { case ((receipt, block), index) =>
          require(
            receipt.state.acquisition.originals == originals.take(index + 1) &&
              receipt.ledgerObservation.candidate.headerHash == block.header.hash &&
              receipt.ledgerObservation.candidate.transactionMemos == block.transactionMemos &&
              receipt.nonceObservation.headerHash == block.header.hash && receipt.state.revision == index + 1,
            "complete receipt/original sequence binding required"
          )
        }
      }
      // Reference post state is first parsed after every candidate has derived and published.
      previousCompared <- IO(compareOracle(context, applied, owned)).flatMap(lift)
      states = initial.state +: applied.map(_.state)
      _ <- (0 to blocks.size).toVector.traverse_ { keep =>
        for
          before <- runtime.snapshot
          restored <- runtime
            .rollbackTo(before.fence, states(keep).acquisition.tip)
            .flatMap(sequence)
          _ <- IO {
            require(
              sameContent(restored.state, states(keep)),
              "internal prefix tuple restoration mismatch"
            )
            require(
              restored.state.revision == before.state.revision + blocks.size - keep,
              "rollback revision mismatch"
            )
          }
          repeated <- applyAll(runtime, blocks.drop(keep))
          _ <- IO(repeated.zipWithIndex.foreach { (receipt, offset) =>
            require(
              sameContent(receipt.state, states(keep + offset + 1)),
              "internal prefix reapply mismatch"
            )
            require(
              receipt.state.revision == restored.state.revision + offset + 1,
              "reapply revision mismatch"
            )
          })
          finalSnapshot <- runtime.snapshot
          _ <- IO(
            require(
              sameContent(finalSnapshot.state, applied.last.state),
              "final tuple reapply mismatch"
            )
          )
        yield ()
      }
      finalSnapshot <- runtime.snapshot
    yield new Report(
      context.id,
      owned.manifestDigest,
      owned.pins("captureSha256"),
      initial.state,
      applied.last.state,
      blocks.size,
      grouping,
      previousCompared,
      finalSnapshot.state.revision
    )
    work.attempt.map {
      case Right(report)       => Right(report)
      case Left(Stop(failure)) => Left(failure)
      case Left(error) =>
        Left(
          Failure.Rejected(
            "observation",
            Option(error.getMessage).getOrElse(error.getClass.getName)
          )
        )
    }
  def observe(contextDirectory: Path, oracleDirectory: Path): IO[Either[Failure, Report]] =
    (for
      context <- IO.blocking(SequenceInput.load(contextDirectory)).flatMap(input)
      owned <- IO.blocking(loadOracle(oracleDirectory)).flatMap(lift)
      result <- assess(context, owned)
    yield result).handleError {
      case Stop(failure) => Left(failure)
      case error =>
        Left(Failure.Rejected("input", Option(error.getMessage).getOrElse(error.getClass.getName)))
    }
  private def capture(port: String, contextDirectory: Path, postDirectory: Path): IO[ExitCode] =
    for
      context <- IO.blocking(SequenceInput.load(contextDirectory)).flatMap(input)
      endpointBytes <- IO.blocking(read(postDirectory.resolve("post-tips.md"), 4194304))
      target <- lift(endpoint(endpointBytes))
      (tip, epoch) = target
      _ <- IO {
        require(
          epoch == context.epoch && tip.slot / context.nonces.context.epochLength == epoch,
          "same epoch endpoint required"
        )
        require(
          tip.slot > context.certificateSeed.tip.slot && tip.blockNo - context.certificateSeed.tip.blockNo >= 2 && tip.blockNo - context.certificateSeed.tip.blockNo <= 8,
          "two to eight successor range required"
        )
      }
      peer <- IO.fromEither(
        ReferenceHandshakeCommand
          .options(List(port, "1082026"))
          .left
          .map(new IllegalArgumentException(_))
      )
      headers <- ReferenceCaptureCommand.headersThrough(
        peer._1,
        peer._2,
        point(context.certificateSeed.tip),
        Some(point(tip)),
        8
      )
      originals <- headers.traverse(h =>
        ReferenceCaptureCommand
          .exactBlock(peer._1, peer._2, h)
          .map(b => BoundedChainFollower.Original(h.envelope, b))
      )
      acquired <- IO.fromEither(
        BoundedChainFollower
          .checked(point(context.certificateSeed.tip), originals)
          .left
          .map(new IllegalArgumentException(_))
      )
      _ <- IO {
        require(
          acquired.tip == point(
            tip
          ) && headers.last.blockNo == tip.blockNo && headers.head.blockNo == context.certificateSeed.tip.blockNo + 1,
          "complete capture endpoint/number mismatch"
        )
      }
      _ <- originals.traverse_(o =>
        IO.println(
          s"""{"record":"transfer-range-block","headerEnvelopeHex":"${o.envelope.hex}","rawBlockHex":"${o.block.hex}"}"""
        )
      )
      _ <- IO.println(
        s"""{"scope":"coherent-sequence-capture","passed":true,"capturedBlocks":${originals.size},"postTipsSha256":"${sha(
            endpointBytes
          ).hex}","acquisitionOnly":true,"fullLedgerValidated":false,"consensusValidated":false}"""
      )
    yield ExitCode.Success
  def render(r: Report): String =
    val ids = r.grouping.transactionIds.map(h => "\"" + h.hex + "\"").mkString("[", ",", "]")
    val order = r.grouping.submissionOrder.mkString("[", ",", "]")
    s"""{"scope":"coherent-sequence-observation","profile":"${CoherentSequence.ProfileId}","passed":true,"scopedSuccess":true,"referencePostStateMatched":true,"wholeTupleRollbackReapply":true,"capturedBlocks":${r.capturedBlocks},"emptyBlocks":${r.grouping.emptyBlocks},"transactionCount":2,"transactionGroupingVerified":true,"originalBodyWitnessPairsMatched":true,"submittedEnvelopeEqualityClaimed":false,"transactionBlockIndex":${r.grouping.blockIndex},"transactionIdsInBlockOrder":$ids,"submissionIndexesInBlockOrder":$order,"contextId":"${r.contextId.hex}","oracleManifestSha256":"${r.oracleManifestDigest.hex}","captureSha256":"${r.captureDigest.hex}","initialStateId":"${r.initial.id.hex}","finalStateId":"${r.finalState.id.hex}","anchorSlot":${r.initial.ledger.slot},"lastSlot":${r.finalState.ledger.slot},"initialRevision":${r.initial.revision},"appliedRevision":${r.finalState.revision},"finalReplayRevision":${r.finalRevision},"independentStateDerived":true,"finalCountersMatched":true,"fiveNonceFieldsMatched":true,"previousEpochNonceKnown":${r.finalState.nonces.fields.previousEpoch.nonEmpty},"previousEpochNonceCompared":${r.previousEpochNonceCompared},"stakeRegistrationEndpointsEqual":true,"parameterEndpointsEqual":true,"endpointEqualityProvesContinuity":false,"preStateSupplied":true,"finalReferenceCompared":true,"prefixReferenceCompared":false,"internalInvariantPrefixes":${r.capturedBlocks + 1},"fullLedgerValidated":false,"consensusValidated":false,"stateDerivedConsensus":false,"referenceSnapshotAtomic":false,"authenticatedSnapshot":false}"""
  private def quote(s: String): String = "\"" + s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""
  private def failure(f: Failure): String =
    val (outcome, stage, detail) = f match
      case Failure.Unsupported(s, d) => ("Unsupported", s, d)
      case Failure.Rejected(s, d)    => ("Rejected", s, d)
    s"""{"scope":"coherent-sequence-observation","passed":false,"scopedSuccess":false,"outcome":"$outcome","stage":${quote(
        stage
      )},"detail":${quote(detail)},"fullLedgerValidated":false,"consensusValidated":false}"""
  def run(args: List[String]): IO[ExitCode] =
    val work = args match
      case List("capture", port, context, post) => capture(port, Path.of(context), Path.of(post))
      case List("observe", context, oracle) =>
        observe(Path.of(context), Path.of(oracle)).flatMap {
          case Right(report) => IO.println(render(report)).as(ExitCode.Success)
          case Left(error)   => IO.println(failure(error)).as(ExitCode(2))
        }
      case _ =>
        IO.raiseError[ExitCode](
          Stop(
            Failure.Rejected(
              "arguments",
              "coherent-sequence capture PORT CONTEXT_DIRECTORY POST_DIRECTORY | coherent-sequence observe CONTEXT_DIRECTORY ORACLE_DIRECTORY"
            )
          )
        )
    work.timeout(120.seconds).handleErrorWith { e =>
      val f = e match
        case Stop(error) => error
        case error =>
          Failure.Rejected("command", Option(error.getMessage).getOrElse(error.getClass.getName))
      IO.println(failure(f)).as(ExitCode(2))
    }
