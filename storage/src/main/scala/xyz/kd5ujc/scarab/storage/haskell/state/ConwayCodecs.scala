// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Block,
  BootstrapWitness,
  Certificate,
  ConwayParam,
  ConwayParamUpdate,
  Credential,
  DRep,
  GovAction,
  GovActionId,
  ByteStrings,
  DecoderVersion,
  Metadatum,
  NativeScript,
  PoolParams,
  PoolRelays,
  Proposal,
  Redeemer,
  RewardAccount,
  ScriptKind,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.state.ProtVer
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.models.runtime.{
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer
}

import NodeOrders.given
import Wire.*

/** The reference's `EncCBOR` of the Conway values Scarab decodes from blocks and transactions and
  * had no writer of: certificates, voters and votes, proposal procedures, scripts, witnesses,
  * metadata, cost models and blocks (cardano-ledger f649f975, cited at each codec). They write what
  * the reference writes when it serialises a decoded value, its canonical choices included:
  * definite records, the variable-length framing of its sequences and maps, map keys in the order
  * of the key's `Ord`, tag 258 where its encoder writes it at the given version, one of Conway's (9
  * and above; the framing of the versions below 2 is not written). Where the reference keeps the
  * bytes it read (`MemoBytes`: a native script, plutus data, a header, a block body), the value
  * written here carries those bytes and they are written as they are.
  *
  * They are re-encodings, never what a hash or a signature is taken over: those stay over the
  * original bytes (CONTRIBUTING, original-bytes rule). The ledger's own decoders read these values
  * ([[Certificate.decode]], [[Voter.decode]], [[xyz.kd5ujc.scarab.ledger.VotingProcedures.decode]],
  * [[Proposal.decode]], `Witnesses.decode`, [[Metadatum.decode]],
  * [[xyz.kd5ujc.scarab.ledger.CostModelDecode.decode]], [[Block.decodeShelley]]): these codecs are
  * for writing, and those that are not plain records refuse to read. A value a codec cannot write
  * as the reference would (a certificate Conway has no constructor of, a field out of its type's
  * range, kept bytes that are not one CBOR item, a block not read from bytes) is refused by
  * [[Codec.check]], so [[Codec.write]] never writes it.
  */
object ConwayCodecs:
  private val state = HaskellStateCodecs
  private val hash28: Codec[Bytes] = Wire.hash(28)
  private val hash32: Codec[Bytes] = Wire.hash(32)

  /** A codec of a value only written here: its decoder is the ledger's. */
  private def writer[A](shape: String)(put: (A, Writer) => Unit)(
      refuse: A => Option[ConstraintViolation]
  ): Codec[A] = new Codec[A]:
    def decode(n: Node): Decoded[A] =
      Left(DecodeError.at(n, DecodeError.Kind.Unsupported(s"reading $shape (the ledger reads it)")))
    def encode(a: A, out: Writer): Unit = put(a, out)
    override def check(a: A): Option[ConstraintViolation] = refuse(a)

  /** The first refusal of `checks`, in order. */
  private def first(checks: Option[ConstraintViolation]*): Option[ConstraintViolation] =
    checks.collectFirst { case Some(v) => v }

  /** Kept bytes written as they are must be one whole CBOR item, or the record around them is not.
    */
  private def item(what: String, bytes: Bytes): Option[ConstraintViolation] =
    Cbor
      .decode(bytes, Cbor.Limits.Unbounded)
      .left
      .toOption
      .flatMap(e => Codec.refuse(true, what, "one CBOR item", e))

  /** A `Coin` the decoders give, a `Word64` (`Coin.hs:108-109`, cardano-ledger f649f975). */
  private def coin(amount: BigInt): Option[ConstraintViolation] = word(64).check(amount)

  /** An `AccountAddress` of a network the reference reads, testnet (0) or mainnet (1)
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs:930-948`, cardano-ledger f649f975).
    */
  private def account(a: RewardAccount): Option[ConstraintViolation] =
    state.rewardAccount
      .check(a)
      .orElse(Codec.refuse(a.network > 1, "AccountAddress", "network 0 or 1", a.network))

  /** A key or script hash of 28 bytes. */
  private def credential(c: Credential): Option[ConstraintViolation] = hash28.check(c.hash)

  private def drep(d: DRep): Option[ConstraintViolation] = d match
    case DRep.Registered(c) => credential(c)
    case _                  => None

  private def anchor(a: Option[Anchor])(using ProtVer): Option[ConstraintViolation] =
    a.flatMap(state.anchor.check)

  /** `encodeNullStrictMaybe` (`Encoding/Encoder.hs:340-343`, cardano-ledger f649f975),
    * [[Wire.nullable]] with the value inside it checked.
    */
  private def orNull[A](inner: Codec[A]): Codec[Option[A]] = new Codec[Option[A]]:
    private val c = nullable(inner)
    def decode(n: Node): Decoded[Option[A]] = c.decode(n)
    def encode(a: Option[A], out: Writer): Unit = c.encode(a, out)
    override def check(a: Option[A]): Option[ConstraintViolation] = a.flatMap(inner.check)

  /** `encodeListLen size <> encodeWord8 tag`: the head of a sum's alternative. */
  private def alternative(out: Writer, size: Int, tag: Int): Unit =
    out.arrayHead(size)
    out.uint(tag.toLong)

  /** An `OSet` (`libs/cardano-data/src/Data/OSet/Strict.hs:112-113`, cardano-ledger f649f975): tag
    * 258 whatever the version, then the elements in the order the set holds them with
    * `encodeStrictSeq` 's framing (a definite array up to 23, an indefinite one above,
    * `Encoding/Encoder.hs:458-468, 512-518`). A set holds an element once (`Strict.hs:109-110,
    * 170-174`), so a repeated element is refused. The element's codec writes its value whatever
    * bytes it was read from, so two elements are one value when they are written alike.
    */
  private def orderedSet[A](shape: String, elem: Codec[A]): Codec[Vector[A]] =
    writer[Vector[A]](shape) { (as, out) =>
      out.tag(258)
      out.variableArray(as.size)(as.foreach(elem.encode(_, out)))
    } { as =>
      as.iterator.flatMap(elem.check).nextOption().orElse {
        val distinct = as.map(elem.encodeUnchecked).distinct.size
        Codec.refuse(
          distinct != as.size,
          shape,
          "each element once",
          s"${as.size - distinct} repeated"
        )
      }
    }

  // ---- certificates

  /** `PoolMetadata` (`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:516-520`,
    * cardano-ledger f649f975): `[url, hash]`.
    */
  private val poolMetadata: Codec[(String, Bytes)] =
    Record.of[(String, Bytes)]((Codec.text, Codec.bytes))

  /** Held registration bytes, written from their values by the same encoders as [[poolFields]].
    * `StakePool.hs:389-404, 516-520`, cardano-ledger f649f975.
    */
  private[state] val poolRelays: Codec[Bytes] =
    writer[Bytes]("pool relays") { (raw, out) =>
      PoolRelays.decodeState(raw).foreach(rs => out.raw(PoolRelays.encode(rs)))
    } { raw =>
      PoolRelays
        .decodeState(raw)
        .left
        .toOption
        .flatMap(e => Codec.refuse(true, "relays", "a relay sequence", e))
    }

  private[state] val poolMetadataBytes: Codec[Bytes] =
    writer[Bytes]("pool metadata") { (raw, out) =>
      poolMetadata.decodeBytes(raw, Cbor.Limits.Unbounded).foreach(poolMetadata.encode(_, out))
    } { raw =>
      poolMetadata
        .decodeBytes(raw, Cbor.Limits.Unbounded)
        .left
        .toOption
        .flatMap(e => Codec.refuse(true, "PoolMetadata", "[url, hash]", e))
    }

  /** The fields of `StakePoolParams` in a flat group (`withStakePoolParamsFlatEncoding`,
    * `State/StakePool.hs:555-572`, cardano-ledger f649f975): the operator, the VRF key hash, the
    * pledge and the cost (a `Coin`, `encodeInteger`, `Coin.hs:93-106`), the margin (a unit
    * interval), the reward account, the owners (a set, [[Wire.sortedSetOf]]), the relays (a
    * sequence, [[PoolRelays.encode]]) and the metadata or null. The registration keeps its relays
    * and metadata as the bytes it was read with; the reference writes their values, so they are
    * read again here and written as values.
    */
  private[state] def poolFields(p: PoolParams, out: Writer)(using ProtVer): Unit =
    hash28.encode(p.operator, out)
    hash32.encode(p.vrfKeyHash, out)
    out.integer(p.pledge)
    out.integer(p.cost)
    unitInterval.encode(p.margin, out)
    state.rewardAccount.encode(p.rewardAccount, out)
    sortedSetOf(hash28).encode(p.owners, out)
    poolRelays.encode(p.relays, out)
    p.metadata.fold(out.nul())(poolMetadataBytes.encode(_, out))

  private def poolDetailsRefusal(p: PoolParams)(using ProtVer): Option[ConstraintViolation] =
    first(
      unitInterval.check(p.margin),
      account(p.rewardAccount),
      p.owners.iterator.flatMap(hash28.check).nextOption(),
      poolRelays.check(p.relays),
      p.metadata.flatMap(poolMetadataBytes.check)
    )

  private def poolRefusal(p: PoolParams)(using ProtVer): Option[ConstraintViolation] =
    first(
      hash28.check(p.operator),
      hash32.check(p.vrfKeyHash),
      coin(p.pledge),
      coin(p.cost),
      poolDetailsRefusal(p)
    )

  /** `EncCBOR (ConwayTxCert era)` (`eras/conway/impl/src/Cardano/Ledger/Conway/TxCert.hs:737-741`,
    * cardano-ledger f649f975): the delegation certificates (`:488-534`), of which a registration or
    * unregistration without a deposit and a delegation to a pool alone are Shelley's
    * (`encodeShelleyDelegCert`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:428-435`),
    * the pool certificates (`encodePoolCert`, `Shelley/TxCert.hs:437-449`) and the governance ones
    * (`Conway/TxCert.hs: 614-643`). Every certificate is a definite array of its tag
    * (`encodeWord8`) and fields, a credential as `[0, key hash] / [1, script hash]`
    * (`Credential.hs:322-325`), a deposit as a `Coin` and an anchor as itself or null
    * (`encodeNullStrictMaybe`). Conway has no genesis delegation or instantaneous-rewards
    * certificate, and a delegation names a pool, a DRep or both: [[Codec.check]] refuses the
    * others. The version reaches the owners of a pool, a set.
    */
  def certificate(using ProtVer): Codec[Certificate] =
    import Certificate.*
    def put(c: Credential, out: Writer) = state.nodeCredential.encode(c, out)
    def putAnchor(a: Option[Anchor], out: Writer) = nullable(state.anchor).encode(a, out)
    writer[Certificate]("a certificate") { (c, out) =>
      c match
        case Register(cred, None) =>
          alternative(out, 2, 0); put(cred, out)
        case Unregister(cred, None) =>
          alternative(out, 2, 1); put(cred, out)
        case Delegate(cred, Some(pool), None, None) =>
          alternative(out, 3, 2); put(cred, out); hash28.encode(pool, out)
        case RegisterPool(params) =>
          alternative(out, 10, 3); poolFields(params, out)
        case RetirePool(pool, epoch) =>
          alternative(out, 3, 4); hash28.encode(pool, out); out.uint(epoch)
        case Register(cred, Some(deposit)) =>
          alternative(out, 3, 7); put(cred, out); out.integer(deposit)
        case Unregister(cred, Some(refund)) =>
          alternative(out, 3, 8); put(cred, out); out.integer(refund)
        case Delegate(cred, None, Some(drep), None) =>
          alternative(out, 3, 9); put(cred, out); state.nodeDRep.encode(drep, out)
        case Delegate(cred, Some(pool), Some(drep), None) =>
          alternative(out, 4, 10); put(cred, out); hash28.encode(pool, out)
          state.nodeDRep.encode(drep, out)
        case Delegate(cred, Some(pool), None, Some(deposit)) =>
          alternative(out, 4, 11); put(cred, out); hash28.encode(pool, out)
          out.integer(deposit)
        case Delegate(cred, None, Some(drep), Some(deposit)) =>
          alternative(out, 4, 12); put(cred, out); state.nodeDRep.encode(drep, out)
          out.integer(deposit)
        case Delegate(cred, Some(pool), Some(drep), Some(deposit)) =>
          alternative(out, 5, 13); put(cred, out); hash28.encode(pool, out)
          state.nodeDRep.encode(drep, out); out.integer(deposit)
        case CommitteeAuthorize(cold, hot) =>
          alternative(out, 3, 14); put(cold, out); put(hot, out)
        case CommitteeResign(cold, a) =>
          alternative(out, 3, 15); put(cold, out); putAnchor(a, out)
        case RegisterDRep(cred, deposit, a) =>
          alternative(out, 4, 16); put(cred, out); out.integer(deposit); putAnchor(a, out)
        case UnregisterDRep(cred, refund) =>
          alternative(out, 3, 17); put(cred, out); out.integer(refund)
        case UpdateDRep(cred, a) =>
          alternative(out, 3, 18); put(cred, out); putAnchor(a, out)
        // refused by `check`; never written
        case GenesisDelegate(_, _, _) | MoveRewards(_, _) | Delegate(_, None, None, _) => ()
    } {
      case GenesisDelegate(_, _, _) | MoveRewards(_, _) | Delegate(_, None, None, _) =>
        Some(ConstraintViolation("ConwayTxCert", "a certificate of Conway", "no Conway form"))
      case Register(cred, deposit)  => first(credential(cred), deposit.flatMap(coin))
      case Unregister(cred, refund) => first(credential(cred), refund.flatMap(coin))
      case Delegate(cred, pool, d, deposit) =>
        first(credential(cred), pool.flatMap(hash28.check), d.flatMap(drep), deposit.flatMap(coin))
      case RegisterPool(params)        => poolRefusal(params)
      case RetirePool(pool, epoch)     => first(hash28.check(pool), word(64).check(epoch))
      case CommitteeAuthorize(c, h)    => first(credential(c), credential(h))
      case CommitteeResign(c, a)       => first(credential(c), anchor(a))
      case RegisterDRep(c, deposit, a) => first(credential(c), coin(deposit), anchor(a))
      case UnregisterDRep(c, refund)   => first(credential(c), coin(refund))
      case UpdateDRep(c, a)            => first(credential(c), anchor(a))
    }

  /** `OSet (TxCert era)`, the certificates of a body ([[orderedSet]]). */
  def certificates(using ProtVer): Codec[Vector[Certificate]] =
    orderedSet("certificates", certificate)

  // ---- governance

  /** `EncCBOR Voter` (`eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs:
    * 365-376`): `[tag, hash]`, the tag 0 and 1 for a committee member's hot key or script, 2 and 3
    * for a DRep's, 4 for a pool.
    */
  val voter: Codec[Voter] =
    writer[Voter]("a voter") { (v, out) =>
      val (tag, hash) = v match
        case Voter.CommitteeHot(Credential.Key(h))    => (0, h)
        case Voter.CommitteeHot(Credential.Script(h)) => (1, h)
        case Voter.DRepVoter(Credential.Key(h))       => (2, h)
        case Voter.DRepVoter(Credential.Script(h))    => (3, h)
        case Voter.Pool(h)                            => (4, h)
      alternative(out, 2, tag)
      hash28.encode(hash, out)
    } {
      case Voter.CommitteeHot(c) => hash28.check(c.hash)
      case Voter.DRepVoter(c)    => hash28.check(c.hash)
      case Voter.Pool(h)         => hash28.check(h)
    }

  /** `EncCBOR (VotingProcedure era)` (`Procedures.hs:469-474`, cardano-ledger f649f975):
    * `[vote, anchor / null]`.
    */
  def votingProcedure(using ProtVer): Codec[VotingProcedure] =
    Record.of[VotingProcedure]((state.vote, orNull(state.anchor)))

  /** `VotingProcedures`, `Map Voter (Map GovActionId (VotingProcedure era))` written by its
    * newtype-derived `EncCBOR` (`Procedures.hs:400-404`, cardano-ledger f649f975): `encodeMap` at
    * both levels (`Encoding/Encoder.hs:397-408`), the variable-length framing, the voters in
    * `Ord Voter` ([[Voter.order]]) and the actions of each in `Ord GovActionId`. A map holds a key
    * once: of two entries for one voter, or one action of a voter, the later is the one written, as
    * `Map.fromList` keeps it.
    */
  def votingProcedures(using
      ProtVer
  ): Codec[Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]] =
    val inner = mapOf(state.govActionId, votingProcedure)
    val outer = mapOf(voter, inner)
    val byAction = Order[GovActionId].toOrdering
    def sorted(all: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]) =
      SortedMap.from(all.map((v, votes) => v -> SortedMap.from(votes)(using byAction)))(using
        Voter.ordering
      )
    writer[Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]]("voting procedures")(
      (all, out) => outer.encode(sorted(all), out)
    )(all => outer.check(sorted(all)))

  /** One field of a Conway `PParamsUpdate`, written from its value by the field's `encCBOR`
    * (`eras/conway/impl/src/Cardano/Ledger/Conway/PParams.hs:862-894`, cardano-ledger f649f975): a
    * count or an amount a `Word` or a `Coin`, `a0` and the reference-script price a non-negative
    * interval, `rho` and `tau` unit intervals, the cost models, the prices (`ExUnits.hs:219-220`),
    * the units of a transaction or a block (two `Natural` s, `ExUnits.hs:192-193`) and the voting
    * thresholds as records of unit intervals.
    */
  private def parameter(p: ConwayParam, out: Writer): Unit =
    import ConwayParam.*
    p match
      case TxFeePerByte(v)               => out.uint(v.value)
      case TxFeeFixed(v)                 => out.uint(v.value)
      case MaxBBSize(v)                  => out.uint(v.value)
      case MaxTxSize(v)                  => out.uint(v.value)
      case MaxBHSize(v)                  => out.uint(v.value)
      case KeyDeposit(v)                 => out.uint(v.value)
      case PoolDeposit(v)                => out.uint(v.value)
      case EMax(v)                       => out.uint(v.value)
      case NOpt(v)                       => out.uint(v.value)
      case A0(v)                         => nonNegativeInterval.encode(v, out)
      case Rho(v)                        => unitInterval.encode(v, out)
      case Tau(v)                        => unitInterval.encode(v, out)
      case MinPoolCost(v)                => out.uint(v.value)
      case CoinsPerUTxOByte(v)           => out.uint(v.value)
      case CostModels(models)            => Params.costModels.encode(models, out)
      case Prices(memory, steps)         => Params.prices.encode((memory, steps), out)
      case MaxTxExUnits(units)           => exUnits(units, out)
      case MaxBlockExUnits(units)        => exUnits(units, out)
      case MaxValSize(v)                 => out.uint(v.value)
      case CollateralPercentage(v)       => out.uint(v.value)
      case MaxCollateralInputs(v)        => out.uint(v.value)
      case PoolThresholds(v)             => Params.poolThresholds.encode(v, out)
      case DRepThresholds(v)             => Params.drepThresholds.encode(v, out)
      case CommitteeMinSize(v)           => out.uint(v.value)
      case CommitteeMaxTermLength(v)     => out.uint(v.value)
      case GovActionLifetime(v)          => out.uint(v.value)
      case GovActionDeposit(v)           => out.uint(v.value)
      case DRepDeposit(v)                => out.uint(v.value)
      case DRepActivity(v)               => out.uint(v.value)
      case MinFeeRefScriptCostPerByte(v) => nonNegativeInterval.encode(v, out)

  private def parameterRefusal(p: ConwayParam): Option[ConstraintViolation] =
    import ConwayParam.*
    p match
      case A0(v)                         => nonNegativeInterval.check(v)
      case Rho(v)                        => unitInterval.check(v)
      case Tau(v)                        => unitInterval.check(v)
      case CostModels(models)            => Params.costModels.check(models)
      case Prices(memory, steps)         => Params.prices.check((memory, steps))
      case MaxTxExUnits(units)           => natural(units)
      case MaxBlockExUnits(units)        => natural(units)
      case PoolThresholds(v)             => Params.poolThresholds.check(v)
      case DRepThresholds(v)             => Params.drepThresholds.check(v)
      case MinFeeRefScriptCostPerByte(v) => nonNegativeInterval.check(v)
      case _                             => None

  private def exUnits(units: xyz.kd5ujc.scarab.plutus.ExUnits, out: Writer): Unit =
    out.arrayHead(2)
    out.integer(units.mem)
    out.integer(units.steps)

  private def natural(units: xyz.kd5ujc.scarab.plutus.ExUnits): Option[ConstraintViolation] =
    Codec.refuse(units.mem.signum < 0 || units.steps.signum < 0, "ExUnits", "two Naturals", units)

  /** `EncCBOR (PParamsUpdate era)` (`libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:
    * 244-255`): a map of a definite length whatever its size, the fields set in the order of
    * `eraPParams` (`Conway/PParams.hs:862-894`, cardano-ledger f649f975, the order of their keys),
    * each key a `Word` and each value written from what it is ([[parameter]]), not the bytes the
    * transaction gave.
    */
  val parameterUpdate: Codec[ConwayParamUpdate] =
    writer[ConwayParamUpdate]("a parameter update") { (u, out) =>
      out.mapHead(u.byKey.size)
      u.byKey.foreach { (key, p) =>
        out.uint(key.toLong)
        parameter(p, out)
      }
    }(u => u.params.iterator.flatMap(parameterRefusal).nextOption())

  /** `EncCBOR (GovAction era)` (`eras/conway/impl/src/Cardano/Ledger/Conway/Governance/
    * Procedures.hs:928-946`, cardano-ledger f649f975): a parameter change
    * `[0, previous / null, update, policy / null]` with its update by [[parameterUpdate]], every
    * other action by [[NodeTypeCodecs.govAction]], which writes them from their values. The version
    * reaches the sets and maps of a committee update and the anchor of a constitution.
    */
  def govAction(using ProtVer): Codec[GovAction] =
    val previous = nullable(state.govActionId)
    val policy = nullable(hash28)
    writer[GovAction]("a governance action") {
      case (GovAction.ParameterChange(prev, update, pol), out) =>
        alternative(out, 4, 0)
        previous.encode(prev, out)
        parameterUpdate.encode(update, out)
        policy.encode(pol, out)
      case (other, out) => state.govAction.encode(other, out)
    }(a => first(govActionBounds(a), decoderLimits(a)))

  /** What the reference's types of a `GovAction` hold (`Procedures.hs:815-855`, cardano-ledger
    * f649f975): a `Version` major of at most 13 and a `Word32` minor (`ProtVer`), a `Word16` action
    * index, accounts of network 0 or 1, epochs of a `Word64`, a quorum in 0..1, hashes of their
    * size. A `Coin` is an `Integer` (`Coin.hs:93`) and a `Url` a `Text` (`BaseTypes.hs:674-676`),
    * so neither is bounded here. The state writer checks these alone.
    */
  def govActionBounds(a: GovAction)(using ProtVer): Option[ConstraintViolation] =
    // `nullable` checks nothing of its own: the value inside it is checked here
    def ids(prev: Option[GovActionId]) = prev.flatMap(state.govActionId.check)
    def script(pol: Option[Bytes]) = pol.flatMap(hash28.check)
    a match
      case GovAction.ParameterChange(prev, update, pol) =>
        first(ids(prev), parameterUpdate.check(update), script(pol))
      case GovAction.HardFork(prev, major, minor) =>
        first(ids(prev), Codec.uintBig(13).check(major), word(32).check(minor))
      case GovAction.TreasuryWithdrawals(ws, pol) =>
        first(ws.iterator.flatMap((to, _) => account(to)).nextOption(), script(pol))
      case GovAction.NoConfidence(prev) => ids(prev)
      case GovAction.UpdateCommittee(prev, removed, added, quorum) =>
        first(
          ids(prev),
          removed.iterator.flatMap(credential).nextOption(),
          added.iterator.flatMap((c, e) => credential(c).orElse(word(64).check(e))).nextOption(),
          unitInterval.check(quorum)
        )
      case GovAction.NewConstitution(prev, guardrails, constitution) =>
        first(ids(prev), script(guardrails), hash32.check(constitution.hash))
      case GovAction.Info => None

  /** What a transaction's action must also satisfy, the limits of the reference's decoders: a
    * withdrawal's `Coin` a `Word64` (`Coin.hs:108-109`), each account once in its `Map`
    * (`Procedures.hs:830-834`), and a constitution's url within its size (`BaseTypes.hs:678-685`),
    * all at cardano-ledger f649f975.
    */
  private def decoderLimits(a: GovAction)(using ProtVer): Option[ConstraintViolation] = a match
    case GovAction.TreasuryWithdrawals(ws, _) =>
      first(
        ws.iterator.flatMap((_, amount) => coin(amount)).nextOption(),
        Codec.refuse(
          ws.map(_._1).distinct.sizeIs != ws.size,
          "TreasuryWithdrawals",
          "each account once",
          ws.size
        )
      )
    case GovAction.NewConstitution(_, _, constitution) => anchor(Some(constitution))
    case _                                             => None

  /** `EncCBOR (ProposalProcedure era)` (`Procedures.hs:537-544`, cardano-ledger f649f975):
    * `[deposit, return account, action, anchor]`, the action by [[govAction]].
    */
  def proposal(using ProtVer): Codec[Proposal] =
    val action = govAction
    writer[Proposal]("a proposal procedure") { (p, out) =>
      out.arrayHead(4)
      out.uint(p.deposit)
      state.rewardAccount.encode(p.returnAccount, out)
      action.encode(p.action, out)
      state.anchor.encode(p.anchor, out)
    } { p =>
      first(
        coin(p.deposit),
        account(p.returnAccount),
        action.check(p.action),
        anchor(Some(p.anchor))
      )
    }

  /** `OSet (ProposalProcedure era)`, the proposal procedures of a body ([[orderedSet]]), each by
    * [[proposal]].
    */
  def proposalProcedures(using ProtVer): Codec[Vector[Proposal]] =
    orderedSet("proposal procedures", proposal)

  // ---- scripts and witnesses

  /** A script as the reference holds it: its language and the bytes its hash is over, which are the
    * native script's own encoding (a `Timelock` is `MemoBytes`,
    * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:253`, cardano-ledger f649f975) or a
    * Plutus script's bytes (see [[xyz.kd5ujc.scarab.ledger.Script]]).
    */
  final case class ScriptPayload(kind: ScriptKind, payload: Bytes)

  /** `EncCBOR (AlonzoScript era)` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:667-689`,
    * cardano-ledger f649f975, `toEraCBOR` of `encodeScript`): `[0, native script]`, the native
    * script as its kept bytes, or `[language, bytes]` of a Plutus script (1 to 3).
    */
  val script: Codec[ScriptPayload] =
    writer[ScriptPayload]("a script") { (s, out) =>
      alternative(out, 2, s.kind.tag)
      if s.kind.isPlutus then out.bytes(s.payload) else out.raw(s.payload)
    }(s => Option.unless(s.kind.isPlutus)(nativeScript(s.payload)).flatten)

  /** A native script's kept bytes: one item that Conway's time-lock decoder reads. */
  private def nativeScript(payload: Bytes): Option[ConstraintViolation] =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Conway))
    item("native script", payload).orElse(
      Cbor
        .decode(payload, Cbor.Limits.Unbounded)
        .toOption
        .flatMap(n => NativeScript.decode(n, NativeScript.Form.Timelock).left.toOption)
        .flatMap(e => Codec.refuse(true, "native script", "a time lock", e))
    )

  /** A redeemer as `keyValueEncoder` writes it (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/
    * TxWits.hs:164-169`, cardano-ledger f649f975): `[tag, index, data, ex_units]`, the purpose by
    * its `encCBORGroup` (a `Word8` tag and a `Word32` index,
    * `eras/conway/impl/src/Cardano/Ledger/Conway/Scripts.hs: 269-278`), the data as its kept bytes
    * (`Data` is `MemoBytes`) and the units by `EncCBOR ExUnits`
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ExUnits.hs:192-193`, two `Natural` s).
    * The reference writes the redeemers of a witness set as a list of these below version 9 and as
    * a map from version 9 (`TxWits.hs:157-162`); this is one element of the list, the shape the
    * cardano-cbor-dataset's `redeemer` rule takes as well.
    */
  val redeemer: Codec[Redeemer] =
    writer[Redeemer]("a redeemer") { (r, out) =>
      out.arrayHead(4)
      out.uint(r.tag.toLong)
      out.uint(r.index)
      out.raw(r.data)
      out.arrayHead(2)
      out.integer(r.exUnits.mem)
      out.integer(r.exUnits.steps)
    } { r =>
      first(
        Codec.intRange(0, 5).check(r.tag),
        word(32).check(r.index),
        PlutusData
          .decode(r.data)
          .left
          .toOption
          .flatMap(e => Codec.refuse(true, "redeemer data", "one item of Plutus data", e)),
        Codec.refuse(
          r.exUnits.mem.signum < 0 || r.exUnits.steps.signum < 0,
          "ExUnits",
          "two Naturals",
          r.exUnits
        )
      )
    }

  /** The redeemer set's `RedeemersRaw` encoder (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/
    * TxWits.hs:144-169`, cardano-ledger f649f975): a list of [[redeemer]] below version 9, a map
    * from `[tag, index]` to `[data, ex_units]` from 9. Both are definite up to 23 entries and
    * indefinite above (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Encoding/
    * Encoder.hs:363-368, 402-408, 431-443, 458-468`). Both visit keys in tag then index order
    * (`eras/conway/impl/src/Cardano/Ledger/Conway/Scripts.hs:201-213, 269-278`). Repeated keys keep
    * the last value, as the reference's decoder builds its map with `Map.fromList`
    * (`TxWits.hs:561-580`). Every input element is checked by [[redeemer]].
    *
    * This must never be used for hashing: the script integrity hash covers the witness set's
    * original `Witnesses.redeemersRaw` bytes (BYT-1), as `Redeemers` retains `MemoBytes`
    * (`TxWits.hs:174-179, 600-601`), not this re-encoding.
    */
  def redeemers(using v: ProtVer): Codec[Vector[Redeemer]] =
    writer[Vector[Redeemer]]("a redeemer set") { (rs, out) =>
      val ordered = rs
        .map(r => (r.tag, r.index) -> r)
        .toMap
        .values
        .toVector
        .sortBy(r => (r.tag, r.index))
      if v.atLeast(9) then
        out.variableMap(ordered.size) {
          ordered.foreach { r =>
            out.arrayHead(2)
            out.uint(r.tag.toLong)
            out.uint(r.index)
            out.arrayHead(2)
            out.raw(r.data)
            out.arrayHead(2)
            out.integer(r.exUnits.mem)
            out.integer(r.exUnits.steps)
          }
        }
      else out.variableArray(ordered.size)(ordered.foreach(redeemer.encode(_, out)))
    }(rs => rs.iterator.flatMap(redeemer.check).nextOption())

  /** `EncCBOR (WitVKey kr)` (`libs/cardano-ledger-core/src/Cardano/Ledger/Keys/WitVKey.hs:70-74`,
    * cardano-ledger f649f975): `[vkey, signature]`, the key and the signature as their raw bytes.
    */
  val vkeyWitness: Codec[(Bytes, Bytes)] =
    Record.of[(Bytes, Bytes)]((Codec.bytesSized(32, 32), Codec.bytesSized(64, 64)))

  /** `EncCBOR BootstrapWitness` (`libs/cardano-ledger-core/src/Cardano/Ledger/Keys/Bootstrap.hs:
    * 93-100`): `[vkey, signature, chain code, attributes]`, each as its bytes.
    */
  val bootstrapWitness: Codec[BootstrapWitness] =
    Record.of[BootstrapWitness](
      (Codec.bytesSized(32, 32), Codec.bytesSized(64, 64), Codec.bytes, Codec.bytes)
    )

  // ---- metadata and parameters

  /** `encodeMetadatum` (`libs/cardano-ledger-core/src/Cardano/Ledger/Metadata.hs:126-141`,
    * cardano-ledger f649f975): an integer by `encodeInteger`, a byte or text string definite, a
    * list and a map with a definite head whatever their size, the pairs of a map in their order.
    * The nesting is walked on the heap (ADR 0011).
    */
  val metadatum: Codec[Metadatum] =
    writer[Metadatum]("a metadatum")((m, out) => writeMetadatum(m, out))(_ => None)

  private def writeMetadatum(root: Metadatum, out: Writer): Unit =
    @tailrec def go(work: List[Metadatum]): Unit = work match
      case Nil => ()
      case m :: rest =>
        m match
          case Metadatum.Entries(pairs) =>
            out.mapHead(pairs.size)
            go(pairs.reverseIterator.foldLeft(rest)((acc, kv) => kv._1 :: kv._2 :: acc))
          case Metadatum.Items(items) =>
            out.arrayHead(items.size)
            go(items.reverseIterator.foldLeft(rest)((acc, m) => m :: acc))
          case Metadatum.Num(n) =>
            out.integer(n)
            go(rest)
          case Metadatum.Bin(b) =>
            out.bytes(b)
            go(rest)
          case Metadatum.Str(s) =>
            out.text(s)
            go(rest)
    go(List(root))

  /** `EncCBOR CostModels` ([[Params.costModels]]): the cost models of a parameter update. */
  val costModels: Codec[Map[Int, Vector[BigInt]]] = Params.costModels

  // ---- blocks

  /** `EncCBOR (Block h era)` (`libs/cardano-ledger-core/src/Cardano/Ledger/Block.hs:83-86`,
    * cardano-ledger f649f975): `encodeListLen (1 + listLen body)`, then the header and the body's
    * group, both of which the reference keeps as the bytes it read (a Praos header is `MemoBytes`,
    * `libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:138-140, 234`; the body's
    * segments are `encodePreEncoded` in `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/BlockBody/
    * Internal.hs:181-186`, four from Alonzo, and `eras/shelley/impl/src/Cardano/Ledger/Shelley/
    * BlockBody/Internal.hs:204-209`, three before). A block holds its segments only when it was
    * read from bytes ([[Block.Shelley.segments]]); another is refused, and so is an empty header or
    * segment.
    */
  val block: Codec[Block.Shelley] =
    writer[Block.Shelley]("a block") { (b, out) =>
      out.arrayHead(1 + b.segments.size)
      out.raw(b.header.raw)
      b.segments.foreach(out.raw)
    } { b =>
      val expected = if b.era.hasInvalidTransactions then 4 else 3
      // the kept bytes are written as they are: a header or a segment may be a detached record
      // (`DetachedRecords`), several items that the reference reads as one, so only their presence
      // is checked
      first(
        Codec.refuse(
          b.segments.sizeIs != expected,
          "Block",
          s"the $expected body segments it was read with",
          s"${b.segments.size} segments"
        ),
        Codec.refuse(b.header.raw.isEmpty, "Block", "the header it was read with", "none"),
        Codec.refuse(
          b.segments.exists(_.isEmpty),
          "Block",
          "segments read from bytes",
          "an empty one"
        )
      )
    }
