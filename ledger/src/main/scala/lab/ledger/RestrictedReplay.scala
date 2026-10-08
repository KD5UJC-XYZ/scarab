// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.charset.StandardCharsets.US_ASCII
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Node, Value as CValue}
import lab.witness.{CardanoWitness, VerificationResult}
import scala.util.control.NonFatal

/** A bounded pure ADA transfer UTxO/fee projection. This is not a complete Cardano ledger, block
  * validator, state root, or persisted store. Revision fencing belongs to this immutable in-memory
  * lineage only; no cross-process ownership, crash recovery or durability is claimed.
  */
object RestrictedReplay:
  val ProfileId = "conway-pv9-transfer-projection-v1"
  val MaxTransactionBytes = 1048576
  val MaxStateBytes = 1048576
  val MaxUtxoEntries = 4096
  val MaxBatchTransactions = 64
  val MaxBatchBytes = 8388608L
  val MaxBatchEvidenceBytes = 33554432L
  val MaxWitnessesPerTransaction = 128
  val MaxDecoderItems = 65536
  val MaxDecoderDepth = 12
  val ParameterSha256 = "23a62c9e17bfb1c9755134f173e29e7ff87f9991a84b5bb40ec177b262647633"
  val FixedResearchSlot: BigInt = BigInt(3883681)
  private val MaxRevision = (BigInt(1) << 64) - 1
  val PredicateSourceSha256: Vector[(String, String)] = Vector(
    "ledger/src/main/scala/lab/ledger/Coverage.scala" -> "fe3f81d388be13e83c3b4026d74b96f967a3c3d39ec1f620fbdc59dff4e6a8c7",
    "ledger/src/main/scala/lab/ledger/Balance.scala" -> "6264a40cb71ec2a6f81b350bf9906d7e952b3bdf7184ddf5c9ff76145008b9c4",
    "ledger/src/main/scala/lab/ledger/FeeSize.scala" -> "3529136a9f9b877ae90441ada3db4dbd9df5cd656dcfe349b88011bb0276e491",
    "core/src/main/scala/lab/witness/CardanoWitness.scala" -> "8d7cc2e29f2e7217e19023313bbd093c28adcfea5e3f80daaed68b53825ab573",
    "core/src/main/scala/lab/witness/StrictEd25519.scala" -> "54ec5850a17f47b80c94e0b6cf1498c8c2ebcfe7941af6870fe2c2c78a184351",
    "core/src/main/scala/lab/cbor/Cbor.scala" -> "a2238aefe44fcf7cfcbd1e78026b9ffcb1feee7fad1136087bdfa3ef48897561"
  )

  /** Changes to admission, checked predicates, crypto semantics, limits, identity encoding or
    * omissions require a new descriptor/profile revision. This hash is a research identity only.
    */
  val ProfileDescriptor: String =
    "cardano-lab:restricted-replay:v1\n" +
      "Conway=9.0;body=0,1,2;isValid=true;aux=null;addresses=0,6;network-nibble=0,1\n" +
      "amount=scalar-uint;witness-fields=0;unique-vkeys;exact-body-blake2b256\n" +
      "predicates=Coverage-v1,StrictEd25519-v1,Balance-v1,FeeSize-v1\n" +
      "crypto=bcprov-jdk18on-1.85.2,curve25519-elisabeth-0.1.3;verification=public-only\n" +
      "transaction-bytes=1048576;state-bytes=1048576;utxo=4096;batch=64;batch-bytes=8388608;batch-evidence-bytes=33554432;fee-accumulator-bits=128;witnesses=128\n" +
      "decoder-depth=12;decoder-items=65536;revision=uint64;identity=sha256-length-prefixed-v1\n" +
      "order=bounded-cbor,resource,scope,resolution,coverage,signatures,balance,fee,size,collision,state-bounds\n" +
      "omitted=network-match,min-utxo,output-value-limits,instant-stake,tick,epoch,complete-ledger,blocks,consensus,persistence\n" +
      s"parameters-sha256=$ParameterSha256;research-slot=$FixedResearchSlot;initial-fees=0\n" +
      PredicateSourceSha256.map((path, hash) => s"source=$path:$hash\n").mkString
  val ProfileHash: Bytes = sha(ascii(ProfileDescriptor))

  enum Failure:
    case Malformed(detail: String)
    case Unsupported(detail: String)
    case Rejected(stage: String, detail: String)
    case ResourceLimit(detail: String)
    case StaleState(detail: String)
    case InternalFailure(kind: String)
  type Checked[A] = Either[Failure, A]

  final class Revision private[RestrictedReplay] (val number: BigInt):
    override def toString: String = number.toString

  final class Environment private[RestrictedReplay] (
      val parameterDigest: Bytes,
      private[RestrictedReplay] val feeParameters: FeeSize.Parameters
  )

  final class ResearchCheckpoint private[RestrictedReplay] (
      val id: Bytes,
      val originalUtxo: Bytes,
      val attributionDigest: Bytes,
      val environment: Environment
  ):
    val profileId: String = ProfileId
    val profileHash: Bytes = ProfileHash
    val researchSlot: BigInt = FixedResearchSlot
    val initialFees: BigInt = BigInt(0)

  final class State private[RestrictedReplay] (
      val checkpoint: ResearchCheckpoint,
      val utxo: Map[TxIn, Coverage.Output],
      val feesSinceCheckpoint: BigInt,
      val outputMap: Bytes,
      val stateId: Bytes,
      val revision: Revision,
      private[RestrictedReplay] val headTransition: Option[Bytes]
  )

  final class Delta private[RestrictedReplay] (
      val beforeId: Bytes,
      val afterId: Bytes,
      val spent: Map[TxIn, Coverage.Output],
      val created: Map[TxIn, Coverage.Output],
      val fee: BigInt,
      val originalTransaction: Bytes,
      val originalTransactionSha256: Bytes,
      val transactionId: Bytes
  )

  final class BatchDelta private[RestrictedReplay] (
      val checkpointId: Bytes,
      val beforeId: Bytes,
      val afterId: Bytes,
      val transitionId: Bytes,
      val transactions: Vector[Delta],
      private[RestrictedReplay] val beforeHead: Option[Bytes]
  )

  final class PreparedBatch private[RestrictedReplay] (
      private[RestrictedReplay] val before: State,
      private[RestrictedReplay] val after: State,
      private[RestrictedReplay] val deltas: Vector[Delta]
  )

  final class BatchApplied private[RestrictedReplay] (
      val state: State,
      val delta: Option[BatchDelta]
  )

  private def protect[A](body: => Checked[A]): Checked[A] =
    try body
    catch case NonFatal(e) => Left(Failure.InternalFailure(e.getClass.getName))
  private def validBytes(b: Bytes): Boolean = b != null && b.value != null
  private def ascii(s: String): Bytes = Bytes.fromArray(s.getBytes(US_ASCII))
  private def sha(b: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(b.toArray))

  private def identity(domain: String, fields: Vector[Bytes]): Bytes =
    val digest = MessageDigest.getInstance("SHA-256")
    (ascii(domain) +: fields).foreach { b =>
      digest.update(
        Array((b.size >>> 24).toByte, (b.size >>> 16).toByte, (b.size >>> 8).toByte, b.size.toByte)
      )
      digest.update(b.toArray)
    }
    Bytes.fromArray(digest.digest())

  private def cborError(detail: String): Failure =
    if detail.endsWith("limit exceeded") then Failure.ResourceLimit(detail)
    else Failure.Malformed(detail)
  private def coverageError(error: CoverageError): Failure = error match
    case CoverageError.TypedUnsupported(reason) => Failure.Unsupported(reason)
    case CoverageError.Malformed(reason)        => cborError(reason)
    case CoverageError.EmptySpendingInputs => Failure.Rejected("inputs", "empty spending inputs")
    case CoverageError.UnknownSpendingInputs(_) =>
      Failure.Rejected("inputs", "unresolved spending inputs")

  private def boundedDecode(raw: Bytes): Checked[Node] =
    Cbor
      .decode(
        raw,
        Cbor.Limits(MaxTransactionBytes, MaxDecoderDepth, MaxDecoderItems, MaxTransactionBytes)
      )
      .left
      .map(cborError)

  /** One immutable historical parameter record, not live defaults or epoch state. */
  def environment(rawParameters: Bytes): Checked[Environment] = protect {
    if !validBytes(rawParameters) then Left(Failure.Malformed("null parameter bytes"))
    else if rawParameters.size > MaxStateBytes then Left(Failure.ResourceLimit("parameter bytes"))
    else if sha(rawParameters).hex != ParameterSha256 then
      Left(Failure.Unsupported("parameters outside pinned Conway PV9 research profile"))
    else
      for
        root <- boundedDecode(rawParameters)
        params <- root.value match
          case CValue.Arr(items) if items.size == 31 =>
            def uint(index: Int): Checked[BigInt] = items(index).value match
              case CValue.UInt(n) => Right(n)
              case _              => Left(Failure.Malformed("parameter integer shape"))
            val protocol = items(12).value match
              case CValue.Arr(Vector(major, minor)) =>
                major.value == CValue.UInt(9) && minor.value == CValue.UInt(0)
              case _ => false
            for
              _ <- Either.cond(protocol, (), Failure.Unsupported("protocol parameters"))
              a <- uint(0)
              b <- uint(1)
              maximum <- uint(3)
              p <- FeeSize.Parameters
                .create("Conway", 9, a, b, maximum)
                .left
                .map(e => Failure.Malformed(e.toString))
            yield p
          case _ => Left(Failure.Malformed("parameter record shape"))
      yield new Environment(sha(rawParameters), params)
  }

  private def head(major: Int, n: Int): Vector[Byte] =
    if n < 24 then Vector(((major << 5) | n).toByte)
    else if n <= 255 then Vector(((major << 5) | 24).toByte, n.toByte)
    else Vector(((major << 5) | 25).toByte, (n >>> 8).toByte, n.toByte)

  /** Pure collection helper: key evaluation occurs exactly once per entry. It cannot construct
    * checked replay state or evidence; encodeOutputs supplies its fixed reference key internally.
    */
  private[ledger] def sortByCachedKey[A, K: Ordering](entries: Vector[A])(
      key: A => K
  ): Vector[A] =
    entries.map(entry => (key(entry), entry)).sortBy(_._1).map(_._2)

  /** Sorted references with untouched output bytes; map/input heads are local canonical framing. */
  private def encodeOutputs(utxo: Map[TxIn, Coverage.Output]): Checked[Bytes] =
    if utxo.size > MaxUtxoEntries then Left(Failure.ResourceLimit("UTxO entry cap"))
    else
      val count = head(5, utxo.size).size.toLong + utxo.iterator.map { (in, out) =>
        35L + head(0, in.index.toInt).size + out.original.size
      }.sum
      if count > MaxStateBytes then Left(Failure.ResourceLimit("encoded state cap"))
      else
        val bytes = Vector.newBuilder[Byte]
        bytes ++= head(5, utxo.size)
        sortByCachedKey(utxo.toVector)((in, _) => (in.id.hex, in.index)).foreach { (in, out) =>
          bytes ++= Vector(0x82.toByte, 0x58.toByte, 0x20.toByte)
          bytes ++= in.id.value
          bytes ++= head(0, in.index.toInt)
          bytes ++= out.original.value
        }
        Right(Bytes(bytes.result()))

  private def state(
      checkpoint: ResearchCheckpoint,
      utxo: Map[TxIn, Coverage.Output],
      fees: BigInt,
      revision: Revision,
      top: Option[Bytes]
  ): Checked[State] =
    if fees < 0 then Left(Failure.InternalFailure("negative fee accumulator"))
    else if fees.bitLength > 128 then Left(Failure.ResourceLimit("fee accumulator cap"))
    else
      encodeOutputs(utxo).flatMap(rawMap => boundedDecode(rawMap).map(_ => rawMap)).map { rawMap =>
        val id = identity(
          "cardano-lab:projection-state:v1",
          Vector(
            ProfileHash,
            checkpoint.id,
            checkpoint.environment.parameterDigest,
            ascii(fees.toString),
            rawMap
          )
        )
        new State(checkpoint, utxo, fees, rawMap, id, revision, top)
      }

  private def scalarAmount(node: Node): Boolean = node.value match
    case CValue.UInt(_) => true
    case _              => false
  private def adaOutput(node: Node): Boolean = node.value match
    case CValue.Arr(Vector(_, amount)) => scalarAmount(amount)
    case CValue.Map(entries) =>
      entries
        .collectFirst {
          case (key, amount) if key.value == CValue.UInt(1) => scalarAmount(amount)
        }
        .contains(true)
    case _ => false

  private def arraySize(node: Node): Option[Int] = node.value match
    case CValue.Arr(xs)                   => Some(xs.size)
    case CValue.Tag(n, inner) if n == 258 => arraySize(inner)
    case _                                => None

  /** Resource preflight uses the stricter bounded decoder before allocating checked projections.
    * Scope/duplicate validation remains owned by Coverage; no parsed values can grant success.
    */
  private def preflight(raw: Bytes): Checked[Unit] =
    boundedDecode(raw).flatMap { root =>
      root.value match
        case CValue.Arr(Vector(body, witnesses, _, _)) =>
          val bodyFields = body.value match
            case CValue.Map(xs) => xs
            case _              => Vector.empty
          val witnessFields = witnesses.value match
            case CValue.Map(xs) => xs
            case _              => Vector.empty
          val bodyBound = bodyFields.forall { (key, value) =>
            key.value match
              case CValue.UInt(n) if n == 0 || n == 1 =>
                arraySize(value).forall(_ <= MaxUtxoEntries)
              case _ => true
          }
          val witnessBound = witnessFields.forall { (key, value) =>
            key.value match
              case CValue.UInt(n) if n == 0 =>
                arraySize(value).forall(_ <= MaxWitnessesPerTransaction)
              case _ => true
          }
          Either.cond(
            bodyBound && witnessBound,
            (),
            Failure.ResourceLimit("transaction collection cap")
          )
        case _ => Right(())
    }

  /** The digest attributes a caller-supplied manifest; it is not source authentication. */
  def initialize(
      env: Environment,
      originalUtxo: Bytes,
      attributionDigest32: Bytes
  ): Checked[State] = protect {
    if env == null || !validBytes(originalUtxo) || !validBytes(attributionDigest32) ||
      attributionDigest32.size != 32
    then Left(Failure.Malformed("checkpoint input"))
    else if originalUtxo.size > MaxStateBytes then Left(Failure.ResourceLimit("checkpoint bytes"))
    else
      for
        root <- boundedDecode(originalUtxo)
        _ <- root.value match
          case CValue.Map(xs) if xs.size > MaxUtxoEntries =>
            Left(Failure.ResourceLimit("UTxO entry cap"))
          case _ => Right(())
        utxo <- Coverage.decodeResolved(originalUtxo).left.map(coverageError)
        _ <- Either.cond(utxo.nonEmpty, (), Failure.Malformed("empty research checkpoint"))
        _ <- root.value match
          case CValue.Map(xs) =>
            Either.cond(
              xs.forall((_, out) => adaOutput(out)),
              (),
              Failure.Unsupported("multiasset state/output encoding")
            )
          case _ => Left(Failure.Malformed("checkpoint map"))
        normalized <- encodeOutputs(utxo)
        id = identity(
          "cardano-lab:research-checkpoint:v1",
          Vector(
            ProfileHash,
            env.parameterDigest,
            attributionDigest32,
            ascii(FixedResearchSlot.toString),
            ascii("fees-since-checkpoint=0"),
            sha(originalUtxo),
            normalized
          )
        )
        checkpoint = new ResearchCheckpoint(id, originalUtxo, attributionDigest32, env)
        initial <- state(checkpoint, utxo, BigInt(0), new Revision(BigInt(0)), None)
      yield initial
  }

  private def project(before: State, original: Bytes): Checked[(State, Delta)] =
    for
      _ <- preflight(original)
      tx <- Coverage.decode(original).left.map(coverageError)
      _ <- tx.outputs.foldLeft[Checked[Unit]](Right(())) { (acc, output) =>
        acc.flatMap(_ =>
          boundedDecode(output.original).flatMap(node =>
            Either.cond(
              adaOutput(node),
              (),
              Failure.Unsupported("multiasset transaction output encoding")
            )
          )
        )
      }
      coverage <- Coverage.check("Conway", 9, before.utxo, tx).left.map(coverageError)
      _ <- Either.cond(coverage.covered, (), Failure.Rejected("coverage", "missing required keys"))
      _ <- tx.witnesses.foldLeft[Checked[Unit]](Right(())) { (acc, witness) =>
        acc.flatMap(_ =>
          CardanoWitness
            .verifyVKeyWitness(tx.body, witness)
            .left
            .map(e => Failure.InternalFailure(e.toString))
            .flatMap(r =>
              Either.cond(
                r == VerificationResult.SignatureVerified,
                (),
                Failure.Rejected("signature", "provided signature rejected")
              )
            )
        )
      }
      body <- Balance.decode(original).left.map(e => Failure.InternalFailure(e.toString))
      balance <- Balance
        .check("Conway", 9, before.utxo.view.mapValues(_.value).toMap, body)
        .left
        .map(e => Failure.InternalFailure(e.toString))
      _ <- balance match
        case BalanceResult.PredicateSatisfied(_, _) => Right(())
        case BalanceResult.ValueNotConserved(_, _, _) =>
          Left(Failure.Rejected("balance", "value not conserved"))
      context <- FeeSize.Context
        .decode(before.checkpoint.environment.feeParameters, before.outputMap)
        .left
        .map(e => Failure.InternalFailure(e.toString))
      feeSize <- FeeSize
        .checkTransferFeeAndSize(context, tx)
        .left
        .map(e => Failure.InternalFailure(e.toString))
      _ <- feeSize.fee match
        case FeePredicate.Satisfied(_, _)   => Right(())
        case FeePredicate.FeeTooSmall(_, _) => Left(Failure.Rejected("fee", "fee too small"))
      _ <- feeSize.size match
        case SizePredicate.Satisfied(_, _) => Right(())
        case SizePredicate.MaxTxSize(_, _) =>
          Left(Failure.Rejected("size", "maximum transaction size"))
      _ <- Either.cond(
        before.utxo.size - tx.inputs.size + tx.outputs.size <= MaxUtxoEntries,
        (),
        Failure.ResourceLimit("next UTxO entry cap")
      )
      txid = tx.body.hash.bytes
      created <- tx.outputs.zipWithIndex.foldLeft[Checked[Map[TxIn, Coverage.Output]]](
        Right(Map.empty)
      ) { case (acc, (output, index)) =>
        for
          entries <- acc
          ref <- TxIn.create(txid, BigInt(index)).left.map(e => Failure.Malformed(e.toString))
          _ <- Either.cond(
            !before.utxo.contains(ref),
            (),
            Failure.Rejected("output", "existing output reference collision")
          )
        yield entries.updated(ref, output)
      }
      spent = tx.inputs.iterator.map(ref => ref -> before.utxo(ref)).toMap
      after <- state(
        before.checkpoint,
        (before.utxo -- tx.inputs) ++ created,
        before.feesSinceCheckpoint + tx.fee,
        before.revision,
        before.headTransition
      )
    yield (
      after,
      new Delta(
        before.stateId,
        after.stateId,
        spent,
        created,
        tx.fee,
        original,
        sha(original),
        txid
      )
    )

  /** Atomic preparation returns no provisional state or partial delta on any failure. */
  def prepareBatch(before: State, originals: Vector[Bytes]): Checked[PreparedBatch] = protect {
    if before == null || originals == null then Left(Failure.Malformed("batch input"))
    else if originals.size > MaxBatchTransactions then Left(Failure.ResourceLimit("batch count"))
    else if originals.exists(b => !validBytes(b)) then Left(Failure.Malformed("transaction bytes"))
    else if originals.exists(_.size > MaxTransactionBytes) || originals.iterator
        .map(_.size.toLong)
        .sum > MaxBatchBytes
    then Left(Failure.ResourceLimit("batch bytes"))
    else if originals.nonEmpty && before.revision.number == MaxRevision then
      Left(Failure.ResourceLimit("revision exhausted"))
    else
      originals
        .foldLeft[Checked[(State, Vector[Delta], Long)]](Right((before, Vector.empty, 0L))) {
          case (acc, raw) =>
            acc.flatMap { (current, deltas, retained) =>
              project(current, raw).flatMap { (next, delta) =>
                val additional = delta.originalTransaction.size.toLong +
                  delta.spent.iterator.map((_, output) => 38L + output.original.size).sum +
                  delta.created.iterator.map((_, output) => 38L + output.original.size).sum + 256L
                Either.cond(
                  additional <= MaxBatchEvidenceBytes - retained,
                  (next, deltas :+ delta, retained + additional),
                  Failure.ResourceLimit("batch evidence byte cap")
                )
              }
            }
        }
        .map((after, deltas, _) => new PreparedBatch(before, after, deltas))
  }

  /** Separate commit checks both semantic content and monotonic revision, defeating in-lineage ABA.
    */
  def commitPrepared(current: State, prepared: PreparedBatch): Checked[BatchApplied] = protect {
    if current == null || prepared == null then Left(Failure.Malformed("prepared batch input"))
    else if current.checkpoint.id != prepared.before.checkpoint.id || current.stateId != prepared.before.stateId ||
      current.revision.number != prepared.before.revision.number || current.headTransition != prepared.before.headTransition
    then Left(Failure.StaleState("prepared batch belongs to another snapshot"))
    else if prepared.deltas.isEmpty then Right(new BatchApplied(current, None))
    else if current.revision.number == MaxRevision then
      Left(Failure.ResourceLimit("revision exhausted"))
    else
      val transitionId = identity(
        "cardano-lab:projection-transition:v1",
        Vector(
          ProfileHash,
          current.checkpoint.id,
          current.stateId,
          prepared.after.stateId,
          ascii(current.revision.toString),
          current.headTransition.getOrElse(Bytes.empty)
        ) ++
          prepared.deltas.map(_.originalTransactionSha256)
      )
      state(
        current.checkpoint,
        prepared.after.utxo,
        prepared.after.feesSinceCheckpoint,
        new Revision(current.revision.number + 1),
        Some(transitionId)
      ).map { committed =>
        new BatchApplied(
          committed,
          Some(
            new BatchDelta(
              current.checkpoint.id,
              current.stateId,
              committed.stateId,
              transitionId,
              prepared.deltas,
              current.headTransition
            )
          )
        )
      }
  }

  def applyBatch(before: State, originals: Vector[Bytes]): Checked[BatchApplied] =
    prepareBatch(before, originals).flatMap(commitPrepared(before, _))
  def applyTransaction(before: State, original: Bytes): Checked[BatchApplied] =
    applyBatch(before, Vector(original))

  /** Restores content, never revision. Only the current branch's top transition is eligible. */
  def undo(current: State, expectedRevision: Revision, batch: BatchDelta): Checked[State] =
    protect {
      if current == null || expectedRevision == null || batch == null then
        Left(Failure.Malformed("undo input"))
      else if current.revision.number != expectedRevision.number || current.checkpoint.id != batch.checkpointId ||
        current.stateId != batch.afterId || current.headTransition != Some(batch.transitionId)
      then Left(Failure.StaleState("undo belongs to another revision, branch or checkpoint"))
      else if current.revision.number == MaxRevision then
        Left(Failure.ResourceLimit("revision exhausted"))
      else
        batch.transactions.reverse
          .foldLeft[Checked[State]](Right(current)) { (acc, delta) =>
            acc.flatMap { after =>
              if after.stateId != delta.afterId then
                Left(Failure.StaleState("undo transaction order"))
              else if !delta.created
                  .forall((ref, out) => after.utxo.get(ref).exists(_.original == out.original))
              then Left(Failure.StaleState("created outputs no longer match"))
              else
                val withoutCreated = after.utxo -- delta.created.keySet
                if delta.spent.keys.exists(withoutCreated.contains) then
                  Left(Failure.StaleState("restore output collision"))
                else
                  state(
                    after.checkpoint,
                    withoutCreated ++ delta.spent,
                    after.feesSinceCheckpoint - delta.fee,
                    after.revision,
                    after.headTransition
                  ).flatMap { restored =>
                    Either.cond(
                      restored.stateId == delta.beforeId,
                      restored,
                      Failure.InternalFailure("undo prior identity mismatch")
                    )
                  }
            }
          }
          .flatMap { restored =>
            if restored.stateId != batch.beforeId then
              Left(Failure.InternalFailure("batch undo identity mismatch"))
            else
              state(
                current.checkpoint,
                restored.utxo,
                restored.feesSinceCheckpoint,
                new Revision(current.revision.number + 1),
                batch.beforeHead
              )
          }
    }
