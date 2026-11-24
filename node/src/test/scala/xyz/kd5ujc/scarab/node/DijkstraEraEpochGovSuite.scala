// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import io.circe.{ACursor, HCursor, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{Credential as FlatCredential, DRep as FlatDRep, GovActionId}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayGovState,
  DRepPulsingState,
  EraCertState,
  FuturePParams,
  GovActionPurpose,
  GovActionState,
  GovPurposeId,
  Proposals
}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraProposals
import xyz.kd5ujc.scarab.ledger.era.rules.{CERTS, ConwayEpoch}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Sts, Tick}
import scala.util.chaining.*
import xyz.kd5ujc.scarab.ledger.state.{Coin, ConwayCertState, Dijkstra, EpochNo, SlotNo}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.{ExternalInputs, LeiosReferenceStates}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LeiosStateCodecs}

/** Dijkstra's EPOCH on the era types ([[xyz.kd5ujc.scarab.ledger.era.rules.DijkstraEpoch]]) where
  * the governance state holds an action that updates keys Dijkstra adds: through
  * [[EraDijkstraTick]], the governance state changed between ticks, each state read and written by
  * the node's codec ([[DijkstraGovStateBytes]]).
  *
  *   - The Leios build's `ratify` case (`fixtures/reference/dijkstra-epoch/ratification.json`,
  *     oracle `scripts/oracle/dijkstra-epoch`): the testnet's state at epoch 100 with a change of
  *     keys 47 and 48 proposed and voted yes by every committee member, DRep and pool, ticked along
  *     both paths. Every state reached equals the build's, its SHA-256: the fresh pulser of the
  *     first boundary ratifies the change, and the second boundary enacts it, the parameters
  *     changing only on the path that ticks past the slot of no return.
  *   - The Leios build's governance cases (`governance.json`), from the same state:
  *     `remove-branches`, seven actions, of which the second boundary removes the enacted one's
  *     competing sibling with the sibling's descendant and an expired action with its descendant,
  *     keeping two; and `deposit-credit`, the ratify case's action with its deposit returned to an
  *     account delegated to both a pool and a DRep. Each derived state is made here from the
  *     testnet's as the build derives it and equals the build's, byte for byte; along both paths
  *     every state equals the build's, and so do its proposals in order, its roots, its pulser's
  *     enacted and expired actions and the account's DRep's and pool's entries in the pulser's
  *     distributions; at each boundary, the maps `proposalsApplyEnactment` gives, which the era's
  *     EPOCH applies, are those of the build's `GovInfoEvent`. The build's distributions at the
  *     first boundary hold the deposit beyond those of its control, the ratify case's state, whose
  *     entries the era's EPOCH gives too.
  *   - Window G of the public Leios testnet (private, not published: [[LeiosReferenceStates]],
  *     `SCARAB_LEIOS_REFERENCE_STATES`; each skipped by name where the variable is not set, an
  *     environmental skip that #54 tracks, and failed where it is set and a state is not there):
  *     its states at the ends of epochs 70 to 73, each ticked into the next epoch at the slot of
  *     that epoch's first block, against the state at the end of that epoch. The pulser the
  *     boundary starts is the later state's, value for value and byte for byte; so are the
  *     committee, the constitution, the current and previous parameters and the roots; the future
  *     parameters are the later state's once solidified; and the later state's proposals are the
  *     boundary's, whole (the sole one 1,555,188's boundary keeps with its votes, none cast in the
  *     later epoch), then those proposed in its epoch, of which only the epoch is compared. What
  *     else the later state holds is the effect of its epoch's blocks, not compared.
  */
class DijkstraEraEpochGovSuite extends FunSuite with ExternalInputs:
  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def sha256(bytes: Bytes): String =
    Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toArray)).hex

  private val config = Path.of("fixtures/leios/testnet/config")

  private val globals: Globals =
    val node = right("config")(
      NodeConfig
        .parse(ReferenceBuild.Leios)(
          Files.readString(config.resolve("config.json")),
          true
        )
        .left
        .map(_.message)
    )
    val setup = right("setup")(
      NodeSetup.assemble(ReferenceBuild.Leios)(
        node,
        name =>
          Option.when(Files.exists(config.resolve(name)))(Files.readAllBytes(config.resolve(name)))
      )
    )
    Globals(setup.globals, setup.geometry)

  private def file(path: Path): Bytes = Bytes.wrap(Files.readAllBytes(path))

  private def stateOf(path: Path): DijkstraGovStateBytes.Read =
    right(s"$path")(
      DijkstraGovStateBytes.newEpochStateOfFile(file(path)).flatMap(DijkstraGovStateBytes.read)
    )

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  /** The DRep pulser's item of a `NewEpochState`'s bytes (`gov[6]`). */
  private def pulserBytes(nes: Bytes): Bytes =
    val root = right("CBOR")(Cbor.decode(nes, HaskellState.limits))
    Vector(3, 1, 1, 3, 6).foldLeft(root)((n, i) => items(n)(i)).original

  // the ratification case of the Leios build

  private val fixture = Path.of("fixtures/reference/dijkstra-epoch")
  private val record: HCursor =
    right("ratification.json")(
      parser.parse(Files.readString(fixture.resolve("ratification.json")))
    ).hcursor

  private def text(c: ACursor, name: String): String = right(name)(c.get[String](name))
  private def number(c: ACursor, name: String): BigInt = right(name)(c.get[BigInt](name))

  private def govActionId(s: String): GovActionId = s.split('#') match
    case Array(tx, ix) => GovActionId(right(tx)(Bytes.fromHex(tx)), BigInt(ix))
    case _             => fail(s"not an action id: $s")

  test("the ratify case's derived state is the testnet's with the change proposed") {
    val input = stateOf(Path.of("fixtures/leios/state/testnet-2168639/state"))
    val derivation = record.downField("derivation")
    val derivedBytes = file(fixture.resolve(text(derivation, "file")))
    assertEquals(sha256(derivedBytes), text(derivation, "stateSha256"))
    val derived = right("derived")(DijkstraGovStateBytes.read(derivedBytes))
    val change = derivation.downField("changes").downN(0)
    val id = govActionId(text(change, "id"))
    val gas = right("the action")(
      Proposals.proposalsLookupId(id, derived.gov.cgsProposals).toRight("not proposed")
    )
    assertEquals(
      gas.gasProposalProcedure,
      right("its proposal")(
        DijkstraProposals
          .proposal(
            right("CBOR")(Cbor.decode(right("hex")(Bytes.fromHex(text(change, "proposal")))))
          )
          .leftMap(_.toString)
      )
    )
    assertEquals(gas.gasCommitteeVotes.size, number(change, "committeeVotes").toInt)
    assertEquals(gas.gasDRepVotes.size, number(change, "drepVotes").toInt)
    assertEquals(gas.gasStakePoolVotes.size, number(change, "stakePoolVotes").toInt)
    // the governance state is the input's with the action added as GOV adds it
    assertEquals(
      derived.gov,
      input.gov.copy(cgsProposals =
        right("added")(
          Proposals.proposalsAddAction(gas, input.gov.cgsProposals).toRight("not attached")
        )
      )
    )
    // the rest is the input's with the deposit in the pot and the DReps' dormant epochs added
    val cert: ConwayCertState = input.nes.es.ls.cert
    val utxo = input.nes.es.ls.utxo
    val expected = input.nes.copy(es =
      input.nes.es.copy(ls =
        input.nes.es.ls.copy[Dijkstra](
          cert = cert.copy(vState = CERTS.updateDormantDRepExpiry(gas.gasProposedIn, cert.vState)),
          utxo = utxo.copy[Dijkstra](
            deposited = Coin(utxo.deposited.value + gas.gasDeposit.value),
            gov = derived.nes.es.ls.utxo.gov
          )
        )
      )
    )
    assertEquals(derived.nes, expected)
  }

  record.downField("paths").values.getOrElse(Vector.empty).map(_.hcursor).foreach { path =>
    val name = text(path, "path")
    test(s"the ratify case, $name: the era's EPOCH agrees with the native tick after every slot") {
      val derivation = record.downField("derivation")
      val derived =
        right("derived")(
          DijkstraGovStateBytes.read(file(fixture.resolve(text(derivation, "file"))))
        )
      val id = govActionId(text(derivation.downField("changes").downN(0), "id"))
      val ticks = right("ticks")(path.downField("ticks").values.toRight("none")).map(_.hcursor)
      val end = ticks.foldLeft(derived.state) { (before, tick) =>
        val slot = right("slot")(SlotNo.fromBigInt(number(tick, "slot")))
        val at = s"$name at slot ${number(tick, "slot")}"
        val after = right(at)(EraDijkstraTick.tick(globals, before, slot).leftMap(_.toString))
        val written = right(at)(DijkstraGovStateBytes.write(after))
        assertEquals(sha256(written), text(tick, "stateSha256"), at)
        assertEquals(BigInt(written.size), number(tick, "bytes"), at)
        after
      }
      // the change is enacted: the action gone and the root of the parameter changes it
      assert(Proposals.proposalsActions(end.gov.cgsProposals).isEmpty)
      assertEquals(
        Proposals.toPrevGovActionIds(end.gov.cgsProposals.pRoots).grPParamUpdate,
        Some(GovPurposeId[GovActionPurpose.PParamUpdate](id))
      )
    }
  }

  // the node's codec holds the ratify case's action, an update of keys 47 and 48, as the build
  // writes it: the derived state reads and writes back byte for byte, and the node's tick runs on it
  test("the ratify case's state is the node's, byte for byte, and its tick is the era's") {
    val bytes = file(fixture.resolve(text(record.downField("derivation"), "file")))
    val derived = right("derived")(DijkstraGovStateBytes.read(bytes))
    assertEquals(right("written")(DijkstraGovStateBytes.write(derived.state)).hex, bytes.hex)
    val slot = right("slot")(SlotNo.fromBigInt(firstTick))
    val era = right("era")(EraDijkstraTick.tick(globals, derived.state, slot).leftMap(_.toString))
    val node = right("node")(
      Sts(Tick.sts[Dijkstra])(globals, (), derived.nes, slot).leftMap(_.toString)
    )
    assertEquals(node, EraDijkstraTick.stored(era))
  }

  /** The first tick of the ratify case's first path. */
  private def firstTick: BigInt =
    val path = record.downField("paths").downN(0)
    number(path.downField("ticks").downN(0), "slot")

  private val nodeCodec = LeiosStateCodecs.newEpochState[Dijkstra]

  // the governance cases of the Leios build

  private val governance: HCursor =
    right("governance.json")(
      parser.parse(Files.readString(fixture.resolve("governance.json")))
    ).hcursor

  private def actionIds(c: ACursor, name: String): Vector[GovActionId] =
    right(name)(c.get[Vector[String]](name)).map(govActionId)

  private def root(c: ACursor, name: String): Option[GovActionId] =
    right(name)(c.get[Option[String]](name)).map(govActionId)

  private def stake(c: ACursor, name: String): Option[BigInt] =
    right(name)(c.get[Option[BigInt]](name))

  private def hash(c: ACursor, name: String): Bytes = right(name)(Bytes.fromHex(text(c, name)))

  /** A credential the build writes as `{"keyHash": …}` or `{"scriptHash": …}`. */
  private def credential(c: ACursor): FlatCredential =
    c.get[String]("keyHash")
      .map(h => FlatCredential.Key(right(h)(Bytes.fromHex(h))))
      .orElse(
        c.get[String]("scriptHash").map(h => FlatCredential.Script(right(h)(Bytes.fromHex(h))))
      )
      .fold(e => fail(s"not a credential: $e"), identity)

  /** The account a governance case watches: its DRep's and its pool's keys in the pulser's
    * distributions.
    */
  private final case class Watched(drep: FlatDRep, pool: Bytes)

  private def watched(record: ACursor): Watched =
    val delegated = record.downField("delegated")
    Watched(FlatDRep.Registered(credential(delegated.downField("drep"))), hash(delegated, "pool"))

  private lazy val testnet = stateOf(Path.of("fixtures/leios/state/testnet-2168639/state"))

  /** The votes of every hot committee credential, DRep and pool: those of the ratify case's action.
    */
  private lazy val everyonesVotes: GovActionState[Dijkstra] =
    val derived = right("derived")(
      DijkstraGovStateBytes.read(
        file(fixture.resolve(text(record.downField("derivation"), "file")))
      )
    )
    right("the ratify action")(
      Proposals
        .proposalsLookupId(
          govActionId(text(record.downField("derivation").downField("changes").downN(0), "id")),
          derived.gov.cgsProposals
        )
        .toRight("not proposed")
    )

  /** A governance case's derived state, made from the testnet's state as `Main.hs` derives it: each
    * action of its changes with its proposal the era reader's of the build's bytes, voted yes by
    * every voter where the build counts votes and by none otherwise, added as GOV adds it, its
    * deposit in the pot and the DReps' dormant epochs added; and the original proposal item of each
    * action.
    */
  private def derivedOf(derivation: ACursor): (EraDijkstraTick.State, Map[GovActionId, Node]) =
    val changes =
      right("changes")(derivation.downField("changes").values.toRight("none")).map(_.hcursor)
    val actions = changes.map { change =>
      val item = right("CBOR")(Cbor.decode(hash(change, "proposal")))
      val counts = Vector("committeeVotes", "drepVotes", "stakePoolVotes").map(number(change, _))
      val votes =
        if counts.forall(_ > 0) then
          assertEquals(
            counts,
            Vector(
              everyonesVotes.gasCommitteeVotes.size,
              everyonesVotes.gasDRepVotes.size,
              everyonesVotes.gasStakePoolVotes.size
            ).map(BigInt(_))
          )
          everyonesVotes
        else
          assertEquals(counts, Vector.fill(3)(BigInt(0)))
          everyonesVotes.copy(
            gasCommitteeVotes = everyonesVotes.gasCommitteeVotes.empty,
            gasDRepVotes = everyonesVotes.gasDRepVotes.empty,
            gasStakePoolVotes = everyonesVotes.gasStakePoolVotes.empty
          )
      val gas = votes.copy(
        gasId = govActionId(text(change, "id")),
        gasProposalProcedure =
          right("its proposal")(DijkstraProposals.proposal(item).leftMap(_.toString)),
        gasProposedIn = right("proposedIn")(EpochNo.fromBigInt(number(change, "proposedIn"))),
        gasExpiresAfter = right("expiresAfter")(EpochNo.fromBigInt(number(change, "expiresAfter")))
      )
      (gas, item)
    }
    val gov = actions.foldLeft(testnet.gov) { case (g, (gas, _)) =>
      g.copy(cgsProposals =
        right("added")(Proposals.proposalsAddAction(gas, g.cgsProposals).toRight("not attached"))
      )
    }
    val cert: ConwayCertState = testnet.nes.es.ls.cert
    val utxo = testnet.nes.es.ls.utxo
    val nes = testnet.nes.copy(es =
      testnet.nes.es.copy(ls =
        testnet.nes.es.ls.copy[Dijkstra](
          cert = cert.copy(vState = actions.foldLeft(cert.vState) { case (v, (gas, _)) =>
            CERTS.updateDormantDRepExpiry(gas.gasProposedIn, v)
          }),
          utxo = utxo.copy[Dijkstra](deposited =
            Coin(utxo.deposited.value + actions.map(_._1.gasDeposit.value).sum)
          )
        )
      )
    )
    (EraDijkstraTick.State(nes, gov), actions.map((gas, item) => gas.gasId -> item).toMap)

  /** What the build's governance of a state says, against the era's state `s`: the proposals in
    * order, the actions the pulser's ratification enacts and those it finds expired, the roots, and
    * the watched DRep's and pool's entries in the pulser's distributions.
    */
  private def assertGovernance(
      s: EraDijkstraTick.State,
      gov: ACursor,
      w: Watched,
      at: String
  ): Unit =
    assertEquals(
      Proposals.proposalsActions(s.gov.cgsProposals).map(_.gasId),
      actionIds(gov, "proposals"),
      s"$at: the proposals"
    )
    val roots = Proposals.toPrevGovActionIds(s.gov.cgsProposals.pRoots)
    val expectedRoots = gov.downField("roots")
    assertEquals(
      (
        roots.grPParamUpdate.map(_.unGovPurposeId),
        roots.grHardFork.map(_.unGovPurposeId),
        roots.grCommittee.map(_.unGovPurposeId),
        roots.grConstitution.map(_.unGovPurposeId)
      ),
      (
        root(expectedRoots, "pparamUpdate"),
        root(expectedRoots, "hardFork"),
        root(expectedRoots, "committee"),
        root(expectedRoots, "constitution")
      ),
      s"$at: the roots"
    )
    s.gov.cgsDRepPulsingState match
      case DRepPulsingState.DRComplete(snapshot, ratify) =>
        assertEquals(ratify.rsEnacted.map(_.gasId), actionIds(gov, "enacted"), s"$at: enacted")
        assertEquals(ratify.rsExpired.toSet, actionIds(gov, "expired").toSet, s"$at: expired")
        assertEquals(snapshot.psPoolDistr.get(w.pool), stake(gov, "poolStake"), s"$at: the pool's")
        assertEquals(snapshot.psDRepDistr.get(w.drep), stake(gov, "drepStake"), s"$at: the DRep's")

  /** What EPOCH removes at the boundary from `before`, against the build's `GovInfoEvent`: the maps
    * `proposalsApplyEnactment` gives of the ratification `before` 's pulser reached, which the
    * era's EPOCH applies, their ids the event's and each action the one `before` 's proposals hold
    * (as the event's are, `before` being the build's state byte for byte); and the deposits
    * `returnProposalDeposits` leaves unclaimed.
    */
  private def assertRemovals(before: EraDijkstraTick.State, removals: ACursor, at: String): Unit =
    val ratify = DRepPulsingState.extractDRepPulsingState(before.gov.cgsDRepPulsingState)
    val (_, enacted, removed, expired) =
      Proposals.proposalsApplyEnactment(
        ratify.rsEnacted,
        ratify.rsExpired,
        before.gov.cgsProposals
      )
    assertEquals(enacted.keySet.toSet, actionIds(removals, "enacted").toSet, s"$at: enacted")
    assertEquals(
      removed.keySet.toSet,
      actionIds(removals, "removedDueToEnactment").toSet,
      s"$at: removed due to enactment"
    )
    assertEquals(expired.keySet.toSet, actionIds(removals, "expired").toSet, s"$at: expired")
    // each removed action as the proposals before the boundary hold it
    (enacted ++ removed ++ expired).foreach { (id, gas) =>
      assertEquals(Some(gas), Proposals.proposalsLookupId(id, before.gov.cgsProposals), s"$at: $id")
    }
    val certs = EraCertState.dijkstra
    val (_, unclaimed) = right(at)(
      ConwayEpoch
        .returnProposalDeposits[Dijkstra](
          (removed ++ enacted ++ expired).values.toVector,
          certs.certDStateL.get(before.nes.es.ls.cert).accounts
        )(using certs.accountsOf)
        .leftMap(_.toString)
    )
    val expectedUnclaimed = removals.downField("unclaimed")
    assertEquals(
      unclaimed.map((id, c) => id -> c.value),
      SortedMap.from(
        expectedUnclaimed.keys
          .getOrElse(Nil)
          .map(k => govActionId(k) -> number(expectedUnclaimed, k))
      ),
      s"$at: unclaimed"
    )

  Vector("remove-branches", "deposit-credit").foreach { name =>
    val governanceCase = governance.downField(name)
    val derivation = governanceCase.downField("derivation")

    test(s"the $name case's derived state is the testnet's with its actions proposed") {
      val (derived, _) = derivedOf(derivation)
      val written = right("written")(DijkstraGovStateBytes.write(derived))
      assertEquals(sha256(written), text(derivation, "stateSha256"))
      assertEquals(BigInt(written.size), number(derivation, "bytes"))
      assertGovernance(derived, derivation.downField("governance"), watched(governanceCase), name)
    }

    governanceCase.downField("paths").values.getOrElse(Vector.empty).map(_.hcursor).foreach {
      path =>
        val pathName = text(path, "path")
        test(
          s"the $name case, $pathName: the era's EPOCH agrees with the build's after every slot"
        ) {
          val (derived, _) = derivedOf(derivation)
          val w = watched(governanceCase)
          val ticks = right("ticks")(path.downField("ticks").values.toRight("none")).map(_.hcursor)
          ticks.foldLeft(derived) { (before, tick) =>
            val slot = right("slot")(SlotNo.fromBigInt(number(tick, "slot")))
            val at = s"$name, $pathName at slot ${number(tick, "slot")}"
            val after = right(at)(EraDijkstraTick.tick(globals, before, slot).leftMap(_.toString))
            assertGovernance(after, tick.downField("governance"), w, at)
            val removals = tick.downField("removals")
            assertEquals(
              after.nes.epoch != before.nes.epoch,
              right(at)(tick.get[Boolean]("crossedBoundary")),
              s"$at: the boundary"
            )
            if after.nes.epoch != before.nes.epoch then assertRemovals(before, removals, at)
            else assert(removals.focus.exists(_.isNull), s"$at: no removals inside an epoch")
            val written = right(at)(DijkstraGovStateBytes.write(after))
            assertEquals(sha256(written), text(tick, "stateSha256"), at)
            assertEquals(BigInt(written.size), number(tick, "bytes"), at)
            after
          }
        }
    }
  }

  test("the deposit-credit case's control: the ratify case's distributions at the first boundary") {
    val control = governance.downField("deposit-credit").downField("control")
    val derived = right("derived")(
      DijkstraGovStateBytes.read(
        file(fixture.resolve(text(record.downField("derivation"), "file")))
      )
    )
    val slot = right("slot")(SlotNo.fromBigInt(number(control, "slot")))
    val after = right("ticked")(
      EraDijkstraTick.tick(globals, derived.state, slot).leftMap(_.toString)
    )
    assertGovernance(
      after,
      control.downField("governance"),
      watched(governance.downField("deposit-credit")),
      "the control"
    )
  }

  // window G of the public Leios testnet, private

  /** Window G's proposal 2, the change of keys 47 and 48 the pulser started at the end of epoch 71
    * ratifies and the end of epoch 72 enacts.
    */
  private val proposal2 =
    "e57ccb64f3777d09cb0a86217d0c02577904f00d4873af705f7bdd585e896821#0"

  /** The state at the end of an epoch of window G, the slot of the next epoch's first block
    * (`MANIFEST.md`), the state at the end of that epoch, the actions the boundary's pulser
    * ratifies, and the action the boundary enacts.
    */
  private val pairs: Vector[(Int, Long, Int, Vector[String], Option[String])] = Vector(
    (1533582, 1533605L, 1555188, Vector.empty, None),
    (1555188, 1555210L, 1576750, Vector(proposal2), None),
    (1576750, 1576812L, 1598375, Vector.empty, Some(proposal2))
  )

  pairs.foreach { (from, slot, to, ratified, enacted) =>
    test(s"window G: $from ticked into the next epoch has the boundary's governance of $to") {
      val fromCapture = LeiosReferenceStates.Capture(LeiosReferenceStates.Window.G, from.toLong)
      val toCapture = LeiosReferenceStates.Capture(LeiosReferenceStates.Window.G, to.toLong)
      withInput(LeiosReferenceStates.input(fromCapture, toCapture)) { root =>
        val fromPath = fromCapture.state(root)
        val toPath = toCapture.state(root)
        val before = stateOf(fromPath)
        val later = stateOf(toPath)
        val after = right(s"$from ticked")(
          EraDijkstraTick
            .tick(globals, before.state, right("slot")(SlotNo.fromBigInt(BigInt(slot))))
            .leftMap(_.toString)
        )
        assertEquals(after.nes.epoch, later.nes.epoch)
        val g: ConwayGovState[Dijkstra] = after.gov
        val l: ConwayGovState[Dijkstra] = later.gov
        // what the boundary sets and the epoch's blocks do not change
        assertEquals(g.cgsDRepPulsingState, l.cgsDRepPulsingState, "the pulser")
        assertEquals(g.cgsCommittee, l.cgsCommittee, "the committee")
        assertEquals(g.cgsConstitution, l.cgsConstitution, "the constitution")
        assertEquals(g.cgsCurPParams, l.cgsCurPParams, "the current parameters")
        assertEquals(g.cgsPrevPParams, l.cgsPrevPParams, "the previous parameters")
        assertEquals(
          Proposals.toPrevGovActionIds(g.cgsProposals.pRoots),
          Proposals.toPrevGovActionIds(l.cgsProposals.pRoots),
          "the roots"
        )
        // the later state is past its epoch's slot of no return, where TICK solidified the future
        assertEquals(FuturePParams.solidifyFuturePParams(g.cgsFuturePParams), l.cgsFuturePParams)
        // the later state's proposals: the boundary's, then those proposed in its epoch
        val boundary = Proposals.proposalsActions(g.cgsProposals)
        val (kept, proposed) =
          Proposals.proposalsActions(l.cgsProposals).splitAt(boundary.size)
        // whole, their votes too: no vote is cast on them in the later state's epoch
        assertEquals(kept, boundary, "the boundary's proposals, whole")
        assert(proposed.forall(_.gasProposedIn == later.nes.epoch), "proposed in its epoch")
        // the pulser written is the later state's, byte for byte
        val written = right("written")(DijkstraGovStateBytes.write(after))
        assertEquals(
          pulserBytes(written).hex,
          pulserBytes(right("later")(DijkstraGovStateBytes.newEpochStateOfFile(file(toPath)))).hex
        )
        // the node's codec reads every one of the three states, proposal 2's update of keys 47 and
        // 48 among them, and the node's tick gives the same bytes
        val nodeRead = right("CBOR")(
          Cbor.decode(
            right("from")(DijkstraGovStateBytes.newEpochStateOfFile(file(fromPath))),
            HaskellState.limits
          )
        ).pipe(nodeCodec.decode)
        assert(nodeRead.isRight, s"read by the node's codec: $nodeRead")
        nodeRead.toOption.foreach { nes =>
          val ticked = right("the node's tick")(
            Sts(Tick.sts[Dijkstra])(
              globals,
              (),
              nes,
              right("slot")(SlotNo.fromBigInt(BigInt(slot)))
            ).leftMap(_.toString)
          )
          assertEquals(
            right("the node's write")(nodeCodec.write(ticked).left.map(_.toString)).hex,
            written.hex
          )
        }
        // what the boundary's pulser ratifies, and what the boundary enacts
        g.cgsDRepPulsingState match
          case DRepPulsingState.DRComplete(_, ratify) =>
            assertEquals(ratify.rsEnacted.map(_.gasId), ratified.map(govActionId))
        enacted.map(govActionId).foreach { id =>
          assert(
            Proposals.proposalsLookupId(id, before.gov.cgsProposals).isDefined,
            "proposed before"
          )
          assert(Proposals.proposalsLookupId(id, g.cgsProposals).isEmpty, "gone after")
          assertEquals(
            Proposals.toPrevGovActionIds(g.cgsProposals.pRoots).grPParamUpdate,
            Some(GovPurposeId[GovActionPurpose.PParamUpdate](id))
          )
          assertNotEquals(g.cgsCurPParams, before.gov.cgsCurPParams, "the parameters changed")
        }
      }
    }
  }
