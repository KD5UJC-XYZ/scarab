// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import scala.util.control.NonFatal

/** Pure restricted UTxO/fee transition. No observed post-state, consensus, ticking, persistence,
  * epoch derivation, or full-ledger claim. Caller owns current state and bounded undo history.
  */
object ClusterTransition:
  val ProfileId = "conway-pv9-ada-interval-native-replay-v1"
  val MaxStateBytes = 1048576
  val MaxEntries = 4096
  val MaxRevision = (BigInt(1) << 64) - 1
  val MaxFees = (BigInt(1) << 128) - 1
  enum Failure:
    case Unsupported(detail: String)
    case DecodeRejected(detail: String)
    case Malformed(detail: String)
    case ResourceLimit(detail: String)
    case Rejected(predicate: NativeSpending.Error)
    case StaleState(detail: String)
    case InternalFailure(kind: String)
  type Checked[A] = Either[Failure, A]

  private def protect[A](body: => Checked[A]): Checked[A] =
    try body
    catch
      case _: NullPointerException => Left(Failure.Malformed("null input"))
      case NonFatal(error)         => Left(Failure.InternalFailure(error.getClass.getName))

  final class Environment private[ClusterTransition] (
      val id: Bytes,
      val genesisDigest: Bytes,
      val parameterDigest: Bytes,
      val networkMagic: Long,
      val epoch: BigInt,
      val feeParameters: FeeSize.Parameters,
      val minimumOutputParameters: MinimumOutput.Parameters
  )
  final class State private[ClusterTransition] (
      val environment: Environment,
      val checkpointId: Bytes,
      val outputMap: Bytes,
      val fees: BigInt,
      val slot: BigInt,
      val id: Bytes,
      val revision: BigInt,
      private[ClusterTransition] val entries: Map[TxIn, Node],
      private[ClusterTransition] val head: Option[Bytes]
  ):
    val profileId = ProfileId
    val fullLedgerValidated = false
    def size: Int = entries.size

  private[lab] final case class LocalImage(
      environmentId: Bytes,
      checkpointId: Bytes,
      outputMap: Bytes,
      fees: BigInt,
      slot: BigInt,
      id: Bytes,
      head: Bytes
  )
  private[lab] def localImage(value: State): Checked[LocalImage] =
    value.head
      .toRight(Failure.Malformed("derived ledger head required"))
      .map(h =>
        LocalImage(
          value.environment.id,
          value.checkpointId,
          value.outputMap,
          value.fees,
          value.slot,
          value.id,
          h
        )
      )

  /** Trusted local lineage restoration, distinct from a new supplied checkpoint. */
  private[lab] def trustedRestoreLocal(
      env: Environment,
      originalCheckpointId: Bytes,
      image: LocalImage,
      revision: BigInt
  ): Checked[State] = protect {
    for
      _ <- Either.cond(
        image.environmentId == env.id && image.checkpointId == originalCheckpointId &&
          image.id.size == 32 && image.head.size == 32,
        (),
        Failure.Malformed("local ledger identity")
      )
      entries <- NativeSpending.snapshot(image.outputMap).left.map(local)
      _ <- entries.values.foldLeft[Checked[Unit]](Right(())) { (acc, node) =>
        acc.flatMap(_ =>
          NativeSpending.output(node, true).left.map(Failure.Unsupported.apply).map(_ => ())
        )
      }
      restored <- state(
        env,
        originalCheckpointId,
        entries,
        image.fees,
        image.slot,
        revision,
        Some(image.head)
      )
      _ <- Either.cond(
        restored.id == image.id && restored.outputMap == image.outputMap,
        (),
        Failure.Malformed("local ledger content identity/original framing")
      )
    yield restored
  }

  final class Candidate private[ClusterTransition] (
      private[ClusterTransition] val before: State,
      private[ClusterTransition] val after: State,
      val originalTransaction: Bytes,
      val transactionId: Bytes,
      val fee: BigInt,
      val minimum: MinimumOutput.Receipt,
      val nativeAdmission: Option[NativeSpending.Admission],
      val spent: Set[TxIn],
      val created: Set[TxIn]
  ):
    val profileId = ProfileId
    val credentialBound = true
    val fullLedgerValidated = false
    def outputMap: Bytes = after.outputMap
    def fees: BigInt = after.fees
    def slot: BigInt = after.slot
    def stateId: Bytes = after.id

  /** One bounded prior snapshot; State holds only a head hash, never a recursive undo chain. */
  final class Undo private[ClusterTransition] (
      private[ClusterTransition] val before: State,
      private[ClusterTransition] val afterId: Bytes,
      private[ClusterTransition] val transitionId: Bytes
  )
  final class Applied private[ClusterTransition] (
      val state: State,
      val undo: Undo,
      val candidate: Candidate
  ):
    val profileId = ProfileId
    val credentialBound = true
    val fullLedgerValidated = false

  /** Transaction memos can be reconstructed from original block components. Internal transaction
    * candidates never escape this block capability; there is one externally visible revision.
    */
  final class BlockCandidate private[ClusterTransition] (
      private[ClusterTransition] val before: State,
      private[ClusterTransition] val after: State,
      val headerHash: Bytes,
      val transactionMemos: Vector[Bytes],
      val transactionIds: Vector[Bytes],
      val created: Set[TxIn],
      private[ledger] val syntheticBoundary: Option[(Bytes, BigInt)] = None
  ):
    val profileId = ProfileId
    val credentialBound = true
    val fullLedgerValidated = false
    def outputMap: Bytes = after.outputMap
    def fees: BigInt = after.fees
    def slot: BigInt = after.slot
    def stateId: Bytes = after.id

  final class BlockApplied private[ClusterTransition] (
      val state: State,
      val undo: Undo,
      val candidate: BlockCandidate
  ):
    val profileId = ProfileId
    val credentialBound = true
    val fullLedgerValidated = false

  private def text(s: String): Bytes = Bytes.fromArray(s.getBytes(UTF_8))
  private def digest(domain: String, fields: Vector[Bytes]): Bytes =
    val md = MessageDigest.getInstance("SHA-256")
    (text(domain) +: fields).foreach { b =>
      md.update(
        Array((b.size >>> 24).toByte, (b.size >>> 16).toByte, (b.size >>> 8).toByte, b.size.toByte)
      )
      md.update(b.toArray)
    }
    Bytes.fromArray(md.digest())

  private def local(error: NativeSpending.Error): Failure = error match
    case NativeSpending.Error.UnsupportedProfile(reason) => Failure.Unsupported(reason)
    case NativeSpending.Error.UnsupportedInput(input, reason) =>
      Failure.Unsupported(s"input $input: $reason")
    case NativeSpending.Error.WitnessProfileRejected(reason) =>
      Failure.Unsupported(s"witness profile: $reason")
    case NativeSpending.Error.DecodeRejected(reason) => Failure.DecodeRejected(reason)
    case NativeSpending.Error.Malformed(reason)      => Failure.Malformed(reason)
    case NativeSpending.Error.ResourceLimit(reason)  => Failure.ResourceLimit(reason)
    case other                                       => Failure.Rejected(other)

  /** Numeric and structural checks only: source/epoch attribution remains caller responsibility. */
  def environment(
      genesisDigest: Bytes,
      parameterDigest: Bytes,
      networkMagic: Long,
      epoch: BigInt,
      major: Int,
      minor: Int,
      feePerByte: BigInt,
      feeFixed: BigInt,
      maxTxSize: BigInt,
      coinsPerUTxOByte: BigInt
  ): Checked[Environment] = protect {
    for
      _ <- Either.cond(
        genesisDigest.size == 32 && parameterDigest.size == 32,
        (),
        Failure.Malformed("32-byte context digests required")
      )
      _ <- Either.cond(
        networkMagic > 0 && networkMagic <= 0xffffffffL && major == 9 && minor == 0,
        (),
        Failure.Unsupported("private testnet Conway PV9.0 context required")
      )
      _ <- Either.cond(
        epoch >= 0 && epoch <= MaxRevision && maxTxSize > 0,
        (),
        Failure.Malformed("epoch/maxTxSize range")
      )
      fee <- FeeSize.Parameters
        .create("Conway", major, feePerByte, feeFixed, maxTxSize)
        .left
        .map(e => Failure.Malformed(e.toString))
      minimum <- MinimumOutput.Parameters
        .checked("Conway", major, minor, coinsPerUTxOByte)
        .left
        .map(Failure.Malformed.apply)
      id = digest(
        ProfileId + ":environment",
        Vector(
          genesisDigest,
          parameterDigest,
          text(networkMagic.toString),
          text(epoch.toString),
          text(feePerByte.toString),
          text(feeFixed.toString),
          text(maxTxSize.toString),
          text(coinsPerUTxOByte.toString)
        )
      )
    yield new Environment(id, genesisDigest, parameterDigest, networkMagic, epoch, fee, minimum)
  }

  def fromContext(
      context: ClusterTransfer.Context,
      minimum: MinimumOutput.Parameters
  ): Checked[Environment] = protect {
    environment(
      context.genesisDigest,
      context.parameterDigest,
      context.networkMagic,
      context.epoch,
      9,
      0,
      context.parameters.feePerByte,
      context.parameters.feeFixed,
      context.parameters.maxTxSize,
      minimum.coinsPerUTxOByte
    )
  }

  private def head(major: Int, value: Int): Vector[Byte] =
    if value < 24 then Vector(((major << 5) | value).toByte)
    else if value <= 255 then Vector(((major << 5) | 24).toByte, value.toByte)
    else Vector(((major << 5) | 25).toByte, (value >>> 8).toByte, value.toByte)

  /** Canonical map/reference framing; original output spans are copied without re-encoding. */
  private def encode(entries: Map[TxIn, Node]): Checked[Bytes] =
    if entries.size > MaxEntries then Left(Failure.ResourceLimit("4096 UTxO entries maximum"))
    else
      val size = head(5, entries.size).size.toLong + entries.iterator.map { (ref, out) =>
        35L + head(0, ref.index.toInt).size + out.original.size
      }.sum
      if size > MaxStateBytes then Left(Failure.ResourceLimit("encoded state exceeds 1 MiB"))
      else
        val bytes = Vector.newBuilder[Byte]
        bytes ++= head(5, entries.size)
        entries.toVector.sortBy((ref, _) => (ref.id.hex, ref.index)).foreach { (ref, out) =>
          bytes ++= Vector(0x82.toByte, 0x58.toByte, 0x20.toByte)
          bytes ++= ref.id.value
          bytes ++= head(0, ref.index.toInt)
          bytes ++= out.original.value
        }
        val raw = Bytes(bytes.result())
        NativeSpending.decode(raw).left.map(local).map(_ => raw)

  private def state(
      env: Environment,
      checkpoint: Bytes,
      entries: Map[TxIn, Node],
      fees: BigInt,
      slot: BigInt,
      revision: BigInt,
      top: Option[Bytes]
  ): Checked[State] =
    for
      _ <- Either.cond(
        fees >= 0 && fees <= MaxFees,
        (),
        Failure.ResourceLimit("uint128 fee pot required")
      )
      _ <- Either.cond(
        slot >= 0 && slot <= MaxRevision && revision >= 0 && revision <= MaxRevision,
        (),
        Failure.ResourceLimit("uint64 slot/revision required")
      )
      raw <- encode(entries)
      id = digest(
        ProfileId + ":state",
        Vector(env.id, checkpoint, raw, text(fees.toString), text(slot.toString))
      )
    yield new State(env, checkpoint, raw, fees, slot, id, revision, entries, top)

  def checkpoint(
      env: Environment,
      rawUtxo: Bytes,
      fees: BigInt,
      slot: BigInt,
      attributionDigest: Bytes
  ): Checked[State] = protect {
    for
      _ <- Either.cond(
        attributionDigest.size == 32,
        (),
        Failure.Malformed("32-byte checkpoint attribution required")
      )
      entries <- NativeSpending.snapshot(rawUtxo).left.map(local)
      _ <- entries.values.foldLeft[Checked[Unit]](Right(())) { (acc, node) =>
        for _ <- acc; _ <- NativeSpending.output(node, true).left.map(Failure.Unsupported.apply)
        yield ()
      }
      raw <- encode(entries)
      id = digest(
        ProfileId + ":checkpoint",
        Vector(env.id, attributionDigest, raw, text(fees.toString), text(slot.toString))
      )
      result <- state(env, id, entries, fees, slot, 0, None)
    yield result
  }

  /** Recovery-only supplied anchor bridge. This confers no applied-state authority. */
  private[lab] def recoveryAnchor(anchor: State, offset: BigInt): Checked[State] = protect {
    if anchor.revision != 0 || anchor.head.nonEmpty then
      Left(Failure.StaleState("recovery requires a fresh supplied anchor"))
    else
      state(
        anchor.environment,
        anchor.checkpointId,
        anchor.entries,
        anchor.fees,
        anchor.slot,
        offset,
        None
      )
  }

  private def projection(original: Bytes): Checked[(Coverage.Projection, Bytes)] =
    for
      root <- NativeSpending.decode(original).left.map(local)
      raw <- root.value match
        case V.Arr(Vector(body, _, _, _)) =>
          body.value match
            case V.Map(fields) =>
              val retained = fields.filter((key, _) =>
                Set[V](V.UInt(0), V.UInt(1), V.UInt(2)).contains(key.value)
              )
              Right(
                Bytes(
                  Vector(0x84.toByte, 0xa3.toByte) ++ retained.flatMap((k, v) =>
                    k.original.value ++ v.original.value
                  ) ++
                    Vector(0xa0.toByte, 0xf5.toByte, 0xf6.toByte)
                )
              )
            case _ => Left(Failure.Malformed("body map required"))
        case _ => Left(Failure.Malformed("four-field transaction required"))
      tx <- Coverage.decode(raw).left.map {
        case CoverageError.TypedUnsupported(reason) => Failure.Unsupported(reason)
        case other                                  => Failure.Malformed(other.toString)
      }
      _ <- Either.cond(
        tx.inputs.size <= 128 && tx.outputs.size <= 128,
        (),
        Failure.ResourceLimit("128 inputs/outputs maximum")
      )
    yield (tx, raw)

  def prepare(before: State, original: Bytes, inclusionSlot: BigInt): Checked[Candidate] = protect {
    for
      _ <- Either.cond(
        before.revision < MaxRevision,
        (),
        Failure.ResourceLimit("revision exhausted")
      )
      _ <- Either.cond(
        inclusionSlot >= before.slot && inclusionSlot <= MaxRevision,
        (),
        Failure.Unsupported("inclusion slot must be uint64 and nondecreasing")
      )
      interval <- ValidityInterval.decode(original).left.map(Failure.Unsupported.apply)
      valid <- ValidityInterval.atSlot(interval, inclusionSlot).left.map(Failure.Malformed.apply)
      _ <- Either.cond(
        valid.satisfied,
        (),
        Failure.Rejected(NativeSpending.Error.OutsideValidityInterval)
      )
      semantic <- projection(original)
      (tx, projectedRaw) = semantic
      missing = tx.inputs -- before.entries.keySet
      _ <- Either.cond(
        missing.isEmpty,
        (),
        Failure.Rejected(NativeSpending.Error.UnresolvedInputs(missing))
      )
      consumed <- tx.inputs.toVector
        .foldLeft[Checked[Vector[NativeSpending.Output]]](Right(Vector.empty)) { (acc, ref) =>
          for
            previous <- acc;
            out <- NativeSpending
              .output(before.entries(ref), true)
              .left
              .map(Failure.Unsupported.apply)
          yield previous :+ out
        }
      native <-
        if consumed.exists(_.script) then
          NativeSpending.check(original, before.outputMap).left.map(local).map(Some(_))
        else
          for
            inspection <- NativeScriptWitnesses.inspect(original).left.map {
              case "native witness signature rejected" =>
                Failure.Rejected(NativeSpending.Error.InvalidSignature)
              case other => Failure.Unsupported(s"witness profile: $other")
            }
            extra = inspection.scripts.map(_.hash).toSet
            _ <- Either.cond(
              extra.isEmpty,
              (),
              Failure.Rejected(NativeSpending.Error.ExtraneousScripts(extra))
            )
            missingKeys = consumed.map(_.credential).toSet -- inspection.verifiedKeys.hashes
            _ <- Either.cond(
              missingKeys.isEmpty,
              (),
              Failure.Rejected(NativeSpending.Error.MissingKeys(missingKeys))
            )
          yield None
      minimum <- MinimumOutput
        .check(before.environment.minimumOutputParameters, projectedRaw)
        .left
        .map(Failure.Unsupported.apply)
      _ <- Either.cond(
        minimum.satisfied,
        (),
        Failure.Rejected(NativeSpending.Error.MinimumOutputFailed)
      )
      total = consumed.map(_.coin).sum
      produced = tx.outputs.map(_.value.lovelace).sum + tx.fee
      _ <- Either.cond(
        total == produced,
        (),
        Failure.Rejected(NativeSpending.Error.ValueNotConserved(total, produced))
      )
      originalRoot <- NativeSpending.decode(original).left.map(local)
      witnessBytes <- originalRoot.value match
        case V.Arr(Vector(_, witnesses, _, _)) => Right(witnesses.original)
        case _ => Left(Failure.Malformed("four-field transaction required"))
      size <- FeeSize
        .componentSize(BigInt(interval.originalBody.size), BigInt(witnessBytes.size))
        .left
        .map(e => Failure.ResourceLimit(e.toString))
      p = before.environment.feeParameters
      requiredFee = size * p.feePerByte + p.feeFixed
      _ <- Either.cond(
        tx.fee >= requiredFee,
        (),
        Failure.Rejected(NativeSpending.Error.FeeTooSmall(tx.fee, requiredFee))
      )
      _ <- Either.cond(
        size <= p.maxTxSize,
        (),
        Failure.Rejected(NativeSpending.Error.TransactionTooLarge(size, p.maxTxSize))
      )
      created <- tx.outputs.zipWithIndex.foldLeft[Checked[Map[TxIn, Node]]](Right(Map.empty)) {
        case (acc, (out, index)) =>
          for
            previous <- acc
            ref <- TxIn
              .create(interval.transactionId, BigInt(index))
              .left
              .map(e => Failure.Malformed(e.toString))
            _ <- Either.cond(
              !before.entries.contains(ref),
              (),
              Failure.Rejected(NativeSpending.Error.StateMismatch("output collision"))
            )
            node <- NativeSpending.decode(out.original).left.map(local)
            _ <- NativeSpending.output(node, false).left.map(Failure.Unsupported.apply)
          yield previous.updated(ref, node)
      }
      nextEntries = (before.entries -- tx.inputs) ++ created
      tentative <- state(
        before.environment,
        before.checkpointId,
        nextEntries,
        before.fees + tx.fee,
        inclusionSlot,
        before.revision + 1,
        None
      )
      transition = digest(
        ProfileId + ":transition",
        Vector(
          before.checkpointId,
          before.id,
          text(before.revision.toString),
          before.head.getOrElse(Bytes.empty),
          tentative.id,
          original,
          text(inclusionSlot.toString)
        )
      )
      after <- state(
        before.environment,
        before.checkpointId,
        nextEntries,
        tentative.fees,
        inclusionSlot,
        tentative.revision,
        Some(transition)
      )
    yield new Candidate(
      before,
      after,
      original,
      interval.transactionId,
      tx.fee,
      minimum,
      native,
      tx.inputs,
      created.keySet
    )
  }

  def commit(current: State, candidate: Candidate): Checked[Applied] = protect {
    val before = candidate.before
    if current.checkpointId != before.checkpointId || current.environment.id != before.environment.id ||
      current.id != before.id || current.revision != before.revision || current.head != before.head
    then
      Left(
        Failure.StaleState("candidate belongs to another checkpoint, content, revision, or branch")
      )
    else
      Right(
        new Applied(
          candidate.after,
          new Undo(before, candidate.after.id, candidate.after.head.get),
          candidate
        )
      )
  }

  def applyTransaction(before: State, original: Bytes, inclusionSlot: BigInt): Checked[Applied] =
    protect {
      prepare(before, original, inclusionSlot).flatMap(commit(before, _))
    }

  /** Restricted block fold: zero through sixteen ordered memos, each at most 64 KiB and at most 1
    * MiB together. Header/body correspondence and same-epoch context are caller checks. Private
    * intermediate states reuse the starting revision so a block consumes exactly one revision even
    * near the uint64 limit; no intermediate candidate or undo escapes.
    */
  def prepareBlock(
      before: State,
      headerHash: Bytes,
      transactionMemos: Vector[Bytes],
      inclusionSlot: BigInt
  ): Checked[BlockCandidate] = protect {
    for
      _ <- Either.cond(headerHash.size == 32, (), Failure.Malformed("32-byte header hash required"))
      _ <- Either.cond(
        transactionMemos.size <= 16 && transactionMemos.forall(_.size <= 65536) &&
          transactionMemos.map(_.size.toLong).sum <= 1048576L,
        (),
        Failure.ResourceLimit("block requires at most 16 memos, 64 KiB each and 1 MiB total")
      )
      _ <- Either.cond(
        before.revision < MaxRevision,
        (),
        Failure.ResourceLimit("revision exhausted")
      )
      _ <- Either.cond(
        inclusionSlot > before.slot && inclusionSlot <= MaxRevision,
        (),
        Failure.Unsupported("block inclusion slot must be uint64 and strictly increasing")
      )
      folded <- transactionMemos.foldLeft[Checked[(State, Vector[Bytes], Set[TxIn])]](
        Right((before, Vector.empty, Set.empty))
      ) { (acc, memo) =>
        for
          prior <- acc
          (current, ids, created) = prior
          intermediate <- state(
            current.environment,
            current.checkpointId,
            current.entries,
            current.fees,
            current.slot,
            before.revision,
            current.head
          )
          candidate <- prepare(intermediate, memo, inclusionSlot)
        yield (
          candidate.after,
          ids :+ candidate.transactionId,
          (created -- candidate.spent) ++ candidate.created
        )
      }
      (last, ids, created) = folded
      tentative <- state(
        before.environment,
        before.checkpointId,
        last.entries,
        last.fees,
        inclusionSlot,
        before.revision + 1,
        None
      )
      transition = digest(
        ProfileId + ":block-transition",
        Vector(
          before.checkpointId,
          before.id,
          text(before.revision.toString),
          before.head.getOrElse(Bytes.empty),
          headerHash,
          text(inclusionSlot.toString),
          text(transactionMemos.size.toString),
          tentative.id
        ) ++ transactionMemos
      )
      after <- state(
        before.environment,
        before.checkpointId,
        last.entries,
        last.fees,
        inclusionSlot,
        before.revision + 1,
        Some(transition)
      )
    yield new BlockCandidate(before, after, headerHash, transactionMemos, ids, created)
  }

  /** Internal synthetic boundary plus body capability. Parameters remain the same objects; the only
    * private intermediate changes are epoch and the checked boundary fee pot. One commit/undo
    * covers boundary and body together, consuming one ledger revision.
    */
  private[lab] def prepareSyntheticSuccessorBlock(
      before: State,
      expectedEpoch: BigInt,
      boundaryFees: BigInt,
      boundaryId: Bytes,
      headerHash: Bytes,
      transactionMemos: Vector[Bytes],
      inclusionSlot: BigInt
  ): Checked[BlockCandidate] = protect {
    for
      _ <- Either.cond(
        expectedEpoch == before.environment.epoch + 1 && expectedEpoch <= MaxRevision &&
          boundaryId.size == 32,
        (),
        Failure.Malformed("synthetic exact successor epoch/boundary identity")
      )
      env = before.environment
      nextEnvironment = new Environment(
        digest(
          ProfileId + ":synthetic-successor-environment",
          Vector(
            env.id,
            before.id,
            boundaryId,
            text(expectedEpoch.toString)
          )
        ),
        env.genesisDigest,
        env.parameterDigest,
        env.networkMagic,
        expectedEpoch,
        env.feeParameters,
        env.minimumOutputParameters
      )
      boundary <- state(
        nextEnvironment,
        before.checkpointId,
        before.entries,
        boundaryFees,
        before.slot,
        before.revision,
        before.head
      )
      block <- prepareBlock(boundary, headerHash, transactionMemos, inclusionSlot)
    yield new BlockCandidate(
      before,
      block.after,
      headerHash,
      transactionMemos,
      block.transactionIds,
      block.created,
      Some((boundaryId, boundaryFees))
    )
  }

  def commitBlock(current: State, candidate: BlockCandidate): Checked[BlockApplied] = protect {
    val before = candidate.before
    if current.checkpointId != before.checkpointId || current.environment.id != before.environment.id ||
      current.id != before.id || current.revision != before.revision || current.head != before.head
    then
      Left(Failure.StaleState("block belongs to another checkpoint, content, revision, or branch"))
    else
      Right(
        new BlockApplied(
          candidate.after,
          new Undo(before, candidate.after.id, candidate.after.head.get),
          candidate
        )
      )
  }

  def applyBlock(
      before: State,
      headerHash: Bytes,
      transactionMemos: Vector[Bytes],
      inclusionSlot: BigInt
  ): Checked[BlockApplied] = protect {
    prepareBlock(before, headerHash, transactionMemos, inclusionSlot).flatMap(
      commitBlock(before, _)
    )
  }

  /** The observed map is only an oracle; it cannot change independently derived block state. */
  def compareBlockReference(
      applied: BlockApplied,
      observedUtxo: Bytes,
      observedFees: BigInt
  ): Checked[Unit] = protect {
    compareOutputs(applied.state, applied.candidate.created, observedUtxo, observedFees)
  }

  /** Compare only after deriving a continuous bounded sequence. Outputs created anywhere in this
    * sequence compare semantically if still unspent; checkpoint survivors retain exact bytes.
    * Receipt continuity includes revision and private branch head, not merely content identity.
    */
  def compareBlockSequenceReference(
      applied: Vector[BlockApplied],
      observedUtxo: Bytes,
      observedFees: BigInt,
      maxBlocks: Int = 8
  ): Checked[Unit] =
    compareBlockSequenceReferenceWithin(applied, observedUtxo, observedFees, maxBlocks, 12)

  /** Explicit fenced audit profile; does not certify live coordination or durability. */
  def compareFencedBlockSequenceReference(
      applied: Vector[BlockApplied],
      observedUtxo: Bytes,
      observedFees: BigInt
  ): Checked[Unit] =
    compareBlockSequenceReferenceWithin(applied, observedUtxo, observedFees, 16, 16)

  private def compareBlockSequenceReferenceWithin(
      applied: Vector[BlockApplied],
      observedUtxo: Bytes,
      observedFees: BigInt,
      maxBlocks: Int,
      ceiling: Int
  ): Checked[Unit] = protect {
    for
      _ <- Either.cond(
        maxBlocks >= 1 && maxBlocks <= ceiling && applied.nonEmpty && applied.size <= maxBlocks,
        (),
        Failure.ResourceLimit(
          s"reference comparison requires receipts within the explicit 1 through $ceiling bound"
        )
      )
      created <- applied.foldLeft[Checked[(Option[State], Set[TxIn])]](
        Right((None, Set.empty))
      ) { (acc, receipt) =>
        for
          prior <- acc
          (previous, surviving) = prior
          before = receipt.candidate.before
          _ <- Either.cond(
            previous.forall { state =>
              state.checkpointId == before.checkpointId && state.environment.id == before.environment.id &&
              state.id == before.id && state.revision == before.revision && state.head == before.head
            },
            (),
            Failure.StaleState(
              "block receipt sequence has discontinuous checkpoint, content, revision, or branch"
            )
          )
        yield (
          Some(receipt.state),
          (surviving intersect receipt.state.entries.keySet) ++ receipt.candidate.created
        )
      }
      _ <- compareOutputs(applied.last.state, created._2, observedUtxo, observedFees)
    yield ()
  }

  def undo(current: State, expectedRevision: BigInt, undo: Undo): Checked[State] = protect {
    if current.revision != expectedRevision || current.checkpointId != undo.before.checkpointId ||
      current.id != undo.afterId || current.head != Some(undo.transitionId)
    then
      Left(Failure.StaleState("undo belongs to another revision, checkpoint, content, or branch"))
    else if current.revision == MaxRevision then Left(Failure.ResourceLimit("revision exhausted"))
    else
      state(
        undo.before.environment,
        undo.before.checkpointId,
        undo.before.entries,
        undo.before.fees,
        undo.before.slot,
        current.revision + 1,
        undo.before.head
      )
  }

  /** Optional observation check AFTER independent derivation. Created outputs compare semantic
    * address/coin; untouched outputs compare original bytes. This never changes candidate state.
    */
  def compareReference(applied: Applied, observedUtxo: Bytes, observedFees: BigInt): Checked[Unit] =
    protect {
      compareOutputs(applied.state, applied.candidate.created, observedUtxo, observedFees)
    }

  private def compareOutputs(
      derived: State,
      created: Set[TxIn],
      observedUtxo: Bytes,
      observedFees: BigInt
  ): Checked[Unit] =
    for
      observed <- NativeSpending.snapshot(observedUtxo).left.map(local)
      _ <- Either.cond(
        observed.keySet == derived.entries.keySet && observedFees == derived.fees,
        (),
        Failure.Rejected(
          NativeSpending.Error.StateMismatch("reference UTxO keys or fee pot differ")
        )
      )
      _ <- observed.toVector.foldLeft[Checked[Unit]](Right(())) { case (acc, (ref, node)) =>
        for
          _ <- acc
          same <-
            if created.contains(ref) then
              for
                expected <- NativeSpending
                  .output(derived.entries(ref), false)
                  .left
                  .map(Failure.Unsupported.apply)
                actual <- NativeSpending.output(node, false).left.map(Failure.Unsupported.apply)
              yield expected == actual
            else Right(node.original == derived.entries(ref).original)
          _ <- Either.cond(
            same,
            (),
            Failure.Rejected(NativeSpending.Error.StateMismatch("reference output differs"))
          )
        yield ()
      }
    yield ()
