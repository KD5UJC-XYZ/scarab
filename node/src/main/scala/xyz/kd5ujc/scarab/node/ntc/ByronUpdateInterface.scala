// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.byron.{
  AppVersion,
  ByronParams,
  ByronVersion,
  CandidateUpdate,
  ProtocolProposal,
  SoftwareProposal,
  UpdateState
}

/** The answer to Byron's `GetUpdateInterfaceState`: the update-interface state as
  * `encodeByronResult` writes it, `toByronCBOR`
  * (`Ouroboros/Consensus/Byron/Ledger/Ledger.hs:563-565`, ouroboros-consensus 82ecba32) of
  * `UPI.State` (`Cardano/Chain/Update/Validation/Interface.hs:157-169`, cardano-ledger f649f975).
  * `toByronCBOR` encodes at `byronProtVer`, version 1 (`Cardano/Ledger/Binary/Version.hs:163`),
  * where the containers of `cardano-ledger-binary` (`Binary/Encoding/Encoder.hs:397-408, 479-491,
  * 499-508`) are:
  *   - a `Map`, a definite map in ascending key order;
  *   - a `Set`, tag 258 around a definite list in ascending order;
  *   - a list, an indefinite list.
  *
  * The orders are the Haskell `Ord` instances: a hash (`UpId`, `KeyHash`) by its bytes, unsigned
  * and lexicographic; a text (`ApplicationName`, `SystemTag`) by its UTF-8 bytes (`text` 2); an
  * `Endorsement` by its protocol version, then its key hash. A value outside the range of the
  * Haskell type it is written as is [[Unanswered.Unwritable]].
  */
object ByronUpdateInterface:
  private type Out = Either[Unanswered, Bytes]

  def encode(state: UpdateState): Out =
    concat(
      Right(CborOut.build(_.array(11))),
      // `currentEpoch`: only `initialState` writes it, with 0, and no rule changes it
      // (`Interface.hs:225-238`)
      word(0, 64),
      Right(version(state.adoptedVersion)),
      params(state.adoptedParams),
      candidates(state.candidates),
      map(state.appVersions.toVector)(text, appVersion)(utf8),
      map(state.registeredProtocol.toVector)(upId, protocolProposal)(identity),
      map(state.registeredSoftware.toVector)(upId, softwareProposal)(identity),
      map(state.confirmed.toVector)(upId, slot)(identity),
      map(state.votes.toVector)(upId, keyHashes)(identity),
      endorsements(state.endorsements),
      map(state.registrationSlot.toVector)(upId, slot)(identity)
    )

  private def concat(parts: Out*): Out =
    parts.toVector.sequence.map(_.foldLeft(Bytes.empty)(_ ++ _))

  /** An unsigned integer of a `bits`-wide `Word`. */
  private def word(n: BigInt, bits: Int): Out =
    Either.cond(n >= 0 && n.bitLength <= bits, CborOut.build(_.uint(n)), Unanswered.Unwritable)

  /** A `Natural`, written as an `Integer` (`encodeInteger`: tag 2 past 64 bits). */
  private def natural(n: BigInt): Out =
    Either.cond(n >= 0, CborOut.build(_.integer(n)), Unanswered.Unwritable)

  private def slot(n: BigInt): Out = word(n, 64)

  /** A hash of `size` bytes (`AbstractHash`), its digest as a byte string. */
  private def hash(size: Int)(h: Bytes): Out =
    Either.cond(h.size == size, CborOut.build(_.bytes(h)), Unanswered.Unwritable)

  private val upId: Bytes => Out = hash(32)
  private val keyHash: Bytes => Out = hash(28)

  private def text(s: String): Out = Right(CborOut.build(_.text(s)))

  private def utf8(s: String): Bytes = Bytes.fromArray(s.getBytes(StandardCharsets.UTF_8))

  /** `ProtocolVersion` (`Update/ProtocolVersion.hs`): `[major, minor, alt]`; its components are
    * bounded by the type.
    */
  private def version(v: ByronVersion): Bytes =
    CborOut.build(_.array(3).uint(v.major).uint(v.minor).uint(v.alt))

  /** A definite map in ascending order of `order` of the keys. */
  private def map[K, V, O: Ordering](entries: Vector[(K, V)])(
      key: K => Out,
      value: V => Out
  )(order: K => O): Out =
    val sorted = entries.sortBy((k, _) => order(k))
    concat(
      Right(CborOut.build(_.map(sorted.size))) +:
        sorted.flatMap((k, v) => Vector(key(k), value(v)))*
    )

  /** A `Set` at version 1: tag 258 around a definite list, in ascending order. */
  private def set[A](items: Vector[A])(item: A => Out): Out =
    concat(Right(CborOut.build(_.tag(258).array(items.size))) +: items.map(item)*)

  /** `ProtocolParameters` (`Update/ProtocolParameters.hs:168-184`): fourteen fields. */
  private def params(p: ByronParams): Out =
    concat(
      Right(CborOut.build(_.array(14))),
      word(p.scriptVersion, 16),
      natural(p.slotDurationMs),
      natural(p.maxBlockSize),
      natural(p.maxHeaderSize),
      natural(p.maxTxSize),
      natural(p.maxProposalSize),
      lovelacePortion(p.mpcThd),
      lovelacePortion(p.heavyDelThd),
      lovelacePortion(p.updateVoteThd),
      lovelacePortion(p.updateProposalThd),
      slot(p.updateProposalTtl),
      softforkRule(p),
      txFeePolicy(p),
      word(p.unlockStakeEpoch, 64)
    )

  /** `LovelacePortion`, its `Word64` of parts of 10^15 (`Common/LovelacePortion.hs:75-76`). */
  private def lovelacePortion(parts: BigInt): Out = word(parts, 64)

  /** `SoftforkRule` (`Update/SoftforkRule.hs:69-75`): three portions. */
  private def softforkRule(p: ByronParams): Out =
    val rule = p.softforkRule
    concat(
      Right(CborOut.build(_.array(3))),
      lovelacePortion(rule.initThd),
      lovelacePortion(rule.minThd),
      lovelacePortion(rule.thdDecrement)
    )

  /** `TxFeePolicy` (`Common/TxFeePolicy.hs:86-91`): `[0, #6.24(bytes .cbor TxSizeLinear)]`, the
    * `TxSizeLinear` (`Common/TxSizeLinear.hs:63-68`) as two `Nano`, each an `Integer` of
    * nano-lovelace: the summand's lovelace times 10^9 and the multiplier's `fromRational`, the
    * floor of 10^9 times it. The node keeps both in nano-lovelace, the summand a whole number of
    * lovelace (the genesis reader and the update decoder round it so), the multiplier exactly the
    * rational's numerator over 10^9, so both are written as they are kept.
    */
  private def txFeePolicy(p: ByronParams): Out =
    val fee = p.txFeePolicy
    Either.cond(
      fee.summandNano >= 0 && fee.summandNano % BigInt(1000000000) == 0,
      CborOut.build { w =>
        val linear =
          CborOut.build(_.array(2).integer(fee.summandNano).integer(fee.multiplierNano))
        w.array(2).uint(0).tag(24).bytes(linear)
      },
      Unanswered.Unwritable
    )

  /** The candidates, a list: indefinite at version 1. */
  private def candidates(cs: Vector[CandidateUpdate]): Out =
    concat(
      Right(CborOut.build(_.indefiniteArray)) +: cs.map(candidate) :+
        Right(CborOut.build(_.break))*
    )

  /** `CandidateProtocolUpdate` (`Update/Validation/Endorsement.hs:84-89`): `[slot, version,
    * parameters]`.
    */
  private def candidate(c: CandidateUpdate): Out =
    concat(
      Right(CborOut.build(_.array(3))),
      slot(c.slot),
      Right(version(c.version)),
      params(c.params)
    )

  /** `ApplicationVersion` (`Update/Validation/Registration.hs:118-123`): `[number, slot,
    * metadata]`, the number a `Word32`.
    */
  private def appVersion(a: AppVersion): Out =
    concat(Right(CborOut.build(_.array(3))), word(a.number, 32), slot(a.slot), metadata(a.metadata))

  /** `ProtocolUpdateProposal` (`Update/Validation/Registration.hs:149-153`): `[version,
    * parameters]`.
    */
  private def protocolProposal(p: ProtocolProposal): Out =
    concat(Right(CborOut.build(_.array(2))), Right(version(p.version)), params(p.params))

  /** `SoftwareUpdateProposal` (`Update/Validation/Registration.hs:180-184`): `[software version,
    * metadata]`, the `SoftwareVersion` (`Update/SoftwareVersion.hs`) `[name, number]`.
    */
  private def softwareProposal(s: SoftwareProposal): Out =
    concat(
      Right(CborOut.build(_.array(2).array(2).text(s.name))),
      word(s.number, 32),
      metadata(s.metadata)
    )

  /** `Metadata`, a `Map SystemTag InstallerHash`. */
  private def metadata(m: Map[String, Bytes]): Out =
    map(m.toVector)(text, installerHash)(utf8)

  /** `hashRaw "\NUL"`, what an `InstallerHash` writes for the three hashes it does not keep. */
  private val emptyHash: Bytes = Blake2b.hash256.hash(Bytes.fromArray(Array[Byte](0)))

  /** `InstallerHash` (`Update/InstallerHash.hs:48-56`): `[empty, hash, empty, empty]`. */
  private def installerHash(h: Bytes): Out =
    concat(
      Right(CborOut.build(_.array(4).bytes(emptyHash))),
      hash(32)(h),
      Right(CborOut.build(_.bytes(emptyHash).bytes(emptyHash)))
    )

  /** A set of key hashes (the voters of a proposal). */
  private def keyHashes(voters: Set[Bytes]): Out =
    set(voters.toVector.sorted)(keyHash)

  /** The endorsements (`Endorsement`, `Update/Validation/Endorsement.hs:94-125`): `[version, key
    * hash]`, in the order of their derived `Ord`.
    */
  private def endorsements(es: Set[(ByronVersion, Bytes)]): Out =
    set(es.toVector.sorted) { (v, key) =>
      concat(Right(CborOut.build(_.array(2))), Right(version(v)), keyHash(key))
    }
