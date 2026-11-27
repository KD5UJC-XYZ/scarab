// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import scala.collection.immutable.SortedSet
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  BootstrapWitness,
  NativeScript,
  Script,
  CborRead,
  DecodeField,
  DetachedRecords,
  LedgerDecodeError,
  Metadatum,
  TxIn,
  TxOut,
  AuxiliaryScripts,
  Block,
  ByteStrings,
  Certificate,
  DecoderVersion,
  PoolParams,
  Pot,
  Redeemer,
  Tx,
  TxBody as FlatTxBody,
  Update as FlatUpdate,
  Witnesses as FlatWitnesses
}
import xyz.kd5ujc.scarab.ledger.era.{
  Data,
  IsPhase2Valid,
  KeptUpdate,
  GenesisDelegCert,
  MIRCert,
  MIRPot,
  MIRTarget,
  Network,
  PoolCert,
  ShelleyDelegCert,
  ShelleyTxCertRep,
  ValidityInterval,
  WitVKey
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Coin}
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusData, PlutusLanguage}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Builders for Babbage transactions whose bytes are true: a body and its witnesses are written in
  * the forms of Babbage's CDDL (the body's keys, the tags of its certificates, an update proposal,
  * plain lists, as Babbage's decoder version reads no set tag), then read by the node's flat
  * decoder at Babbage ([[Tx.fromBlock]]) and by Babbage's ([[BabbageTx.decode]]) at every layout,
  * and the two must agree field by field ([[sameBody]], [[sameWits]], [[sameAux]], a projection of
  * the era's values onto the flat ones). The bytes are a form both decoders read, not checked
  * against the reference's own encoder.
  */
object BabbageTestKit extends munit.Assertions:
  import ConwayTestKit.Cb.*
  import ConwayTestKit.{credential, multiasset, output, rewardAccount, txIn, unitInterval}

  private val layouts: Vector[StateLayout] = StateLayout.values.toVector

  // ---- the parts --------------------------------------------------------------------------------

  /** A pool's parameters, the owners a plain list. */
  def poolParams(p: PoolParams): Seq[Bytes] =
    Seq(
      bytes(p.operator),
      bytes(p.vrfKeyHash),
      uint(p.pledge),
      uint(p.cost),
      unitInterval(p.margin),
      bytes(rewardAccount(p.rewardAccount)),
      arr(p.owners.toVector.sorted.map(bytes)*),
      if p.relays.isEmpty then arr() else p.relays,
      p.metadata.getOrElse(`null`)
    )

  /** `certificate` of Babbage's CDDL, tags 0 to 6. */
  def certificate(c: Certificate): Bytes = c match
    case Certificate.Register(cred, None)   => arr(uint(0), credential(cred))
    case Certificate.Unregister(cred, None) => arr(uint(1), credential(cred))
    case Certificate.Delegate(cred, Some(pool), None, None) =>
      arr(uint(2), credential(cred), bytes(pool))
    case Certificate.RegisterPool(p)         => arr((uint(3) +: poolParams(p))*)
    case Certificate.RetirePool(pool, epoch) => arr(uint(4), bytes(pool), uint(epoch))
    case Certificate.GenesisDelegate(genesis, delegate, vrf) =>
      arr(uint(5), bytes(genesis), bytes(delegate), bytes(vrf))
    case Certificate.MoveRewards(pot, transfer) =>
      val target = transfer.fold(
        uint,
        m => map(m.toVector.map((cred, amount) => credential(cred) -> int(amount))*)
      )
      arr(uint(6), arr(uint(if pot == Pot.Reserves then 0 else 1), target))
    case other => fail(s"no Babbage certificate: $other")

  /** An update proposal: the delegates' proposals, each field the bytes it keeps, and the epoch. */
  def update(u: FlatUpdate): Bytes =
    arr(
      map(u.proposals.toVector.sortBy(_._1).map { (delegate, fields) =>
        bytes(delegate) -> map(fields.fields.toVector.sortBy(_._1).map((k, v) => uint(k) -> v)*)
      }*),
      uint(u.epoch)
    )

  /** `transaction_body` of the flat body `b`, an empty optional field left out, its keys in the
    * order `order` gives them (ascending by default).
    */
  def body(b: FlatTxBody, order: Vector[Int] => Vector[Int] = identity): Bytes =
    def ins(xs: Vector[TxIn]): Bytes = arr(xs.map(txIn)*)
    val fields = Vector(
      Some(0 -> ins(b.inputs)),
      Some(1 -> arr(b.outputs.map(output)*)),
      Some(2 -> uint(b.fee)),
      b.ttl.map(t => 3 -> uint(t)),
      Option.when(b.certificates.nonEmpty)(4 -> arr(b.certificates.map(certificate)*)),
      Option.when(b.withdrawals.nonEmpty)(
        5 -> map(
          b.withdrawals.toVector
            .sortBy(_._1)
            .map((ra, c) => bytes(rewardAccount(ra)) -> uint(c.value))*
        )
      ),
      b.update.map(u => 6 -> update(u)),
      b.auxDataHash.map(h => 7 -> bytes(h)),
      b.validityStart.map(t => 8 -> uint(t)),
      Option.when(b.mint.nonEmpty)(9 -> multiasset(b.mint, signed = true)),
      b.scriptDataHash.map(h => 11 -> bytes(h)),
      Option.when(b.collateral.nonEmpty)(13 -> ins(b.collateral)),
      Option.when(b.requiredSigners.nonEmpty)(14 -> arr(b.requiredSigners.map(bytes)*)),
      b.networkId.map(n => 15 -> uint(n)),
      b.collateralReturn.map(o => 16 -> output(o)),
      b.totalCollateral.map(c => 17 -> uint(c)),
      Option.when(b.referenceInputs.nonEmpty)(18 -> ins(b.referenceInputs))
    ).flatten.toMap
    map(order(fields.keys.toVector.sorted).map(k => uint(k) -> fields(k))*)

  /** `transaction_witness_set` with the key witnesses `vkeys`, the native scripts' bytes `native`,
    * the datums' bytes and the redeemers in the list form, each field present only where it holds
    * something.
    */
  def witnessSet(
      vkeys: Vector[(Bytes, Bytes)],
      native: Vector[Bytes] = Vector.empty,
      datums: Vector[Bytes] = Vector.empty,
      redeemers: Vector[Redeemer] = Vector.empty
  ): Bytes =
    val fields = Vector(
      Option.when(vkeys.nonEmpty)(0 -> arr(vkeys.map((vk, sig) => arr(bytes(vk), bytes(sig)))*)),
      Option.when(native.nonEmpty)(1 -> arr(native*)),
      Option.when(datums.nonEmpty)(4 -> arr(datums*)),
      Option.when(redeemers.nonEmpty)(
        5 -> arr(
          redeemers.map(r =>
            arr(uint(r.tag), uint(r.index), r.data, arr(uint(r.exUnits.mem), uint(r.exUnits.steps)))
          )*
        )
      )
    ).flatten
    map(fields.map((k, v) => uint(k) -> v)*)

  /** A transaction's parts read by the flat decoder and by Babbage's at every layout, the two
    * agreeing field by field.
    */
  final case class Built(parts: Block.Transaction, flat: Tx, babbage: Map[StateLayout, BabbageTx]):
    /** The transaction Babbage's decoder read at the 11.1 build's layout. */
    def tx: BabbageTx = babbage(StateLayout.Node11_1)

  def read(parts: Block.Transaction): Built =
    val flat = Tx.fromBlock(parts, Era.Babbage).fold(e => fail(s"flat decoder: $e"), identity)
    val babbage = layouts.map { l =>
      val tx = BabbageTx.decode(parts, l).fold(e => fail(s"Babbage's decoder at $l: $e"), identity)
      sameTx(tx, flat, l.toString)
      l -> tx
    }.toMap
    Built(parts, flat, babbage)

  /** The transaction of the body `b`, its keys in the order `order` gives them, and the witness set
    * `wits`, valid, without auxiliary data.
    */
  def encode(
      b: FlatTxBody,
      wits: Bytes = witnessSet(Vector.empty),
      order: Vector[Int] => Vector[Int] = identity
  ): Built =
    read(Block.Transaction(body(b, order), wits, None, isValid = true))

  /** A flat body of the given inputs, outputs and fee, the other fields empty. */
  def flatBody(inputs: Vector[TxIn], outputs: Vector[TxOut], fee: BigInt): FlatTxBody =
    FlatTxBody(
      Bytes.empty,
      inputs,
      outputs,
      fee,
      None,
      None,
      Vector.empty,
      Map.empty,
      Map.empty,
      Vector.empty,
      Vector.empty,
      None,
      Vector.empty,
      None,
      hasGovernance = false,
      donation = 0
    )

  // ---- the projection onto the flat values

  def flatCert(c: BabbageTxCert): Certificate = c.rep match
    case ShelleyTxCertRep.ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyRegCert(cred)) =>
      Certificate.Register(Convert.back(cred), None)
    case ShelleyTxCertRep.ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyUnRegCert(cred)) =>
      Certificate.Unregister(Convert.back(cred), None)
    case ShelleyTxCertRep.ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyDelegCert(cred, pool)) =>
      Certificate.Delegate(Convert.back(cred), Some(pool.bytes), None, None)
    case ShelleyTxCertRep.ShelleyTxCertPool(PoolCert.RegPool(params)) =>
      Certificate.RegisterPool(Convert.poolParamsBack(params))
    case ShelleyTxCertRep.ShelleyTxCertPool(PoolCert.RetirePool(pool, epoch)) =>
      Certificate.RetirePool(pool.bytes, epoch.toBigInt)
    case ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(GenesisDelegCert(g, d, vrf)) =>
      Certificate.GenesisDelegate(g.bytes, d.bytes, vrf.bytes)
    case ShelleyTxCertRep.ShelleyTxCertMir(MIRCert(pot, target)) =>
      Certificate.MoveRewards(
        pot match
          case MIRPot.ReservesMIR => Pot.Reserves
          case MIRPot.TreasuryMIR => Pot.Treasury
        ,
        target match
          case MIRTarget.SendToOppositePotMIR(coin) => Left(coin.value)
          case MIRTarget.StakeAddressesMIR(rewards) =>
            Right(rewards.map((cred, delta) => Convert.back(cred) -> delta.value))
      )

  /** One field of the era's value against the flat value's, a difference named by the field. */
  private def check[A](field: String, era: A, flat: A): Vector[String] =
    if era == flat then Vector.empty else Vector(s"$field: era $era, flat $flat")

  /** Where the body projected onto the flat body's fields differs from it, the update proposal both
    * as the bytes the node stores and as its values read from them by the typed update's stored
    * reader ([[KeptUpdate.fromStored]]).
    */
  def bodyDifferences(era: BabbageTxBody, flat: FlatTxBody): Vector[String] =
    Vector(
      check("raw", era.raw, flat.raw),
      check("id", era.id, flat.id),
      check("inputs", era.spendInputs, SortedSet.from(flat.inputs)),
      check("outputs", era.outputs.map(o => BabbageTxOut.toStored(o.value)), flat.outputs),
      check("output sizes", era.outputs.map(_.size), flat.outputs.map(_.ledgerSize.toLong)),
      check("fee", era.fee, Coin(flat.fee)),
      check("validity", era.validity, ValidityInterval(flat.validityStart, flat.ttl)),
      check("certificates", era.certs.map(flatCert), flat.certificates),
      check("withdrawals", Right(era.withdrawals), Convert.withdrawals(flat.withdrawals)),
      check("update bytes", era.update.map(_.stored), flat.update),
      check(
        "update values",
        era.update.map(u => Right(u.update)),
        flat.update.map(KeptUpdate.fromStored[Babbage](_).map(_.update))
      ),
      check("auxiliary data hash", era.auxDataHash, flat.auxDataHash),
      check("mint", era.mint, flat.mint),
      check("script integrity hash", era.scriptIntegrityHash, flat.scriptDataHash),
      check("collateral", era.collateralInputs, SortedSet.from(flat.collateral)),
      check("required signers", era.reqSignerHashes, SortedSet.from(flat.requiredSigners)),
      check("network", era.networkId, flat.networkId.flatMap(Network.of)),
      check(
        "collateral return",
        era.collateralReturn.map(o => BabbageTxOut.toStored(o.value)),
        flat.collateralReturn
      ),
      check(
        "collateral return size",
        era.collateralReturn.map(_.size),
        flat.collateralReturn.map(_.ledgerSize.toLong)
      ),
      check("total collateral", era.totalCollateral, flat.totalCollateral.map(Coin(_))),
      check("reference inputs", era.referenceInputs, SortedSet.from(flat.referenceInputs)),
      check(
        "Conway's fields",
        (false, BigInt(0), Option.empty[BigInt]),
        (flat.hasGovernance, flat.donation, flat.currentTreasury)
      )
    ).flatten

  /** A witness set's fields as values both decoders' readings give: each script by hash with its
    * bytes and, for a native script, its decoded tree; each datum by hash with its bytes and its
    * decoded data; each redeemer with its purpose, data and units.
    */
  final case class WitsView(
      raw: Option[Bytes],
      vkeys: SortedSet[WitVKey],
      bootstrap: Vector[BootstrapWitness],
      scripts: Vector[(Bytes, Option[Bytes], Option[NativeScript])],
      arrival: Vector[Bytes],
      datumsRaw: Option[Bytes],
      datums: Map[Bytes, (Bytes, PlutusData)],
      redeemersRaw: Option[Bytes],
      redeemers: Vector[(Int, BigInt, Bytes, ExUnits)]
  )

  def witsView(era: BabbageTxWits): WitsView =
    val s = BabbageScript.instance
    WitsView(
      Some(era.raw),
      era.vkeys,
      era.bootstrap,
      era.scripts.toVector.map((h, x) =>
        (h, Some(s.originalBytes(x)), s.getNativeScript(x).map(_.rep.script))
      ),
      era.scriptArrival,
      era.datums.raw,
      era.datums.datums.map((h, d) => h -> (d.originalBytes, d.getPlutusData)),
      era.redeemers.raw,
      era.redeemers.entries.toVector.map((p, v) => (p.tag, p.ix.value, v._1.originalBytes, v._2))
    )

  /** The flat witnesses as the same values, each datum decoded from its bytes. */
  def flatWitsView(flat: FlatWitnesses): WitsView =
    WitsView(
      flat.raw,
      SortedSet.from(flat.vkeys.map(WitVKey(_, _))),
      flat.bootstrap,
      flat.scriptsByHash.toVector
        .sortBy(_._1)
        .map((h, x) => (h, x.encoding.orElse(x.plutus), x.native)),
      flat.scripts.map(_.hash).distinct,
      flat.datumsRaw,
      flat.datums
        .map(b =>
          Blake2b.hash256
            .hash(b) -> (b, Data.decode(b).fold(e => fail(e.toString), _.getPlutusData))
        )
        .toMap,
      flat.redeemersRaw,
      flat.redeemers.map(r => (r.tag, r.index, r.data, r.exUnits)).sortBy(r => (r._1, r._2))
    )

  /** Where two witness sets' values differ, field by field. */
  def witsViewDifferences(era: WitsView, flat: WitsView): Vector[String] =
    Vector(
      check("raw", era.raw, flat.raw),
      check("key witnesses", era.vkeys, flat.vkeys),
      check("bootstrap witnesses", era.bootstrap, flat.bootstrap),
      check(
        "scripts' bytes",
        era.scripts.map((h, b, _) => h -> b),
        flat.scripts.map((h, b, _) => h -> b)
      ),
      check(
        "native script trees",
        era.scripts.map((h, _, t) => h -> t),
        flat.scripts.map((h, _, t) => h -> t)
      ),
      check("script arrival", era.arrival, flat.arrival),
      check("datums' bytes", era.datumsRaw, flat.datumsRaw),
      check("datums", era.datums, flat.datums),
      check("redeemers' bytes", era.redeemersRaw, flat.redeemersRaw),
      check("redeemers", era.redeemers, flat.redeemers)
    ).flatten

  /** Where the witness set projected onto the flat witnesses differs from them. */
  def witsDifferences(era: BabbageTxWits, flat: FlatWitnesses): Vector[String] =
    witsViewDifferences(witsView(era), flatWitsView(flat))

  /** Auxiliary data's fields as values both readings give: the metadata by label, the native
    * scripts with their bytes and decoded trees, each language's Plutus scripts in their order,
    * repeats kept, and the Plutus scripts in the order they arrived.
    */
  final case class AuxView(
      raw: Bytes,
      metadata: Map[BigInt, Metadatum],
      native: Vector[(Bytes, NativeScript)],
      plutus: Map[PlutusLanguage, Vector[Bytes]],
      arrival: Vector[(PlutusLanguage, Bytes)]
  )

  def auxView(era: BabbageTxAuxData): AuxView =
    AuxView(
      era.raw,
      era.metadata.map((k, v) => k.value -> v),
      era.nativeScripts.map(t => t.rep.raw -> t.rep.script),
      era.plutusScripts.toMap.map((l, ss) => l -> ss.toVector),
      era.plutusArrival
    )

  /** The auxiliary data's fields as the flat readers read them from its bytes at `version`,
    * independently of Babbage's decoder: the metadata by label (a label written twice its last
    * metadatum, below version 9), each by the flat metadatum reader; the native scripts by the flat
    * native-script reader; and the Plutus scripts with their languages in the order they arrived
    * ([[AuxiliaryScripts.read]]), each language's in its order.
    */
  def flatAuxView(
      raw: Bytes,
      version: DecoderVersion = DecoderVersion.of(Era.Babbage)
  ): Either[LedgerDecodeError, AuxView] =
    given ByteStrings = ByteStrings.of(version)
    def metadata(n: Node) =
      CborRead
        .map(n, DecodeField.Metadata)
        .flatMap(
          _.traverse((k, v) =>
            (CborRead.uint(k, DecodeField.MetadataLabel), Metadatum.read(v, true)).tupled
          )
        )
        .map(_.toMap)
    def native(n: Node) =
      CborRead
        .array(n, DecodeField.AuxiliaryNativeScripts)
        .flatMap(
          _.traverse(x =>
            Script.native(x, NativeScript.Form.Timelock).map(sc => x.original -> sc.native.get)
          )
        )
    for
      plutus <- AuxiliaryScripts.read(raw, Era.Babbage, version.value)
      parts <- DetachedRecords.read(
        raw,
        Cbor.Limits.Unbounded,
        DetachedRecords.Shapes.auxiliaryDataAt(Era.Babbage, version.value)
      )(LedgerDecodeError.Syntax(_)) { root =>
        root.value match
          case Value.Map(_)  => metadata(root).map((_, Vector.empty[(Bytes, NativeScript)]))
          case Value.Arr(xs) => (metadata(xs(0)), native(xs(1))).tupled
          case Value.Tag(_, inner) =>
            CborRead.map(inner, DecodeField.AuxiliaryData).flatMap { kvs =>
              val byKey = kvs.collect {
                case (k, v) if k.value == Value.UInt(0) => 0 -> v
                case (k, v) if k.value == Value.UInt(1) => 1 -> v
              }.toMap
              (
                byKey.get(0).traverse(metadata).map(_.getOrElse(Map.empty)),
                byKey.get(1).traverse(native).map(_.orEmpty)
              ).tupled
            }
          case other =>
            Left(
              LedgerDecodeError
                .Expected(DecodeField.AuxiliaryData, LedgerDecodeError.Shape.ArrayOrMap, other)
            )
      }
      arrival = plutus.map(p => p.language -> p.code)
    yield AuxView(
      raw,
      parts._1,
      parts._2,
      arrival.groupMap(_._1)(_._2),
      arrival
    )

  /** Where two auxiliary data's values differ, field by field. */
  def auxViewDifferences(era: AuxView, flat: AuxView): Vector[String] =
    Vector(
      check("raw", era.raw, flat.raw),
      check("metadata", era.metadata, flat.metadata),
      check("native scripts' bytes", era.native.map(_._1), flat.native.map(_._1)),
      check("native script trees", era.native.map(_._2), flat.native.map(_._2)),
      check("Plutus scripts", era.plutus, flat.plutus),
      check("Plutus arrival", era.arrival, flat.arrival)
    ).flatten

  /** Where the auxiliary data differs from its fields as the flat readers read them from `raw`. */
  def auxDifferences(era: BabbageTxAuxData, raw: Bytes): Vector[String] =
    flatAuxView(raw) match
      case Left(e)     => Vector(s"the flat readers refuse the auxiliary data: $e")
      case Right(flat) => auxViewDifferences(auxView(era), flat)

  /** Where the whole transaction differs from the flat one: its body, witness set, phase-two flag,
    * fee size and auxiliary data.
    */
  def txDifferences(era: BabbageTx, flat: Tx): Vector[String] =
    bodyDifferences(era.body, flat.body).map("body " + _) ++
      witsDifferences(era.wits, flat.witnesses).map("witnesses " + _) ++
      check("phase-two flag", era.isValid, IsPhase2Valid.of(flat.isValid)) ++
      check("fee size", era.sizeTxForFeeCalculation, flat.size) ++
      check("auxiliary data's bytes", era.auxData.map(_.raw), flat.auxiliary) ++
      era.auxData
        .zip(flat.auxiliary)
        .toVector
        .flatMap((a, raw) => auxDifferences(a, raw).map("auxiliary data " + _))

  def sameBody(era: BabbageTxBody, flat: FlatTxBody, what: String): Unit =
    assertEquals(bodyDifferences(era, flat), Vector.empty, what)

  def sameWits(era: BabbageTxWits, flat: FlatWitnesses, what: String): Unit =
    assertEquals(witsDifferences(era, flat), Vector.empty, what)

  def sameAux(era: BabbageTxAuxData, raw: Bytes, what: String): Unit =
    assertEquals(auxDifferences(era, raw), Vector.empty, what)

  def sameTx(era: BabbageTx, flat: Tx, what: String): Unit =
    assertEquals(txDifferences(era, flat), Vector.empty, what)
