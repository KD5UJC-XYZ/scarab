// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.syntax.all.*
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** The era witness and the match types: the right era's type compiles, any other does not. */
class ErasSuite extends FunSuite:
  import ShelleyBasedEra.*

  /** Era-generic: the witness refines `E`, so `CertState[E]` reduces in each branch. */
  private def certName[E <: EraTag](era: ShelleyBasedEra[E], cert: CertState[E]): String = era match
    case SConway =>
      val c: ConwayCertState = cert
      s"conway ${c.show}"
    case SShelley =>
      val c: ShelleyCertState = cert
      s"shelley ${c.show}"
    case SAllegra | SMary | SAlonzo | SBabbage =>
      val c: ShelleyCertState = cert
      s"shelley-based ${c.show}"

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
    assertEquals(certName(SConway, ConwayCertState()), "conway ConwayCertState")
    assertEquals(certName(SMary, ShelleyCertState()), "shelley-based ShelleyCertState")
  }

  test("the match types split the eras as the Haskell type families do") {
    val conway: CertState[Conway] = ConwayCertState()
    val babbage: CertState[Babbage] = ShelleyCertState()
    val accounts: Accounts[Conway] = ConwayAccounts()
    val stake: InstantStake[Babbage] = ShelleyInstantStake()
    val gov: GovState[Conway] = ConwayGovState()
    val avvm: StashedAVVM[Shelley] = StashedUtxo()
    val none: StashedAVVM[Allegra] = ()
    assertEquals(conway, ConwayCertState())
    assertEquals(babbage, ShelleyCertState())
    assertEquals(accounts, ConwayAccounts())
    assertEquals(stake, ShelleyInstantStake())
    assertEquals(gov, ConwayGovState())
    assertEquals(avvm, StashedUtxo())
    assertEquals(none, ())
  }

  test("wrong-era access does not compile") {
    assert(typeCheckErrors("val c: CertState[Conway] = ShelleyCertState()").nonEmpty)
    assert(typeCheckErrors("val c: CertState[Babbage] = ConwayCertState()").nonEmpty)
    assert(typeCheckErrors("val a: Accounts[Conway] = ShelleyAccounts()").nonEmpty)
    assert(typeCheckErrors("val s: StashedAVVM[Shelley] = ()").nonEmpty)
    // in a branch of the witness, only that branch's era
    assert(
      typeCheckErrors(
        "def f[E <: EraTag](era: ShelleyBasedEra[E], c: CertState[E]): ConwayCertState = era match { case ShelleyBasedEra.SShelley => c; case _ => ConwayCertState() }"
      ).nonEmpty
    )
    // without a witness, an abstract era reduces nothing
    assert(typeCheckErrors("def g[E <: EraTag](c: CertState[E]): ConwayCertState = c").nonEmpty)
    // not an era
    assert(typeCheckErrors("val w = ShelleyBasedEra[String]").nonEmpty)
    assert(typeCheckErrors("val c: CertState[String] = ConwayCertState()").nonEmpty)
  }

  test("right-era access compiles") {
    assertEquals(typeCheckErrors("val c: CertState[Conway] = ConwayCertState()"), Nil)
    assertEquals(typeCheckErrors("val c: CertState[Alonzo] = ShelleyCertState()"), Nil)
    assertEquals(
      typeCheckErrors(
        "def f[E <: EraTag](era: ShelleyBasedEra[E], c: CertState[E]): ConwayCertState = era match { case ShelleyBasedEra.SConway => c; case _ => ConwayCertState() }"
      ),
      Nil
    )
  }
