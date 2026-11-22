// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Eq
import java.nio.file.{Files, Path}
import java.time.Instant
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, TestGeometry}
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, EraNes, Globals, Hfc}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  Diff,
  FuturePParams,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.MempoolLedger
import xyz.kd5ujc.scarab.node.read.ConwayView
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, HaskellState}

/** The ledger state the forger builds a Conway block on, against the state block application
  * reaches for the same block: its parent's state ticked once to the block's slot (`Hfc.tick`, as
  * `EraBlock.ticked` ticks it; the reference's forger ticks so too, `getTickedLedgerState` 's
  * `applyChainTick`, ouroboros-consensus 2416493b `NodeKernel/Forge.hs:686-705`). The forger's
  * state is what the mempool's transactions are selected on ([[MempoolLedger]]).
  *
  * The parents are recorded Conway states, ticked as block application ticks them to a slot of
  * their epoch from which the forecast range (the stability window after the parent,
  * [[Forecastable.check]]) reaches into the next epoch: the last slot of the epoch, and the first
  * slot whose range reaches the boundary. Each is checked at every slot of the range past the
  * boundary (at the edges and the slots of no return where the range is long), on the geometry of
  * the network it was recorded on:
  *
  *   - `conway-4593` (`fixtures/haskell-state`) and `reward-later`
  *     (`fixtures/reference/ntc-wallet-peers-11.1.3`): the era-walk devnet, 500 slots, k = 5, f =
  *     0.2;
  *   - `peer-relays` (`fixtures/reference/ntc-wallet-peers-11.1.3`): the `devnet.py up` cluster the
  *     `pool` scenario forges on, 500 slots, k = 5, f = 0.05, where two stability windows are
  *     longer than the epoch, so every slot is past its epoch's slot of no return;
  *   - `conway-4593` 's state on preprod's geometry (no preprod or preview Conway state is in the
  *     repository; the preview states are private inputs of some gigabytes): 432,000 slots, k =
  *     2160, f = 0.05, as mainnet's and, scaled, preview's (ten times k/f);
  *   - `peer-relays` 's state on `up-dijkstra` 's geometry, 200 slots, k = 5, f = 0.2, where the
  *     slot of no return falls inside the range after the boundary.
  *
  * A forger that ticks the parent to the epoch's first slot and the result to the slot (the two
  * ticks the Conway forger made before, cached per tip and epoch) reaches another state wherever
  * the slot is at or past the new epoch's slot of no return: TICK solidifies the future parameters
  * before NEWEPOCH (`validatingTickTransition`, cardano-ledger f649f975
  * `Shelley/Rules/Tick.hs:160-181`), so the single tick solidifies the parent's, which EPOCH then
  * replaces with the new epoch's `Potential` prediction (`Conway/Rules/Epoch.hs:325`,
  * `Conway/Governance.hs:458-493`), and the second of the two ticks solidifies that prediction.
  * Before the slot of no return the two agree: the second tick's NEWEPOCH predicts again from the
  * pulser the boundary made (`Conway/Rules/NewEpoch.hs:160-166`), which gives the same prediction,
  * and RUPD reads the state only after `randomnessStabilisationWindow` slots of the epoch
  * (`Shelley/Rules/Rupd.hs`), which the range never reaches (4k/f against 3k/f).
  */
class ConwayForgeTickSuite extends munit.FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(10, "min")

  /** A network's rules: the globals and the hard-fork transition the forger and block application
    * tick with.
    */
  final case class Net(name: String, globals: Globals, transition: Hfc.Transition):
    def geometry: EpochGeometry = globals.geometry
    def window: BigInt = geometry.stabilityWindow.toBigInt

  /** A recorded Conway state, the slot of its tip and the network it is ticked on. */
  final case class Recorded(name: String, nes: NewEpochState[Conway], tip: BigInt, net: Net):
    def epoch: BigInt = nes.epoch.toBigInt

    /** The first slot of the epoch after the state's. */
    def boundary: BigInt = net.geometry.firstSlot(epoch + 1)

  private def setupOf(dir: String): NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(Path.of(dir))
      .fold(e => fail(e.message), identity)

  private def netOf(name: String, setup: NodeSetup): Net =
    Net(
      name,
      Globals(setup.globals, setup.geometry),
      setup.praos.forks.transition(setup.geometry.k)
    )

  private def bytes(path: String): Bytes = Bytes.wrap(Files.readAllBytes(Path.of(path)))

  /** The Conway state of a reference node's ledger snapshot (`[1, [telescope, header]]`). */
  private def snapshot(path: String): NewEpochState[Conway] =
    val file = HaskellLedgerFile.decode(bytes(path)).fold(e => fail(e.render), identity)
    file.ext.getOrElse(fail(s"$path: no Shelley-based state")).ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, s) => s.nes
      case other => fail(s"not Conway: ${other.era}")

  /** A reference node's `DebugNewEpochState` of a Conway state. */
  private def debugState(path: String): NewEpochState[Conway] =
    HaskellState
      .decode[Conway](bytes(path))(using ShelleyBasedEra.SConway)
      .fold(e => fail(e.toString), identity)

  private val wallet = "fixtures/reference/ntc-wallet-peers-11.1.3"

  private lazy val erasNet = netOf("era walk", setupOf("fixtures/eras/genesis"))
  private lazy val peerSetup = setupOf(s"$wallet/peer-relays/genesis")
  private lazy val poolNet = netOf("pool devnet", peerSetup)
  // preprod enters Shelley at epoch 4: the node learns it from the chain, the test from the network
  private lazy val preprodNet =
    val setup = setupOf("fixtures/preprod/genesis")
    Net(
      "preprod",
      Globals(setup.globals, setup.geometry.resolved(4)),
      setup.praos.forks.transition(setup.geometry.k)
    )
  private lazy val dijkstraNet = Net(
    "up-dijkstra",
    Globals(
      peerSetup.globals,
      EpochGeometry(
        Instant.EPOCH,
        100,
        epochLength = 200,
        securityParam = TestGeometry.k(5),
        activeSlotsCoeff = TestGeometry.f("0.2")
      )
    ),
    peerSetup.praos.forks.transition(5)
  )

  private lazy val conway4593 = snapshot("fixtures/haskell-state/conway-4593/state")
  private lazy val peerRelays = debugState(s"$wallet/peer-relays/new-epoch-state.cbor")

  // the tips' slots are the snapshot's (README) and the acquired points of the manifest
  private lazy val recorded: Vector[Recorded] = Vector(
    Recorded("conway-4593", conway4593, 4593, erasNet),
    Recorded(
      "reward-later",
      debugState(s"$wallet/reward-later/new-epoch-state.cbor"),
      8977,
      netOf("era walk (reward-later)", setupOf(s"$wallet/reward-later/genesis"))
    ),
    Recorded("peer-relays", peerRelays, 29, poolNet),
    Recorded("conway-4593 on preprod", conway4593, 0, preprodNet),
    Recorded("peer-relays on up-dijkstra", peerRelays, 29, dijkstraNet)
  )

  // --- the two sides ---------------------------------------------------------------------------

  private def conway(e: EraNes): NewEpochState[Conway] =
    ConwayView.of(e).getOrElse(fail(s"left Conway: ${e.toEra}"))

  /** Block application's tick of `nes` to `slot` ([[Hfc.tick]], as `EraBlock.ticked` makes it). */
  private def applied(net: Net, nes: NewEpochState[Conway], slot: BigInt): NewEpochState[Conway] =
    val target = SlotNo.fromBigInt(slot).fold(e => fail(e.toString), identity)
    Hfc
      .tick(net.globals, net.transition)(EraNes.InEra(ShelleyBasedEra.SConway, nes), 0L, target)
      .fold(e => fail(s"${net.name} slot $slot: $e"), conway)

  private def tipLedger(t: Forger.TipLedger): NewEpochState[Conway] = t match
    case Forger.TipLedger.InConway(nes) => nes
    case other                          => fail(s"not Conway: ${other.era}")

  /** The forger's side for a block of `slot` on `parent`: what it keeps of the tip for the slot's
    * epoch ([[Forger.crossed]], as `crossedTo` caches it), the whole state its ledger reaches by
    * the led slot's tick ([[Forger.tickedTo]]), and what that tick hands to block building and the
    * mempool's selection ([[Forger.blockLedger]], the one tick `signing` makes).
    */
  private def forged(
      net: Net,
      parent: NewEpochState[Conway],
      slot: BigInt
  ): (NewEpochState[Conway], Forger.Ticked) =
    val epoch = net.geometry.epochOf(slot)
    val crossed =
      Forger.crossed(
        (None, epoch),
        Forger.TipLedger.InConway(parent),
        peerSetup.mirrorStart.protocol,
        epoch
      )
    val whole = tipLedger(
      Forger
        .tickedTo(net.globals, net.transition, crossed.ledger, slot)
        .fold(e => fail(e.message), identity)
    )
    val handed = Forger
      .blockLedger(net.globals, net.transition, crossed, slot)
      .fold(e => fail(e.message), identity)
    (whole, handed)

  /** The two ticks the Conway forger made before: to the first slot of the slot's epoch, then to
    * the slot.
    */
  private def twoTicks(net: Net, parent: NewEpochState[Conway], slot: BigInt) =
    applied(net, applied(net, parent, net.geometry.firstSlot(net.geometry.epochOf(slot))), slot)

  private def same(a: NewEpochState[Conway], b: NewEpochState[Conway]): Boolean =
    Eq[NewEpochState[Conway]].eqv(a, b)

  private def difference(a: NewEpochState[Conway], b: NewEpochState[Conway]): String =
    Diff[NewEpochState[Conway]]
      .diff(a, b)
      .fold("none")(d => s"${d.path.render}: ${d.left} /= ${d.right}")

  private def future(nes: NewEpochState[Conway]): FuturePParams = nes.es.ls.utxo.gov.futurePParams

  private def withFuture(nes: NewEpochState[Conway], f: FuturePParams): NewEpochState[Conway] =
    nes.copy(es =
      nes.es.copy(ls =
        nes.es.ls.copy(utxo = nes.es.ls.utxo.copy(gov = nes.es.ls.utxo.gov.copy(futurePParams = f)))
      )
    )

  // --- the parents and their slots ---------------------------------------------------------------

  /** A parent of the state's epoch, at `slot`, and the slots of its forecast range in the next
    * epoch that are checked.
    */
  final case class Case(r: Recorded, parentSlot: BigInt, parent: NewEpochState[Conway]):
    private def net = r.net
    def label: String = s"${r.name} (${net.name}), parent at slot $parentSlot"

    /** The new epoch's slot of no return. */
    def noReturn: BigInt = net.geometry.slotOfNoReturn(r.epoch + 1)

    /** Every slot the forger forges in from this parent past the boundary: those of the forecast
      * range; all of them where there are few, else the edges and the slots of no return.
      */
    def slots: Vector[BigInt] =
      val first = r.boundary
      val last = parentSlot + net.window // the last slot before `maxFor`
      val all =
        if last - first <= 400 then (first to last).toVector
        else
          Vector(first, first + 1, (first + last) / 2, last - 1, last, noReturn - 1, noReturn)
            .filter(s => s >= first && s <= last)
            .distinct
            .sorted
      all.filter(s => Forecastable.check(Some(parentSlot), net.window, s).isRight)

  /** The parents of `r` 's epoch whose ranges reach into the next: the first slot whose range does
    * (the tip itself where it is later) and the last slot of the epoch.
    */
  private def parentsOf(r: Recorded): Vector[Case] =
    val b = r.boundary
    Vector((b - r.net.window).max(r.tip), b - 1).distinct.map { s =>
      Case(r, s, if s == r.tip then r.nes else applied(r.net, r.nes, s))
    }

  private lazy val cases: Vector[Case] = recorded.flatMap(parentsOf)

  test("the recorded states are Conway states of their tips' epochs, each with crossing parents") {
    recorded.foreach { r =>
      if r.net ne preprodNet then assertEquals(r.net.geometry.epochOf(r.tip), r.epoch, r.name)
      assert(r.tip < r.boundary, r.name)
      parentsOf(r).foreach { c =>
        assertEquals(r.net.geometry.epochOf(c.parentSlot), r.epoch, c.label)
        assert(c.slots.nonEmpty, c.label)
        assert(c.slots.forall(s => r.net.geometry.epochOf(s) == r.epoch + 1), c.label)
      }
    }
  }

  // --- the forger's state is block application's ----------------------------------------------

  test(
    "a Conway block's ledger is its parent's ticked once to the slot, at every slot of the range"
  ) {
    cases.foreach { c =>
      c.slots.foreach { s =>
        val (forger, handed) = forged(c.r.net, c.parent, s)
        val block = applied(c.r.net, c.parent, s)
        assert(same(forger, block), s"${c.label}, slot $s: ${difference(forger, block)}")
        assertEquals(
          handed,
          Forger.Ticked.InConway(MempoolLedger.of(block)),
          s"${c.label}, slot $s"
        )
      }
    }
  }

  test(
    "with a future-parameter update pending at the parent, the forger's state is block application's"
  ) {
    cases.foreach { c =>
      val cur = c.parent.es.ls.utxo.gov.curPParams
      // a change the selection reads: the fee per byte
      val changed = cur.copy(minFeeA = cur.minFeeA + 1)
      Vector(
        FuturePParams.NoPParamsUpdate,
        FuturePParams.PotentialPParamsUpdate(None),
        FuturePParams.PotentialPParamsUpdate(Some(changed)),
        FuturePParams.DefinitePParamsUpdate(changed)
      ).foreach { pending =>
        val parent = withFuture(c.parent, pending)
        c.slots.foreach { s =>
          val (forger, handed) = forged(c.r.net, parent, s)
          val block = applied(c.r.net, parent, s)
          assert(
            same(forger, block),
            s"${c.label}, $pending, slot $s: ${difference(forger, block)}"
          )
          assertEquals(
            handed,
            Forger.Ticked.InConway(MempoolLedger.of(block)),
            s"${c.label}, $pending, slot $s"
          )
          // the boundary adopts a definite update, and a potential one the single tick solidifies
          // first, past the new epoch's slot of no return
          val adopted = pending match
            case FuturePParams.DefinitePParamsUpdate(_)        => true
            case FuturePParams.PotentialPParamsUpdate(Some(_)) => s >= c.noReturn
            case _                                             => false
          assertEquals(
            block.es.ls.utxo.gov.curPParams,
            if adopted then changed else cur,
            s"${c.label}, $pending, slot $s"
          )
        }
      }
    }
  }

  // --- why: where the two ticks the forger made before agree, and where they do not -------------

  test(
    "the two ticks reach block application's state wherever the range ends before the slot of no return"
  ) {
    // preprod's (and mainnet's) and the era walk's geometry: the slot of no return of the new epoch
    // is 4k/f and 14k/f slots into it, and the range from a parent of the old epoch ends before 3k/f
    val commuting = Set("conway-4593", "reward-later", "conway-4593 on preprod")
    cases
      .filter(c => commuting(c.r.name))
      .foreach(c => assert(c.slots.forall(_ < c.noReturn), c.label))
    cases.foreach { c =>
      val cur = c.parent.es.ls.utxo.gov.curPParams
      val changed = cur.copy(minFeeA = cur.minFeeA + 1)
      Vector(
        future(c.parent),
        FuturePParams.PotentialPParamsUpdate(Some(changed)),
        FuturePParams.DefinitePParamsUpdate(changed)
      ).foreach { pending =>
        val parent = withFuture(c.parent, pending)
        c.slots.filter(_ < c.noReturn).foreach { s =>
          val two = twoTicks(c.r.net, parent, s)
          val one = applied(c.r.net, parent, s)
          assert(same(two, one), s"${c.label}, $pending, slot $s: ${difference(two, one)}")
        }
      }
    }
  }

  test("past the new epoch's slot of no return the two ticks solidify the new epoch's prediction") {
    // the pool scenario's geometry (every slot is past it) and up-dijkstra's (the range reaches it)
    val reaching = cases.filter(c => c.slots.exists(_ >= c.noReturn))
    assertEquals(
      reaching.map(_.r.name).distinct,
      Vector("peer-relays", "peer-relays on up-dijkstra")
    )
    reaching.foreach { c =>
      c.slots.foreach { s =>
        val two = twoTicks(c.r.net, c.parent, s)
        val one = applied(c.r.net, c.parent, s)
        if s < c.noReturn then
          assert(same(two, one), s"${c.label}, slot $s: ${difference(two, one)}")
        else
          // the single tick leaves the boundary's `Potential` prediction, the second of two ticks
          // solidifies it; on these recorded states nothing else differs (a pending update made at
          // the parent can change the parameters in force too: the next test), and the mempool's
          // ledger holds the difference
          val potential = future(one) match
            case p: FuturePParams.PotentialPParamsUpdate => p
            case other => fail(s"${c.label}, slot $s: not a potential update: $other")
          assertEquals(future(two), EraLedger.solidify(potential), s"${c.label}, slot $s")
          assert(!same(two, one), s"${c.label}, slot $s")
          assert(same(withFuture(two, potential), one), s"${c.label}, slot $s")
          assertNotEquals(MempoolLedger.of(two), MempoolLedger.of(one), s"${c.label}, slot $s")
      }
    }
  }

  test(
    "a potential update at the parent is adopted by the single tick only, where the first tick precedes the slot of no return"
  ) {
    // up-dijkstra's geometry: the epoch's first slot is before the new epoch's slot of no return and
    // the led slot past it, so the first of two ticks does not solidify the parent's update and
    // EPOCH does not adopt it, where the single tick does: the parameters in force differ
    val c = cases
      .find(c => c.r.name == "peer-relays on up-dijkstra" && c.parentSlot == c.r.boundary - 1)
      .getOrElse(fail("no up-dijkstra parent"))
    assert(c.r.boundary < c.noReturn && c.slots.exists(_ >= c.noReturn), c.label)
    val cur = c.parent.es.ls.utxo.gov.curPParams
    val changed = cur.copy(minFeeA = cur.minFeeA + 1)
    val parent = withFuture(c.parent, FuturePParams.PotentialPParamsUpdate(Some(changed)))
    c.slots.filter(_ >= c.noReturn).foreach { s =>
      assertEquals(applied(c.r.net, parent, s).es.ls.utxo.gov.curPParams, changed, s"slot $s")
      assertEquals(twoTicks(c.r.net, parent, s).es.ls.utxo.gov.curPParams, cur, s"slot $s")
    }
  }

  test(
    "the reward update reads the state only past the range: the RUPD environment does not differ in it"
  ) {
    recorded.foreach { r =>
      val g = r.net.geometry
      // the last slot a parent of the old epoch forecasts, against the first RUPD starts a pulser in
      val last = r.boundary - 1 + r.net.window
      assert(last <= r.boundary + g.randomnessStabilisationWindow.toBigInt, r.name)
    }
  }
