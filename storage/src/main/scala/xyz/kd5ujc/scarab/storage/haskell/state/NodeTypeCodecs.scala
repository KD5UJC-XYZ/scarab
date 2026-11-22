// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import cats.syntax.all.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError, Interned, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Committee,
  Constitution,
  ConwayParamUpdate,
  Credential,
  DRep,
  DRepState,
  DecodeField,
  DecoderVersion,
  EnactState,
  GenDelegPair,
  GovAction,
  GovActionId,
  GovActionState,
  LedgerDecodeError,
  ParamUpdate,
  PoolParams,
  PrevIds,
  Proposal,
  Proposals,
  ProtocolParams,
  PulsingSnapshot,
  RatifyState,
  RewardAccount,
  Vote
}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayGovState as EraConwayGovState,
  DRepPulsingState,
  DecodeAt,
  EnactState as EraEnactState,
  EraPParams,
  FuturePParams as EraFuturePParams,
  GovAction as EraGovAction,
  GovActionState as EraGovActionState,
  PParams,
  PParamsUpdate,
  ProposalProcedure,
  Proposals as EraProposals,
  PulsingSnapshot as EraPulsingSnapshot,
  RatifyState as EraRatifyState
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayProposals
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraProposals
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{Coin, Dijkstra, ProtVer, Sorted, Staking}
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, StateLayout}
import xyz.kd5ujc.scarab.models.runtime.{
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer,
  at
}

import DecodeError.Kind
import NodeOrders.given
import Wire.*

/** `StakePoolParams` of ten items, its third the BLS key's place: holding none
  * ([[NodeTypeCodecs.poolParams]]) or a key (`CertStateCodecs.stakePoolParams`).
  */
private[state] final case class PoolParamsBlsSlot[K](
    operator: Bytes,
    vrfKeyHash: Bytes,
    blsKey: K,
    pledge: BigInt,
    cost: BigInt,
    margin: Rational,
    rewardAccount: RewardAccount,
    owners: Set[Bytes],
    relays: Bytes,
    metadata: Option[Bytes]
):
  def params: PoolParams =
    PoolParams(operator, vrfKeyHash, pledge, cost, margin, rewardAccount, owners, relays, metadata)

/** The codecs of the types the mirror shares with the node's own ledger (`Committee`, `Proposals`,
  * `EnactState`, `PoolParams`, ...): the shapes the node-to-client answers are written in, and
  * their readers. The node's values keep maps and sets unordered and hashes as plain byte strings,
  * so the codecs sort on writing (by [[NodeOrders]]) and check the sizes on reading.
  */
trait NodeTypeCodecs extends VocabularyCodecs:
  /** How this family reads the governance proposals of a Conway state: as the reference reads its
    * `state` file, or as a native snapshot wrote them.
    */
  def proposalsReading: ProposalsReading

  // ---- byte strings and numbers the node keeps as `Bytes` and `BigInt`

  private val hash28: Codec[Bytes] = Wire.hash(28)
  private val credentials = new Interned[Credential]

  /** The hash of a credential, not shared by itself: the credential is, whole, and a second table
    * for its hash would double the lookups of the largest collections of the state.
    */
  private val credentialHash: Codec[Bytes] = Codec.bytesSized(28, 28)
  private val hash32Bytes: Codec[Bytes] = Wire.hash(32)

  /** `Coin`, `EpochNo`, `CompactForm Coin` held as a number: a `Word64`. */
  private val amount: Codec[BigInt] = word(64)

  /** An item kept as it is on the wire, used by pre-Conway parameter updates. */
  val rawItem: Codec[Bytes] = new Codec[Bytes]:
    def decode(n: Node): Decoded[Bytes] = Right(n.original)
    def encode(a: Bytes, out: Writer): Unit = out.raw(a)

  private def sortedMap[K: Order, V](key: Codec[K], value: Codec[V])(using
      ProtVer
  ): Codec[Map[K, V]] =
    mapOf(key, value).imap(m => m: Map[K, V])(m => Sorted.map(m))

  private def sortedSet[A: Order](elem: Codec[A])(using ProtVer): Codec[Set[A]] =
    setOf(elem).imap(s => s: Set[A])(s => Sorted.set(s))

  // ---- credentials, keys and addresses

  /** A credential is shared across a decode as a whole: the voters of a proposal are the voters of
    * the others and of the DRep pulser's copy of them, a million objects each.
    */
  given nodeCredential: Codec[Credential] = (new Codec[Credential]:
    def decode(n: Node): Decoded[Credential] =
      variant(n).flatMap {
        case (t, Vector(h)) if t == 0 => credentialHash.decode(h).map(Credential.Key(_)).at("hash")
        case (t, Vector(h)) if t == 1 =>
          credentialHash.decode(h).map(Credential.Script(_)).at("hash")
        case (t, xs) => unknownVariant(n, "Credential", t, xs.size)
      }
    def encode(a: Credential, out: Writer): Unit =
      out.arrayHead(2)
      a match
        case Credential.Key(h) =>
          out.uint(0L)
          out.bytes(h)
        case Credential.Script(h) =>
          out.uint(1L)
          out.bytes(h)
    override def check(a: Credential): Option[ConstraintViolation] = credentialHash.check(a.hash)
  ).interned(credentials)

  given nodeDRep: Codec[DRep] = new Codec[DRep]:
    def decode(n: Node): Decoded[DRep] =
      variant(n).flatMap {
        case (t, Vector(h)) if t == 0 =>
          hash28.decode(h).map(x => DRep.Registered(Credential.Key(x))).at("hash")
        case (t, Vector(h)) if t == 1 =>
          hash28.decode(h).map(x => DRep.Registered(Credential.Script(x))).at("hash")
        case (t, Vector()) if t == 2 => Right(DRep.AlwaysAbstain)
        case (t, Vector()) if t == 3 => Right(DRep.AlwaysNoConfidence)
        case (t, xs)                 => unknownVariant(n, "DRep", t, xs.size)
      }
    def encode(a: DRep, out: Writer): Unit = a match
      case DRep.Registered(Credential.Key(h)) =>
        out.arrayHead(2); out.uint(0L); out.bytes(h)
      case DRep.Registered(Credential.Script(h)) =>
        out.arrayHead(2); out.uint(1L); out.bytes(h)
      case DRep.AlwaysAbstain =>
        out.arrayHead(1); out.uint(2L)
      case DRep.AlwaysNoConfidence =>
        out.arrayHead(1); out.uint(3L)

  /** `AccountAddress` (`Address.hs:383-385`, `EncCBOR` of the address bytes): a byte string of 29
    * bytes, a header `0xe_` (key) or `0xf_` (script) with the network in its low nibble, and the
    * credential hash.
    */
  given rewardAccount: Codec[RewardAccount] = new Codec[RewardAccount]:
    private val raw = Codec.bytesSized(29, 29)
    def decode(n: Node): Decoded[RewardAccount] =
      raw.decode(n).flatMap { b =>
        val header = b.value(0) & 0xff
        val hash = Bytes(b.value.drop(1))
        (header >> 4) match
          case 14 => Right(RewardAccount(header & 0x0f, Credential.Key(hash)))
          case 15 => Right(RewardAccount(header & 0x0f, Credential.Script(hash)))
          case _ =>
            Left(DecodeError.at(n, Kind.OutOfRange("a reward account header 0xe_ or 0xf_", header)))
      }
    def encode(a: RewardAccount, out: Writer): Unit =
      val base = a.credential match
        case Credential.Key(_)    => 0xe0
        case Credential.Script(_) => 0xf0
      out.bytes(Bytes(Vector((base | a.network).toByte) ++ a.credential.hash.value))
    override def check(a: RewardAccount): Option[ConstraintViolation] =
      Codec.refuse(
        !(a.network >= 0 && a.network <= 15 && a.credential.hash.size == 28),
        "RewardAccount",
        "a network in 0..15 and a 28-byte hash",
        s"network ${a.network}, ${a.credential.hash.size} bytes"
      )

  given genDelegPair: Codec[GenDelegPair] =
    Record.of[GenDelegPair]((hash28, hash32Bytes))

  // ---- pools

  /** A `Coin` held as the reference's `Integer` (`Coin.hs:93-111`, cardano-ledger f649f975):
    * written as an `Integer` and read as a `Word64`. The pledge and cost of `StakePoolParams` and
    * the amounts of a treasury withdrawal. A genesis pool may hold a negative one or one past
    * `Word64`, which the reference's genesis reader accepts; it is written as the reference writes
    * it, in a query answer and in the state, and refused when the state is read back.
    */
  private val heldCoin: Codec[BigInt] = new Codec[BigInt]:
    private val under = word(64)
    def decode(n: Node): Decoded[BigInt] = under.decode(n)
    def encode(a: BigInt, out: Writer): Unit = out.integer(a)

  /** The third of ten items of a `StakePoolParams` in the Leios layout from version 12
    * (`decodeBlsKeyBackwardsCompatible`, cardano-ledger 9df9f98c `State/StakePool.hs:762-771`), as
    * the node's parameters read it: `null` is no key; a list is a key, which the node's parameters
    * have no place for, refused ([[DijkstraNotYet.PoolParamsBlsKey]]); the state's parameters read
    * it (`CertStateCodecs.stakePoolParams`). Never written: the writer writes nine items.
    */
  private val noBlsKey: Codec[Unit] = new Codec[Unit]:
    def decode(n: Node): Decoded[Unit] = n.value match
      case Value.Null   => Right(())
      case Value.Arr(_) => Left(DecodeError.at(n, Kind.Domain(DijkstraNotYet.PoolParamsBlsKey)))
      case _            => Left(Codec.mismatch("null or a BLS key", n))
    def encode(a: Unit, out: Writer): Unit = out.nul()

  /** `StakePoolParams` (`State/StakePool.hs:550-572`, cardano-ledger f649f975): nine fields in a
    * flat array, the owners a set, the metadata `null` when there is none. Held relays and metadata
    * are written from their values, as by [[ConwayCodecs]]; no relays at all is `Bytes.empty`,
    * written as an empty array and read back as it.
    *
    * In the Leios layout from version 12 a pool may carry its BLS key third
    * (`decodeStakePoolParamsFlat`, cardano-ledger 9df9f98c `State/StakePool.hs:744-771`): ten items
    * whose third is `null` are no key and read as the nine; ten whose third is a list are a key,
    * refused ([[DijkstraNotYet.PoolParamsBlsKey]]): the node's parameters have no place for it, and
    * the state's parameters read it (`CertStateCodecs.stakePoolParams`). The writer writes nine, as
    * the reference does for a pool with no key (`:716-736`).
    */
  given poolParams(using v: ProtVer): Codec[PoolParams] = new Codec[PoolParams]:
    private val stored = Record.of[PoolParams](
      (
        hash28,
        hash32Bytes,
        heldCoin,
        heldCoin,
        unitIntervalAt,
        rewardAccount,
        sortedSet(hash28),
        relays,
        nullable(poolMetadata)
      )
    )
    private val withSlot = poolParamsWithSlot(noBlsKey)
    private val read: Node => Decoded[PoolParams] = stateLayout match
      case StateLayout.Node11_1 => stored.decode
      case StateLayout.Leios =>
        if v.atLeast(12) then
          n =>
            n.value match
              case Value.Arr(xs) if xs.sizeIs == 10 => withSlot.decode(n).map(_.params)
              case _                                => stored.decode(n)
        else stored.decode
    def decode(n: Node): Decoded[PoolParams] = read(n)
    def encode(a: PoolParams, out: Writer): Unit =
      out.arrayHead(9)
      ConwayCodecs.poolFields(a, out)
    override def check(a: PoolParams): Option[ConstraintViolation] =
      stored.check(a).orElse(a.metadata.flatMap(poolMetadata.check))

  /** The ten items of a `StakePoolParams` in the Leios layout from version 12, the third read by
    * `slot` (`decodeStakePoolParamsFlat`, cardano-ledger 9df9f98c `State/StakePool.hs:746-771`).
    * Read only: the parameters are written by [[ConwayCodecs.poolFieldsWith]].
    */
  protected def poolParamsWithSlot[K](slot: Codec[K])(using
      ProtVer
  ): Codec[PoolParamsBlsSlot[K]] =
    Record.of[PoolParamsBlsSlot[K]](
      (
        hash28,
        hash32Bytes,
        slot,
        heldCoin,
        heldCoin,
        unitIntervalAt,
        rewardAccount,
        sortedSet(hash28),
        relays,
        nullable(poolMetadata)
      )
    )

  /** The metadata bytes retained by the ledger, written as `PoolMetadata` 's value
    * (`State/StakePool.hs:516-520`, cardano-ledger f649f975).
    */
  val poolMetadata: Codec[Bytes] = new Codec[Bytes]:
    def decode(n: Node): Decoded[Bytes] = Right(n.original)
    def encode(a: Bytes, out: Writer): Unit = ConwayCodecs.poolMetadataBytes.encode(a, out)
    override def check(a: Bytes): Option[ConstraintViolation] =
      ConwayCodecs.poolMetadataBytes.check(a)

  /** The relays of a pool, retained as bytes but written as a sequence of relay values
    * (`State/StakePool.hs:389-404, 570`, cardano-ledger f649f975); `Bytes.empty` for the empty one.
    */
  val relays: Codec[Bytes] = new Codec[Bytes]:
    private val empty = Bytes(Vector(0x80.toByte))
    def decode(n: Node): Decoded[Bytes] = n.value match
      case Value.Arr(_) => Right(if n.original == empty then Bytes.empty else n.original)
      case _            => Left(Codec.mismatch("array", n))
    def encode(a: Bytes, out: Writer): Unit =
      ConwayCodecs.poolRelays.encode(a, out)
    override def check(a: Bytes): Option[ConstraintViolation] = ConwayCodecs.poolRelays.check(a)

  // ---- governance

  /** A parameter update (`PParamsUpdate`): a map from the parameter's index to its new value, the
    * values kept as they came; written in key order.
    */
  given paramUpdate: Codec[ParamUpdate] = new Codec[ParamUpdate]:
    private val key = Codec.uintBig(255)
    def decode(n: Node): Decoded[ParamUpdate] =
      Codec.pairs(key, rawItem).decode(n).flatMap { pairs =>
        val fields = pairs.map((k, v) => k.toInt -> v).toMap
        if fields.sizeIs == pairs.size then Right(ParamUpdate(fields))
        else Left(DecodeError.at(n, Kind.DuplicateKey("a parameter index")))
      }
    def encode(a: ParamUpdate, out: Writer): Unit =
      out.variableMap(a.fields.size)(a.fields.toVector.sortBy(_._1).foreach { (k, v) =>
        out.uint(k.toLong)
        out.raw(v)
      })

  /** The Conway `PParamsUpdate`: a definite map of field values in reference order
    * (`Core/PParams.hs:244-255`, `Conway/PParams.hs:862-894`, cardano-ledger f649f975), written by
    * [[ConwayCodecs.parameterUpdate]]. The stored-update reader keeps accepting earlier encodings.
    */
  given conwayParamUpdate: Codec[ConwayParamUpdate] = new Codec[ConwayParamUpdate]:
    def decode(n: Node): Decoded[ConwayParamUpdate] =
      ConwayParamUpdate.decodeStored(n).left.map(why => DecodeError.at(n, Kind.Domain(why)))
    def encode(a: ConwayParamUpdate, out: Writer): Unit =
      ConwayCodecs.parameterUpdate.encode(a, out)
    override def check(a: ConwayParamUpdate): Option[ConstraintViolation] =
      ConwayCodecs.parameterUpdate.check(a)

  /** The keys of the parameters Dijkstra adds to an update, 34 to 56 (cardano-ledger 9df9f98c
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs:798-1123`); the keys up to 33 are
    * Conway's, the same `PParam` s (`:741-776`).
    */
  private val dijkstraUpdateKeys: Set[BigInt] = (34 to 56).map(BigInt(_)).toSet

  /** The `PParamsUpdate` of a flat proposal at version 12 (`decodeSparseKeyed` over the era's keys,
    * each value `decCBOR` at 12, cardano-ledger 9df9f98c `Core/PParams.hs:264-299`): Conway's keys
    * as a body is read at 12 ([[ConwayParamUpdate.decode]]), its bounded ratios of `Word64` s and
    * its `ExUnits` and `Prices` definite or indefinite. The flat update holds Conway's keys only,
    * so a key of a parameter Dijkstra adds is refused ([[DijkstraNotYet.ParameterUpdate]]), any
    * other unknown key as the reference refuses it. A Dijkstra state's proposals are not read so:
    * its governance state is the era's record ([[dijkstraGovState]]).
    */
  private val paramUpdateFrom12: Codec[ConwayParamUpdate] = new Codec[ConwayParamUpdate]:
    def decode(n: Node): Decoded[ConwayParamUpdate] =
      ConwayParamUpdate
        .decode(n, DecoderVersion.Dijkstra)
        .left
        .map {
          case LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, key)
              if dijkstraUpdateKeys.contains(key) =>
            DecodeError.at(n, Kind.Domain(DijkstraNotYet.ParameterUpdate))
          case why => DecodeError.at(n, Kind.Domain(why))
        }
    def encode(a: ConwayParamUpdate, out: Writer): Unit = conwayParamUpdate.encode(a, out)
    override def check(a: ConwayParamUpdate): Option[ConstraintViolation] =
      conwayParamUpdate.check(a)

  given govActionId: Codec[GovActionId] =
    Record.of[GovActionId]((hash32Bytes, word16))

  given vote: Codec[Vote] = new Codec[Vote]:
    def decode(n: Node): Decoded[Vote] = n.value match
      case Value.UInt(v) if v == 0 => Right(Vote.No)
      case Value.UInt(v) if v == 1 => Right(Vote.Yes)
      case Value.UInt(v) if v == 2 => Right(Vote.Abstain)
      case Value.UInt(v)           => Left(DecodeError.at(n, Kind.OutOfRange("0..2", v)))
      case _                       => Left(Codec.mismatch("unsigned integer", n))
    def encode(a: Vote, out: Writer): Unit = out.uint(a.ordinal.toLong)

  /** `Committee` (`Procedures.hs:596-602`): the members with the last epoch of their term, and the
    * threshold (a unit interval).
    */
  given committee(using ProtVer): Codec[Committee] =
    Record.of[Committee]((sortedMap(nodeCredential, amount), unitIntervalAt))

  given constitution(using ProtVer): Codec[Constitution] =
    Record.of[Constitution]((anchor, nullable(hash28)))

  private def previous: Codec[Option[GovActionId]] = nullable(govActionId)

  /** `GovAction` (`Procedures.hs:928-946`): `[tag, ...]`, the tag the constructor. A hard fork's
    * version is read by `decodeProtVer @era` (`Procedures.hs:876-889`, cardano-ledger f649f975):
    * the `ProtVer` whole, a `Version` major (at most `MaxVersion`, 13) and a `Word32` minor, and
    * then a major above `succVersion (ProtVerHigh era)` refused (`BaseTypes.hs:243-263`): 12 for a
    * Conway state, whose version is 9 to 11, and 13 from version 12
    * (`Internal/Definition/Era.hs:132-141`). `EncCBOR` writes the held value; `check` refuses only
    * what the reference's types cannot hold ([[ConwayCodecs.govActionBounds]]), not the transaction
    * writer's rules.
    */
  given govAction(using v: ProtVer): Codec[GovAction] = new Codec[GovAction]:
    private val policy = nullable(hash28)
    private val withdrawals = sortedMap(rewardAccount, heldCoin)
    private val removed = sortedSet(nodeCredential)
    private val added = sortedMap(nodeCredential, amount)
    private val version = Record.of[(BigInt, BigInt)]((Codec.uintBig(13), word32))
    private val maxMajor: BigInt = if v.atLeast(12) then 13 else 12
    private val threshold = unitIntervalAt
    private val parameters = if v.atLeast(12) then paramUpdateFrom12 else conwayParamUpdate
    def decode(n: Node): Decoded[GovAction] =
      variant(n).flatMap {
        case (t, Vector(g, u, p)) if t == 0 =>
          for
            prev <- previous.decode(g).at("previous")
            update <- parameters.decode(u).at("update")
            pol <- policy.decode(p).at("policy")
          yield GovAction.ParameterChange(prev, update, pol)
        case (t, Vector(g, pv)) if t == 1 =>
          for
            prev <- previous.decode(g).at("previous")
            ver <- version.decode(pv).at("protocolVersion")
            _ <- Either
              .cond(
                ver._1 <= maxMajor,
                (),
                DecodeError.at(pv, Kind.OutOfRange(s"0..$maxMajor", ver._1))
              )
              .at("protocolVersion")
          yield GovAction.HardFork(prev, ver._1, ver._2)
        case (t, Vector(w, p)) if t == 2 =>
          for
            ws <- withdrawals.decode(w).at("withdrawals")
            pol <- policy.decode(p).at("policy")
          yield GovAction.TreasuryWithdrawals(Sorted.map(ws).toVector, pol)
        case (t, Vector(g)) if t == 3 =>
          previous.decode(g).at("previous").map(GovAction.NoConfidence(_))
        case (t, Vector(g, r, a, q)) if t == 4 =>
          for
            prev <- previous.decode(g).at("previous")
            gone <- removed.decode(r).at("removed")
            members <- added.decode(a).at("added")
            quorum <- threshold.decode(q).at("quorum")
          yield GovAction.UpdateCommittee(prev, gone, members, quorum)
        case (t, Vector(g, c)) if t == 5 =>
          for
            prev <- previous.decode(g).at("previous")
            k <- constitution.decode(c).at("constitution")
          yield GovAction.NewConstitution(prev, k.script, k.anchor)
        case (t, Vector()) if t == 6 => Right(GovAction.Info)
        case (t, xs)                 => unknownVariant(n, "GovAction", t, xs.size)
      }
    def encode(a: GovAction, out: Writer): Unit = a match
      case GovAction.ParameterChange(prev, update, pol) =>
        out.arrayHead(4); out.uint(0L)
        previous.encode(prev, out); parameters.encode(update, out)
        policy.encode(pol, out)
      case GovAction.HardFork(prev, major, minor) =>
        out.arrayHead(3); out.uint(1L)
        previous.encode(prev, out); version.encode((major, minor), out)
      case GovAction.TreasuryWithdrawals(ws, pol) =>
        out.arrayHead(3); out.uint(2L)
        // a map: a repeated account is one entry holding the sum (`Map RewardAccount Coin`)
        withdrawals.encode(Sorted.map(ws.groupMapReduce(_._1)(_._2)(_ + _)), out)
        policy.encode(pol, out)
      case GovAction.NoConfidence(prev) =>
        out.arrayHead(2); out.uint(3L)
        previous.encode(prev, out)
      case GovAction.UpdateCommittee(prev, gone, members, quorum) =>
        out.arrayHead(5); out.uint(4L)
        previous.encode(prev, out); removed.encode(gone, out); added.encode(members, out)
        threshold.encode(quorum, out)
      case GovAction.NewConstitution(prev, script, anchor) =>
        out.arrayHead(3); out.uint(5L)
        previous.encode(prev, out); constitution.encode(Constitution(anchor, script), out)
      case GovAction.Info =>
        out.arrayHead(1); out.uint(6L)
    override def check(a: GovAction): Option[ConstraintViolation] = ConwayCodecs.govActionBounds(a)

  /** `ProposalProcedure` (`Procedures.hs:537-543`): the deposit, the account the deposit returns
    * to, the action and its anchor.
    */
  given proposal(using ProtVer): Codec[Proposal] =
    Record.of[Proposal]((amount, rewardAccount, govAction, anchor))

  /** `GovActionState` (`Procedures.hs:318-333`): the id, the votes of the committee, the DReps and
    * the pools, the proposal, and the epochs it was proposed in and expires after.
    */
  given govActionState(using ProtVer): Codec[GovActionState] =
    Record.of[GovActionState](
      (
        govActionId,
        sortedMap(nodeCredential, vote),
        sortedMap(nodeCredential, vote),
        sortedMap(hash28, vote),
        proposal,
        amount,
        amount
      )
    )

  /** `GovRelation StrictMaybe` (`Procedures.hs:742-750`): the last enacted action of each lineage.
    */
  given prevIds: Codec[PrevIds] =
    val id = strictMaybe(govActionId)
    Record.of[PrevIds]((id, id, id, id))

  /** `Proposals` (`Proposals.hs:385-388`, `encCBOR (roots, props)`): the lineage roots and the
    * proposals in order. A reference `state` file is read as `decSharePlusCBOR` reads it
    * (`Proposals.hs:401-406`): of the proposals with one id the first is kept (`OMap.|>`,
    * `Data/OMap/Strict.hs:179-188`, under `decodeListLikeWithCountT`, cardano-ledger-binary
    * 048b78d2, `Decoding/Decoder.hs:1013-1039`), and the store is rebuilt by `mkProposals`
    * ([[Proposals.rebuild]]), which refuses a proposal that attaches to neither its lineage's root
    * nor an earlier proposal of its lineage. A native snapshot's store is read as it was written
    * ([[ProposalsReading.AsWritten]]).
    */
  given proposals(using ProtVer): Codec[Proposals] = new Codec[Proposals]:
    private val stored =
      Record.of[(PrevIds, Vector[GovActionState])]((prevIds, seqOf(govActionState)))
    def decode(n: Node): Decoded[Proposals] =
      stored.decode(n).flatMap { (roots, actions) =>
        proposalsReading match
          case ProposalsReading.Reference =>
            Proposals
              .rebuild(roots, actions.distinctBy(_.id))
              .left
              .map(why => DecodeError.at(n, Kind.Domain(why)))
          case ProposalsReading.AsWritten => Right(Proposals(actions, roots))
      }
    def encode(p: Proposals, out: Writer): Unit = stored.encode((p.roots, p.actions), out)
    override def check(p: Proposals): Option[ConstraintViolation] =
      stored.check((p.roots, p.actions))

  /** `EnactState` at the Conway parameters ([[enactStateWith]]). */
  given enactState(using ProtVer): Codec[EnactState] = enactStateWith(Params.conwayCodec)

  /** `EnactState` (`Conway/Governance/Internal.hs:235-245`, cardano-ledger 9df9f98c): `[committee,
    * constitution, current parameters, previous parameters, treasury, withdrawals, previous
    * actions]`, the parameters those of the era (`params`): Dijkstra's state holds Conway's record
    * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Governance.hs:29`) over its own parameters.
    */
  def enactStateWith(params: Codec[ProtocolParams])(using ProtVer): Codec[EnactState] =
    Record.of[EnactState](
      (
        strictMaybe(committee),
        constitution,
        params,
        params,
        amount,
        sortedMap(nodeCredential, amount),
        prevIds
      )
    )

  /** `RatifyState` at the Conway parameters ([[ratifyStateWith]]). */
  given ratifyState(using ProtVer): Codec[RatifyState] = ratifyStateWith(Params.conwayCodec)

  /** `RatifyState` (`Conway/Governance/Internal.hs:670-678`, cardano-ledger 9df9f98c): the enact
    * state, its parameters those of the era (`params`), the actions to enact, the expired ones and
    * whether a delaying action was enacted.
    */
  def ratifyStateWith(params: Codec[ProtocolParams])(using ProtVer): Codec[RatifyState] =
    Record.of[RatifyState](
      (enactStateWith(params), seqOf(govActionState), sortedSet(govActionId), Codec.bool)
    )

  /** The DRep state of the node's ledger (`DRep.hs:165-198`): the expiry, the anchor (a
    * `StrictMaybe`), the deposit and the delegators (a set). The node holds the amounts as numbers.
    */
  given nodeDRepState(using ProtVer): Codec[DRepState] =
    Record
      .of[(BigInt, Option[Anchor], BigInt, Set[Credential])](
        (amount, strictMaybe(anchor), amount, sortedSet(nodeCredential))
      )
      .imap { case (expiry, anchor, deposit, delegs) =>
        DRepState(deposit, expiry, anchor, delegs)
      }(d => (d.expiry, d.anchor, d.deposit, d.delegs))

  /** `PulsingSnapshot` (`DRepPulser.hs:147-155`, `Rec`): the proposals, the DRep distribution, the
    * DRep states and the pool distribution.
    */
  given pulsingSnapshot(using ProtVer): Codec[PulsingSnapshot] =
    Record.of[PulsingSnapshot](
      (
        seqOf(govActionState),
        sortedMap(nodeDRep, amount),
        sortedMap(nodeCredential, nodeDRepState),
        sortedMap(hash28, amount)
      )
    )

  // ---- Dijkstra's governance state, on the era's types

  /** The codec of `A` from one of `R`: read through `make`, whose refusal is a located decode error
    * of the domain, and written as the `R` it came from.
    */
  private def eraRead[R, A](c: Codec[R])(make: R => Either[DomainError, A])(
      unmake: A => R
  ): Codec[A] = new Codec[A]:
    def decode(n: Node): Decoded[A] =
      c.decode(n).flatMap(make(_).left.map(e => DecodeError.at(n, Kind.Domain(e))))
    def encode(a: A, out: Writer): Unit = c.encode(unmake(a), out)
    override def check(a: A): Option[ConstraintViolation] = c.check(unmake(a))

  /** Where the era's decoders of a Dijkstra state's values read: at version 12, the version a
    * Dijkstra state is read at ([[Versions.eraLow]]).
    */
  private def dijkstraAt(site: DecodeField): DecodeAt = DecodeAt(DecoderVersion.Dijkstra, site)

  /** `PParams DijkstraEra` as the Leios build's state holds it: `EncCBOR (PParams era)`, a list of
    * the era's parameters in the order of its descriptions (`Core/PParams.hs:204-219`,
    * cardano-ledger 9df9f98c; [[PParams.encodePParams]], [[PParams.decodePParams]]). The 11.1
    * layout's Dijkstra parameters are not the Leios build's, and are refused both ways
    * ([[Versions.notYet]]).
    */
  def dijkstraPParams: Codec[PParams[Dijkstra]] = stateLayout match
    case StateLayout.Node11_1 => Versions.notYet("PParams")
    case StateLayout.Leios =>
      new Codec[PParams[Dijkstra]]:
        def decode(n: Node): Decoded[PParams[Dijkstra]] =
          PParams
            .decodePParams[Dijkstra](n)(using
              EraPParams.dijkstra,
              dijkstraAt(DecodeField.ProtocolParameters)
            )
            .left
            .map(why => DecodeError.at(n, Kind.Domain(why)))
        def encode(a: PParams[Dijkstra], out: Writer): Unit =
          out.raw(PParams.encodePParams(a)(using EraPParams.dijkstra).bytes)

  /** `FuturePParams DijkstraEra` (`State/Governance.hs:148-159`, cardano-ledger 9df9f98c): `[0]`,
    * `[1, params]` or `[2, params?]`, the parameters [[dijkstraPParams]].
    */
  private def dijkstraFuturePParams: Codec[EraFuturePParams[Dijkstra]] =
    new Codec[EraFuturePParams[Dijkstra]]:
      private val params = dijkstraPParams
      private val maybeParams = strictMaybe(params)
      def decode(n: Node): Decoded[EraFuturePParams[Dijkstra]] =
        variant(n).flatMap {
          case (t, Vector()) if t == 0 => Right(EraFuturePParams.NoPParamsUpdate())
          case (t, Vector(p)) if t == 1 =>
            params.decode(p).map(EraFuturePParams.DefinitePParamsUpdate(_)).at("params")
          case (t, Vector(p)) if t == 2 =>
            maybeParams.decode(p).map(EraFuturePParams.PotentialPParamsUpdate(_)).at("params")
          case (t, xs) => unknownVariant(n, "FuturePParams", t, xs.size)
        }
      def encode(a: EraFuturePParams[Dijkstra], out: Writer): Unit = a match
        case EraFuturePParams.NoPParamsUpdate() =>
          out.arrayHead(1); out.uint(0L)
        case EraFuturePParams.DefinitePParamsUpdate(p) =>
          out.arrayHead(2); out.uint(1L); params.encode(p, out)
        case EraFuturePParams.PotentialPParamsUpdate(p) =>
          out.arrayHead(2); out.uint(2L); maybeParams.encode(p, out)
      override def check(a: EraFuturePParams[Dijkstra]): Option[ConstraintViolation] = a match
        case EraFuturePParams.NoPParamsUpdate()         => None
        case EraFuturePParams.DefinitePParamsUpdate(p)  => params.check(p)
        case EraFuturePParams.PotentialPParamsUpdate(p) => maybeParams.check(p)

  /** `ProposalProcedure DijkstraEra` (`Procedures.hs:567-580`, cardano-ledger 9df9f98c): `[deposit,
    * return account, action, anchor]`, read by Dijkstra's decoder at version 12
    * ([[DijkstraProposals.proposal]]). Its action is written as the flat record's writer writes it
    * ([[govAction]]), but a parameter change, `[0, previous / null, update, policy / null]` with
    * the era's update written by `EncCBOR (PParamsUpdate era)` (`Core/PParams.hs:250-262`,
    * [[PParamsUpdate.encodePParamsUpdate]]): a definite map of the fields set, by ascending key,
    * Dijkstra's keys 34 to 56 among them.
    */
  def dijkstraProposal(using ProtVer): Codec[ProposalProcedure[Dijkstra]] =
    new Codec[ProposalProcedure[Dijkstra]]:
      private val action = govAction
      private val policy = nullable(hash28)
      def decode(n: Node): Decoded[ProposalProcedure[Dijkstra]] =
        DijkstraProposals
          .proposal(n)
          .left
          .map(e => DecodeError.at(n, Kind.Domain(EraGovReadError.Proposal(e))))
      private def writeAction(a: EraGovAction[Dijkstra], out: Writer): Unit =
        DijkstraProposals.flatOrUpdate(a) match
          case Right(flat) => action.encode(flat, out)
          case Left(EraGovAction.ParameterChange(parent, update, guardrails)) =>
            out.arrayHead(4); out.uint(0L)
            previous.encode(parent.map(_.unGovPurposeId), out)
            out.raw(PParamsUpdate.encodePParamsUpdate(update)(using EraPParams.dijkstra).bytes)
            policy.encode(guardrails.map(_.bytes), out)
      def encode(p: ProposalProcedure[Dijkstra], out: Writer): Unit =
        out.arrayHead(4)
        amount.encode(p.pProcDeposit.value, out)
        rewardAccount.encode(p.pProcReturnAddr, out)
        writeAction(p.pProcGovAction, out)
        anchor.encode(p.pProcAnchor, out)
      override def check(p: ProposalProcedure[Dijkstra]): Option[ConstraintViolation] =
        amount
          .check(p.pProcDeposit.value)
          .orElse(rewardAccount.check(p.pProcReturnAddr))
          .orElse(DijkstraProposals.flatOrUpdate(p.pProcGovAction).toOption.flatMap(action.check))
          .orElse(anchor.check(p.pProcAnchor))

  /** `GovActionState DijkstraEra` ([[govActionState]]'s items), its proposal [[dijkstraProposal]]:
    * read into the era's ([[ConwayProposals.actionOf]]).
    */
  private def dijkstraGovActionState(using ProtVer): Codec[EraGovActionState[Dijkstra]] =
    eraRead(
      Record.of[
        (
            GovActionId,
            Map[Credential, Vote],
            Map[Credential, Vote],
            Map[Bytes, Vote],
            ProposalProcedure[Dijkstra],
            BigInt,
            BigInt
        )
      ](
        (
          govActionId,
          sortedMap(nodeCredential, vote),
          sortedMap(nodeCredential, vote),
          sortedMap(hash28, vote),
          dijkstraProposal,
          amount,
          amount
        )
      )
    )((id, committee, dreps, pools, p, proposedIn, expiresAfter) =>
      ConwayProposals
        .actionOf[Dijkstra](id, committee, dreps, pools, p, proposedIn, expiresAfter)
        .left
        .map(EraGovReadError.Action(_))
    )(gas =>
      (
        gas.gasId,
        gas.gasCommitteeVotes.iterator.map((c, v) => Convert.back(c) -> v).toMap,
        gas.gasDRepVotes.iterator.map((c, v) => Convert.back(c) -> v).toMap,
        gas.gasStakePoolVotes.iterator.map((k, v) => k.bytes -> v).toMap,
        gas.gasProposalProcedure,
        gas.gasProposedIn.toBigInt,
        gas.gasExpiresAfter.toBigInt
      )
    )

  /** `Proposals DijkstraEra` ([[proposals]]'s items): the roots and the actions in order, the store
    * `mkProposals` (`Proposals.hs:340-353`, cardano-ledger 9df9f98c) over them, as
    * [[ProposalsReading]] says: of the proposals of one id the first kept, as the reference reads
    * its `state` file, or the actions as written, which `mkProposals` takes as they are.
    */
  private def dijkstraProposals(using ProtVer): Codec[EraProposals[Dijkstra]] =
    eraRead(
      Record.of[(PrevIds, Vector[EraGovActionState[Dijkstra]])](
        (prevIds, seqOf(dijkstraGovActionState))
      )
    )((roots, actions) =>
      val read = proposalsReading match
        case ProposalsReading.Reference => actions.distinctBy(_.gasId)
        case ProposalsReading.AsWritten => actions
      EraProposals
        .mkProposals[Dijkstra](ConwayProposals.roots(roots), read)
        .left
        .map(EraGovReadError.Action(_))
    )(ps =>
      (
        ConwayProposals.prevIds(EraProposals.toPrevGovActionIds(ps.pRoots)),
        ps.pProps.toVector
      )
    )

  /** `EnactState DijkstraEra` ([[enactStateWith]]'s items), its parameters [[dijkstraPParams]] and
    * its withdrawals keyed by stake credentials.
    */
  private def dijkstraEnactState(using ProtVer): Codec[EraEnactState[Dijkstra]] =
    val params = dijkstraPParams
    eraRead(
      Record.of[
        (
            Option[Committee],
            Constitution,
            PParams[Dijkstra],
            PParams[Dijkstra],
            BigInt,
            Map[Credential, BigInt],
            PrevIds
        )
      ](
        (
          strictMaybe(committee),
          constitution,
          params,
          params,
          amount,
          sortedMap(nodeCredential, amount),
          prevIds
        )
      )
    )((committee, constitution, cur, prev, treasury, withdrawals, roots) =>
      withdrawals.toVector
        .traverse((c, v) => Convert.credential[Staking](c).map(_ -> Coin(v)))
        .map(ws =>
          EraEnactState[Dijkstra](
            committee,
            constitution,
            cur,
            prev,
            Coin(treasury),
            Sorted.map(ws),
            ConwayProposals.roots(roots)
          )
        )
    )(e =>
      (
        e.ensCommittee,
        e.ensConstitution,
        e.ensCurPParams,
        e.ensPrevPParams,
        e.ensTreasury.value,
        e.ensWithdrawals.iterator.map((c, v) => Convert.back(c) -> v.value).toMap,
        ConwayProposals.prevIds(e.ensPrevGovActionIds)
      )
    )

  /** `RatifyState DijkstraEra` ([[ratifyStateWith]]'s items). */
  def dijkstraRatifyState(using ProtVer): Codec[EraRatifyState[Dijkstra]] =
    Record
      .of[
        (EraEnactState[Dijkstra], Vector[EraGovActionState[Dijkstra]], Set[GovActionId], Boolean)
      ](
        (dijkstraEnactState, seqOf(dijkstraGovActionState), sortedSet(govActionId), Codec.bool)
      )
      .imap((enact, enacted, expired, delayed) =>
        EraRatifyState[Dijkstra](enact, enacted, SortedSet.from(expired), delayed)
      )(r => (r.rsEnactState, r.rsEnacted, r.rsExpired, r.rsDelayed))

  /** `DRepPulsingState DijkstraEra` as `DRComplete` ([[drCompleteWith]]'s items): the snapshot
    * ([[pulsingSnapshot]]'s items) and the ratification.
    */
  private def dijkstraDRepPulsingState(using ProtVer): Codec[DRepPulsingState[Dijkstra]] =
    val snapshot = Record
      .of[
        (
            Vector[EraGovActionState[Dijkstra]],
            Map[DRep, BigInt],
            Map[Credential, DRepState],
            Map[Bytes, BigInt]
        )
      ](
        (
          seqOf(dijkstraGovActionState),
          sortedMap(nodeDRep, amount),
          sortedMap(nodeCredential, nodeDRepState),
          sortedMap(hash28, amount)
        )
      )
      .imap(EraPulsingSnapshot[Dijkstra](_, _, _, _))(s =>
        (s.psProposals, s.psDRepDistr, s.psDRepState, s.psPoolDistr)
      )
    Record
      .of[(EraPulsingSnapshot[Dijkstra], EraRatifyState[Dijkstra])]((snapshot, dijkstraRatifyState))
      .imap(DRepPulsingState.DRComplete(_, _))({ case DRepPulsingState.DRComplete(s, r) =>
        (s, r)
      })

  /** `ConwayGovState DijkstraEra`, the governance state of a Dijkstra state
    * (`Conway/Governance.hs:359-369`, cardano-ledger 9df9f98c; `Dijkstra/Governance.hs:29`):
    * `[proposals, committee, constitution, current parameters, previous parameters, future
    * parameters, DRep pulser]`, read and written on the era's types, as the reference writes them
    * (#85, FMT-1).
    */
  def dijkstraGovState(using ProtVer): Codec[EraConwayGovState[Dijkstra]] =
    val params = dijkstraPParams
    Record.of[EraConwayGovState[Dijkstra]](
      (
        dijkstraProposals,
        strictMaybe(committee),
        constitution,
        params,
        params,
        dijkstraFuturePParams,
        dijkstraDRepPulsingState
      )
    )

/** Why a stored value of Dijkstra's governance state is not a value of the era's types: a proposal
  * Dijkstra's decoder refuses ([[xyz.kd5ujc.scarab.ledger.era.EraTxError]]), or an action's state
  * or the proposals the era does not read ([[xyz.kd5ujc.scarab.ledger.era.ProposalsReadError]]).
  */
enum EraGovReadError extends DomainError:
  case Proposal(cause: xyz.kd5ujc.scarab.ledger.era.EraTxError)
  case Action(cause: xyz.kd5ujc.scarab.ledger.era.ProposalsReadError)
