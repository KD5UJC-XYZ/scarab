// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
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
import xyz.kd5ujc.scarab.ledger.state.Coin
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
      sameBody(tx.body, flat.body, l.toString)
      sameWits(tx.wits, flat.witnesses, l.toString)
      tx.auxData.foreach(a => sameAux(a, a.raw, l.toString))
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

  /** The body projected onto the flat body's fields, each asserted equal. */
  def sameBody(era: BabbageTxBody, flat: FlatTxBody, what: String): Unit =
    assertEquals(era.raw, flat.raw, what)
    assertEquals(era.id, flat.id, what)
    assertEquals(era.spendInputs, SortedSet.from(flat.inputs), what)
    assertEquals(era.outputs.map(o => BabbageTxOut.toStored(o.value)), flat.outputs, what)
    assertEquals(era.outputs.map(_.size), flat.outputs.map(_.ledgerSize.toLong), what)
    assertEquals(era.fee, Coin(flat.fee), what)
    assertEquals(era.validity, ValidityInterval(flat.validityStart, flat.ttl), what)
    assertEquals(era.certs.map(flatCert), flat.certificates, what)
    assertEquals(Right(era.withdrawals), Convert.withdrawals(flat.withdrawals), what)
    assertEquals(era.update.map(_.stored), flat.update, what)
    assertEquals(era.auxDataHash, flat.auxDataHash, what)
    assertEquals(era.mint, flat.mint, what)
    assertEquals(era.scriptIntegrityHash, flat.scriptDataHash, what)
    assertEquals(era.collateralInputs, SortedSet.from(flat.collateral), what)
    assertEquals(era.reqSignerHashes, SortedSet.from(flat.requiredSigners), what)
    assertEquals(era.networkId, flat.networkId.flatMap(Network.of), what)
    assertEquals(
      era.collateralReturn.map(o => BabbageTxOut.toStored(o.value)),
      flat.collateralReturn
    )
    assertEquals(era.collateralReturn.map(_.size), flat.collateralReturn.map(_.ledgerSize.toLong))
    assertEquals(era.totalCollateral, flat.totalCollateral.map(Coin(_)), what)
    assertEquals(era.referenceInputs, SortedSet.from(flat.referenceInputs), what)
    // the fields of Conway's a Babbage body has not
    assert(!flat.hasGovernance && flat.donation == 0 && flat.currentTreasury.isEmpty, what)

  def sameWits(era: BabbageTxWits, flat: FlatWitnesses, what: String): Unit =
    assertEquals(Some(era.raw), flat.raw, what)
    assertEquals(era.vkeys, SortedSet.from(flat.vkeys.map(WitVKey(_, _))), what)
    assertEquals(era.bootstrap, flat.bootstrap, what)
    assertEquals(era.scripts.keySet, flat.scriptsByHash.keySet, what)
    assertEquals(era.scriptArrival, flat.scripts.map(_.hash).distinct, what)
    era.scripts.foreach((h, s) =>
      assertEquals(BabbageScript.instance.originalBytes(s), flatScriptBytes(flat, h), what)
    )
    assertEquals(era.datums.raw, flat.datumsRaw, what)
    assertEquals(era.datums.datums.keySet, flat.datumHashes, what)
    assertEquals(era.redeemers.raw, flat.redeemersRaw, what)
    assertEquals(
      era.redeemers.entries.toVector.map((p, v) => (p.tag, p.ix.value, v._1.originalBytes, v._2)),
      flat.redeemers
        .map(r => (r.tag, r.index, r.data, r.exUnits))
        .sortBy(r => (r._1, r._2))
    )

  // the bytes of a flat witness script: a native one's encoding, a Plutus one's binary
  private def flatScriptBytes(flat: FlatWitnesses, hash: Bytes): Bytes =
    val s = flat.scriptsByHash(hash)
    s.encoding.orElse(s.plutus).getOrElse(fail(s"script $hash keeps no bytes"))

  def sameAux(era: BabbageTxAuxData, raw: Bytes, what: String): Unit =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Babbage))
    val flat = AuxiliaryScripts.read(raw, Era.Babbage, 7).fold(e => fail(s"$what: $e"), identity)
    assertEquals(era.raw, raw, what)
    assertEquals(era.plutusArrival, flat.map(p => p.language -> p.code), what)
