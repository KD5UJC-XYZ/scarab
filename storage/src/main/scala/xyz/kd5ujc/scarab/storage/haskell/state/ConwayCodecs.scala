// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BootstrapWitness,
  Certificate,
  Credential,
  GovActionId,
  Metadatum,
  PoolParams,
  PoolRelays,
  Proposal,
  Redeemer,
  ScriptKind,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.state.ProtVer
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
  * of the key's `Ord`, tag 258 where its encoder writes it at the given version. Where the
  * reference keeps the bytes it read (`MemoBytes`: a native script, plutus data, a header, a block
  * body), the value written here carries those bytes and they are written as they are.
  *
  * They are re-encodings, never what a hash or a signature is taken over: those stay over the
  * original bytes (CONTRIBUTING, original-bytes rule). The ledger's own decoders read these values
  * ([[Certificate.decode]], [[Voter.decode]], [[xyz.kd5ujc.scarab.ledger.VotingProcedures.decode]],
  * [[Proposal.decode]], `Witnesses.decode`, [[Metadatum.decode]],
  * [[xyz.kd5ujc.scarab.ledger.CostModelDecode.decode]], [[Block.decodeShelley]]): these codecs are
  * for writing, and those that are not plain records refuse to read. A value a codec cannot write
  * as the reference would (a certificate Conway has no constructor of, a block not read from bytes)
  * is refused by [[Codec.check]], so [[Codec.write]] never writes it.
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

  /** `encodeListLen size <> encodeWord8 tag`: the head of a sum's alternative. */
  private def alternative(out: Writer, size: Int, tag: Int): Unit =
    out.arrayHead(size)
    out.uint(tag.toLong)

  /** An `OSet` (`libs/cardano-data/src/Data/OSet/Strict.hs:112-113`): tag 258 whatever the version,
    * then the elements in the order the set holds them with `encodeStrictSeq` 's framing (a
    * definite array up to 23, an indefinite one above, `Encoding/Encoder.hs:458-468, 512-518`).
    */
  private def orderedSet[A](shape: String, elem: Codec[A]): Codec[Vector[A]] =
    writer[Vector[A]](shape) { (as, out) =>
      out.tag(258)
      out.variableArray(as.size)(as.foreach(elem.encode(_, out)))
    }(as => as.iterator.flatMap(elem.check).nextOption())

  // ---- certificates

  /** `PoolMetadata` (`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:516-520`):
    * `[url, hash]`.
    */
  private val poolMetadata: Codec[(String, Bytes)] =
    Record.of[(String, Bytes)]((Codec.text, Codec.bytes))

  /** The fields of `StakePoolParams` in a flat group (`withStakePoolParamsFlatEncoding`,
    * `State/StakePool.hs:555-572`): the operator, the VRF key hash, the pledge and the cost (a
    * `Coin`, `encodeInteger`, `Coin.hs:93-106`), the margin (a unit interval), the reward account,
    * the owners (a set, [[Wire.sortedSetOf]]), the relays (a sequence, [[PoolRelays.encode]]) and
    * the metadata or null. The registration keeps its relays and metadata as the bytes it was read
    * with; the reference writes their values, so they are read again here and written as values.
    */
  private def poolFields(p: PoolParams, out: Writer)(using ProtVer): Unit =
    hash28.encode(p.operator, out)
    hash32.encode(p.vrfKeyHash, out)
    out.integer(p.pledge)
    out.integer(p.cost)
    unitInterval.encode(p.margin, out)
    state.rewardAccount.encode(p.rewardAccount, out)
    sortedSetOf(hash28).encode(p.owners, out)
    // refused by `check` when the kept bytes do not read; never written then
    PoolRelays.decode(p.relays).foreach(rs => out.raw(PoolRelays.encode(rs)))
    p.metadata.fold(out.nul())(raw =>
      poolMetadata.decodeBytes(raw).foreach(poolMetadata.encode(_, out))
    )

  private def poolRefusal(p: PoolParams)(using ProtVer): Option[ConstraintViolation] =
    first(
      hash28.check(p.operator),
      hash32.check(p.vrfKeyHash),
      coin(p.pledge),
      coin(p.cost),
      unitInterval.check(p.margin),
      state.rewardAccount.check(p.rewardAccount),
      p.owners.iterator.flatMap(hash28.check).nextOption(),
      PoolRelays
        .decode(p.relays)
        .left
        .toOption
        .flatMap(e => Codec.refuse(true, "relays", "a relay sequence", e)),
      p.metadata.flatMap(raw =>
        poolMetadata
          .decodeBytes(raw)
          .left
          .toOption
          .flatMap(e => Codec.refuse(true, "PoolMetadata", "[url, hash]", e))
      )
    )

  /** `EncCBOR (ConwayTxCert era)` (`eras/conway/impl/src/Cardano/Ledger/Conway/TxCert.hs:737-741`):
    * the delegation certificates (`:488-534`), of which a registration or unregistration without a
    * deposit and a delegation to a pool alone are Shelley's (`encodeShelleyDelegCert`,
    * `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:428-435`), the pool certificates
    * (`encodePoolCert`, `Shelley/TxCert.hs:437-449`) and the governance ones (`Conway/TxCert.hs:
    * 614-643`). Every certificate is a definite array of its tag (`encodeWord8`) and fields, a
    * credential as `[0, key hash] / [1, script hash]` (`Credential.hs:322-325`), a deposit as a
    * `Coin` and an anchor as itself or null (`encodeNullStrictMaybe`). Conway has no genesis
    * delegation or instantaneous-rewards certificate, and a delegation names a pool, a DRep or
    * both: [[Codec.check]] refuses the others. The version reaches the owners of a pool, a set.
    */
  def certificate(using ProtVer): Codec[Certificate] =
    import Certificate.*
    def credential(c: Credential, out: Writer) = state.nodeCredential.encode(c, out)
    def anchor(a: Option[xyz.kd5ujc.scarab.ledger.Anchor], out: Writer) =
      nullable(state.anchor).encode(a, out)
    writer[Certificate]("a certificate") { (c, out) =>
      c match
        case Register(cred, None) =>
          alternative(out, 2, 0); credential(cred, out)
        case Unregister(cred, None) =>
          alternative(out, 2, 1); credential(cred, out)
        case Delegate(cred, Some(pool), None, None) =>
          alternative(out, 3, 2); credential(cred, out); hash28.encode(pool, out)
        case RegisterPool(params) =>
          alternative(out, 10, 3); poolFields(params, out)
        case RetirePool(pool, epoch) =>
          alternative(out, 3, 4); hash28.encode(pool, out); out.uint(epoch)
        case Register(cred, Some(deposit)) =>
          alternative(out, 3, 7); credential(cred, out); out.integer(deposit)
        case Unregister(cred, Some(refund)) =>
          alternative(out, 3, 8); credential(cred, out); out.integer(refund)
        case Delegate(cred, None, Some(drep), None) =>
          alternative(out, 3, 9); credential(cred, out); state.nodeDRep.encode(drep, out)
        case Delegate(cred, Some(pool), Some(drep), None) =>
          alternative(out, 4, 10); credential(cred, out); hash28.encode(pool, out)
          state.nodeDRep.encode(drep, out)
        case Delegate(cred, Some(pool), None, Some(deposit)) =>
          alternative(out, 4, 11); credential(cred, out); hash28.encode(pool, out)
          out.integer(deposit)
        case Delegate(cred, None, Some(drep), Some(deposit)) =>
          alternative(out, 4, 12); credential(cred, out); state.nodeDRep.encode(drep, out)
          out.integer(deposit)
        case Delegate(cred, Some(pool), Some(drep), Some(deposit)) =>
          alternative(out, 5, 13); credential(cred, out); hash28.encode(pool, out)
          state.nodeDRep.encode(drep, out); out.integer(deposit)
        case CommitteeAuthorize(cold, hot) =>
          alternative(out, 3, 14); credential(cold, out); credential(hot, out)
        case CommitteeResign(cold, a) =>
          alternative(out, 3, 15); credential(cold, out); anchor(a, out)
        case RegisterDRep(cred, deposit, a) =>
          alternative(out, 4, 16); credential(cred, out); out.integer(deposit); anchor(a, out)
        case UnregisterDRep(cred, refund) =>
          alternative(out, 3, 17); credential(cred, out); out.integer(refund)
        case UpdateDRep(cred, a) =>
          alternative(out, 3, 18); credential(cred, out); anchor(a, out)
        // refused by `check`; never written
        case GenesisDelegate(_, _, _) | MoveRewards(_, _) | Delegate(_, None, None, _) => ()
    } {
      case GenesisDelegate(_, _, _) | MoveRewards(_, _) | Delegate(_, None, None, _) =>
        Some(ConstraintViolation("ConwayTxCert", "a certificate of Conway", "no Conway form"))
      case RegisterPool(params) => poolRefusal(params)
      case RetirePool(pool, epoch) =>
        first(hash28.check(pool), word(64).check(epoch))
      case Register(_, Some(amount))       => coin(amount)
      case Unregister(_, Some(amount))     => coin(amount)
      case Delegate(_, _, _, Some(amount)) => coin(amount)
      case RegisterDRep(_, amount, _)      => coin(amount)
      case UnregisterDRep(_, amount)       => coin(amount)
      case _                               => None
    }

  /** A deposit or a refund: a `Coin` the decoders give, a `Word64` (`Coin.hs:108-109`). */
  private def coin(amount: BigInt): Option[ConstraintViolation] = word(64).check(amount)

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

  /** `EncCBOR (VotingProcedure era)` (`Procedures.hs:469-474`): `[vote, anchor / null]`. */
  def votingProcedure(using ProtVer): Codec[VotingProcedure] =
    Record.of[VotingProcedure]((state.vote, nullable(state.anchor)))

  /** `VotingProcedures`, `Map Voter (Map GovActionId (VotingProcedure era))` written by its
    * newtype-derived `EncCBOR` (`Procedures.hs:400-404`): `encodeMap` at both levels
    * (`Encoding/Encoder.hs:397-408`), the variable-length framing, the voters in `Ord Voter`
    * ([[Voter.order]]) and the actions of each in `Ord GovActionId`. A map holds a key once: of two
    * entries for one voter, or one action of a voter, the later is the one written, as
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

  /** `OSet (ProposalProcedure era)`, the proposal procedures of a body ([[orderedSet]]), each by
    * [[NodeTypeCodecs.proposal]].
    */
  def proposalProcedures(using ProtVer): Codec[Vector[Proposal]] =
    orderedSet("proposal procedures", state.proposal)

  // ---- scripts and witnesses

  /** A script as the reference holds it: its language and the bytes its hash is over, which are the
    * native script's own encoding (a `Timelock` is `MemoBytes`,
    * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:253`) or a Plutus script's bytes (see
    * [[xyz.kd5ujc.scarab.ledger.Script]]).
    */
  final case class ScriptPayload(kind: ScriptKind, payload: Bytes)

  /** `EncCBOR (AlonzoScript era)` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:667-689`,
    * `toEraCBOR` of `encodeScript`): `[0, native script]`, the native script as its kept bytes, or
    * `[language, bytes]` of a Plutus script (1 to 3).
    */
  val script: Codec[ScriptPayload] =
    writer[ScriptPayload]("a script") { (s, out) =>
      alternative(out, 2, s.kind.tag)
      if s.kind.isPlutus then out.bytes(s.payload) else out.raw(s.payload)
    }(s =>
      Codec.refuse(
        !s.kind.isPlutus && s.payload.isEmpty,
        "script",
        "the encoding of a native script",
        s.kind
      )
    )

  /** A redeemer as `keyValueEncoder` writes it (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/
    * TxWits.hs:164-169`): `[tag, index, data, ex_units]`, the purpose by its `encCBORGroup` (a
    * `Word8` tag and a `Word32` index, `eras/conway/impl/src/Cardano/Ledger/Conway/Scripts.hs:
    * 269-278`), the data as its kept bytes (`Data` is `MemoBytes`) and the units by `EncCBOR
    * ExUnits` (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ExUnits.hs:192-193`, two
    * `Natural` s). The reference writes the redeemers of a witness set as a list of these below
    * version 9 and as a map from version 9 (`TxWits.hs:157-162`); this is one element of the list,
    * the shape the cardano-cbor-dataset's `redeemer` rule takes as well.
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
        word(8).check(BigInt(r.tag)),
        word(32).check(r.index),
        Codec.refuse(
          r.exUnits.mem.signum < 0 || r.exUnits.steps.signum < 0,
          "ExUnits",
          "two Naturals",
          r.exUnits
        )
      )
    }

  /** `EncCBOR (WitVKey kr)` (`libs/cardano-ledger-core/src/Cardano/Ledger/Keys/WitVKey.hs:70-74`):
    * `[vkey, signature]`, the key and the signature as their raw bytes.
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

  /** `encodeMetadatum` (`libs/cardano-ledger-core/src/Cardano/Ledger/Metadata.hs:126-141`): an
    * integer by `encodeInteger`, a byte or text string definite, a list and a map with a definite
    * head whatever their size, the pairs of a map in their order. The nesting is walked on the heap
    * (ADR 0011).
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
            go(pairs.foldRight(rest)((kv, acc) => kv._1 :: kv._2 :: acc))
          case Metadatum.Items(items) =>
            out.arrayHead(items.size)
            go(items.foldRight(rest)(_ :: _))
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

  /** `EncCBOR (Block h era)` (`libs/cardano-ledger-core/src/Cardano/Ledger/Block.hs:83-86`):
    * `encodeListLen (1 + listLen body)`, then the header and the body's group, both of which the
    * reference keeps as the bytes it read (the header's `headerBytes`; the body's segments,
    * `encodePreEncoded` in `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/BlockBody/Internal.hs:
    * 181-186`, four from Alonzo, and `eras/shelley/impl/src/Cardano/Ledger/Shelley/BlockBody/
    * Internal.hs:204-209`, three before). A block holds its segments only when it was read from
    * bytes ([[Block.Shelley.segments]]); another is refused.
    */
  val block: Codec[Block.Shelley] =
    writer[Block.Shelley]("a block") { (b, out) =>
      out.arrayHead(1 + b.segments.size)
      out.raw(b.header.raw)
      b.segments.foreach(out.raw)
    } { b =>
      val expected = if b.era.hasInvalidTransactions then 4 else 3
      Codec.refuse(
        b.segments.size != expected,
        "Block",
        s"the $expected body segments it was read with",
        s"${b.segments.size} segments"
      )
    }
