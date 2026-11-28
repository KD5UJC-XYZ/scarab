// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import scala.collection.immutable.SortedSet
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  AuxiliaryScripts,
  ByteStrings,
  Certificate,
  Credential,
  DecodeField,
  DecoderVersion,
  LedgerDecodeError,
  ParamUpdate,
  PoolParams,
  Pot,
  Redeemer,
  RewardAccount,
  ScriptKind,
  TestLedger,
  TxBody as FlatTxBody,
  TxIn,
  Unsigned,
  Update as FlatUpdate,
  Witnesses as FlatWitnesses
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoPlutusPurpose,
  AsIx,
  AsIxItem,
  EraTx,
  EraTxBody,
  EraTxCert,
  EraTxError,
  GenesisDelegCert,
  IsPhase2Valid,
  MIRCert,
  MIRPot,
  MIRTarget,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Coin,
  DeltaCoin,
  EpochNo,
  Genesis,
  GenesisDelegate,
  KeyHash,
  KeyRole,
  StakePool,
  Staking,
  VRFVerKeyHash
}
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusLanguage}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Alonzo's transaction built through the encoders and read by both decoders ([[AlonzoTestKit]]): a
  * body with every field Alonzo has read by the era's classes, Babbage's keys refused, and the
  * refusals where the era decoders follow the reference's order and the flat decoder does not.
  */
class AlonzoTxSuite extends FunSuite:
  import ConwayTestKit.Cb.*
  import AlonzoTestKit.*
  import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit.{flatBody, witnessSet}

  private def hash(n: Int, size: Int): Bytes = Bytes(Vector.fill(size)(n.toByte))
  private val owner = hash(1, 28)
  private val script = hash(2, 28)
  private val pool = hash(3, 28)
  private val genesis = hash(4, 28)
  private val delegate = hash(5, 28)
  private val vrf = hash(6, 32)
  private val signer = hash(7, 28)
  private val policy = hash(8, 28)
  private def in(i: Int): TxIn = TxIn(hash(9, 32), i)
  private val out = ConwayTestKit.out(TestLedger.key().address(), 2000000)

  /** A certificate of each of Alonzo's kinds, the pool's retirement twice: a repeat, which Alonzo's
    * sequence of certificates keeps.
    */
  private val certificates: Vector[Certificate] = Vector(
    Certificate.Register(Credential.Key(owner), None),
    Certificate.Unregister(Credential.Key(owner), None),
    Certificate.Delegate(Credential.Script(script), Some(pool), None, None),
    Certificate.RetirePool(pool, 300),
    Certificate.GenesisDelegate(genesis, delegate, vrf),
    Certificate.MoveRewards(Pot.Reserves, Right(Map(Credential.Key(owner) -> BigInt(-5)))),
    Certificate.MoveRewards(Pot.Treasury, Left(1000)),
    Certificate.RetirePool(pool, 300),
    Certificate.RegisterPool(
      PoolParams(
        pool,
        vrf,
        1000,
        340,
        Rational(1, 10),
        RewardAccount(0, Credential.Key(owner)),
        SortedSet(owner),
        Bytes.empty,
        None
      )
    )
  )

  private val proposal = FlatUpdate(
    Map(genesis -> ParamUpdate(Map(0 -> uint(44), 14 -> arr(uint(6), uint(0))))),
    5
  )

  private val everyField: FlatTxBody =
    flatBody(Vector(in(0), in(1)), Vector(out), 200000).copy(
      ttl = Some(1000),
      validityStart = Some(10),
      certificates = certificates,
      withdrawals = Map(RewardAccount(0, Credential.Key(owner)) -> Coin(10)),
      mint = Map(policy -> Map(hash(10, 4) -> BigInt(5), hash(11, 4) -> BigInt(-3))),
      collateral = Vector(in(2)),
      requiredSigners = Vector(signer, owner),
      networkId = Some(0),
      scriptDataHash = Some(hash(12, 32)),
      auxDataHash = Some(hash(13, 32)),
      update = Some(proposal)
    )

  private def keyHash[R <: KeyRole](b: Bytes): KeyHash[R] =
    KeyHash.fromBytes[R](b).fold(e => fail(e.toString), identity)

  private def ix(i: Int): Unsigned[Unsigned.Width.W32] =
    Unsigned.from[Unsigned.Width.W32](BigInt(i)).getOrElse(fail(s"$i"))

  test("a body of every field Alonzo has reads alike both ways, and the era's classes read it") {
    val built = encode(everyField)
    val tx = built.tx
    val b = EraTxBody.alonzo
    val body = EraTx.alonzo.body[TxLevel.Top](tx)(using b.top)
    val certs = b.certs[TxLevel.Top](body)(using b.top)
    // the certificates in the order read, the repeat kept
    assertEquals(certs.size, 9)
    val c = EraTxCert.alonzo
    assertEquals(
      certs.map(c.getVKeyWitnessTxCert),
      Vector(None, Some(owner), None, Some(pool), Some(genesis), None, None, Some(pool), Some(pool))
    )
    assertEquals(certs.map(c.getScriptWitnessTxCert).flatten, Vector(script))
    assertEquals(
      certs.flatMap(c.lookupRegStakeTxCert),
      Vector(Convert.credential[Staking](Credential.Key(owner)).toOption.get)
    )
    assertEquals(certs.flatMap(c.lookupUnRegStakeTxCert).size, 1)
    assertEquals(
      certs.flatMap(c.getRetirePoolTxCert),
      Vector.fill(2)((keyHash[StakePool](pool), EpochNo.fromBigInt(300).toOption.get))
    )
    assertEquals(
      certs.flatMap(c.getGenesisDelegTxCert),
      Vector(
        GenesisDelegCert(
          keyHash[Genesis](genesis),
          keyHash[GenesisDelegate](delegate),
          VRFVerKeyHash.fromBytes(vrf).fold(e => fail(e.toString), identity)
        )
      )
    )
    assertEquals(
      certs.flatMap(c.getMirTxCert),
      Vector(
        MIRCert(
          MIRPot.ReservesMIR,
          MIRTarget.StakeAddressesMIR(
            Map(Convert.credential[Staking](Credential.Key(owner)).toOption.get -> DeltaCoin(-5))
          )
        ),
        MIRCert(MIRPot.TreasuryMIR, MIRTarget.SendToOppositePotMIR(Coin(1000)))
      )
    )
    assertEquals(certs.flatMap(c.getRegPoolTxCert).map(_.id), Vector(keyHash[StakePool](pool)))
    // a certificate built is one read
    assertEquals(c.mkMirTxCert(c.getMirTxCert(certs(6)).get), certs(6))
    // the update proposal, typed, by the genesis delegate's key hash, and its stored form the flat one
    val update = b.update(body)
    assertEquals(update.map(_.epoch), Some(EpochNo.fromBigInt(5).toOption.get))
    assertEquals(update.map(_.proposals.updates.keySet), Some(Set(keyHash[Genesis](genesis))))
    assertEquals(body.update.map(_.stored), Some(built.flat.body.update.get))
    // the inputs and outputs of the classes: every input the UTxO set must hold is spendable,
    // the spent and the collateral ones (`Alonzo/TxBody.hs:243-247`, cardano-ledger f649f975)
    assertEquals(b.allInputs(body), SortedSet(in(0), in(1), in(2)))
    assertEquals(b.spendableInputs[TxLevel.Top](body)(using b.top), b.allInputs(body))
    assertEquals(b.outputs[TxLevel.Top](body)(using b.top).map(AlonzoTxOut.toStored), Vector(out))
    assertEquals(b.reqSignerHashes[TxLevel.Top](body)(using b.top), SortedSet(signer, owner))
    assertEquals(b.fee(body), Coin(200000))
    // a redeemer's pointer names an item of the body, or nothing
    def inverse(p: AlonzoPlutusPurpose[AsIx, Alonzo]) =
      b.redeemerPointerInverse[TxLevel.Top](body, p)(using b.top)
    assertEquals(
      inverse(AlonzoPlutusPurpose.AlonzoCertifying(AsIx(ix(6)))),
      Some(AlonzoPlutusPurpose.AlonzoCertifying[AsIxItem, Alonzo](AsIxItem(ix(6), certs(6))))
    )
    assertEquals(
      inverse(AlonzoPlutusPurpose.AlonzoMinting(AsIx(ix(0)))),
      Some(AlonzoPlutusPurpose.AlonzoMinting[AsIxItem, Alonzo](AsIxItem(ix(0), policy)))
    )
    assertEquals(inverse(AlonzoPlutusPurpose.AlonzoSpending(AsIx(ix(2)))), None)
    assertEquals(EraTx.alonzo.isValid(tx), IsPhase2Valid.Phase2Valid)
    assertEquals(EraTx.alonzo.sizeTxF[TxLevel.Top](tx)(using b.top), built.flat.size)
  }

  test("a body whose keys are written in another order reads as the same values") {
    val reversed = encode(everyField.copy(update = None), order = _.reverse)
    val ascending = encode(everyField.copy(update = None))
    assertNotEquals(reversed.tx.body.raw, ascending.tx.body.raw)
    assertEquals(reversed.tx.body.certs, ascending.tx.body.certs)
    assertEquals(reversed.tx.body.withdrawals, ascending.tx.body.withdrawals)
  }

  private def refusals(raw: Bytes): (Option[LedgerDecodeError], Option[EraTxError]) =
    (
      FlatTxBody.decode(raw, Era.Alonzo).left.toOption,
      AlonzoTxBody.decode(raw, StateLayout.Node11_1).left.toOption
    )

  private val base = Vector(0 -> arr(ConwayTestKit.txIn(in(0))), 1 -> arr(out.raw), 2 -> uint(1))

  private def bodyOf(fields: Vector[(Int, Bytes)]): Bytes = map(fields.map((k, v) => uint(k) -> v)*)

  test("a body's keys are read as written: a value is refused before a later unknown key") {
    // the reference's `decodeSparse` reads key 3's slot before it meets key 12
    val (flat, era) = refusals(bodyOf(base ++ Vector(3 -> text("x"), 12 -> uint(0))))
    assertEquals(flat, Some(LedgerDecodeError.UnknownFields(DecodeField.TransactionBody, Set(12))))
    assert(
      era.exists {
        case EraTxError.Decode(LedgerDecodeError.Expected(DecodeField.Ttl, _, _)) => true
        case _                                                                    => false
      },
      era.toString
    )
    // an unknown key before it is refused first, both ways
    val (flat2, era2) = refusals(bodyOf(base ++ Vector(12 -> uint(0), 3 -> text("x"))))
    assertEquals(flat2, Some(LedgerDecodeError.UnknownFields(DecodeField.TransactionBody, Set(12))))
    assertEquals(era2, flat2.map(EraTxError.Decode(_)))
  }

  test("a repeated key, a missing fee and a certificate of Conway's are refused both ways") {
    val repeated = refusals(bodyOf(base :+ (2 -> uint(2))))
    assertEquals(repeated._1, Some(LedgerDecodeError.DuplicateKey(DecodeField.TransactionBody)))
    assertEquals(repeated._2, repeated._1.map(EraTxError.Decode(_)))
    val noFee = refusals(bodyOf(base.take(2)))
    assertEquals(
      noFee._1,
      Some(LedgerDecodeError.MissingField(DecodeField.TransactionBody, DecodeField.Fee))
    )
    assertEquals(noFee._2, noFee._1.map(EraTxError.Decode(_)))
    // a registration with a deposit, tag 7, which Alonzo's decoder does not read
    val conway = refusals(
      bodyOf(
        base :+ (4 -> arr(arr(uint(7), ConwayTestKit.credential(Credential.Key(owner)), uint(2))))
      )
    )
    assert(conway._1.isDefined)
    assertEquals(conway._2, conway._1.map(EraTxError.Decode(_)))
  }

  test("Babbage's keys are refused, as keys Alonzo does not know, before their values") {
    // `bodyFields n = invalidField n` (`Alonzo/TxBody.hs:485`, cardano-ledger f649f975): the
    // reference inputs, the collateral return and the total collateral are Babbage's
    Vector(16 -> text("x"), 17 -> text("x"), 18 -> text("x")).foreach { (k, v) =>
      val (flat, era) = refusals(bodyOf(base :+ (k -> v)))
      assertEquals(flat, Some(LedgerDecodeError.UnknownFields(DecodeField.TransactionBody, Set(k))))
      assertEquals(era, flat.map(EraTxError.Decode(_)))
    }
  }

  private def aux(raw: Bytes): (Option[LedgerDecodeError], Either[EraTxError, AlonzoTxAuxData]) =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Alonzo))
    (AuxiliaryScripts.read(raw, Era.Alonzo, 5).left.toOption, AlonzoTxAuxData.decode(raw))

  test(
    "auxiliary data's Plutus keys: PlutusV1 read, the later languages refused before their value"
  ) {
    val (flat, era) =
      aux(tag(259, map(uint(2) -> arr(bytes(hash(1, 4)), bytes(hash(2, 4))))))
    assertEquals(flat, None)
    val read = era.fold(e => fail(e.toString), identity)
    assertEquals(
      read.plutusArrival,
      Vector(PlutusLanguage.V1 -> hash(1, 4), PlutusLanguage.V1 -> hash(2, 4))
    )
    // PlutusV2's and PlutusV3's keys, refused below versions 7 and 9 both ways (`guardPlutus`,
    // `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:639-647`, cardano-ledger
    // f649f975), whatever the value: the guard comes first
    def guarded(minimum: Int) = Some(
      LedgerDecodeError.NumberOutOfRange(DecodeField.AuxiliaryScriptProtocol, 5, minimum, 11)
    )
    val v2 = aux(tag(259, map(uint(3) -> text("x"))))
    assertEquals(v2._1, guarded(7))
    assertEquals(v2._2.left.toOption, v2._1.map(EraTxError.Decode(_)))
    val v3 = aux(tag(259, map(uint(4) -> arr())))
    assertEquals(v3._1, guarded(9))
    assertEquals(v3._2.left.toOption, v3._1.map(EraTxError.Decode(_)))
    // PlutusV4's key: the reference knows it and refuses the language below version 12
    // (`auxDataField 5`, `Alonzo/TxAuxData.hs:299`, cardano-ledger f649f975), where the flat
    // reader refuses the key as unknown
    val v4 = aux(tag(259, map(uint(5) -> arr())))
    assertEquals(v4._1, Some(LedgerDecodeError.UnknownTag(DecodeField.AuxiliaryField, 5)))
    assertEquals(
      v4._2.left.toOption,
      Some(
        EraTxError.Decode(
          LedgerDecodeError.NumberOutOfRange(DecodeField.AuxiliaryScriptProtocol, 5, 12, 12)
        )
      )
    )
  }

  private def wits(raw: Bytes): (Option[LedgerDecodeError], Option[EraTxError]) =
    (
      FlatWitnesses.decode(raw, Era.Alonzo).left.toOption,
      AlonzoTxWits.decode(raw).left.toOption
    )

  private def unsupported(kind: ScriptKind): LedgerDecodeError =
    LedgerDecodeError.UnsupportedLanguage(kind, ScriptKind.PlutusV1)

  test("a witness set's PlutusV2 and PlutusV3 scripts are refused, an empty list read, both ways") {
    // `txWitnessField 6` and `7` (`Alonzo/TxWits.hs:673-674`, cardano-ledger f649f975): keys
    // Alonzo knows, of languages newer than its own
    Vector(6 -> ScriptKind.PlutusV2, 7 -> ScriptKind.PlutusV3).foreach { (k, kind) =>
      val held = wits(map(uint(k) -> arr(bytes(hash(1, 4)))))
      assertEquals(held._1, Some(unsupported(kind)))
      assertEquals(held._2, held._1.map(EraTxError.Decode(_)))
      assertEquals(wits(map(uint(k) -> arr())), (None, None))
    }
    // PlutusV1's scripts are read
    assertEquals(wits(map(uint(3) -> arr(bytes(hash(1, 4))))), (None, None))
  }

  test("a newer language's list is refused at its first script, the keys in the order written") {
    // `scriptDecoder` of `decodePlutusScript` (`Alonzo/TxWits.hs:753-761`,
    // `Alonzo/Scripts.hs:658-665`, cardano-ledger f649f975): the first item's bytes are read and
    // refused as no Alonzo script before the second is read
    val raw = map(uint(6) -> arr(bytes(Bytes.empty), uint(0)))
    assertEquals(
      wits(raw),
      (
        Some(
          LedgerDecodeError.Expected(
            DecodeField.PlutusScript,
            LedgerDecodeError.Shape.Bytes,
            xyz.kd5ujc.scarab.codec.Value.UInt(0)
          )
        ),
        Some(EraTxError.Decode(unsupported(ScriptKind.PlutusV2)))
      )
    )
    // PlutusV3's key written before PlutusV2's: the era decoder refuses the first written, as
    // `decodeSparse` reads them; the flat decoder reads key 6 first
    val v3First = map(uint(7) -> arr(bytes(hash(1, 4))), uint(6) -> arr(bytes(hash(2, 4))))
    assertEquals(
      wits(v3First),
      (
        Some(unsupported(ScriptKind.PlutusV2)),
        Some(EraTxError.Decode(unsupported(ScriptKind.PlutusV3)))
      )
    )
    val v2First = map(uint(6) -> arr(bytes(hash(2, 4))), uint(7) -> arr(bytes(hash(1, 4))))
    assertEquals(
      wits(v2First),
      (
        Some(unsupported(ScriptKind.PlutusV2)),
        Some(EraTxError.Decode(unsupported(ScriptKind.PlutusV2)))
      )
    )
  }

  test("of the redeemers of one purpose the last is kept, keyed by Alonzo's purpose") {
    val data = uint(1)
    val redeemers = Vector(
      Redeemer(2, 0, uint(0), ExUnits(1, 1)),
      Redeemer(0, 1, data, ExUnits(2, 2)),
      Redeemer(2, 0, data, ExUnits(3, 3))
    )
    val built =
      encode(everyField.copy(update = None), witnessSet(Vector.empty, redeemers = redeemers))
    assertEquals(
      built.tx.wits.redeemers.entries.toVector.map((p, v) => (p, v._2)),
      Vector(
        AlonzoPlutusPurpose.AlonzoSpending[AsIx, Alonzo](AsIx(ix(1))) -> ExUnits(2, 2),
        AlonzoPlutusPurpose.AlonzoCertifying[AsIx, Alonzo](AsIx(ix(0))) -> ExUnits(3, 3)
      )
    )
    // the units of every redeemer kept, added up
    assertEquals(
      EraTx.alonzo.getTotalExUnits[TxLevel.Top](built.tx)(using EraTxBody.alonzo.top),
      ExUnits(5, 5)
    )
  }

  test("an update proposing a protocol version above Alonzo's is refused both ways") {
    // the proposed major version is read as a version at most Alonzo's decoder version allows,
    // both ways with the same refusal
    val update = xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit.update(
      FlatUpdate(Map(genesis -> ParamUpdate(Map(14 -> arr(uint(8), uint(0))))), 5)
    )
    val (flat, era) = refusals(bodyOf(base :+ (6 -> update)))
    assert(
      flat.exists {
        case LedgerDecodeError.NumberOutOfRange(_, v, _, _) => v == BigInt(8)
        case _                                              => false
      },
      flat.toString
    )
    assertEquals(era, flat.map(EraTxError.Decode(_)))
  }

  test("a pool registration's voting key is compared at each layout, a wrong one named") {
    // the field the flat certificate has no place for: the 11.1 build's parameters have none, the
    // Leios build's none read below protocol version 12
    val built = encode(everyField.copy(update = None))
    StateLayout.values.foreach { l =>
      val body = built.alonzo(l).body
      val keys = poolKeys(body)
      assertEquals(keys.size, 1)
      assertEquals(poolKeyDifferences(keys, built.flat.body, l), Vector.empty)
    }
    // the 11.1 build's key read at the Leios layout, and the Leios build's at the 11.1 layout
    assertEquals(
      StateLayout.values.toVector.map(l =>
        poolKeyDifferences(
          poolKeys(built.alonzo(l).body),
          built.flat.body,
          StateLayout.values.find(_ != l).get
        ).map(_.takeWhile(_ != ':'))
      ),
      Vector.fill(2)(Vector("pool registrations' voting keys"))
    )
  }
