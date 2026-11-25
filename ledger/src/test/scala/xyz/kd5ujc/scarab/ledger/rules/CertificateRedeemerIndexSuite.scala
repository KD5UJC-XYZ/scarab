// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.data.NonEmptyVector
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Babbage, Conway}

/** The scripts a transaction needs and the redeemers its UTXOW asks for, through each era's LEDGER
  * (cardano-ledger f649f975). Before Conway a certificate that occurs again takes the redeemer
  * index of its first occurrence (`getAlonzoScriptsNeeded`, `Alonzo/UTxO.hs:318-361`, which Babbage
  * uses, `Babbage/UTxO.hs:52`); from Conway each certificate has its own position
  * (`getConwayScriptsNeeded`, `Conway/UTxO.hs:62-80`). UTXOW asks for exactly the redeemers of
  * those purposes (`hasExactSetOfRedeemers`, `Alonzo/Rules/Utxow.hs:239-262`), and lists the
  * missing ones in the order of the scripts needed: spending, withdrawing, certifying, minting,
  * voting, proposing. Conway's pool judges by its era rules: its distinct certificates and its
  * missing redeemers of every purpose are held to the reference node's answers through them
  * (`RedeemerIndexReferenceSuite`).
  */
class CertificateRedeemerIndexSuite extends FunSuite:
  import RedeemerIndexCases.*

  private def extra(index: Int): PredicateFailure =
    PredicateFailure.utxow(UtxowFailure.ExtraRedeemers(Vector((2, BigInt(index)))))

  private def collect(purposes: Vector[Purpose]): PredicateFailure =
    PredicateFailure.utxo(
      UtxoFailure.Utxos(
        UtxosFailure.CollectErrors(
          NonEmptyVector
            .fromVector(purposes.map(CollectError.NoRedeemer(_)))
            .getOrElse(fail("no purpose"))
        )
      )
    )

  // ---- a certificate that occurs twice

  private val first = Purpose.Certifying(0, delegation)

  test("Alonzo: the repeated certificate takes its first redeemer index (Alonzo/UTxO.hs:351-361)") {
    // each certificate is a purpose, both at index 0: the script runs twice, on the one redeemer
    assertEquals(verdict[Alonzo](6)(twice[Alonzo](_, Vector(0))), Right(2L))
    assertEquals(verdict[Alonzo](6)(twice[Alonzo](_, Vector(0, 1))), Left(Vector(extra(1))))
  }

  test("Babbage: the repeated certificate takes its first redeemer index (Babbage/UTxO.hs:52)") {
    assertEquals(verdict[Babbage](8)(twice[Babbage](_, Vector(0))), Right(2L))
    assertEquals(verdict[Babbage](8)(twice[Babbage](_, Vector(0, 1))), Left(Vector(extra(1))))
  }

  test("Conway: a body cannot repeat a certificate, so each certificate has its own index") {
    // `getConwayScriptsNeeded` indexes the certificates by their position in the body's ordered set
    // (`zipAsIxItem`, `Conway/UTxO.hs:78` (f649f975)), which holds no repeat: `decodeOSet`
    // (`Data/OSet/Strict.hs:109-110` (f649f975)) refuses a body that repeats one, and Conway's era
    // types cannot hold one (`OSet`). The body Alonzo and Babbage index the repeat of by its first
    // occurrence is no Conway body. A body `{0: [input], 1: [], 2: 0, 4: [c, c']}` with the stake
    // registrations `c` and `c'` (`[0, [0, key hash]]`):
    def body(certificates: String*): Bytes =
      Bytes
        .fromHex(
          "a4" + "00" + "81" + "825820" + "11" * 32 + "00" + "01" + "80" + "02" + "00" + "04" +
            "82" + certificates.mkString
        )
        .fold(e => fail(e.message), identity)
    def registration(key: Int): String = "8200" + "8200" + "581c" + f"$key%02x" * 28
    def decoded(raw: Bytes) =
      xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxBody
        .decode(raw, xyz.kd5ujc.scarab.protocol.StateLayout.Node11_1)
    assertEquals(
      decoded(body(registration(0x22), registration(0x22))).map(_ => ()),
      Left(
        xyz.kd5ujc.scarab.ledger.era.EraTxError
          .Decode(LedgerDecodeError.DuplicateElement(DecodeField.Certificates))
      )
    )
    // two distinct certificates are read, each at its own position; their redeemers are held to
    // the reference node's answers through Conway's era rules (`RedeemerIndexReferenceSuite`)
    assertEquals(
      decoded(body(registration(0x22), registration(0x33)))
        .map(_.certs.size),
      Right(2)
    )
  }

  test("Alonzo and Babbage give different certificates their own redeemer indices") {
    // only a certificate equal to an earlier one takes that one's index (`Map.lookup txCert`)
    val other = Purpose.Certifying(1, otherDelegation)
    val missing =
      PredicateFailure.utxow(UtxowFailure.MissingRedeemers(Vector(other -> script.hash)))
    val collected = collect(Vector(other))
    assertEquals(
      verdict[Alonzo](6)(distinct[Alonzo](_, Vector(0))),
      Left(Vector(missing, collected))
    )
    assertEquals(verdict[Alonzo](6)(distinct[Alonzo](_, Vector(0, 1))), Right(2L))
    assertEquals(
      verdict[Babbage](8)(distinct[Babbage](_, Vector(0))),
      Left(Vector(missing, collected))
    )
    assertEquals(verdict[Babbage](8)(distinct[Babbage](_, Vector(0, 1))), Right(2L))
  }

  test("Alonzo and Babbage list a repeated certificate's missing redeemer once for each") {
    // `extSymmetricDifference` keeps every entry of the scripts needed (cardano-ledger f649f975
    // `Alonzo/Rules/Utxow.hs:378-383`)
    val missing = PredicateFailure.utxow(
      UtxowFailure.MissingRedeemers(Vector(first -> script.hash, first -> script.hash))
    )
    val collected = collect(Vector(first, first))
    assertEquals(
      verdict[Alonzo](6)(twice[Alonzo](_, Vector.empty)),
      Left(Vector(missing, collected))
    )
    assertEquals(
      verdict[Babbage](8)(twice[Babbage](_, Vector.empty)),
      Left(Vector(missing, collected))
    )
  }

  // ---- the order of the missing redeemers

  /** `MissingRedeemers` in the order of the scripts needed, and UTXOS's collection errors, which
    * list them in reverse (`merge`, `Alonzo/Plutus/Evaluate.hs:141-185`, cardano-ledger f649f975).
    */
  private val missing = PredicateFailure.utxow(
    UtxowFailure.MissingRedeemers(purposes.map(_ -> script.hash))
  )
  private val collected = collect(purposes.reverse)

  test("Alonzo lists missing redeemers as spending, withdrawing, certifying, minting") {
    assertEquals(verdict[Alonzo](6)(unredeemed[Alonzo]), Left(Vector(missing, collected)))
  }

  test("Babbage lists missing redeemers as spending, withdrawing, certifying, minting") {
    assertEquals(verdict[Babbage](8)(unredeemed[Babbage]), Left(Vector(missing, collected)))
  }

  test("Conway lists missing voting and proposing redeemers last (Conway/UTxO.hs:67-73)") {
    // UTXOW's check alone: a transaction with these votes and proposals needs a governance state
    // that LEDGER's other rules would refuse it for first
    val voter = Voter.DRepVoter(credential)
    val proposal =
      Proposal(
        0,
        account,
        GovAction.TreasuryWithdrawals(Vector(account -> BigInt(1)), Some(script.hash))
      )
    val body = TestLedger.body(
      "missing-governance-redeemers",
      Vector(funding, locked),
      Vector.empty,
      fee,
      certificates = Vector(delegation),
      withdrawals = Map(account -> BigInt(0)),
      mint = Map(script.hash -> Map(Bytes(Vector(0x41.toByte)) -> BigInt(1))),
      votes =
        Vector(voter -> Vector(GovActionId(Bytes(Vector.fill(32)(0x07.toByte)), 0) -> Vote.Yes)),
      proposals = Vector(proposal)
    )
    val tx = TestLedger.tx(body, Vector(alice), Witnesses(Vector.empty, scripts = Vector(script)))
    val spent = Map(locked -> lockedOut)
    val needed = TxChecks.scriptsNeeded[Conway](body, spent)
    val governance = Vector(Purpose.Voting(0, voter), Purpose.Proposing(0, proposal))
    assertEquals(
      TestLedger.verdict(body.id, TxChecks.redeemerChecks(tx, Map(script.hash -> script), needed)),
      Left(
        LedgerFailure.Rejected(
          body.id,
          PredicateFailure.utxow(
            UtxowFailure.MissingRedeemers((purposes ++ governance).map(_ -> script.hash))
          )
        )
      )
    )
  }
