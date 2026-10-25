// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import xyz.kd5ujc.scarab.codec.{Bytes, Interned, Value}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Committee,
  Constitution,
  ConwayParamUpdate,
  Credential,
  DRep,
  DRepState,
  EnactState,
  GenDelegPair,
  GovAction,
  GovActionId,
  GovActionState,
  ParamUpdate,
  PoolParams,
  PrevIds,
  Proposal,
  Proposals,
  PulsingSnapshot,
  RatifyState,
  RewardAccount,
  Vote
}
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, Sorted}
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

/** The codecs of the types the mirror shares with the node's own ledger (`Committee`, `Proposals`,
  * `EnactState`, `PoolParams`, ...): the shapes the node-to-client answers are written in, and
  * their readers. The node's values keep maps and sets unordered and hashes as plain byte strings,
  * so the codecs sort on writing (by [[NodeOrders]]) and check the sizes on reading.
  */
trait NodeTypeCodecs extends VocabularyCodecs:
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

  /** An item kept as it is on the wire (the node keeps the relays and the metadata of a pool, and
    * the parameter updates of a proposal, as the transaction gave them).
    */
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
      val base = if a.credential.isInstanceOf[Credential.Script] then 0xf0 else 0xe0
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

  /** `StakePoolParams` (`StakePool.hs:550-571`): nine fields in a flat array, the owners a set, the
    * metadata `null` when there is none. The relays and the metadata are the registered items, as
    * they came; no relays at all is `Bytes.empty`, written as an empty array and read back as it.
    */
  given poolParams(using ProtVer): Codec[PoolParams] =
    Record.of[PoolParams](
      (
        hash28,
        hash32Bytes,
        amount,
        amount,
        unitInterval,
        rewardAccount,
        sortedSet(hash28),
        relays,
        nullable(rawItem)
      )
    )

  /** The relays of a pool, a sequence: the item as it is, `Bytes.empty` for the empty one. */
  val relays: Codec[Bytes] = new Codec[Bytes]:
    private val empty = Bytes(Vector(0x80.toByte))
    def decode(n: Node): Decoded[Bytes] = n.value match
      case Value.Arr(_) => Right(if n.original == empty then Bytes.empty else n.original)
      case _            => Left(Codec.mismatch("array", n))
    def encode(a: Bytes, out: Writer): Unit =
      if a.size == 0 then out.arrayHead(0) else out.raw(a)

  // ---- governance

  /** A parameter update (`PParamsUpdate`): a map from the parameter's index to its new value, the
    * values kept as they came; written in key order.
    */
  given paramUpdate: Codec[ParamUpdate] = new Codec[ParamUpdate]:
    private val key = Codec.uintBig(255)
    def decode(n: Node): Decoded[ParamUpdate] =
      Codec.pairs(key, rawItem).decode(n).flatMap { pairs =>
        val fields = pairs.map((k, v) => k.toInt -> v).toMap
        if fields.size == pairs.size then Right(ParamUpdate(fields))
        else Left(DecodeError.at(n, Kind.DuplicateKey("a parameter index")))
      }
    def encode(a: ParamUpdate, out: Writer): Unit =
      out.variableMap(a.fields.size)(a.fields.toVector.sortBy(_._1).foreach { (k, v) =>
        out.uint(k.toLong)
        out.raw(v)
      })

  /** The Conway `PParamsUpdate` of a parameter-change proposal: written as [[paramUpdate]] writes
    * an update, each value in its original encoding, and read by the ledger's Conway decoder
    * ([[ConwayParamUpdate.decode]]), so a state holding a key that is not a Conway field or a value
    * not of its field's type is refused with the ledger's typed error.
    */
  given conwayParamUpdate: Codec[ConwayParamUpdate] = new Codec[ConwayParamUpdate]:
    def decode(n: Node): Decoded[ConwayParamUpdate] =
      ConwayParamUpdate.decode(n).left.map(why => DecodeError.at(n, Kind.Domain(why)))
    def encode(a: ConwayParamUpdate, out: Writer): Unit =
      out.variableMap(a.encodings.size)(a.encodings.foreach { (k, v) =>
        out.uint(k.toLong)
        out.raw(v)
      })

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
    Record.of[Committee]((sortedMap(nodeCredential, amount), unitInterval))

  given constitution(using ProtVer): Codec[Constitution] =
    Record.of[Constitution]((anchor, nullable(hash28)))

  private def previous: Codec[Option[GovActionId]] = nullable(govActionId)

  /** `GovAction` (`Procedures.hs:928-946`): `[tag, ...]`, the tag the constructor. */
  given govAction(using v: ProtVer): Codec[GovAction] = new Codec[GovAction]:
    private val policy = nullable(hash28)
    private val withdrawals = sortedMap(rewardAccount, amount)
    private val removed = sortedSet(nodeCredential)
    private val added = sortedMap(nodeCredential, amount)
    private val version = Record.of[(BigInt, BigInt)]((Codec.uintBig(13), word32))
    def decode(n: Node): Decoded[GovAction] =
      variant(n).flatMap {
        case (t, Vector(g, u, p)) if t == 0 =>
          for
            prev <- previous.decode(g).at("previous")
            update <- conwayParamUpdate.decode(u).at("update")
            pol <- policy.decode(p).at("policy")
          yield GovAction.ParameterChange(prev, update, pol)
        case (t, Vector(g, pv)) if t == 1 =>
          for
            prev <- previous.decode(g).at("previous")
            ver <- version.decode(pv).at("protocolVersion")
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
            quorum <- unitInterval.decode(q).at("quorum")
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
        previous.encode(prev, out); conwayParamUpdate.encode(update, out)
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
        unitInterval.encode(quorum, out)
      case GovAction.NewConstitution(prev, script, anchor) =>
        out.arrayHead(3); out.uint(5L)
        previous.encode(prev, out); constitution.encode(Constitution(anchor, script), out)
      case GovAction.Info =>
        out.arrayHead(1); out.uint(6L)

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
    * proposals in order.
    */
  given proposals(using ProtVer): Codec[Proposals] =
    Record
      .of[(PrevIds, Vector[GovActionState])]((prevIds, seqOf(govActionState)))
      .imap { case (roots, actions) =>
        Proposals(actions, roots)
      }(p => (p.roots, p.actions))

  /** `EnactState` (`Internal.hs:229-239`), at the Conway parameters. */
  given enactState(using ProtVer): Codec[EnactState] =
    Record.of[EnactState](
      (
        strictMaybe(committee),
        constitution,
        Params.conwayCodec,
        Params.conwayCodec,
        amount,
        sortedMap(nodeCredential, amount),
        prevIds
      )
    )

  /** `RatifyState` (`Internal.hs:664-672`): the enact state, the actions to enact, the expired ones
    * and whether a delaying action was enacted.
    */
  given ratifyState(using ProtVer): Codec[RatifyState] =
    Record.of[RatifyState](
      (enactState, seqOf(govActionState), sortedSet(govActionId), Codec.bool)
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
