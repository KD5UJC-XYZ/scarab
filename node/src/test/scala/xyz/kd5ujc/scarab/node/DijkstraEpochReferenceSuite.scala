// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import io.circe.{ACursor, HCursor, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{PoolParams, RewardAccount}
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, Globals, HardFork, Sts, Tick}
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.state.{
  ConwayGovState,
  Dijkstra,
  FuturePParams,
  EpochNo,
  KeyHash,
  NewEpochState,
  PState,
  PoolBlsKey,
  ShelleyBasedEra,
  SlotNo,
  StakePool,
  VRFVerKeyHash
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
  * ran with. No case is excluded.
  *
  * Neither state retires or re-registers a pool, so the oracle also derives states from each with
  * the ledger's own lenses (`cases.json`'s `derivation.changes`): a pool retiring at the next
  * epoch; a pool re-registered with another VRF key hash while a second pool shares its current one
  * (POOLREAP releases one reference per pool, `Dijkstra/Rules/PoolReap.hs`); both; and the sharing
  * pool retiring too. The same changes are made here, the derived state is checked against the
  * oracle's, and it is ticked across the boundary. A re-registration of a pool that holds a voting
  * key gives future parameters that carry it (`stakePoolStateToStakePoolParams`), which a pool's
  * parameters do not hold here yet (`DijkstraNotYet.PoolParamsBlsKey`, #826 comment 6967015840, #85
  * item 38): on the testnet, where every pool holds one, those derivations are checked to be such,
  * and not compared.
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
        p.copy(future = p.future.updated(id, params))
      case "populateVRFKeyHashes" => HardFork.populateVRFKeyHashes(p)
      case "retire" =>
        p.copy(retiring =
          p.retiring
            .updated(keyHash(text(c, "pool")), right(EpochNo.fromBigInt(number(c, "epoch"))))
        )
      case other => fail(s"unknown change $other")

  /** Whether a derivation gives future parameters to a pool that holds a voting key. */
  private def keyedFuture(c: HCursor, nes: NewEpochState[Dijkstra]): Boolean =
    val pools = EraLedger.pState(nes.es.ls).stakePools
    c.downField("derivation")
      .downField("changes")
      .values
      .getOrElse(Vector.empty)
      .map(_.hcursor)
      .exists(ch =>
        text(ch, "change") == "setFutureParams" &&
          (pools(keyHash(text(ch, "pool"))).blsKey match
            case PoolBlsKey.Leios(Some(_))                    => true
            case PoolBlsKey.Leios(None) | PoolBlsKey.Node11_1 => false)
      )

  /** One of the oracle's changes to the state: of the parameters, or of the pool state. */
  private def changeState(nes: NewEpochState[Dijkstra], c: ACursor): NewEpochState[Dijkstra] =
    val ls = nes.es.ls
    val gov: ConwayGovState = ls.utxo.gov
    def withGov(g: ConwayGovState) =
      nes.copy(es = nes.es.copy(ls = ls.copy[Dijkstra](utxo = ls.utxo.copy[Dijkstra](gov = g))))
    text(c, "change") match
      case "setMaxPledgeLeverage" =>
        // `ppMaxPledgeLeverageL` of the current and the previous parameters
        val l = Some(Rational(number(c, "numerator"), number(c, "denominator")))
        withGov(
          gov.copy(
            curPParams = gov.curPParams.copy(maxPledgeLeverage = l),
            prevPParams = gov.prevPParams.copy(maxPledgeLeverage = l)
          )
        )
      case "setFutureLeiosCommitteeSize" =>
        // `cgsFuturePParamsL .~ DefinitePParamsUpdate (current & ppLeiosCommitteeSizeL .~ n)`
        withGov(
          gov.copy(futurePParams =
            FuturePParams.DefinitePParamsUpdate(
              gov.curPParams.copy(leiosCommitteeSize = number(c, "leiosCommitteeSize"))
            )
          )
        )
      case _ =>
        nes.copy(es = nes.es.copy(ls = EraLedger.withPState(ls, change(EraLedger.pState(ls), c))))

  /** The state the case starts from: the input, or the oracle's derivation of it. */
  private def startOf(c: HCursor, nes: NewEpochState[Dijkstra]): NewEpochState[Dijkstra] =
    val derivation = c.downField("derivation")
    derivation.get[String]("name").toOption match
      case None => nes
      case Some(name) =>
        val changes = right(derivation.downField("changes").values.toRight("no changes")).toVector
        val out = changes.foldLeft(nes)((n, j) => changeState(n, j.hcursor))
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

  cases.foreach { c =>
    val name = text(c, "name")
    val path = text(c, "path")
    val derivation = c.downField("derivation").get[String]("name").getOrElse("none")
    val read = input(name, text(c, "stateSha256"))
    if keyedFuture(c, read) then
      // the gap above: the reference's derived state holds future parameters that carry the
      // pool's voting key (ten items), which a pool's parameters do not hold here yet
      test(s"$name, $derivation, $path: not compared, its re-registration carries a voting key") {
        assertEquals(name, "testnet-2168639")
        assert(derivation.contains("reregister") || derivation.startsWith("both"), derivation)
      }
    else
      test(
        s"$name, $derivation, $path: Dijkstra's tick agrees with the native tick after every slot"
      ) {
        val globals = globalsOf(configOf(text(c, "genesis")))
        assertEquals(sha256(state.encodeUnchecked(read)), text(c, "newEpochStateSha256"))
        tickAll(c, globals, startOf(c, read))
      }
  }

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
