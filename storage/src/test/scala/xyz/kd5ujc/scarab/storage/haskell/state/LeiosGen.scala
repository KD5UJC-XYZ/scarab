// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import java.nio.file.{Files, Path}
import org.scalacheck.{Arbitrary, Gen}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{StateGen as _, *}
import xyz.kd5ujc.scarab.consensus.state.StateGen as ConsensusGen
import xyz.kd5ujc.scarab.consensus.state.StateGen.given
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.EpochStateGen.given
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.given
import xyz.kd5ujc.scarab.protocol.Era

/** Values in the layout of the Leios build (#826): the generators of the 11.1 layout's values with
  * their layout parts replaced by Leios ones. The voting keys are valid BLS12-381 MinSig points,
  * the three pools' keys of the Leios proto-devnet (`fixtures/leios/bls/devnet-pools.tsv`).
  */
object LeiosGen:
  private def arb[A: Arbitrary]: Gen[A] = Arbitrary.arbitrary[A]

  /** `traverse` in `Gen`, which has no `Applicative` instance here. */
  private def each[A, B](xs: Vector[A])(f: A => Gen[B]): Gen[Vector[B]] =
    xs.foldLeft(Gen.const(Vector.empty[B]))((acc, a) => acc.flatMap(bs => f(a).map(bs :+ _)))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => sys.error(e.toString), identity)

  val keys: Vector[BlsKey] =
    Files
      .readString(Path.of("fixtures/leios/bls/devnet-pools.tsv"))
      .linesIterator
      .filterNot(_.startsWith("#"))
      .filter(_.nonEmpty)
      .map { line =>
        val p = line.split("\t", -1)
        BlsKey(hex(p(2)), hex(p(3)))
      }
      .toVector

  val blsKeyState: Gen[BlsKeyState] =
    Gen.zip(Gen.oneOf(keys), arb[EpochNo]).map(BlsKeyState(_, _))

  val poolBlsKey: Gen[PoolBlsKey] = Gen.option(blsKeyState).map(PoolBlsKey.Leios(_))

  val leiosSeat: Gen[LeiosSeat] =
    Gen.zip(StateGen.nonNegative, Gen.option(Gen.oneOf(keys).map(_.key))).map(LeiosSeat(_, _))

  /** Up to 30 seats: the encoding is definite up to 23 and indefinite past. */
  val committee: Gen[LeiosCommittee] =
    Gen.choose(0, 30).flatMap(Gen.listOfN(_, leiosSeat)).map(s => LeiosCommittee(s.toVector))

  val snapShotsLayout: Gen[SnapShots.Layout] =
    Gen
      .zip(arb[EpochNo], Gen.choose(0, 65535), Gen.choose(0L, 0xffffffffL), committee)
      .map((e, size, age, c) => SnapShots.Layout.Leios(e, BigInt(size), BigInt(age), c))

  private def poolSnapShot(p: StakePoolSnapShot): Gen[StakePoolSnapShot] =
    poolBlsKey.map(k => p.copy(blsKey = k))

  def snapShot(s: SnapShot): Gen[SnapShot] =
    each(s.pools.toVector)((k, p) => poolSnapShot(p).map(k -> _))
      .map(ps => s.copy(pools = Sorted.map(ps)))

  val snapShots: Gen[SnapShots] =
    for
      s <- StateGen.snapShots
      mark <- snapShot(s.mark)
      set <- snapShot(s.set)
      go <- snapShot(s.go)
      layout <- snapShotsLayout
    yield SnapShots(mark, set, go, s.fee, layout)

  val stakePoolState: Gen[StakePoolState] =
    Gen.zip(StateGen.stakePoolState, poolBlsKey).map((p, k) => p.copy(blsKey = k))

  val stakePoolSnapShot: Gen[StakePoolSnapShot] =
    arb[StakePoolSnapShot].flatMap(p =>
      poolSnapShot(
        p.copy(numDelegators = Word64.fromUnsigned(p.numDelegators.bits & Long.MaxValue))
      )
    )

  private def rewardUpdate(ru: PulsingRewUpdate): Gen[PulsingRewUpdate] = ru match
    case PulsingRewUpdate.Pulsing(snapshot, pulser) =>
      each(pulser.free.pools.toVector)((k, info) =>
        poolSnapShot(info.snapshot).map(s => k -> info.copy(snapshot = s))
      )
        .map(pools =>
          PulsingRewUpdate.Pulsing(
            snapshot,
            pulser.copy(free = pulser.free.copy(pools = Sorted.map(pools)))
          )
        )
    case complete => Gen.const(complete)

  /** A state of `E` in the Leios layout: no pool distribution, the Leios snapshots, and the pools
    * of the state and of a pending reward update with voting keys.
    */
  def newEpochState[E <: EraTag](using ShelleyBasedEra[E]): Gen[NewEpochState[E]] =
    for
      nes <- StateGen.newEpochState[E]
      snapshots <- snapShots
      pools <- each(LedgerState.pState[E].get(nes.es.ls).stakePools.toVector)((k, p) =>
        poolBlsKey.map(b => k -> p.copy(blsKey = b))
      )
      ru <- nes.ru.fold(Gen.const(Option.empty[PulsingRewUpdate]))(rewardUpdate(_).map(Some(_)))
    yield nes.copy(
      es = nes.es.copy(
        snapshots = snapshots,
        ls = LedgerState.pState[E].modify(_.copy(stakePools = Sorted.map(pools)))(nes.es.ls)
      ),
      ru = ru,
      layout = NewEpochState.Layout.Leios
    )

  val perasRound: Gen[PerasRoundNo] = arb[PerasRoundNo]

  /** A bound in the Leios layout: its round a function of its slot, so that the end of an era and
    * the start of the next stay the same bound.
    */
  def bound(b: Bound): Bound =
    b.copy(layout =
      Bound.Layout.Leios(PerasRoundNo(Word64.fromUnsigned((b.slot % 1000003).toLong)))
    )

  val leiosBound: Gen[Bound] = arb[Bound].map(bound)

  def telescope[A: EraIndexed](t: Telescope[A]): Telescope[A] =
    Telescope
      .make(
        t.past.map(p => Past(bound(p.start), bound(p.end))),
        t.current.copy(start = bound(t.current.start))
      )
      .fold(e => sys.error(e.toString), identity)

  val perasResolver: Gen[PerasResolver] =
    Gen.oneOf(
      Gen.const(PerasResolver.Disabled),
      Gen
        .listOf(Gen.choose(0, 255).map(_.toByte))
        .map(b => PerasResolver.Failed(Bytes.wrap(b.toArray)))
    )

  val perasState: Gen[PerasState] =
    Gen.zip(perasResolver, Gen.option(perasRound)).map(PerasState(_, _))

  private def shelleyIn[E <: EraTag](using ShelleyBasedEra[E]): Gen[LedgerFileEra] =
    Gen
      .zip(arb[WithOrigin[ShelleyTip]], newEpochState[E], arb[ShelleyTransition], arb[Word64])
      .map((t, nes, tr, bytes) =>
        LedgerFileEra.Mirrored(
          EraLedgerState.inEra(
            ShelleyLedgerState[E](t, nes, tr, ShelleyLedgerState.Layout.Leios(bytes))
          )
        )
      )

  /** The Shelley-based eras before Dijkstra: those whose states this build reads. */
  private val eras: Vector[Era] =
    Era.values.toVector.filter(e => e != Era.Byron && e != Era.Dijkstra)

  /** The ledger state of `era`, a Shelley-based era, in a `state` file of the Leios layout. */
  def ledgerEra(era: Era): Gen[LedgerFileEra] =
    Versions.shelleyBased(era).fold(Gen.fail[LedgerFileEra]) { case w: ShelleyBasedEra[e] =>
      shelleyIn[e](using w)
    }

  /** A whole `state` file of the Leios layout, in a Shelley-based era before Dijkstra. */
  val file: Gen[HaskellLedgerFile] =
    for
      era <- Gen.oneOf(eras)
      ledger <- ConsensusGen.telescope(era, ledgerEra)
      header <- arb[HeaderState]
      deps <- ConsensusGen.telescope(era, ConsensusGen.chainDepState)
      peras <- perasState
    yield HaskellLedgerFile(
      telescope(ledger),
      header.copy(chainDep = telescope(deps)),
      ExtLedgerState.Layout.Leios(peras)
    )
