// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

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
  Babbage,
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

/** Babbage's transaction built through the encoders and read by both decoders ([[BabbageTestKit]]):
  * a body with every field Babbage has read by the era's classes, and the refusals where the era
  * decoders follow the reference's order and the flat decoder does not.
  */
class BabbageTxSuite extends FunSuite:
  import ConwayTestKit.Cb.*
  import BabbageTestKit.*

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

  /** A certificate of each of Babbage's kinds, the pool's retirement twice: a repeat, which
    * Babbage's sequence of certificates keeps.
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
    Map(genesis -> ParamUpdate(Map(0 -> uint(44), 14 -> arr(uint(8), uint(0))))),
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
      referenceInputs = Vector(in(3)),
      scriptDataHash = Some(hash(12, 32)),
      auxDataHash = Some(hash(13, 32)),
      update = Some(proposal),
      collateralReturn = Some(out),
      totalCollateral = Some(5)
    )

  private def keyHash[R <: KeyRole](b: Bytes): KeyHash[R] =
    KeyHash.fromBytes[R](b).fold(e => fail(e.toString), identity)

  private def ix(i: Int): Unsigned[Unsigned.Width.W32] =
    Unsigned.from[Unsigned.Width.W32](BigInt(i)).getOrElse(fail(s"$i"))

  test("a body of every field Babbage has reads alike both ways, and the era's classes read it") {
    val built = encode(everyField)
    val tx = built.tx
    val b = EraTxBody.babbage
    val body = EraTx.babbage.body[TxLevel.Top](tx)(using b.top)
    val certs = b.certs[TxLevel.Top](body)(using b.top)
    // the certificates in the order read, the repeat kept
    assertEquals(certs.size, 9)
    val c = EraTxCert.babbage
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
    // the inputs and outputs of the classes
    assertEquals(b.allInputs(body), SortedSet(in(0), in(1), in(2), in(3)))
    assertEquals(b.spendableInputs[TxLevel.Top](body)(using b.top), SortedSet(in(0), in(1), in(2)))
    assertEquals(b.allSizedOutputs[TxLevel.Top](body)(using b.top).size, 2)
    assertEquals(b.reqSignerHashes[TxLevel.Top](body)(using b.top), SortedSet(signer, owner))
    assertEquals(b.fee(body), Coin(200000))
    // a redeemer's pointer names an item of the body, or nothing
    def inverse(p: AlonzoPlutusPurpose[AsIx, Babbage]) =
      b.redeemerPointerInverse[TxLevel.Top](body, p)(using b.top)
    assertEquals(
      inverse(AlonzoPlutusPurpose.AlonzoCertifying(AsIx(ix(6)))),
      Some(AlonzoPlutusPurpose.AlonzoCertifying[AsIxItem, Babbage](AsIxItem(ix(6), certs(6))))
    )
    assertEquals(
      inverse(AlonzoPlutusPurpose.AlonzoMinting(AsIx(ix(0)))),
      Some(AlonzoPlutusPurpose.AlonzoMinting[AsIxItem, Babbage](AsIxItem(ix(0), policy)))
    )
    assertEquals(inverse(AlonzoPlutusPurpose.AlonzoSpending(AsIx(ix(2)))), None)
    assertEquals(EraTx.babbage.isValid(tx), IsPhase2Valid.Phase2Valid)
    assertEquals(EraTx.babbage.sizeTxF[TxLevel.Top](tx)(using b.top), built.flat.size)
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
      FlatTxBody.decode(raw, Era.Babbage).left.toOption,
      BabbageTxBody.decode(raw, StateLayout.Node11_1).left.toOption
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
    // a registration with a deposit, tag 7, which Babbage's decoder does not read
    val conway = refusals(
      bodyOf(
        base :+ (4 -> arr(arr(uint(7), ConwayTestKit.credential(Credential.Key(owner)), uint(2))))
      )
    )
    assert(conway._1.isDefined)
    assertEquals(conway._2, conway._1.map(EraTxError.Decode(_)))
  }

  private def aux(raw: Bytes): (Option[LedgerDecodeError], Either[EraTxError, BabbageTxAuxData]) =
    given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Babbage))
    (AuxiliaryScripts.read(raw, Era.Babbage, 7).left.toOption, BabbageTxAuxData.decode(raw))

  test("auxiliary data's Plutus keys: the languages Babbage has read, the later ones refused") {
    val (flat, era) =
      aux(tag(259, map(uint(3) -> arr(bytes(hash(1, 4))), uint(2) -> arr(bytes(hash(2, 4))))))
    assertEquals(flat, None)
    val read = era.fold(e => fail(e.toString), identity)
    // the scripts in the order their keys are written, and by language
    assertEquals(
      read.plutusArrival,
      Vector(PlutusLanguage.V2 -> hash(1, 4), PlutusLanguage.V1 -> hash(2, 4))
    )
    assertEquals(read.plutusScripts.keySet, Set(PlutusLanguage.V1, PlutusLanguage.V2))
    // PlutusV3's key, refused below version 9 both ways
    val v3 = aux(tag(259, map(uint(4) -> arr())))
    assertEquals(v3._2.left.toOption, v3._1.map(EraTxError.Decode(_)))
    // PlutusV4's key: the reference knows it and refuses the language below version 12
    // (`auxDataField 5`, `guardPlutus`), where the flat reader refuses the key as unknown
    val v4 = aux(tag(259, map(uint(5) -> arr())))
    assertEquals(v4._1, Some(LedgerDecodeError.UnknownTag(DecodeField.AuxiliaryField, 5)))
    assertEquals(
      v4._2.left.toOption,
      Some(
        EraTxError.Decode(
          LedgerDecodeError.NumberOutOfRange(DecodeField.AuxiliaryScriptProtocol, 7, 12, 12)
        )
      )
    )
  }

  test("a witness set's PlutusV3 scripts are refused, an empty list of them read, both ways") {
    def wits(raw: Bytes) =
      (
        FlatWitnesses.decode(raw, Era.Babbage).left.toOption,
        BabbageTxWits.decode(raw).left.toOption
      )
    val held = wits(map(uint(7) -> arr(bytes(hash(1, 4)))))
    assertEquals(
      held._1,
      Some(LedgerDecodeError.UnsupportedLanguage(ScriptKind.PlutusV3, ScriptKind.PlutusV2))
    )
    assertEquals(held._2, held._1.map(EraTxError.Decode(_)))
    assertEquals(wits(map(uint(7) -> arr())), (None, None))
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
        AlonzoPlutusPurpose.AlonzoSpending[AsIx, Babbage](AsIx(ix(1))) -> ExUnits(2, 2),
        AlonzoPlutusPurpose.AlonzoCertifying[AsIx, Babbage](AsIx(ix(0))) -> ExUnits(3, 3)
      )
    )
  }
