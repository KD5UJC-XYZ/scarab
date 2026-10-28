// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import xyz.kd5ujc.scarab.ledger.DecodeField
import xyz.kd5ujc.scarab.ledger.LedgerDecodeError
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.CborRead
import xyz.kd5ujc.scarab.ledger.ProviderDefect
import xyz.kd5ujc.scarab.crypto.witness.{
  ByronEd25519,
  VerificationError,
  VerificationResult,
  WitnessInputError
}

/** Verifies a signature of an extended Byron key (64 bytes: the Ed25519 key then a chain code),
  * with the Byron verification semantics of the reference ([[ByronEd25519]]).
  *
  * A malformed key or signature and a rejected signature are `false`; a verifier that fails is the
  * [[ProviderDefect]], no verdict on the signature ([[ProviderDefect.witness]]).
  */
object ByronSig:
  /** An Ed25519 verifier: `(key, signature, message)` to its answer. The ledger and the header
    * rules take it from their configuration, so that a test injects a failing provider.
    */
  type Verifier =
    (Bytes, Bytes, Bytes) => Either[WitnessInputError | VerificationError, VerificationResult]

  /** [[ByronEd25519.verify]], one instance so that configurations compare equal. */
  val Standard: Verifier = ByronEd25519.verify(_, _, _)

  def verify(
      extendedKey: Bytes,
      signature: Bytes,
      message: Bytes,
      verifier: Verifier = Standard
  ): Either[ProviderDefect, Boolean] =
    if extendedKey.size >= 32 && signature.size == 64 then
      ProviderDefect.witness(verifier(extendedKey.take(32), signature, message))
    else Right(false)

  /** A redeem signature: plain Ed25519 under a 32-byte key. */
  def verifyRedeem(
      key: Bytes,
      signature: Bytes,
      message: Bytes,
      verifier: Verifier = Standard
  ): Either[ProviderDefect, Boolean] =
    if key.size == 32 && signature.size == 64 then
      ProviderDefect.witness(verifier(key, signature, message))
    else Right(false)

  /** The signing tag of a message kind followed by the CBOR protocol magic (`signTag`). */
  def tag(kind: Int, magic: Bytes): Bytes = ByronCbor.of(kind) ++ magic

  val SignTx = 0x01
  val SignRedeemTx = 0x02
  val SignUpdateProposal = 0x04
  val SignUpdateVote = 0x06
  val SignCertificate = 0x0a

/** A heavyweight delegation certificate `[epoch, issuer, delegate, signature]`: the genesis key
  * `issuer` lets `delegate` sign blocks from `epoch` on.
  */
final case class ByronCert(
    raw: Bytes,
    epoch: BigInt,
    rawEpoch: Bytes,
    issuer: Bytes,
    delegate: Bytes,
    signature: Bytes
):
  def issuerHash: Bytes = ByronCbor.keyHash(issuer)
  def delegateHash: Bytes = ByronCbor.keyHash(delegate)

  /** `Certificate.isValid`: the issuer signed `"00" || delegate || epoch`, wrapped as a CBOR byte
    * string, under the certificate tag and the protocol magic.
    */
  def isValid(
      magic: Bytes,
      signatures: ByronSig.Verifier = ByronSig.Standard
  ): Either[ProviderDefect, Boolean] =
    val signed = ByronCbor.bytes(
      ByronCbor.concat(ByronCbor.of(0x30, 0x30), delegate, rawEpoch)
    )
    ByronSig.verify(
      issuer,
      signature,
      ByronSig.tag(ByronSig.SignCertificate, magic) ++ signed,
      signatures
    )

object ByronCert:
  def decode(n: Node): Either[LedgerDecodeError, ByronCert] =
    CborRead.arrayN(n, 4, DecodeField.DelegationCertificate).flatMap { xs =>
      (
        CborRead.uint(xs(0), DecodeField.CertificateEpoch),
        CborRead.bytesN(xs(1), 64, DecodeField.CertificateIssuer),
        CborRead.bytesN(xs(2), 64, DecodeField.CertificateDelegate),
        CborRead.bytesN(xs(3), 64, DecodeField.CertificateSignature)
      ).mapN(ByronCert(n.original, _, xs(0).original, _, _, _))
    }

/** Why a delegation certificate is refused (`Scheduling.Error`). */
enum DelegationError:
  case InvalidCertificate
  case MultipleDelegationsForEpoch(epoch: BigInt, delegator: Bytes)
  case MultipleDelegationsForSlot(slot: BigInt, delegator: Bytes)
  case NonGenesisDelegator(delegator: Bytes)
  case WrongEpoch(current: BigInt, certificate: BigInt)

  def message: String = this match
    case InvalidCertificate => "delegation certificate has an invalid signature"
    case MultipleDelegationsForEpoch(e, d) =>
      s"delegator ${d.hex.take(16)} already delegated for epoch $e"
    case MultipleDelegationsForSlot(s, d) =>
      s"delegator ${d.hex.take(16)} already delegated in slot $s"
    case NonGenesisDelegator(d) => s"delegator ${d.hex.take(16)} is not a genesis key"
    case WrongEpoch(c, e) =>
      s"delegation for epoch $e in epoch $c (only this or the next is allowed)"

final case class ScheduledDelegation(slot: BigInt, delegator: Bytes, delegate: Bytes)

/** The delegation state: scheduled certificates, the (genesis key -> delegate) bimap, and the slot
  * of each genesis key's latest activation (`Delegation.Validation.Interface`).
  */
final case class DelegationState(
    scheduled: Vector[ScheduledDelegation],
    keyEpochDelegations: Set[(BigInt, Bytes)],
    delegationMap: Map[Bytes, Bytes],
    delegationSlots: Map[Bytes, BigInt]
):
  /** The genesis key a delegate signs for (`Bimap.lookupR`). */
  def delegatorOf(delegate: Bytes): Option[Bytes] =
    delegationMap.collectFirst { case (g, d) if d == delegate => g }

  def isDelegate(delegate: Bytes): Boolean = delegationMap.valuesIterator.contains(delegate)

object DelegationRules:
  final case class Env(
      magic: Bytes,
      allowedDelegators: Set[Bytes],
      currentEpoch: BigInt,
      currentSlot: BigInt,
      k: BigInt,
      signatures: ByronSig.Verifier = ByronSig.Standard
  )

  /** Genesis keys delegate to themselves until a certificate says otherwise. */
  def empty(genesisKeys: Iterable[Bytes]): DelegationState =
    DelegationState(
      Vector.empty,
      Set.empty,
      genesisKeys.map(g => g -> g).toMap,
      genesisKeys.map(_ -> BigInt(0)).toMap
    )

  /** `Bimap.insert`: drops any pair that shares the delegator or the delegate. */
  private def insert(m: Map[Bytes, Bytes], a: Bytes, b: Bytes): Map[Bytes, Bytes] =
    m.filterNot((k, v) => k == a || v == b) + (a -> b)

  /** Schedule a certificate. A verifier that fails is the [[ProviderDefect]], beside the refusals
    * and never an [[DelegationError.InvalidCertificate]].
    */
  def schedule(
      env: Env,
      st: DelegationState,
      cert: ByronCert
  ): Either[DelegationError | ProviderDefect, DelegationState] =
    val delegator = cert.issuerHash
    val activation = env.currentSlot + 2 * env.k
    for
      _ <- Either.cond(
        env.allowedDelegators.contains(delegator),
        (),
        DelegationError.NonGenesisDelegator(delegator)
      )
      _ <- Either.cond(
        env.currentEpoch <= cert.epoch && cert.epoch <= env.currentEpoch + 1,
        (),
        DelegationError.WrongEpoch(env.currentEpoch, cert.epoch)
      )
      _ <- Either.cond(
        !st.keyEpochDelegations.contains((cert.epoch, delegator)),
        (),
        DelegationError.MultipleDelegationsForEpoch(cert.epoch, delegator)
      )
      _ <- Either.cond(
        !st.scheduled.exists(sd => sd.slot == activation && sd.delegator == delegator),
        (),
        DelegationError.MultipleDelegationsForSlot(env.currentSlot, delegator)
      )
      valid <- cert.isValid(env.magic, env.signatures)
      _ <- Either.cond(valid, (), DelegationError.InvalidCertificate)
    yield st.copy(
      scheduled = st.scheduled :+ ScheduledDelegation(activation, delegator, cert.delegateHash),
      keyEpochDelegations = st.keyEpochDelegations + ((cert.epoch, delegator))
    )

  /** `activateDelegations`: scheduled delegations due by `slot` take effect, in order. */
  def activate(st: DelegationState, slot: BigInt): DelegationState =
    st.scheduled.filter(_.slot <= slot).foldLeft(st) { (s, d) =>
      val previous = s.delegationSlots.getOrElse(d.delegator, BigInt(0))
      if !s.isDelegate(d.delegate) && (previous < d.slot || d.slot == 0) then
        s.copy(
          delegationMap = insert(s.delegationMap, d.delegator, d.delegate),
          delegationSlots = s.delegationSlots.updated(d.delegator, d.slot)
        )
      else s
    }

  /** `tickDelegation`: activate what is due, then forget what is past. */
  def tick(st: DelegationState, epoch: BigInt, slot: BigInt): DelegationState =
    val active = activate(st, slot)
    active.copy(
      scheduled = active.scheduled.filter(_.slot >= slot + 1),
      keyEpochDelegations = active.keyEpochDelegations.filter(_._1 >= epoch)
    )

  /** `updateDelegation`: schedule every certificate of a block, then tick. */
  def update(
      env: Env,
      st: DelegationState,
      certs: Vector[ByronCert]
  ): Either[DelegationError | ProviderDefect, DelegationState] =
    certs
      .foldLeftM[[A] =>> Either[DelegationError | ProviderDefect, A], DelegationState](st)(
        schedule(env, _, _)
      )
      .map(tick(_, env.currentEpoch, env.currentSlot))

  /** The state at slot 0: the genesis heavy delegation certificates applied with `k = 0`. */
  def initial(
      magic: Bytes,
      genesisKeys: Vector[Bytes],
      certs: Vector[ByronCert],
      signatures: ByronSig.Verifier = ByronSig.Standard
  ): Either[DelegationError | ProviderDefect, DelegationState] =
    update(Env(magic, genesisKeys.toSet, 0, 0, 0, signatures), empty(genesisKeys), certs)

  extension [A](result: Either[DelegationError | ProviderDefect, A])
    /** The refusal as the enclosing rule's, a provider defect as itself. */
    def refusedAs[E](refusal: DelegationError => E): Either[E | ProviderDefect, A] = result match
      case Right(a)                 => Right(a)
      case Left(e: DelegationError) => Left(refusal(e))
      case Left(d: ProviderDefect)  => Left(d)
