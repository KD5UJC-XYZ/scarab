// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.syntax.all.*
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import xyz.kd5ujc.scarab.ledger.Utxo

/** The era witness and the match types: the right era's type compiles, any other does not. */
class ErasSuite extends FunSuite:
  import ShelleyBasedEra.*

  /** Era-generic: the witness refines `E`, so `CertState[E]` reduces in each branch. */
  private def certName[E <: EraTag](era: ShelleyBasedEra[E], cert: CertState[E]): String = era match
    case SConway =>
      val c: ConwayCertState = cert
      s"conway ${c.pState.stakePools.size}"
    case SShelley =>
      val c: ShelleyCertState = cert
      s"shelley ${c.pState.stakePools.size}"
    case SAllegra | SMary | SAlonzo | SBabbage =>
      val c: ShelleyCertState = cert
      s"shelley-based ${c.dState.genDelegs.size}"

  private def isConway[E <: EraTag](using era: ShelleyBasedEra[E]): Boolean = era match
    case SConway => true
    case _       => false

  test("the witness is given for every era") {
    assertEquals(ShelleyBasedEra[Shelley], SShelley)
    assertEquals(ShelleyBasedEra[Allegra], SAllegra)
    assertEquals(ShelleyBasedEra[Mary], SMary)
    assertEquals(ShelleyBasedEra[Alonzo], SAlonzo)
    assertEquals(ShelleyBasedEra[Babbage], SBabbage)
    assertEquals(ShelleyBasedEra[Conway], SConway)
    assertEquals(ShelleyBasedEra.values.length, 6)
  }

  test("a using witness drives era-generic code") {
    assert(isConway[Conway])
    assert(!isConway[Babbage])
    assertEquals(certName(SConway, LedgerStateGen.conwayCert), "conway 0")
    assertEquals(certName(SMary, LedgerStateGen.shelleyCert), "shelley-based 0")
  }

  test("the match types split the eras as the Haskell type families do") {
    val conway: CertState[Conway] = LedgerStateGen.conwayCert
    val babbage: CertState[Babbage] = LedgerStateGen.shelleyCert
    val accounts: Accounts[Conway] = LedgerStateGen.conwayAccounts
    val stake: InstantStake[Babbage] = LedgerStateGen.shelleyInstant
    val gov: GovState[Conway] = LedgerStateGen.conwayGov
    val avvm: StashedAVVM[Shelley] = UtxoTables(Utxo.empty)
    val none: StashedAVVM[Allegra] = ()
    assertEquals(conway, LedgerStateGen.conwayCert)
    assertEquals(babbage, LedgerStateGen.shelleyCert)
    assertEquals(accounts, LedgerStateGen.conwayAccounts)
    assertEquals(stake, LedgerStateGen.shelleyInstant)
    assertEquals(gov, LedgerStateGen.conwayGov)
    assertEquals(avvm, UtxoTables(Utxo.empty))
    assertEquals(none, ())
  }

  test("wrong-era access does not compile") {
    assert(typeCheckErrors("val c: CertState[Conway] = LedgerStateGen.shelleyCert").nonEmpty)
    assert(typeCheckErrors("val c: CertState[Babbage] = LedgerStateGen.conwayCert").nonEmpty)
    assert(typeCheckErrors("val a: Accounts[Conway] = LedgerStateGen.shelleyAccounts").nonEmpty)
    assert(typeCheckErrors("val s: StashedAVVM[Shelley] = ()").nonEmpty)
    // in a branch of the witness, only that branch's era
    assert(
      typeCheckErrors(
        "def f[E <: EraTag](era: ShelleyBasedEra[E], c: CertState[E]): ConwayCertState = era match { case ShelleyBasedEra.SShelley => c; case _ => LedgerStateGen.conwayCert }"
      ).nonEmpty
    )
    // without a witness, an abstract era reduces nothing
    assert(typeCheckErrors("def g[E <: EraTag](c: CertState[E]): ConwayCertState = c").nonEmpty)
    // not an era
    assert(typeCheckErrors("val w = ShelleyBasedEra[String]").nonEmpty)
    assert(typeCheckErrors("val c: CertState[String] = LedgerStateGen.conwayCert").nonEmpty)
  }

  test("right-era access compiles") {
    assertEquals(typeCheckErrors("val c: CertState[Conway] = LedgerStateGen.conwayCert"), Nil)
    assertEquals(typeCheckErrors("val c: CertState[Alonzo] = LedgerStateGen.shelleyCert"), Nil)
    assertEquals(
      typeCheckErrors(
        "def f[E <: EraTag](era: ShelleyBasedEra[E], c: CertState[E]): ConwayCertState = era match { case ShelleyBasedEra.SConway => c; case _ => LedgerStateGen.conwayCert }"
      ),
      Nil
    )
  }
