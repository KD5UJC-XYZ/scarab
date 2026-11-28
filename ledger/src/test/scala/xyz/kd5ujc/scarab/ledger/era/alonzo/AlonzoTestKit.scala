// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  Certificate,
  DecoderVersion,
  LedgerDecodeError,
  Tx,
  TxBody as FlatTxBody,
  Witnesses as FlatWitnesses
}
import xyz.kd5ujc.scarab.ledger.era.{Data, IsPhase2Valid, KeptUpdate, Network, ValidityInterval}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit.{AuxView, WitsView}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Coin, PoolParamsBlsKey}
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Builders and comparisons for Alonzo transactions whose bytes are true: a body and its witnesses
  * are written in the forms of Alonzo's CDDL by Babbage's builders ([[BabbageTestKit.body]],
  * [[BabbageTestKit.witnessSet]], which write only the fields a body holds), then read by the
  * node's flat decoder at Alonzo ([[Tx.fromBlock]]) and by Alonzo's ([[AlonzoTx.decode]]) at every
  * layout, and the two must agree field by field ([[sameBody]], [[sameWits]], [[sameAux]], a
  * projection of the era's values onto the flat ones). The bytes are a form both decoders read, not
  * checked against the reference's own encoder.
  */
object AlonzoTestKit extends munit.Assertions:
  private val layouts: Vector[StateLayout] = StateLayout.values.toVector

  /** A transaction's parts read by the flat decoder and by Alonzo's at every layout, the two
    * agreeing field by field.
    */
  final case class Built(parts: Block.Transaction, flat: Tx, alonzo: Map[StateLayout, AlonzoTx]):
    /** The transaction Alonzo's decoder read at the 11.1 build's layout. */
    def tx: AlonzoTx = alonzo(StateLayout.Node11_1)

  def read(parts: Block.Transaction): Built =
    val flat = Tx.fromBlock(parts, Era.Alonzo).fold(e => fail(s"flat decoder: $e"), identity)
    val alonzo = layouts.map { l =>
      val tx = AlonzoTx.decode(parts, l).fold(e => fail(s"Alonzo's decoder at $l: $e"), identity)
      sameTx(tx, flat, l.toString, l)
      l -> tx
    }.toMap
    Built(parts, flat, alonzo)

  /** The transaction of the body `b`, its keys in the order `order` gives them, and the witness set
    * `wits`, valid, without auxiliary data.
    */
  def encode(
      b: FlatTxBody,
      wits: Bytes = BabbageTestKit.witnessSet(Vector.empty),
      order: Vector[Int] => Vector[Int] = identity
  ): Built =
    read(Block.Transaction(BabbageTestKit.body(b, order), wits, None, isValid = true))

  // ---- the projection onto the flat values

  def flatCert(c: AlonzoTxCert): Certificate = BabbageTestKit.flatCertRep(c.rep)

  /** One field of the era's value against the flat value's, a difference named by the field. */
  private def check[A](field: String, era: A, flat: A): Vector[String] =
    if era == flat then Vector.empty else Vector(s"$field: era $era, flat $flat")

  /** The voting key each pool registration's parameters hold at `layout`, written out here rather
    * than taken from the code under test: none of the 11.1 build's place, and no key in the Leios
    * build's, whose parameters read one from protocol version 12 only (`decodeStakePoolParamsFlat`,
    * cardano-ledger 9df9f98c
    * `libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:746-762`).
    */
  def expectedPoolKeys(flat: FlatTxBody, layout: StateLayout): Vector[PoolParamsBlsKey] =
    val key = layout match
      case StateLayout.Node11_1 => PoolParamsBlsKey.Node11_1
      case StateLayout.Leios    => PoolParamsBlsKey.Leios(None)
    flat.certificates.collect { case Certificate.RegisterPool(_) => key }

  /** The voting key of each pool registration of the body, in order. */
  def poolKeys(era: AlonzoTxBody): Vector[PoolParamsBlsKey] =
    era.certs.flatMap(AlonzoTxCert.instance.getRegPoolTxCert).map(_.blsKey)

  /** Where the pool registrations' voting keys differ from those `layout` gives: the field the flat
    * certificate has no place for, so its projection ([[flatCert]]) does not see it.
    */
  def poolKeyDifferences(
      era: Vector[PoolParamsBlsKey],
      flat: FlatTxBody,
      layout: StateLayout
  ): Vector[String] =
    check("pool registrations' voting keys", era, expectedPoolKeys(flat, layout))

  /** Where the body projected onto the flat body's fields differs from it, the update proposal both
    * as the bytes the node stores and as its values read from them by the typed update's stored
    * reader ([[KeptUpdate.fromStored]]), a pool registration's parameters whole, its voting key at
    * `layout` among them ([[poolKeyDifferences]]), and the fields of later eras, which the flat
    * body leaves empty, compared as empty.
    */
  def bodyDifferences(era: AlonzoTxBody, flat: FlatTxBody, layout: StateLayout): Vector[String] =
    poolKeyDifferences(poolKeys(era), flat, layout) ++ Vector(
      check("raw", era.raw, flat.raw),
      check("id", era.id, flat.id),
      check("inputs", era.spendInputs, SortedSet.from(flat.inputs)),
      check("outputs", era.outputs.map(AlonzoTxOut.toStored), flat.outputs),
      check("fee", era.fee, Coin(flat.fee)),
      check("validity", era.validity, ValidityInterval(flat.validityStart, flat.ttl)),
      check("certificates", era.certs.map(flatCert), flat.certificates),
      check("withdrawals", Right(era.withdrawals), Convert.withdrawals(flat.withdrawals)),
      check("update bytes", era.update.map(_.stored), flat.update),
      check(
        "update values",
        era.update.map(u => Right(u.update)),
        flat.update.map(KeptUpdate.fromStored[Alonzo](_).map(_.update))
      ),
      check("auxiliary data hash", era.auxDataHash, flat.auxDataHash),
      check("mint", era.mint, flat.mint),
      check("script integrity hash", era.scriptIntegrityHash, flat.scriptDataHash),
      check("collateral", era.collateralInputs, SortedSet.from(flat.collateral)),
      check("required signers", era.reqSignerHashes, SortedSet.from(flat.requiredSigners)),
      check("network", era.networkId, flat.networkId.flatMap(Network.of)),
      check(
        "Babbage's fields",
        (Vector.empty, None, None),
        (flat.referenceInputs, flat.collateralReturn, flat.totalCollateral)
      ),
      check(
        "Conway's fields",
        (false, BigInt(0), Option.empty[BigInt]),
        (flat.hasGovernance, flat.donation, flat.currentTreasury)
      )
    ).flatten

  def witsView(era: AlonzoTxWits): WitsView =
    val s = AlonzoScript.instance
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

  /** Each redeemer's decoded data by its purpose's tag and index, in the purposes' order. */
  def redeemerData(era: AlonzoTxWits): Vector[((Int, BigInt), PlutusData)] =
    era.redeemers.entries.toVector.map((p, v) => (p.tag, p.ix.value) -> v._1.getPlutusData)

  /** The flat redeemers' data decoded from their bytes, in the same order. */
  def flatRedeemerData(flat: FlatWitnesses): Vector[((Int, BigInt), PlutusData)] =
    flat.redeemers
      .map(r =>
        (r.tag, r.index) -> Data.decode(r.data).fold(e => fail(e.toString), _.getPlutusData)
      )
      .sortBy(_._1)

  /** Where the redeemers' decoded data differ: what the comparison of their bytes and units alone
    * ([[BabbageTestKit.witsViewDifferences]]) does not see.
    */
  def redeemerDataDifferences(
      era: Vector[((Int, BigInt), PlutusData)],
      flat: FlatWitnesses
  ): Vector[String] =
    check("redeemer data", era, flatRedeemerData(flat))

  /** Where the witness set projected onto the flat witnesses differs from them. */
  def witsDifferences(era: AlonzoTxWits, flat: FlatWitnesses): Vector[String] =
    BabbageTestKit.witsViewDifferences(witsView(era), BabbageTestKit.flatWitsView(flat)) ++
      redeemerDataDifferences(redeemerData(era), flat)

  def auxView(era: AlonzoTxAuxData): AuxView =
    AuxView(
      era.raw,
      era.metadata.map((k, v) => k.value -> v),
      era.nativeScripts.map(t => t.rep.raw -> t.rep.script),
      era.plutusScripts.toMap.map((l, ss) => l -> ss.toVector),
      era.plutusArrival
    )

  /** The auxiliary data's fields as the flat readers read them from its bytes at `version`, one of
    * Alonzo's ([[BabbageTestKit.flatAuxView]] at Alonzo).
    */
  def flatAuxView(
      raw: Bytes,
      version: DecoderVersion = DecoderVersion.of(Era.Alonzo)
  ): Either[LedgerDecodeError, AuxView] =
    BabbageTestKit.flatAuxView(raw, version, Era.Alonzo)

  /** Where the auxiliary data differs from its fields as the flat readers read them from `raw`. */
  def auxDifferences(era: AlonzoTxAuxData, raw: Bytes): Vector[String] =
    flatAuxView(raw) match
      case Left(e)     => Vector(s"the flat readers refuse the auxiliary data: $e")
      case Right(flat) => BabbageTestKit.auxViewDifferences(auxView(era), flat)

  /** Where the whole transaction differs from the flat one: its body, witness set, phase-two flag,
    * fee size and auxiliary data.
    */
  def txDifferences(era: AlonzoTx, flat: Tx, layout: StateLayout): Vector[String] =
    bodyDifferences(era.body, flat.body, layout).map("body " + _) ++
      witsDifferences(era.wits, flat.witnesses).map("witnesses " + _) ++
      check("phase-two flag", era.isValid, IsPhase2Valid.of(flat.isValid)) ++
      check("fee size", era.sizeTxForFeeCalculation, flat.size) ++
      check("auxiliary data's bytes", era.auxData.map(_.raw), flat.auxiliary) ++
      era.auxData
        .zip(flat.auxiliary)
        .toVector
        .flatMap((a, raw) => auxDifferences(a, raw).map("auxiliary data " + _))

  def sameBody(era: AlonzoTxBody, flat: FlatTxBody, what: String, layout: StateLayout): Unit =
    assertEquals(bodyDifferences(era, flat, layout), Vector.empty, what)

  def sameWits(era: AlonzoTxWits, flat: FlatWitnesses, what: String): Unit =
    assertEquals(witsDifferences(era, flat), Vector.empty, what)

  def sameAux(era: AlonzoTxAuxData, raw: Bytes, what: String): Unit =
    assertEquals(auxDifferences(era, raw), Vector.empty, what)

  def sameTx(era: AlonzoTx, flat: Tx, what: String, layout: StateLayout): Unit =
    assertEquals(txDifferences(era, flat, layout), Vector.empty, what)
