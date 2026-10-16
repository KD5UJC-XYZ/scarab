// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}

/** The `rule` and `reason` fields of the `rejected` log event are parsed by the devnet tools and by
  * the runs on remote machines: the closed [[BlockFailure]] has to render the strings that the
  * `(rule, reason)` pair carried, byte for byte. These are those strings.
  */
class BlockFailureSuite extends FunSuite:
  private def rendered(f: BlockFailure): (String, String) = (f.rule.name, f.reason)

  test("the structure failures render as they did") {
    assertEquals(
      rendered(BlockFailure.HeaderDiffers),
      "structure" -> "block header differs from announced header"
    )
    assertEquals(
      rendered(BlockFailure.BodyCommitment),
      "structure" -> "body does not match the header commitment"
    )
    assertEquals(
      rendered(BlockFailure.EraAnnounced(Era.Babbage, Era.Byron)),
      "structure" -> "Babbage block announced as Byron"
    )
    assertEquals(
      rendered(BlockFailure.EpochPrecedesLedger(BigInt(7))),
      "structure" -> "block epoch 7 precedes ledger epoch"
    )
    assertEquals(
      rendered(BlockFailure.ByronAfterShelley),
      "structure" -> "Byron block after the Shelley era began"
    )
    assertEquals(
      rendered(BlockFailure.ByronAfterTransition(BigInt(4), BigInt(3))),
      "structure" -> "Byron block in epoch 4, the chain enters Shelley in epoch 3"
    )
  }

  test("the byron, decode and ledger failures render as they did") {
    assertEquals(rendered(BlockFailure.NoByronGenesis), "byron" -> "no Byron genesis configured")
    assertEquals(
      rendered(BlockFailure.TransitionUndecided(Era.Shelley)),
      "byron" -> "Shelley block, but the Byron ledger has not decided on the transition to Shelley"
    )
    assertEquals(
      rendered(BlockFailure.ShelleyBeforeTransition(BigInt(2), BigInt(3), BigInt(99))),
      "byron" -> "Shelley block in epoch 2, the transition is to epoch 3 after Byron slot 99"
    )
    assertEquals(rendered(BlockFailure.Byron("x")), "byron" -> "x")
    assertEquals(rendered(BlockFailure.Praos("y")), "praos" -> "y")
    assertEquals(rendered(BlockFailure.Lite("z")), "ledger" -> "z")
    assertEquals(rendered(BlockFailure.Handover("h")), "ledger" -> "h")
    val id = Bytes.fromHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
    assertEquals(
      id.map(i => rendered(BlockFailure.TxDecode(i, "bad"))),
      Right("decode" -> "tx 0011223344556677: bad")
    )
    assertEquals(
      rendered(BlockFailure.SupplyInvariant(BigInt(5), BigInt(10), BigInt(11))),
      "ledger" -> "supply invariant broken entering epoch 5: 10 != 11"
    )
    assertEquals(
      rendered(BlockFailure.RefScriptsTooLarge(1048576L)),
      "ledger" -> "reference scripts of the block exceed 1048576 bytes"
    )
    assertEquals(
      rendered(BlockFailure.BlockExUnitsTooBig(ExUnits(7, 8), ExUnits(5, 6))),
      "ledger" -> "block execution units 7 mem / 8 steps exceed the maximum 5 / 6"
    )
  }

  test("a validation error renders the rule and the reason of its failure") {
    val e = ValidationError(ChainSync.Point.Origin, BlockFailure.HeaderDiffers)
    assertEquals(e.rule, "structure")
    assertEquals(e.reason, "block header differs from announced header")
    assert(e.message.endsWith("rejected by structure: block header differs from announced header"))
  }
