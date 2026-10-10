// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.syntax.all.*

/** Explicit research composition of the checked original native source. Early restored blocks are
  * replayed; no mutable ledger snapshot or pending admission is imported.
  */
private[lab] object RepeatedPlutusBootstrap:
  private def get[A](value: Either[?, A]): A =
    value.fold(error => throw new IllegalArgumentException(error.toString), identity)

  def start(
      joined: NativeLedgerV2.Checked,
      early: PlutusServiceCheckpoint.Started,
      oracle: NativeLikelihoodOracle.Oracle[IO]
  ): IO[PlutusServiceCheckpoint.Started] = for
    context <- IO {
      require(
        joined != null && early != null && oracle != null,
        "complete repeated bootstrap required"
      )
      require(joined.crossingBlockers.isEmpty, "native crossing source geometry unsupported")
      require(
        joined.ledger.globals.geometry.epochLength == 1000 &&
          joined.ledger.globals.rewardGlobals.activeSlotCoefficient == lab.ledger.ConwayStake
            .Ratio(1, 20),
        "registered repeated native comparison geometry required"
      )
      val c = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
      require(c.ledger.environment.plutus.nonEmpty, "checked Plutus source required")
      require(
        early.snapshot.state.contextId == c.id && early.snapshot.state.ledger.environment.epoch == 0 &&
          early.snapshot.state.compactedBlocks == 0 && early.snapshot.state.depth <= 8,
        "only uncompacted checked early epoch-zero replay supported"
      )
      c
    }
    source = joined.ledger
    epoch = source.epochComponents
    profile <- IO(
      get(
        CoherentSequence.syntheticBoundaryProfile(
          source.parameterRoles,
          source.pools,
          source.globals,
          source.globals.stabilityWindow
        )
      )
    )
    runtime <- CoherentSequence
      .createWithRepeatedBoundary[IO](
        context,
        epoch.stake,
        profile,
        source.governanceInput,
        epoch.nonMyopic,
        epoch.pots,
        epoch.previousBlocks,
        epoch.currentBlocks,
        epoch.reward.componentSHA256,
        8
      )
      .map(get(_))
    _ <- early.snapshot.state.acquisition.originals.traverse_ { original =>
      for
        block <- IO(get(SequenceInput.block(original)))
        snapshot <- runtime.snapshot
        candidate <- runtime
          .prepareRepeatedBlock(snapshot.fence, block, None, oracle.generate)
          .map(get(_))
        _ <- runtime.publish(candidate).map(get(_))
      yield ()
    }
    snapshot <- runtime.snapshot
    _ <- IO {
      val expected = early.snapshot.state
      require(
        snapshot.state.acquisition.tip == expected.acquisition.tip &&
          snapshot.state.ledger.outputMap == expected.ledger.outputMap &&
          snapshot.state.ledger.fees == expected.ledger.fees &&
          snapshot.state.nonces.fields == expected.nonces.fields &&
          snapshot.state.certificates.state.tip == expected.certificates.state.tip &&
          snapshot.state.stake.get.instantaneous == expected.stake.get.instantaneous &&
          snapshot.state.stake.get.snapshots == expected.stake.get.snapshots,
        "repeated replay does not match independently checked early state"
      )
    }
  yield PlutusServiceCheckpoint.Started(runtime, snapshot, early.restored)
