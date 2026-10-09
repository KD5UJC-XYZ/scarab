// SPDX-License-Identifier: Apache-2.0
package lab.header

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.vrf.PraosVrfCertificate as Vrf
import scala.util.control.NonFatal

/** Source-derived Conway nonce component, seeded by explicit supplied state. Neither stake
  * evolution nor leader eligibility nor full consensus is established by this component.
  */
object PraosNonceEvolution:
  private val Max = (BigInt(1) << 64) - 1
  enum Nonce:
    case Neutral
    case Hash(bytes: Bytes)
  final case class Fields(
      evolving: Nonce,
      candidate: Nonce,
      epoch: Nonce,
      previousEpoch: Option[Nonce],
      lab: Nonce,
      lastEpochBlock: Nonce
  )
  final class Context private[PraosNonceEvolution] (
      val id: Bytes,
      val certificates: PraosCertificateState.Context,
      val epochLength: BigInt,
      val window: BigInt
  )
  object Context:
    /** Fixed Conway epoch geometry and ceil(4k/f); excludes overflow and hard-fork translation. */
    def checked(
        certificates: PraosCertificateState.Context,
        epochLength: BigInt,
        securityParam: BigInt,
        activeNumerator: BigInt,
        activeDenominator: BigInt
    ): Either[String, Context] = protect {
      require(
        certificates != null && positive(epochLength) && positive(securityParam),
        "nonce epoch/security parameters"
      )
      require(
        positive(activeNumerator) && positive(
          activeDenominator
        ) && activeNumerator <= activeDenominator,
        "nonce active coefficient"
      )
      val n = 4 * securityParam * activeDenominator
      val window = (n + activeNumerator - 1) / activeNumerator
      require(
        word(window) && certificates.lastSlot + window <= Max &&
          (certificates.lastSlot / epochLength + 1) * epochLength <= Max,
        "nonce arithmetic outside supported nonwrapping range"
      )
      val id = digest(
        Vector(
          Value.Text("experimental-conway-nonce-v1"),
          Value.ByteString(certificates.id),
          Value.UInt(epochLength),
          Value.UInt(securityParam),
          Value.UInt(activeNumerator),
          Value.UInt(activeDenominator)
        )
      )
      new Context(id, certificates, epochLength, window)
    }
  final class State private[PraosNonceEvolution] (
      val id: Bytes,
      val contextId: Bytes,
      val certificateStateId: Bytes,
      val lastSlot: BigInt,
      val fields: Fields
  )
  private[lab] final case class LocalImage(
      id: Bytes,
      contextId: Bytes,
      certificateStateId: Bytes,
      lastSlot: BigInt,
      fields: Fields
  )
  private[lab] def localImage(state: State): LocalImage =
    LocalImage(state.id, state.contextId, state.certificateStateId, state.lastSlot, state.fields)

  /** Preserves attested historical IDs; does not verify the discarded prefix. */
  private[lab] def trustedRestoreLocal(
      context: Context,
      certificate: PraosCertificateState.State,
      image: LocalImage
  ): Either[String, State] = protect {
    require(
      size(image.id, 32) && image.contextId == context.id &&
        image.certificateStateId == certificate.id && certificate.contextId == context.certificates.id &&
        word(image.lastSlot) && image.lastSlot == certificate.tip.slot,
      "local nonce binding"
    )
    validate(image.fields)
    new State(image.id, context.id, certificate.id, image.lastSlot, image.fields)
  }

  final class Applied private[PraosNonceEvolution] (
      val before: State,
      val after: State,
      val headerHash: Bytes,
      val epochNonceUsed: Nonce
  ):
    val nonceTransitionChecked = true
    val vrfProofChecked = true
    val leaderEligibilityChecked = false
    val stateDerivedConsensus = false

  private def word(n: BigInt): Boolean = n != null && n >= 0 && n <= Max
  private def positive(n: BigInt): Boolean = word(n) && n > 0
  private def size(b: Bytes, n: Int): Boolean = b != null && b.value != null && b.size == n
  private def valid(n: Nonce): Boolean = n match
    case Nonce.Neutral => true
    case Nonce.Hash(b) => size(b, 32)
    case null          => false
  private def validate(f: Fields): Unit =
    require(
      f != null && Vector(f.evolving, f.candidate, f.epoch, f.lab, f.lastEpochBlock)
        .forall(valid) &&
        f.previousEpoch != null && f.previousEpoch.forall(valid),
      "malformed nonce snapshot"
    )
  private def protect[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def nv(n: Nonce): Value = n match
    case Nonce.Neutral => Value.Null
    case Nonce.Hash(b) => Value.ByteString(b)
  private def values(f: Fields): Vector[Value] = Vector(
    nv(f.evolving),
    nv(f.candidate),
    nv(f.epoch),
    f.previousEpoch.fold[Value](Value.Text("unknown"))(nv),
    nv(f.lab),
    nv(f.lastEpochBlock)
  )
  private def digest(v: Vector[Value]): Bytes =
    Blake2b.hash256.hash(get(Cbor.encode(Value.Arr(v.map(n => Node(n, Bytes.empty))))))
  private[header] def combine(a: Nonce, b: Nonce): Nonce = (a, b) match
    case (Nonce.Neutral, x) => x
    case (x, Nonce.Neutral) => x
    case (Nonce.Hash(x), Nonce.Hash(y)) =>
      Nonce.Hash(Blake2b.hash256.hash(Bytes(x.value ++ y.value)))
  private[header] def contribution(output: Bytes): Nonce =
    Nonce.Hash(
      Blake2b.hash256.hash(Blake2b.hash256.hash(Bytes(Vector(0x4e.toByte) ++ output.value)))
    )
  private[header] def tick(context: Context, lastSlot: BigInt, slot: BigInt, f: Fields): Fields =
    if slot / context.epochLength > lastSlot / context.epochLength then
      f.copy(
        epoch = combine(f.candidate, f.lastEpochBlock),
        previousEpoch = Some(f.epoch),
        lastEpochBlock = f.lab
      )
    else f
  private[header] def update(
      context: Context,
      slot: BigInt,
      ticked: Fields,
      parent: Nonce,
      output: Bytes
  ): Fields =
    val evolving = combine(ticked.evolving, contribution(output))
    val nextEpoch = (slot / context.epochLength + 1) * context.epochLength
    ticked.copy(
      evolving = evolving,
      candidate = if slot + context.window < nextEpoch then evolving else ticked.candidate,
      lab = parent
    )

  def seed(
      context: Context,
      certificates: PraosCertificateState.State,
      fields: Fields,
      sourceSha256: Bytes
  ): Either[String, State] = protect {
    require(
      context != null && certificates != null && certificates.contextId == context.certificates.id,
      "nonce seed certificate context mismatch"
    )
    require(
      size(sourceSha256, 32) && certificates.tip.slot >= context.certificates.firstSlot &&
        certificates.tip.slot <= context.certificates.lastSlot,
      "nonce seed source/window"
    )
    validate(fields)
    val id = digest(
      Vector(
        Value.Text("nonce-seed"),
        Value.ByteString(context.id),
        Value.ByteString(certificates.id),
        Value.ByteString(sourceSha256)
      ) ++ values(fields)
    )
    new State(id, context.id, certificates.id, certificates.tip.slot, fields)
  }
  private def arr(n: Node): Vector[Node] = n.value match
    case Value.Arr(v) => v
    case _            => throw new IllegalArgumentException("nonce header array required")
  private def bytes(n: Node): Bytes = n.value match
    case Value.ByteString(b) => b
    case _                   => throw new IllegalArgumentException("nonce header bytes required")

  /** Tick before verifying VRF with the new epoch nonce; publish nothing on proof failure. */
  def applyHeader(
      context: Context,
      state: State,
      step: PraosCertificateState.Applied
  ): Either[String, Applied] = protect {
    require(
      context != null && state != null && step != null && state.contextId == context.id &&
        step.before.contextId == context.certificates.id && step.before.id == state.certificateStateId &&
        step.before.tip.slot == state.lastSlot,
      "nonce branch/context mismatch"
    )
    val slot = step.after.tip.slot
    require(
      slot > state.lastSlot && slot <= context.certificates.lastSlot,
      "nonce slot ordering/window"
    )
    val ticked = tick(context, state.lastSlot, slot, state.fields)
    val body =
      arr(get(Cbor.decode(step.observation.originalBody, Cbor.Limits(65536, 8, 64, 65536))))
    val key = bytes(body(4))
    require(
      Blake2b.hash256.hash(key) == context.certificates.registrations(step.issuer),
      "nonce VRF registration mismatch"
    )
    val nonce = ticked.epoch match
      case Nonce.Neutral => Vrf.NeutralNonce
      case Nonce.Hash(b) => Vrf.Hash32.fromBytes(b).toOption.get
    val input = Vrf.Input.create(Vrf.Slot.fromBigInt(slot).toOption.get, nonce).toOption.get
    val proof = arr(body(5))
    val output = Vrf.verify(input, key, bytes(proof(1)), bytes(proof(0))) match
      case Vrf.Result.VerifiedCertificate(value) => value
      case other => throw new IllegalArgumentException("nonce VRF rejected: " + other.toString)
    val parent = body(2).value match
      case Value.Null                         => Nonce.Neutral
      case Value.ByteString(b) if size(b, 32) => Nonce.Hash(b)
      case _ => throw new IllegalArgumentException("nonce parent hash")
    val fields = update(context, slot, ticked, parent, output)
    val id = digest(
      Vector(
        Value.Text("nonce-applied"),
        Value.ByteString(state.id),
        Value.ByteString(step.after.id)
      ) ++ values(fields)
    )
    val after = new State(id, context.id, step.after.id, slot, fields)
    new Applied(state, after, step.after.tip.hash, ticked.epoch)
  }
  def undo(current: State, applied: Applied): Either[String, State] = protect {
    require(
      current != null && applied != null && current.id == applied.after.id,
      "nonce undo branch mismatch"
    )
    applied.before
  }
