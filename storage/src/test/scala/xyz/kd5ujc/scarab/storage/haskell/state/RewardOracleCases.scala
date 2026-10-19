// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.Json
import java.nio.file.{Files, Path}
import munit.Assertions
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TestLedger
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, RewardPulserFixture, Rewards}
import xyz.kd5ujc.scarab.ledger.state.*

/** Explicit small inputs for the unchanged released Haskell start/pulse/complete API. */
object RewardOracleCases extends Assertions:
  import RewardPulserFixture.*
  import HaskellStateCodecs.pulsingRewUpdate
  given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)

  enum Operation:
    case Pulse, Complete
    def wire: String = this match
      case Pulse    => "pulse"
      case Complete => "complete"

  final case class Input(
      name: String,
      state: Option[EraNes],
      pending: Option[PulsingRewUpdate],
      operations: Vector[Operation]
  )
  private val steps = Vector.fill(10)(Operation.Pulse) :+ Operation.Complete

  private def shelley(major: Int): NewEpochState[Shelley] =
    val b = babbage(rewarding(conway(5, 7, 2)))
    val params = TestLedger.params(major).copy(rho = EraLedgerParams.rho)
    val base = NewEpochState.empty[Shelley](b.epoch, params)
    base.copy(
      bprev = b.bprev,
      bcur = b.bcur,
      es = base.es.copy(
        chainAccount = b.es.chainAccount,
        ls = base.es.ls.copy[Shelley](
          cert = b.es.ls.cert,
          utxo =
            base.es.ls.utxo.copy(fees = b.es.ls.utxo.fees, instantStake = b.es.ls.utxo.instantStake)
        ),
        snapshots = b.es.snapshots
      )
    )

  // Keep the scalar used by the common fixture without approximating it as a decimal.
  private object EraLedgerParams:
    val rho = xyz.kd5ujc.scarab.codec.Rational(3, 1000)

  private def local(name: String, state: EraNes): Input = Input(name, Some(state), None, steps)
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
  private val base = rewarding(conway(5, 7, 2))
  private val started =
    Rewards.start(base.es, base.bprev, globals, 5).fold(e => fail(s"start: $e"), identity)
  private val imported = started match
    case p: PulsingRewUpdate.Pulsing => p
    case other                       => fail(s"not a pulser: $other")

  private val history = NonMyopic(
    Sorted.map(List(pool(0) -> Likelihood(Vector(LogWeight(2f), LogWeight(-1f))))),
    RewardPulserFixture.coin(77)
  )
  private val likelihoods =
    Sorted.map(List(pool(0) -> Likelihood(Vector(LogWeight(-3f), LogWeight(5f)))))

  private def mapGo[E <: EraTag](n: NewEpochState[E])(f: SnapShot => SnapShot): NewEpochState[E] =
    n.copy(es = n.es.copy(snapshots = n.es.snapshots.copy(go = f(n.es.snapshots.go))))

  private def sharedLeaders[E <: EraTag](n: NewEpochState[E]): NewEpochState[E] =
    mapGo(n)(go =>
      SnapShot(go.activeStake, go.pools.transform((_, p) => p.copy(accountId = credential(0))))
    )

  private def missingAccount[E <: EraTag](n: NewEpochState[E]): NewEpochState[E] =
    mapGo(n)(go =>
      SnapShot(
        ActiveStake(
          go.activeStake.entries
            .removed(credential(6))
            .updated(credential(999), go.activeStake.entries(credential(6)))
        ),
        go.pools
      )
    )

  private val script =
    Credential.ScriptHashObj[Staking](ok(ScriptHash.fromBytes(RewardPulserFixture.bytes(900, 28))))
  private val scripted = mapGo(base)(go =>
    SnapShot(
      ActiveStake(
        go.activeStake.entries
          .removed(credential(6))
          .updated(script, go.activeStake.entries(credential(6)))
      ),
      go.pools
    )
  )
  private val owned = mapGo(base)(go =>
    SnapShot(
      go.activeStake,
      go.pools.updated(
        pool(0),
        go.pools(pool(0))
          .copy(
            selfDelegatedOwners =
              Sorted.set(List(ok(KeyHash.fromBytes[Staking](RewardPulserFixture.bytes(0, 28))))),
            selfDelegatedOwnersStake = RewardPulserFixture.coin(5001000000L)
          )
      )
    )
  )

  val inputs: Vector[Input] = Vector(
    local("shelley-v2-prefilter", EraNes.InEra(ShelleyBasedEra.SShelley, shelley(2))),
    local("shelley-v3-aggregation", EraNes.InEra(ShelleyBasedEra.SShelley, shelley(3))),
    local("babbage-v8", EraNes.InEra(ShelleyBasedEra.SBabbage, babbage(base))),
    local("conway-v10", EraNes.InEra(ShelleyBasedEra.SConway, base)),
    local("empty-active", EraNes.InEra(ShelleyBasedEra.SConway, conway(5, 0, 1))),
    local(
      "shelley-v2-multiple-leaders",
      EraNes.InEra(ShelleyBasedEra.SShelley, sharedLeaders(shelley(2)))
    ),
    local(
      "shelley-v3-multiple-leaders",
      EraNes.InEra(ShelleyBasedEra.SShelley, sharedLeaders(shelley(3)))
    ),
    local(
      "shelley-v2-unregistered-prefilter",
      EraNes.InEra(ShelleyBasedEra.SShelley, missingAccount(shelley(2)))
    ),
    local(
      "babbage-unregistered-retained",
      EraNes.InEra(ShelleyBasedEra.SBabbage, missingAccount(babbage(base)))
    ),
    local("conway-script-member", EraNes.InEra(ShelleyBasedEra.SConway, scripted)),
    local("conway-owner-excluded", EraNes.InEra(ShelleyBasedEra.SConway, owned)),
    Input(
      "imported-likelihood-history",
      None,
      Some(
        imported.copy(snapshot =
          imported.snapshot.copy(nonMyopic = history, likelihoods = likelihoods)
        )
      ),
      steps
    ),
    Input(
      "imported-empty-new-likelihoods",
      None,
      Some(
        imported.copy(snapshot =
          imported.snapshot.copy(nonMyopic = history, likelihoods = Sorted.empty)
        )
      ),
      steps
    ),
    Input(
      "imported-likelihood-identity100",
      None,
      Some(
        imported.copy(snapshot =
          imported.snapshot.copy(
            nonMyopic = NonMyopic.empty,
            likelihoods = Sorted.map(
              List(pool(0) -> Likelihood(Vector.tabulate(101)(i => LogWeight(i.toFloat))))
            )
          )
        )
      ),
      steps
    ),
    Input(
      "imported-pool-discriminators-and-bignum",
      None,
      Some(
        imported.copy(pulser =
          imported.pulser.copy(
            size = ok(RewardPulseSize.fromBigInt(Long.MaxValue)),
            free = imported.pulser.free.copy(pools =
              imported.pulser.free.pools.transform((_, info) =>
                info.copy(
                  blocks = ok(RewardBlockCount.fromBigInt((BigInt(1) << 80) + 7)),
                  leader = info.leader.copy(pool = pool(999))
                )
              )
            )
          )
        )
      ),
      steps
    )
  )

  def bytes[E <: EraTag](state: NewEpochState[E])(using ShelleyBasedEra[E]): Bytes =
    HaskellState.encodeEpochState(state.es).fold(e => fail(e.toString), identity)

  def json(in: Input): Json =
    val (era, epochState, blocks) = in.state match
      case Some(i: EraNes.InEra[e]) =>
        given ShelleyBasedEra[e] = i.era
        (
          EraNes.eraOf(i.era).toString,
          Json.fromString(bytes(i.nes).hex),
          HaskellStateCodecs.blocksMade.write(i.nes.bprev).fold(e => fail(e.toString), identity)
        )
      case None =>
        (
          "Conway",
          Json.Null,
          HaskellStateCodecs.blocksMade.write(base.bprev).fold(e => fail(e.toString), identity)
        )
    Json.obj(
      "name" -> Json.fromString(in.name),
      "era" -> Json.fromString(era),
      "epochState" -> epochState,
      "blocksMade" -> Json.fromString(blocks.hex),
      "pending" -> in.pending.fold(Json.Null)(p =>
        Json.fromString(pulsingRewUpdate.write(p).fold(e => fail(e.toString), identity).hex)
      ),
      "slots" -> Json.fromInt(10000),
      "supply" -> Json.fromBigInt(Supply),
      "fNum" -> Json.fromInt(1),
      "fDen" -> Json.fromInt(20),
      "security" -> Json.fromInt(5),
      "operations" -> Json.arr(in.operations.map(op => Json.fromString(op.wire))*)
    )

  def main(args: Array[String]): Unit =
    Files.writeString(Path.of(args(0)), Json.arr(inputs.map(json)*).spaces2)
