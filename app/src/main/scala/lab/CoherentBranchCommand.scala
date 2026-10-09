// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.header.PraosCertificateState as Certificate
import lab.header.PraosNonceEvolution as Nonces
import lab.ledger.ClusterTransition as Ledger
import scala.util.control.NonFatal

/** Offline observation: external post-state is read only after independent publication. */
object CoherentBranchCommand:
  enum Failure:
    case Unsupported(stage: String, detail: String)
    case Rejected(stage: String, detail: String)
  private final case class Stop(failure: Failure) extends RuntimeException
  private[lab] val sources: Map[String, String] = Map(
    "postUtxoCborSha256" -> "post-utxo-cbor.md",
    "postLedgerSha256" -> "post-ledger-state.md",
    "postProtocolSha256" -> "post-protocol-state.md",
    "postTipsSha256" -> "post-tips.md"
  )
  private[lab] final case class Oracle(
      utxo: Bytes,
      fees: BigInt,
      counters: Map[Bytes, BigInt],
      tip: Certificate.Point,
      epoch: BigInt,
      nonces: PraosNonceSnapshot.Snapshot,
      manifestDigest: Bytes
  )
  final class Report private[CoherentBranchCommand] (
      val inputId: Bytes,
      val transactionId: Bytes,
      val transactionSha256: Bytes,
      val headerHash: Bytes,
      val headerSha256: Bytes,
      val initialStateId: Bytes,
      val finalStateId: Bytes,
      val oracleManifestSha256: Bytes,
      val initialNonceStateId: Bytes,
      val finalNonceStateId: Bytes,
      val previousEpochNonceCompared: Boolean,
      val previousEpochNonceKnown: Boolean
  )
  private def text(b: Bytes): String =
    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b.toArray)).toString
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def hash(s: String, size: Int): Bytes =
    val b = get(Bytes.fromHex(s))
    require(b.size == size && b.hex == s, "canonical hash required")
    b
  private def read(path: Path, bound: Int): Bytes =
    val in = Files.newInputStream(path)
    val bytes =
      try in.readNBytes(bound + 1)
      finally in.close()
    require(bytes.nonEmpty && bytes.length <= bound, "nonempty bounded source required")
    Bytes.fromArray(bytes)
  private def obj(j: ReferenceJson.Json): Map[String, ReferenceJson.Json] = j match
    case ReferenceJson.Json.Obj(fields) => fields
    case _                              => throw new IllegalArgumentException("object required")

  private[lab] def bindOracle(
      manifest: Bytes,
      originals: Map[String, Bytes]
  ): Either[String, Oracle] =
    try
      require(manifest.size > 0 && manifest.size <= 8192, "manifest bound")
      val entries = text(manifest).linesIterator.map { line =>
        val parts = line.split("\t", -1)
        require(parts.length == 2 && parts.forall(_.nonEmpty), "strict manifest TSV required")
        parts(0) -> parts(1)
      }.toVector
      require(entries.map(_._1).distinct.size == entries.size, "duplicate manifest field")
      val fields = entries.toMap
      require(fields.keySet == sources.keySet + "format", "exact manifest fields required")
      require(fields("format") == "coherent-branch-oracle-v1", "oracle format required")
      require(originals.keySet == sources.values.toSet, "exact oracle sources required")
      sources.foreach { (key, file) =>
        val raw = originals(file)
        require(raw.size > 0 && raw.size <= 4194304, "oracle source bound")
        require(
          ClusterHeaderObservation.sha256(raw) == hash(fields(key), 32),
          "digest mismatch: " + file
        )
      }
      import ReferenceJson.{field, uint, string, array}
      val nonceSnapshot = get(
        PraosNonceSnapshot.parse(
          originals("post-protocol-state.md"),
          hash(fields("postProtocolSha256"), 32)
        )
      )
      val protocol = ReferenceJson.parse(originals("post-protocol-state.md"))
      val ledger = ReferenceJson.parse(originals("post-ledger-state.md"))
      val tips = array(ReferenceJson.parse(originals("post-tips.md")))
      require(tips.size >= 2 && tips.size <= 32, "bounded stable tip brackets required")
      val points = tips.map { t =>
        require(string(field(t, "era")) == "Conway", "Conway tip required")
        (
          Certificate.Point(
            hash(string(field(t, "hash")), 32),
            uint(field(t, "slot")),
            uint(field(t, "block"))
          ),
          uint(field(t, "epoch"))
        )
      }
      require(points.distinct.size == 1, "unstable post tip brackets")
      val (tip, epoch) = points.head
      require(uint(field(protocol, "lastSlot")) == tip.slot, "post protocol slot mismatch")
      require(uint(field(ledger, "lastEpoch")) == epoch, "post ledger epoch mismatch")
      val counters = obj(field(protocol, "oCertCounters")).map { (id, n) =>
        val counter = uint(n)
        require(counter <= lab.network.ChainSync.UInt64.Max, "counter Word64 bound")
        hash(id, 28) -> counter
      }
      val utxo = get(Bytes.fromHex(text(originals("post-utxo-cbor.md")).trim))
      Right(
        Oracle(
          utxo,
          uint(field(ledger, "stateBefore", "esLState", "utxoState", "fees")),
          counters,
          tip,
          epoch,
          nonceSnapshot,
          ClusterHeaderObservation.sha256(manifest)
        )
      )
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))

  /** Comparison only: never repairs or replaces independently published nonce state. */
  private[lab] def compareNonces(
      derived: Nonces.State,
      observed: PraosNonceSnapshot.Snapshot
  ): Either[String, Boolean] =
    if observed.lastSlot != derived.lastSlot ||
      observed.fields.copy(previousEpoch = None) != derived.fields.copy(previousEpoch = None)
    then Left("five nonce fields or lastSlot mismatch")
    else
      (observed.fields.previousEpoch, derived.fields.previousEpoch) match
        case (Some(a), Some(b)) if a != b => Left("known previous epoch nonce mismatch")
        case (Some(_), Some(_))           => Right(true)
        case _                            => Right(false)

  private def loadOracle(directory: Path): Oracle =
    get(
      bindOracle(
        read(directory.resolve("coherent-branch-oracle.md"), 8192),
        sources.values.map(n => n -> read(directory.resolve(n), 4194304)).toMap
      )
    )

  private def branch[A](value: Either[CoherentBranch.Failure, A]): IO[A] = IO.fromEither(
    value.left.map {
      case CoherentBranch.Failure.Unsupported(stage, detail) =>
        Stop(Failure.Unsupported(stage, detail))
      case other => Stop(Failure.Rejected("coordinator", other.toString))
    }
  )
  private def sameTuple(a: CoherentBranch.State, b: CoherentBranch.State): Boolean =
    a.id == b.id && a.contextId == b.contextId &&
      a.acquisition.anchor == b.acquisition.anchor && a.acquisition.tip == b.acquisition.tip &&
      a.acquisition.originals == b.acquisition.originals &&
      a.certificates.initial.id == b.certificates.initial.id &&
      a.certificates.steps.map(_.after.id) == b.certificates.steps.map(_.after.id) &&
      a.certificates.state.id == b.certificates.state.id &&
      a.certificates.state.tip == b.certificates.state.tip &&
      a.certificates.state.counters == b.certificates.state.counters &&
      a.nonces.id == b.nonces.id && a.nonces.contextId == b.nonces.contextId &&
      a.nonces.certificateStateId == b.nonces.certificateStateId &&
      a.nonces.lastSlot == b.nonces.lastSlot && a.nonces.fields == b.nonces.fields &&
      a.eligibility.map(e => (e.contextId, e.headers)) == b.eligibility.map(e =>
        (e.contextId, e.headers)
      ) &&
      a.ledger.environment.id == b.ledger.environment.id && a.ledger.checkpointId == b.ledger.checkpointId &&
      a.ledger.id == b.ledger.id && a.ledger.outputMap == b.ledger.outputMap &&
      a.ledger.fees == b.ledger.fees && a.ledger.slot == b.ledger.slot

  def observe(inputDirectory: Path, oracleDirectory: Path): IO[Either[Failure, Report]] =
    val work = for
      input <- IO
        .blocking(BranchInput.load(inputDirectory))
        .flatMap(e =>
          IO.fromEither(e.left.map {
            case BranchInput.Failure.Unsupported(detail) =>
              Stop(Failure.Unsupported("input", detail))
            case BranchInput.Failure.Rejected(stage, detail) =>
              Stop(Failure.Rejected(stage, detail))
          })
        )
      runtime <- CoherentBranch.create[IO](input).flatMap(branch)
      before <- runtime.snapshot
      candidate <- runtime.prepare(input).flatMap(branch)
      prepared <- runtime.snapshot
      accepted <- runtime.publish(candidate).flatMap(branch)
      // No oracle read, parse or projection is allowed above this point.
      oracle <- IO.blocking(loadOracle(oracleDirectory)).adaptError { case NonFatal(e) =>
        Stop(Failure.Rejected("oracle", Option(e.getMessage).getOrElse(e.getClass.getName)))
      }
      nonceCompared <- IO.fromEither(
        compareNonces(accepted.state.nonces, oracle.nonces).left.map(detail =>
          Stop(Failure.Rejected("oracle-nonce", detail))
        )
      )
      _ <- IO {
        require(
          accepted.state.nonces.certificateStateId == accepted.state.certificates.state.id &&
            accepted.state.nonces.lastSlot == accepted.state.ledger.slot &&
            accepted.nonceObservation.before.id == before.nonces.id &&
            accepted.nonceObservation.after.id == accepted.state.nonces.id,
          "published nonce/certificate/ledger tuple mismatch"
        )
        require(
          sameTuple(before, prepared) && before.revision == 0 && prepared.revision == 0,
          "prepare published state"
        )
        require(accepted.state.revision == 1, "publish revision")
        require(
          oracle.tip == accepted.state.certificates.state.tip,
          "oracle complete point mismatch"
        )
        require(
          oracle.epoch == accepted.state.ledger.environment.epoch,
          "oracle candidate epoch mismatch"
        )
        require(
          oracle.counters == accepted.state.certificates.state.counters,
          "full oracle counter map mismatch"
        )
        Ledger
          .compareReference(accepted.ledgerObservation, oracle.utxo, oracle.fees)
          .fold(e => throw Stop(Failure.Rejected("oracle-ledger", e.toString)), identity)
      }
      restored <- runtime.rollback(accepted).flatMap(branch)
      staleCandidate <- runtime.publish(candidate)
      staleUndo <- runtime.rollback(accepted)
      afterStale <- runtime.snapshot
      fresh <- runtime.prepare(input).flatMap(branch)
      reapplied <- runtime.publish(fresh).flatMap(branch)
      _ <- IO {
        require(
          sameTuple(before, restored) && restored.revision == 2,
          "whole tuple rollback mismatch"
        )
        require(
          staleCandidate == Left(CoherentBranch.Failure.StaleCandidate),
          "old candidate accepted"
        )
        require(staleUndo == Left(CoherentBranch.Failure.StaleUndo), "old undo accepted")
        require(
          sameTuple(restored, afterStale) && afterStale.revision == 2,
          "stale operation mutated tuple"
        )
        require(
          sameTuple(accepted.state, reapplied.state) && reapplied.state.revision == 3,
          "whole tuple reapply mismatch"
        )
      }
    yield new Report(
      input.id,
      accepted.ledgerObservation.candidate.transactionId,
      ClusterHeaderObservation.sha256(input.transaction),
      input.header.hash,
      ClusterHeaderObservation.sha256(input.header.raw),
      before.id,
      accepted.state.id,
      oracle.manifestDigest,
      before.nonces.id,
      accepted.state.nonces.id,
      nonceCompared,
      accepted.state.nonces.fields.previousEpoch.nonEmpty
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

  private def quote(s: String): String = "\"" + s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""
  def render(r: Report): String =
    s"""{"profile":"${CoherentBranch.ProfileId}","scopedSuccess":true,"inputId":"${r.inputId.hex}","transactionId":"${r.transactionId.hex}","originalTransactionSha256":"${r.transactionSha256.hex}","headerHash":"${r.headerHash.hex}","originalHeaderSha256":"${r.headerSha256.hex}","initialStateId":"${r.initialStateId.hex}","finalStateId":"${r.finalStateId.hex}","oracleManifestSha256":"${r.oracleManifestSha256.hex}","independentStateDerived":true,"referencePostStateMatched":true,"wholeTupleRollbackReapply":true,"nonceStateDerived":true,"eligibilityNonceFromDerivedState":true,"fiveNonceFieldsMatched":true,"initialNonceStateId":"${r.initialNonceStateId.hex}","finalNonceStateId":"${r.finalNonceStateId.hex}","previousEpochNonceCompared":${r.previousEpochNonceCompared},"previousEpochNonceKnown":${r.previousEpochNonceKnown},"epochTickChecked":false,"revisions":[0,1,2,3],"fullLedgerValidated":false,"consensusValidated":false,"authenticatedSnapshot":false,"referenceSnapshotAtomic":false}"""
  private def renderFailure(failure: Failure): String =
    val (kind, stage, detail) = failure match
      case Failure.Unsupported(s, d) => ("Unsupported", s, d)
      case Failure.Rejected(s, d)    => ("Rejected", s, d)
    s"""{"profile":"${CoherentBranch.ProfileId}","scopedSuccess":false,"outcome":"$kind","stage":${quote(
        stage
      )},"detail":${quote(detail)},"fullLedgerValidated":false,"consensusValidated":false}"""
  def run(args: List[String]): IO[ExitCode] = args match
    case List(input, oracle) =>
      observe(Path.of(input), Path.of(oracle)).flatMap {
        case Right(report) => IO.println(render(report)).as(ExitCode.Success)
        case Left(failure) => IO.println(renderFailure(failure)).as(ExitCode(2))
      }
    case _ =>
      IO.println(
        renderFailure(
          Failure.Rejected("arguments", "usage: coherent-branch INPUT_DIRECTORY ORACLE_DIRECTORY")
        )
      ).as(ExitCode(2))
