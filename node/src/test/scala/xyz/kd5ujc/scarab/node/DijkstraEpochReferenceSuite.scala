// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import io.circe.{ACursor, HCursor, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{PoolParams, ProtocolParams, RewardAccount}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayGovState,
  FuturePParams,
  GovStateRecord,
  PParams,
  StoredGov
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  DijkstraEpoch,
  DijkstraNewEpoch,
  EpochGovState,
  NewEpochGovState
}
import xyz.kd5ujc.scarab.ledger.rules.{
  Convert,
  Epoch,
  EraLedger,
  Globals,
  NewEpoch,
  Rupd,
  RupdEnv,
  Sts,
  Tick,
  Translate
}
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.state.{
  Dijkstra,
  EpochInterval,
  EpochNo,
  KeyHash,
  NewEpochState,
  PState,
  PoolBlsKey,
  PoolMetadata,
  PoolParamsBlsKey,
  ShelleyBasedEra,
  SlotNo,
  StakePool,
  VRFVerKeyHash,
  addInterval
}
import xyz.kd5ujc.scarab.node.read.EraRead
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LeiosStateCodecs}

/** Dijkstra's tick across epoch boundaries, against the native TICK of the Leios build
  * (`fixtures/reference/dijkstra-epoch`, oracle `scripts/oracle/dijkstra-epoch`): the two Dijkstra
  * states the build wrote (`fixtures/leios/state`: the devnet's first and the public testnet's at
  * epoch 100), each ticked to the first slot of the next two epochs, and on a second path also once
  * inside each epoch past the start of its reward update, so that the boundary applies it. After
  * every tick the state as the build writes it equals the native one (its SHA-256, and its bytes
  * where the fixture holds them): Dijkstra's EPOCH, its POOLREAP and its SNAP, with the Leios
  * committee the mark seats and the key age the KES constants give, and the reward update. The
  * globals are the node's for each network's configuration, checked against those the native tick
  * ran with. No case is excluded. The node's tick of a Dijkstra state is Dijkstra's tick on the era
  * types, its governance record read before each tick and written back after it
  * (`DijkstraTick.onRecord`); each case also runs with the era's governance state carried from tick
  * to tick ([[EraDijkstraTick]]), written back over the start's records, with the same result.
  *
  * Neither state retires or re-registers a pool, so the oracle also derives states from each with
  * the ledger's own lenses (`cases.json`'s `derivation.changes`): a pool retiring at the next
  * epoch; a pool re-registered with another VRF key hash while a second pool shares its current one
  * (POOLREAP releases one reference per pool, `Dijkstra/Rules/PoolReap.hs`); both; and the sharing
  * pool retiring too. The same changes are made here, the derived state is checked against the
  * oracle's, and it is ticked across the boundary.
  *
  * A re-registration of a pool that holds a voting key gives future parameters that carry it
  * (`stakePoolStateToStakePoolParams`), as on the testnet, where every pool holds one: the state
  * writes them as ten items with the key third (`withStakePoolParamsFlatEncoding`), and at the
  * boundary Dijkstra's POOLREAP keeps the key with the epoch it was registered in
  * (`mkStakePoolState e (currentState ^. spsBlsKeyL)`).
  */
class DijkstraEpochReferenceSuite extends FunSuite:
  private given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra
  private val state = LeiosStateCodecs.newEpochState[Dijkstra]

  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)

  private def globalsOf(dir: Path): Globals =
    val config = right(
      NodeConfig
        .parse(ReferenceBuild.LeiosB004fe0c)(Files.readString(dir.resolve("config.json")), true)
        .left
        .map(_.message)
    )
    val setup = right(
      NodeSetup.assemble(ReferenceBuild.LeiosB004fe0c)(
        config,
        name => Option.when(Files.exists(dir.resolve(name)))(Files.readAllBytes(dir.resolve(name)))
      )
    )
    Globals(setup.globals, setup.geometry)

  private val fixture = Path.of("fixtures/reference/dijkstra-epoch")
  private val cases: Vector[HCursor] =
    right(parser.parse(Files.readString(fixture.resolve("cases.json")))).hcursor
      .downField("cases")
      .values
      .getOrElse(fail("no cases"))
      .map(_.hcursor)
      .toVector

  private def text(c: ACursor, name: String): String = right(c.get[String](name))
  private def number(c: ACursor, name: String): BigInt = right(c.get[BigInt](name))

  private def sha256(bytes: Bytes): String =
    Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toArray)).hex

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  /** The Dijkstra `NewEpochState` of a `state` file the build wrote: the extended state's ledger
    * telescope at its eighth era, `[start, [2, [tip, NewEpochState, transition, bytes]]]`.
    */
  private def input(name: String, sha: String): NewEpochState[Dijkstra] =
    val raw = Bytes.wrap(Files.readAllBytes(Path.of(s"fixtures/leios/state/$name/state")))
    assertEquals(sha256(raw), sha, s"$name: the input is the oracle's")
    val root = right(Cbor.decode(raw, HaskellState.limits))
    val nes = Vector(1, 0, 7, 1, 1, 1).foldLeft(root)((n, i) => items(n)(i))
    right(state.decode(nes).left.map(_.render))

  private def keyHash(hex: String): KeyHash[StakePool] =
    right(KeyHash.fromBytes[StakePool](right(Bytes.fromHex(hex))))
  private def vrf(hex: String): VRFVerKeyHash = right(
    VRFVerKeyHash.fromBytes(right(Bytes.fromHex(hex)))
  )

  /** One of the oracle's changes to the pool state (`derivation.changes`). */
  private def change(p: PState, c: ACursor): PState =
    text(c, "change") match
      case "setActiveVrf" =>
        val id = keyHash(text(c, "pool"))
        p.copy(stakePools =
          p.stakePools.updated(id, p.stakePools(id).copy(vrf = vrf(text(c, "vrf"))))
        )
      case "setFutureParams" =>
        // `stakePoolStateToStakePoolParams Testnet` (`State/StakePool.hs:325-343`, cardano-ledger 9df9f98c) with another VRF
        val id = keyHash(text(c, "pool"))
        val s = p.stakePools(id)
        assertEquals(text(c, "network"), "Testnet")
        val params = PoolParams(
          id.bytes,
          right(Bytes.fromHex(text(c, "vrf"))),
          s.pledge.value,
          s.cost.value,
          s.margin,
          RewardAccount(0, EraRead.credential(s.accountId)),
          s.owners.iterator.map(_.bytes).toSet,
          s.relays,
          s.metadata
        )
        // `sppBlsKey = bksKey <$> spsBlsKey sps`: the pool's own voting key, whose layout is the state's
        val blsKey = s.blsKey match
          case PoolBlsKey.Node11_1 => PoolParamsBlsKey.Node11_1
          case PoolBlsKey.Leios(k) => PoolParamsBlsKey.Leios(k.map(_.key))
        val metadata = right(s.metadata.traverse(PoolMetadata.held))
        p.copy(future =
          p.future.updated(id, right(Convert.stakePoolParams(params, blsKey, metadata)))
        )
      case "populateVRFKeyHashes" =>
        Translate.populateVRFKeyHashes(p)
      case "retire" =>
        p.copy(retiring =
          p.retiring
            .updated(keyHash(text(c, "pool")), right(EpochNo.fromBigInt(number(c, "epoch"))))
        )
      case other => fail(s"unknown change $other")

  /** The future parameters of `nes` that carry a voting key. */
  private def keyedFuture(nes: NewEpochState[Dijkstra]): Set[KeyHash[StakePool]] =
    EraLedger
      .pState(nes.es.ls)
      .future
      .collect { case (id, p) if carriesKey(p.blsKey) => id }
      .toSet

  private def carriesKey(k: PoolParamsBlsKey): Boolean = k match
    case PoolParamsBlsKey.Leios(key) => key.isDefined
    case PoolParamsBlsKey.Node11_1   => false

  /** One of the oracle's changes to the state: of the parameters, or of the pool state. */
  private def changeState(nes: NewEpochState[Dijkstra], c: ACursor): NewEpochState[Dijkstra] =
    val ls = nes.es.ls
    val gov: ConwayGovState[Dijkstra] = ls.utxo.gov
    def withGov(g: ConwayGovState[Dijkstra]) =
      nes.copy(es = nes.es.copy(ls = ls.copy[Dijkstra](utxo = ls.utxo.copy[Dijkstra](gov = g))))
    // a field of the era's parameters set through the node's flat record of them
    def set(pp: PParams[Dijkstra])(f: ProtocolParams => ProtocolParams): PParams[Dijkstra] =
      right(GovStateRecord.dijkstra.pparams(f(GovStateRecord.view(pp))))
    text(c, "change") match
      case "setMaxPledgeLeverage" =>
        // `ppMaxPledgeLeverageL` of the current and the previous parameters
        val l = Some(Rational(number(c, "numerator"), number(c, "denominator")))
        withGov(
          gov.copy(
            cgsCurPParams = set(gov.cgsCurPParams)(_.copy(maxPledgeLeverage = l)),
            cgsPrevPParams = set(gov.cgsPrevPParams)(_.copy(maxPledgeLeverage = l))
          )
        )
      case "setFutureLeiosCommitteeSize" =>
        // `cgsFuturePParamsL .~ DefinitePParamsUpdate (current & ppLeiosCommitteeSizeL .~ n)`
        withGov(
          gov.copy(cgsFuturePParams =
            FuturePParams.DefinitePParamsUpdate(
              set(gov.cgsCurPParams)(_.copy(leiosCommitteeSize = number(c, "leiosCommitteeSize")))
            )
          )
        )
      case _ =>
        nes.copy(es = nes.es.copy(ls = EraLedger.withPState(ls, change(EraLedger.pState(ls), c))))

  /** The oracle's derivation of the input, its changes made (none for an input itself). */
  private def derived(c: HCursor, nes: NewEpochState[Dijkstra]): NewEpochState[Dijkstra] =
    c.downField("derivation")
      .downField("changes")
      .values
      .getOrElse(Vector.empty)
      .foldLeft(nes)((n, j) => changeState(n, j.hcursor))

  /** The state the case starts from: the input, or the oracle's derivation of it, checked against
    * the oracle's where it is written.
    */
  private def startOf(c: HCursor, nes: NewEpochState[Dijkstra]): NewEpochState[Dijkstra] =
    val derivation = c.downField("derivation")
    derivation.get[String]("name").toOption match
      case None => nes
      case Some(name) =>
        val out = derived(c, nes)
        val encoded = right(state.write(out).left.map(_.toString))
        derivation.get[String]("file").toOption.foreach { file =>
          assertEquals(encoded.hex, Bytes.wrap(Files.readAllBytes(fixture.resolve(file))).hex, name)
        }
        assertEquals(sha256(encoded), text(derivation, "stateSha256"), name)
        out

  /** The configuration directory of the network whose Shelley genesis the oracle read. */
  private def configOf(genesis: String): Path = Path.of(genesis).getParent

  test("the corpus holds both states on both paths, and the node's globals are the native tick's") {
    assertEquals(
      cases.map(c => (text(c, "name"), text(c, "path"))).toSet,
      for
        name <- Set("dijkstra-203", "testnet-2168639")
        path <- Set("boundaries", "rewards")
      yield (name, path)
    )
    assertEquals(cases.size, 28)
    cases.foreach { c =>
      val g = globalsOf(configOf(text(c, "genesis")))
      val native = c.downField("globals")
      val at = text(c, "name")
      assertEquals(BigInt(g.geometry.epochLength), number(native, "epochSize"), at)
      assertEquals(g.securityParameter.value.toBigInt, number(native, "securityParameter"), at)
      assertEquals(g.stabilityWindow, number(native, "stabilityWindow"), at)
      assertEquals(
        g.randomnessStabilisationWindow,
        number(native, "randomnessStabilisationWindow"),
        at
      )
      assertEquals(g.ledger.kes.maxEvolutions.toBigInt, number(native, "maxKESEvo"), at)
      assertEquals(g.ledger.kes.slotsPerPeriod.toBigInt, number(native, "slotsPerKESPeriod"), at)
      assertEquals(BigInt(right(g.maxKeyAgeEpochs).toLong), number(native, "maxKeyAge"), at)
    }
  }

  /** The cases whose derived state holds future parameters that carry a voting key. */
  private val keyed: Vector[HCursor] =
    cases.filter(c =>
      keyedFuture(derived(c, input(text(c, "name"), text(c, "stateSha256")))).nonEmpty
    )

  cases.foreach { c =>
    val name = text(c, "name")
    val path = text(c, "path")
    val derivation = c.downField("derivation").get[String]("name").getOrElse("none")
    test(
      s"$name, $derivation, $path: Dijkstra's tick agrees with the native tick after every slot"
    ) {
      val read = input(name, text(c, "stateSha256"))
      val globals = globalsOf(configOf(text(c, "genesis")))
      assertEquals(sha256(state.encodeUnchecked(read)), text(c, "newEpochStateSha256"))
      tickAll(c, globals, startOf(c, read))
    }
  }

  cases.foreach { c =>
    val name = text(c, "name")
    val path = text(c, "path")
    val derivation = c.downField("derivation").get[String]("name").getOrElse("none")
    test(
      s"$name, $derivation, $path: the era's EPOCH agrees with the native tick after every slot"
    ) {
      val read = input(name, text(c, "stateSha256"))
      eraTickAll(c, globalsOf(configOf(text(c, "genesis"))), startOf(c, read))
    }
  }

  test(
    "the testnet's re-registrations carry a voting key, which the boundary keeps with its epoch"
  ) {
    assertEquals(
      keyed.map(c => (text(c, "name"), text(c.downField("derivation"), "name"))).toSet,
      Set("reregister-new-vrf", "both", "both-sharer").map(("testnet-2168639", _))
    )
    assertEquals(keyed.size, 6)
    // the key is registered before the epoch the boundary enters, so a fresh registration there
    // (Shelley's `mkStakePoolState e SNothing`) would differ from keeping it
    keyed.foreach { c =>
      val start = derived(c, input(text(c, "name"), text(c, "stateSha256")))
      val next = start.epoch.addInterval(EpochInterval.one)
      keyedFuture(start).foreach { id =>
        EraLedger.pState(start.es.ls).stakePools(id).blsKey match
          case PoolBlsKey.Leios(Some(k)) => assert(k.registeredIn != next, s"$id: $k")
          case other                     => fail(s"$id holds $other")
      }
    }
  }

  // the node's NEWEPOCH and EPOCH of a Dijkstra state, which its tick does not call, are the era's
  // rules with the governance state taken out and put back, as the tick's
  test("the node's NEWEPOCH and EPOCH of a Dijkstra state are the era's on its governance state") {
    val c = cases.head
    val start = startOf(c, input(text(c, "name"), text(c, "stateSha256")))
    val globals = globalsOf(configOf(text(c, "genesis")))
    val next = right(EpochNo.fromBigInt(start.epoch.toBigInt + 1))
    val record = NewEpochGovState.record
    val gov = record.get(start)
    val newEpoch = right(Sts(DijkstraNewEpoch.sts)(globals, (), NewEpochGovState(start, gov), next))
    assertEquals(
      right(Sts(NewEpoch.sts[Dijkstra])(globals, (), start, next)),
      record.replace(newEpoch.gov)(newEpoch.nes)
    )
    val epoch = right(Sts(DijkstraEpoch.sts)(globals, (), EpochGovState(start.es, gov), next))
    assertEquals(
      right(Sts(Epoch.sts[Dijkstra])(globals, (), start.es, next)),
      StoredGov.recordOf.replace(epoch.gov)(epoch.es)
    )
  }

  // a tick that crosses the boundary to a slot past the start of the new epoch's reward update
  // starts RUPD there: from the blocks and the epoch state the tick began with (`RupdEnv bprev es`
  // of the state before the tick, `Shelley/Rules/Tick.hs:261-277`, cardano-ledger 9df9f98c), not
  // those after the boundary, which give another update; no tick of the build's fixture lands
  // there (each boundary tick is at the epoch's first slot, where RUPD reads no environment)
  test("a boundary tick past the start of the reward update reads the state the tick began with") {
    val c = cases.find(c => text(c, "path") == "boundaries").getOrElse(fail("no boundaries case"))
    val start = startOf(c, input(text(c, "name"), text(c, "stateSha256")))
    val globals = globalsOf(configOf(text(c, "genesis")))
    val next = start.epoch.toBigInt + 1
    val slot = right(
      SlotNo.fromBigInt(globals.firstSlot(next) + globals.randomnessStabilisationWindow + 1)
    )
    val after = right(Sts(Tick.sts[Dijkstra])(globals, (), start, slot).leftMap(_.show))
    assertEquals(after.epoch.toBigInt, next)
    def rupd(env: RupdEnv[Dijkstra]) =
      right(Sts(Rupd.sts[Dijkstra])(globals, env, None, slot).leftMap(_.show))
    val began = rupd(RupdEnv(start.bprev, start.es))
    assert(began.isDefined, "the reward update starts at the slot")
    assertEquals(after.ru, began)
    assertNotEquals(rupd(RupdEnv(after.bprev, after.es)), began, "the boundary's environment")
  }

  /** Each tick of the case from `start` with the era's EPOCH ([[EraDijkstraTick]]), its governance
    * state carried beside the state, the state after it equal to the native one.
    */
  private def eraTickAll(c: HCursor, globals: Globals, start: NewEpochState[Dijkstra]): Unit =
    val ticks = right(c.downField("ticks").values.toRight("no ticks")).toVector
    ticks.foldLeft(EraDijkstraTick.read(start)) { (before, tickJson) =>
      val tick = tickJson.hcursor
      val slot = right(SlotNo.fromBigInt(number(tick, "slot")))
      val at = s"at slot ${number(tick, "slot")}"
      val after = right(EraDijkstraTick.tick(globals, before, slot).leftMap(_.show))
      val encoded = right(state.write(EraDijkstraTick.stored(after)).left.map(_.toString))
      tick.get[String]("file").toOption.foreach { file =>
        val expected = Bytes.wrap(Files.readAllBytes(fixture.resolve(file)))
        assertEquals(encoded.hex, expected.hex, at)
      }
      assertEquals(sha256(encoded), text(tick, "stateSha256"), at)
      after
    }
    ()

  /** Each tick of the case from `start`, the state after it equal to the native one. */
  private def tickAll(c: HCursor, globals: Globals, start: NewEpochState[Dijkstra]): Unit =
    val ticks = right(c.downField("ticks").values.toRight("no ticks")).toVector
    ticks.foldLeft(start) { (before, tickJson) =>
      val tick = tickJson.hcursor
      val slot = right(SlotNo.fromBigInt(number(tick, "slot")))
      val at = s"at slot ${number(tick, "slot")}"
      val after = right(Sts(Tick.sts[Dijkstra])(globals, (), before, slot).leftMap(_.show))
      val encoded = right(state.write(after).left.map(_.toString))
      tick.get[String]("file").toOption.foreach { file =>
        val expected = Bytes.wrap(Files.readAllBytes(fixture.resolve(file)))
        assertEquals(encoded.hex, expected.hex, at)
      }
      assertEquals(sha256(encoded), text(tick, "stateSha256"), at)
      assertEquals(after.epoch.toBigInt, number(tick.downField("facts"), "epoch"), at)
      after
    }
    ()
