// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
import xyz.kd5ujc.scarab.ledger.ProtocolParams
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPParams
import xyz.kd5ujc.scarab.ledger.state.{ConwayNewEpochState, FuturePParams, ShelleyBasedEra}

/** Conway's protocol-parameter record over the parameters of the recorded Conway state: the current
  * and previous parameters read at the reference's types and written back unchanged. Its future
  * parameters are a potential update with no parameters, and it holds no parameter-change proposal;
  * the recorded Preview proposals are in `EraPParamsSuite`.
  */
class ConwayPParamsStateSuite extends FunSuite:
  private val gov =
    val file = HaskellLedgerFile
      .decode(Bytes.wrap(Files.readAllBytes(Path.of("fixtures/haskell-state/conway-4593/state"))))
      .fold(e => fail(e.toString), identity)
    file.ext.getOrElse(fail("complete reference state")).ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state) =>
        ConwayNewEpochState.gov.get(state.nes)
      case _ => fail("Conway reference state")

  private def roundTrip(p: ProtocolParams): Unit =
    val pp = ConwayPParams.fromStored(p).fold(e => fail(s"parameters: $e"), identity)
    assertEquals(ConwayPParams.toStored(pp, p), p)

  test("the current and previous parameters come back unchanged"):
    roundTrip(gov.curPParams)
    roundTrip(gov.prevPParams)
    assertEquals(gov.futurePParams, FuturePParams.PotentialPParamsUpdate(None))
