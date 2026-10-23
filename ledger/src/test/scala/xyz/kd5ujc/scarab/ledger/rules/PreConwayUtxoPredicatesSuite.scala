// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.byron.{ByronAddress, ByronCbor}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.rules.Utxo as ConwayUtxo
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.plutus.ExUnits

/** Alonzo/Rules/Utxo.hs:551-571 and Babbage/Rules/Utxo.hs:392-412 at f649f975: bootstrap
  * attributes, output networks and collateral count are phase-1 predicates, independent of
  * redeemers and the phase-2 validity flag. These tests call the production UTXO validation.
  */
class PreConwayUtxoPredicatesSuite extends FunSuite:
  private val alice = TestLedger.key()
  private val funds = BigInt(10000000)
  private val fee = BigInt(200)
  private val input = TestLedger.txIn(0)
  private val collateral = Vector.tabulate(4)(n => TestLedger.txIn(n + 1))
  private val utxo = Utxo.fromMap(
    (input +: collateral).map(_ -> TestLedger.out(alice.address(), funds)).toMap
  )

  private def bootstrap(size: Int, magic: Option[Long], derivation: Boolean = false): Address =
    val payload = Bytes(Vector.fill(size)(2.toByte))
    val attribute = if derivation then ByronCbor.bytes(payload) else payload
    val fields = Vector(
      ByronCbor.uint(if derivation then 1 else 5) ++ ByronCbor.bytes(attribute)
    ) ++ magic.toVector.map(m => ByronCbor.uint(2) ++ ByronCbor.bytes(ByronCbor.uint(m)))
    val attributes = ByronCbor.of(0xa0 + fields.size) ++ fields.foldLeft(Bytes.empty)(_ ++ _)
    val key = Bytes(Vector.fill(64)(1.toByte))
    val body = ByronCbor.concat(
      ByronCbor.of(0x83),
      ByronCbor.bytes(ByronAddress.rootOf(ByronAddress.VerKey, key, attributes)),
      attributes,
      ByronCbor.uint(ByronAddress.VerKey)
    )
    val raw = ByronCbor.concat(
      ByronCbor.of(0x82, 0xd8, 0x18),
      ByronCbor.bytes(body),
      ByronCbor.uint(ByronCbor.crc32(body))
    )
    Address.decode(raw).fold(e => fail(e.toString), identity)

  private def transaction(
      outputs: Vector[TxOut],
      coll: Vector[TxIn] = Vector.empty,
      returned: Option[TxOut] = None,
      isValid: Boolean = true
  ): Tx =
    TestLedger.tx(
      TestLedger.body(
        "pre-conway-utxo",
        Vector(input),
        outputs,
        fee,
        collateral = coll,
        collateralReturn = returned
      ),
      Vector(alice),
      isValid = isValid
    )

  private def cases[E <: EraTag](name: String, major: Int)(using
      era: ShelleyBasedEra[E],
      pre: PreConway[E]
  ): Unit =
    val pp = TestLedger.params(major)
    val cert = pre.cert.get(Empty.ledgerState[E](pp))
    def validate(tx: Tx, network: Int = 0) =
      ShelleyUtxo.validate[E](
        UtxoEnv(TestLedger.env.copy(network = network), pp, cert),
        StAnnTx(tx, utxo),
        0,
        0
      )
    def rejected(tx: Tx, failure: UtxoFailure, network: Int = 0): Unit =
      assertEquals(
        validate(tx, network),
        Left(LedgerFailure.Rejected(tx.body.id, PredicateFailure.utxo(failure)))
      )
    def paying(address: Address, isValid: Boolean) =
      transaction(Vector(TestLedger.out(address, funds - fee)), isValid = isValid)

    test(s"$name: collateral count is checked without redeemers under either validity flag") {
      for valid <- Vector(true, false) do
        val tx = transaction(
          Vector(TestLedger.out(alice.address(), funds - fee)),
          collateral,
          isValid = valid
        )
        assert(tx.witnesses.redeemers.isEmpty)
        rejected(tx, UtxoFailure.TooManyCollateralInputs(Mismatch(4, 3)))
        assertEquals(
          validate(tx.copy(body = tx.body.copy(collateral = collateral.take(3)))),
          Right(TxChecks.Collateral.none)
        )
    }

    test(s"$name: semantic collateral inputs count and pay once, preserving original bytes") {
      val rows =
        parse(Files.readString(Path.of("fixtures/reference/pre-conway-utxo/predicates.json")))
          .fold(e => fail(e.message), identity)
          .asArray
          .getOrElse(fail("reference array"))
      val row = rows
        .find(_.hcursor.get[String]("name").contains(s"$major-semantic-duplicate-collateral"))
        .getOrElse(fail("semantic collateral fixture"))
      def read[A: io.circe.Decoder](field: String): A =
        row.hcursor.get[A](field).fold(e => fail(e.message), identity)
      val raw = Bytes.fromHex(read[String]("body")).fold(e => fail(e.toString), identity)
      val body = TxBody
        .decode(raw, if major == 5 then Era.Alonzo else Era.Babbage)
        .fold(e => fail(e.toString), identity)
      assertEquals(body.collateral.size, 4)
      assertEquals(body.collateral.toSet.size, read[Int]("collateralCount"))
      val out = body.outputs.head.copy(address = alice.baseAddress(alice))
      val inputs = Utxo.fromMap(body.collateral.map(_ -> out).toMap)
      val witnesses =
        Witnesses.empty.copy(redeemers = Vector(Redeemer(0, 0, Bytes.empty, ExUnits.zero)))
      val tx = Tx(body, witnesses, 100, isValid = false, auxiliary = None)
      val checked = TxChecks
        .collateralChecks(inputs, pp, tx)
        .fold(e => fail(e.toString), identity)
      assertEquals(checked.outputs, Vector(out))
      assertEquals(checked.fee, read[BigInt]("collateralCoin"))
      val empty = Empty.ledgerState[E](pp).utxo
      val stake = ShelleyInstantStakeOps
        .update(pre.instantStake.get(empty), Vector(out), Vector.empty)
        .fold(e => fail(e.toString), identity)
      val initial = pre.instantStake.replace(stake)(empty.copy[E](utxo = UtxoTables(inputs)))
      val next = ShelleyUtxo
        .update[E](initial, StAnnTx(tx, inputs), checked, 0, 0)
        .fold(e => fail(e.toString), identity)
      assertEquals(next.utxo.utxo.size, 0)
      assertEquals(next.fees.value, checked.fee)
      assertEquals(pre.instantStake.get(next), pre.instantStake.get(empty))
      assertEquals(body.raw, raw)
    }

    test(s"$name: bootstrap attribute payloads allow 64 bytes and refuse 65") {
      for valid <- Vector(true, false); derivation <- Vector(false, true) do
        val allowed = paying(bootstrap(64, Some(42), derivation), valid)
        assert(validate(allowed).isRight)
        val tx = paying(bootstrap(65, Some(42), derivation), valid)
        val original = tx.body.raw
        rejected(tx, UtxoFailure.OutputBootAddrAttrsTooBig(tx.body.outputs))
        assertEquals(tx.body.raw, original)
    }

    test(s"$name: bootstrap network is mainnet without magic and testnet with any magic") {
      for valid <- Vector(true, false) do
        for magic <- Vector(1L, 42L) do
          val tx = paying(bootstrap(0, Some(magic)), valid)
          assert(validate(tx).isRight)
          rejected(tx, UtxoFailure.WrongNetwork(1, Set(tx.body.outputs.head.address.raw)), 1)
        val tx = paying(bootstrap(0, None), valid)
        assert(validate(tx, 1).isRight)
        rejected(tx, UtxoFailure.WrongNetwork(0, Set(tx.body.outputs.head.address.raw)))
    }

    test(s"$name: bootstrap attributes precede the combined output-network failure") {
      val big = TestLedger.out(bootstrap(65, None), funds / 2)
      val wrong = TestLedger.out(alice.address(1), funds / 2 - fee)
      rejected(transaction(Vector(big, wrong)), UtxoFailure.OutputBootAddrAttrsTooBig(Vector(big)))
      val byron = big.copy(address = bootstrap(64, None))
      val tx = transaction(Vector(byron, wrong))
      rejected(tx, UtxoFailure.WrongNetwork(0, Set(byron.address.raw, wrong.address.raw)))
    }

    if major >= 7 then
      test(s"$name: collateral return participates in bootstrap attribute and network checks") {
        val ordinary = Vector(TestLedger.out(alice.address(), funds - fee))
        for valid <- Vector(true, false) do
          val big = TestLedger.out(bootstrap(65, Some(42)), funds)
          rejected(
            transaction(ordinary, returned = Some(big), isValid = valid),
            UtxoFailure.OutputBootAddrAttrsTooBig(Vector(big))
          )
          val wrong = TestLedger.out(bootstrap(64, None), funds)
          rejected(
            transaction(ordinary, returned = Some(wrong), isValid = valid),
            UtxoFailure.WrongNetwork(0, Set(wrong.address.raw))
          )
      }

  private def earlyCases[E <: EraTag](name: String, major: Int)(using
      era: ShelleyBasedEra[E],
      pre: PreConway[E]
  ): Unit =
    val pp = TestLedger.params(major)
    val initial = Empty.ledgerState[E](pp)
    val funded = initial.copy[E](utxo = initial.utxo.copy[E](utxo = UtxoTables(utxo)))
    def admit(tx: Tx, network: Int = 0) = Sts(ShelleyLedger.sts[E])(
      TestLedger.globals.copy(ledger = TestLedger.globals.ledger.copy(network = network)),
      ShelleyLedgerEnv(
        TestLedger.env.copy(network = network),
        ChainAccountState(Coin.zero, Coin.zero)
      ),
      Counted(funded, PlutusStats.zero),
      tx
    )
    def rejected(tx: Tx, expected: UtxoFailure, network: Int = 0): Unit =
      assertEquals(
        admit(tx, network),
        Left(LedgerFailure.Rejected(tx.body.id, PredicateFailure.utxo(expected)))
      )

    test(s"$name: signed LEDGER admission allows 64 bootstrap attribute bytes and refuses 65") {
      for derivation <- Vector(false, true) do
        val allowed =
          transaction(Vector(TestLedger.out(bootstrap(64, Some(42), derivation), funds - fee)))
        assert(admit(allowed).isRight)
        val tx =
          transaction(Vector(TestLedger.out(bootstrap(65, Some(42), derivation), funds - fee)))
        val original = tx.body.raw
        rejected(tx, UtxoFailure.OutputBootAddrAttrsTooBig(tx.body.outputs))
        assertEquals(tx.body.raw, original)
        assertEquals(funded.utxo.utxo.utxo, utxo)
    }

    test(s"$name: bootstrap network precedes value conservation and attribute-size failures") {
      val out = TestLedger.out(bootstrap(65, None), funds - fee - 1)
      val tx = transaction(Vector(out))
      rejected(tx, UtxoFailure.WrongNetwork(0, Set(out.address.raw)))
      // Once network agrees, value conservation still precedes attributes.
      rejected(
        tx,
        UtxoFailure.ValueNotConserved(Mismatch(Value.coin(funds), Value.coin(funds - 1))),
        1
      )
      val balanced = transaction(Vector(out.copy(value = Value.coin(funds - fee))))
      rejected(balanced, UtxoFailure.OutputBootAddrAttrsTooBig(balanced.body.outputs), 1)
      val allowed = transaction(Vector(TestLedger.out(bootstrap(64, None), funds - fee)))
      assert(admit(allowed, 1).isRight)
    }

    test(s"$name: minimum output precedes bootstrap attribute size") {
      val big = TestLedger.out(bootstrap(65, Some(42)), 1)
      val change = TestLedger.out(alice.address(), funds - fee - 1)
      rejected(transaction(Vector(big, change)), UtxoFailure.OutputTooSmall(Vector(big)))
    }

  earlyCases[Shelley]("Shelley", 2)
  earlyCases[Allegra]("Allegra", 3)
  earlyCases[Mary]("Mary", 4)

  cases[Alonzo]("Alonzo", 5)(using ShelleyBasedEra.SAlonzo, summon[PreConway[Alonzo]])
  cases[Babbage]("Babbage", 7)(using ShelleyBasedEra.SBabbage, summon[PreConway[Babbage]])

  test("Conway collateral predicates and failed-phase2 accounting share decoded set semantics") {
    val rows =
      parse(Files.readString(Path.of("fixtures/reference/pre-conway-utxo/predicates.json")))
        .fold(e => fail(e.message), identity)
        .asArray
        .getOrElse(fail("reference array"))
    val row = rows
      .find(_.hcursor.get[String]("name").contains("5-semantic-duplicate-collateral"))
      .getOrElse(fail("semantic collateral fixture"))
    def read[A: io.circe.Decoder](field: String): A =
      row.hcursor.get[A](field).fold(e => fail(e.message), identity)
    assertEquals(row.hcursor.downField("conwayCollateral").get[Boolean]("accepted"), Right(true))
    val raw = Bytes.fromHex(read[String]("body")).fold(e => fail(e.toString), identity)
    // The reference Conway API takes the same decoded Set, not these pre-Conway wire bytes.
    val body = TxBody.decode(raw, Era.Alonzo).fold(e => fail(e.toString), identity)
    val pp = TestLedger.params(9)
    val empty = Empty.ledgerState[Conway](pp)
    val out = body.outputs.head.copy(address = alice.baseAddress(alice))
    val coll = body.collateral.map(_ -> out).toMap
    val inputs = Utxo.fromMap(
      coll + (body.inputs.head -> out.copy(value = Value.coin(out.value.coin + body.fee)))
    )
    for valid <- Vector(true, false); redeemers <- Vector(false, true) do
      val witnesses =
        if redeemers then
          Witnesses.empty.copy(redeemers = Vector(Redeemer(0, 0, Bytes.empty, ExUnits.zero)))
        else Witnesses.empty
      val tx = Tx(body, witnesses, 100, isValid = valid, auxiliary = None)
      val checked = ConwayUtxo
        .validate(UtxoEnv(TestLedger.env, pp, empty.cert), StAnnTx(tx, inputs), 0, 0)
        .fold(e => fail(e.toString), identity)
      assertEquals(checked.fee, if redeemers then read[BigInt]("collateralCoin") else BigInt(0))
      if !valid && redeemers then
        val stake = InstantStakeOps
          .update(empty.utxo.instantStake, Vector(out), Vector.empty)
          .fold(e => fail(e.toString), identity)
        val initial =
          empty.utxo.copy[Conway](utxo = UtxoTables(Utxo.fromMap(coll)), instantStake = stake)
        val next = ConwayUtxo
          .update(initial, StAnnTx(tx, inputs), checked, 0, 0)
          .fold(e => fail(e.toString), identity)
        assertEquals(next.utxo.utxo.size, 0)
        assertEquals(next.fees.value, read[BigInt]("collateralCoin"))
        assertEquals(next.instantStake, empty.utxo.instantStake)
      assertEquals(body.raw, raw)
  }

  test("the original bodies match the executed Haskell predicates in both eras") {
    val rows =
      parse(Files.readString(Path.of("fixtures/reference/pre-conway-utxo/predicates.json")))
        .fold(e => fail(e.message), identity)
        .asArray
        .getOrElse(fail("reference array"))
    assertEquals(rows.size, 32)
    rows.foreach { row =>
      val c = row.hcursor
      def read[A: io.circe.Decoder](field: String): A =
        c.get[A](field).fold(e => fail(e.message), identity)
      def passed(field: String) = c
        .downField(field)
        .get[Boolean]("accepted")
        .fold(e => fail(e.message), identity)
      val major = read[Int]("protocolMajor")
      val raw = Bytes.fromHex(read[String]("body")).fold(e => fail(e.toString), identity)
      val body = TxBody
        .decode(raw, if major == 5 then Era.Alonzo else Era.Babbage)
        .fold(e => fail(e.toString), identity)
      val tx = Tx(body, Witnesses.empty, 100, isValid = true, auxiliary = None)
      val outputs = body.outputs ++ body.collateralReturn.toVector
      val bootstrap = outputs.flatMap(out =>
        out.address.kind match
          case Address.Kind.Byron(_) =>
            ByronAddress
              .decode(out.address.raw)
              .fold(e => fail(e.toString), address => Some(out -> address))
          case _ => None
      )
      val network = read[Int]("network")
      val wrong = outputs
        .filter(_.address.kind match
          case Address.Kind.Shelley(n, _, _, _) => n != network
          case _                                => false)
        .map(_.address.raw)
        .toSet ++
        bootstrap.collect { case (out, address) if address.network != network => out.address.raw }
      val expected =
        if !passed("attributes") then
          Some(UtxoFailure.OutputBootAddrAttrsTooBig(bootstrap.collect {
            case (out, address) if address.attributesSize > 64 => out
          }))
        else if !passed("addresses") then Some(UtxoFailure.WrongNetwork(network, wrong))
        else if !passed("collateral") then
          Some(UtxoFailure.TooManyCollateralInputs(Mismatch(body.collateral.toSet.size, 3)))
        else None
      assertEquals(body.inputs.size, 1)
      val spent = body.outputs.map(_.value.coin).sum + body.fee
      val inputs = Utxo.fromMap(
        body.collateral.map(_ -> TestLedger.out(alice.address(), funds)).toMap +
          (body.inputs.head -> TestLedger.out(alice.address(), spent))
      )
      def validate[E <: EraTag](using era: ShelleyBasedEra[E], pre: PreConway[E]) =
        val pp = TestLedger.params(major)
        ShelleyUtxo.validate[E](
          UtxoEnv(
            TestLedger.env.copy(network = network),
            pp,
            pre.cert.get(Empty.ledgerState[E](pp))
          ),
          StAnnTx(tx, inputs),
          0,
          0
        )
      val actual =
        if major == 5 then
          validate[Alonzo](using ShelleyBasedEra.SAlonzo, summon[PreConway[Alonzo]])
        else validate[Babbage](using ShelleyBasedEra.SBabbage, summon[PreConway[Babbage]])
      assertEquals(
        actual,
        expected.fold[Either[LedgerFailure, TxChecks.Collateral]](Right(TxChecks.Collateral.none))(
          f => Left(LedgerFailure.Rejected(body.id, PredicateFailure.utxo(f)))
        ),
        read[String]("name")
      )
      assertEquals(body.raw, raw)
    }
  }
