// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import java.util.concurrent.atomic.AtomicInteger
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.node.MirrorRules
import xyz.kd5ujc.scarab.node.mempool.{Source, TxKit}
import xyz.kd5ujc.scarab.plutus.*

/** The block rules of the node (`MirrorRules`, which `Main` builds with the run's one
  * [[PlutusBackend]]) admit a block's scripts with the backend they are given (`--plutus-vm`, ADR
  * 0027). A block is forged on the real rules with a transaction whose output carries a PlutusV3
  * reference script, which the rules admit in phase 1 (`MalformedReferenceScripts`) through the
  * backend's decoder; the same block is then applied by `MirrorRules` with a decoder that has no
  * verdict and counts what it is asked. Rules that kept another backend would accept the block,
  * which both real backends accept, and leave the probe unasked.
  */
class PlutusBackendBlockProbeSuite
    extends xyz.kd5ujc.scarab.network.FailsOnUncaught
    with ForgeFixture:

  /** A PlutusV3 validator that returns `()`: well formed for both backends. */
  private val script: Bytes = FlatProgram.script((1, 1, 0), FlatProgram.Lam(FlatProgram.unit))

  private final class Probe:
    val decoded = AtomicInteger(0)
    val backend: PlutusBackend = PlutusBackend(
      PlutusVm.Scarab,
      _ =>
        EvalResult.Failure(
          EvalFailure.Unsupported(BackendFailure.NoResult),
          ExUnits.zero,
          Vector.empty
        ),
      (_, _, _) =>
        decoded.incrementAndGet()
        Left(ScriptDecodeFailure.Unavailable(BackendFailure.NoResult))
    )

  test("the block rules ask the backend they are given, and nothing else") {
    val p = pool(kesStart = 50)
    val start = ledgerWith(p, 3)
    val (raw, id) = TxKit.tx(
      Vector(TestLedger.txIn(1)),
      Vector(TxKit.bob.address() -> 99 * TxKit.ada),
      TxKit.ada,
      Vector(TxKit.alice),
      referenceScript = Some(ScriptKind.PlutusV3 -> script)
    )
    withHandle {
      for
        h <- harness(p, start)
        _ <- h.mempool.add(raw, Source.Local)
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        forged <- forgedIn(h)
        raws <- h.raws.get
      yield
        val first = forged.headOption.getOrElse(fail("no block forged in 40 slots"))
        assertEquals(first.txs, Vector(id))
        val block = raws.getOrElse(first.hash, fail("the forged block's bytes are not kept"))
        val header = Block
          .decodeShelley(block)
          .fold(e => fail(e.toString), _.header)
        def apply(backend: PlutusBackend) =
          MirrorRules.applyBlock(
            mirrorState(start),
            header,
            block,
            config,
            globals,
            None,
            None,
            mirrorEnv.byron,
            backend
          )
        for vm <- PlutusVm.values do
          assert(apply(PlutusBackend.of(vm)).isRight, s"${vm.name}: ${apply(PlutusBackend.of(vm))}")
        val probe = Probe()
        apply(probe.backend) match
          case Left(BlockFailure.Tx(LedgerFailure.Unavailable(_, ScriptLocation.Phase1(_), _))) =>
            assert(probe.decoded.get > 0, "the probe was not asked")
          case other => fail(s"the block rules kept another backend: $other")
    }
  }
