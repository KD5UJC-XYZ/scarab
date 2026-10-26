// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorExtras, MirrorState}
import xyz.kd5ujc.scarab.ledger.{Ptr, ProtocolParams}
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, FromByron}
import xyz.kd5ujc.scarab.ledger.state.*

/** The proof the flat genesis path is no longer needed (#488): on every genesis set of the
  * repository, and on the devnet genesis started in each Shelley-based era, the native start state
  * ([[NodeSetup.fromFiles]], `GenesisStart` and `GenesisMirror`) is the one the frozen flat path
  * made (`legacy.GenesisState` converted by `legacy.Mirroring`, [[NodeSetup.legacyStart]]).
  *
  * A chain that starts in Byron has the same start state both ways, and the same hand-over. A chain
  * whose first era is Shelley-based has the same start state except in four places, where the flat
  * path did not make the reference's state and the native one does; each is checked against the
  * value the reference makes (cardano-ledger f649f975) before the two are compared whole:
  *
  *   - `futurePParams` is `NoPParamsUpdate` (`emptyGovState`, which the Byron to Shelley
  *     translation starts from, `Shelley/API/ByronTranslation.hs:167`); the flat path's mirror
  *     predicted a `PotentialPParamsUpdate` from the slot;
  *   - `nesRu` is empty (`nesRu = SNothing`, `ByronTranslation.hs:109`); the flat path made the
  *     epoch's reward update at the start;
  *   - before Conway, the injected staking keys have the pointer of their position (`indexToPtr`,
  *     `Shelley/Transition.hs:281-286`) and the pointer map holds them; the flat path gave them all
  *     the zero pointer and no entry;
  *   - the Alonzo genesis `extraConfig.costModels` override the current parameters' models only,
  *     and only from Alonzo (`alonzoInjectCostModels`, `Alonzo/Transition.hs:47-66`); the flat path
  *     merged them into every era's current and previous parameters.
  */
class GenesisStartEquivalenceSuite extends FunSuite:
  private val sets = Vector(
    "fixtures/mainnet/genesis",
    "fixtures/preprod/genesis",
    "fixtures/preview/genesis",
    "fixtures/eras/genesis",
    "fixtures/chain-dep/genesis",
    "fixtures/devnet/lsq/genesis",
    "fixtures/leios/testnet/config",
    "fixtures/reference/ntc-wallet-peers-11.1.3/peer-relays/genesis",
    "fixtures/reference/ntc-wallet-peers-11.1.3/reward-history/genesis",
    "fixtures/reference/ntc-wallet-peers-11.1.3/reward-later/genesis",
    "fixtures/reference/plutus-forecast-horizon/conway-past-horizon/genesis",
    "fixtures/reference/plutus-forecast-horizon/alonzo-horizon/genesis",
    "fixtures/reference/plutus-forecast-horizon/babbage-horizon/genesis",
    "fixtures/reference/plutus-forecast-horizon/babbage-known-horizon/genesis"
  )

  /** The devnet genesis of `devnet.py up` started in each Shelley-based era: the hard forks after
    * it are left to the protocol version, which is that era's.
    */
  private val skips = Vector(
    "Shelley" -> 2,
    "Allegra" -> 3,
    "Mary" -> 4,
    "Alonzo" -> 5,
    "Babbage" -> 7,
    "Conway" -> 9
  )

  private def reader(dir: Path, edit: (String, Json) => Json): String => Option[Array[Byte]] =
    name =>
      Option.when(Files.exists(dir.resolve(name))) {
        val bytes = Files.readAllBytes(dir.resolve(name))
        if name.endsWith(".json") || name.endsWith(".yaml") then
          parser
            .parse(new String(bytes, "UTF-8"))
            .toOption
            .fold(bytes)(j =>
              val e = edit(name, j)
              if e == j then bytes else e.noSpaces.getBytes("UTF-8")
            )
        else bytes
      }

  private def startingIn(era: String, major: Int)(name: String, j: Json): Json =
    val later = skips.map(_._1).dropWhile(_ != era).drop(1)
    name match
      case "configuration.yaml" =>
        later.foldLeft(j)((c, e) => c.mapObject(_.remove(s"Test${e}HardForkAtEpoch")))
      case "shelley-genesis.json" =>
        j.deepMerge(
          Json.obj(
            "protocolParams" -> Json.obj(
              "protocolVersion" -> Json.obj(
                "major" -> Json.fromInt(major),
                "minor" -> Json.fromInt(0)
              )
            )
          )
        )
      case _ => j

  private def config(read: String => Option[Array[Byte]]): NodeConfig =
    NodeSetup.ConfigNames
      .collectFirst(Function.unlift(read))
      .fold(NodeConfig.empty)(b =>
        NodeConfig.parse(new String(b, "UTF-8")).fold(e => fail(e.message), identity)
      )

  private def json(read: String => Option[Array[Byte]], name: String): Json =
    read(name)
      .flatMap(b => parser.parse(new String(b, "UTF-8")).toOption)
      .getOrElse(fail(s"no $name"))

  /** The flat path's state with the four places put as the native path has them, after each is
    * checked against the reference's value.
    */
  private def reconciled(
      label: String,
      read: String => Option[Array[Byte]],
      old: MirrorState,
      now: MirrorState
  ): MirrorState =
    (old.ext.ledgerState.state, now.ext.ledgerState.state) match
      case (o: EraLedgerState.InShelleyEra[e], n: EraLedgerState.InShelleyEra[?]) =>
        given era: ShelleyBasedEra[e] = o.era
        assertEquals(n.era: ShelleyBasedEra[?], o.era: ShelleyBasedEra[?], label)
        val nn = n.state.nes.asInstanceOf[NewEpochState[e]]
        val on = o.state.nes
        val c = config(read)
        val conway = Option.when(
          json(read, c.files.shelley).hcursor
            .downField("protocolParams")
            .downField("protocolVersion")
            .downField("major")
            .as[Int]
            .exists(_ >= 9)
        )(json(read, c.files.conway))
        val translated = ProtocolParams
          .translatedFromGenesis(json(read, c.files.shelley), json(read, c.files.alonzo), conway)
          .fold(e => fail(e.message), identity)
        val injected = ProtocolParams
          .injectedCostModels(json(read, c.files.alonzo))
          .fold(e => fail(e.message), identity)
        val alonzoOnwards = era match
          case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary =>
            false
          case ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway => true
        val gov = nn.es.ls.utxo.gov
        // the reference's values
        assertEquals(EraLedger.futurePParams(gov), FuturePParams.NoPParamsUpdate, label)
        assertEquals(nn.ru, None, label)
        assertEquals(EraLedger.prevPParams(gov).costModels, translated.costModels, label)
        assertEquals(
          EraLedger.curPParams(gov).costModels,
          if alonzoOnwards then translated.costModels ++ injected else translated.costModels,
          label
        )
        // the flat path's
        on.ru match
          case Some(PulsingRewUpdate.Complete(u)) => assert(u.rs.isEmpty, label)
          case other                              => fail(s"$label: flat reward update $other")
        val accounts: CertState[e] = (on.es.ls.cert, nn.es.ls.cert) match
          case (oc: ShelleyCertState, nc: ShelleyCertState) =>
            val states = nc.dState.accounts.states
            states.keys.zipWithIndex.foreach { (credential, index) =>
              val ptr = Ptr(BigInt(0), BigInt(index) / 65536, BigInt(index) % 65536)
              assertEquals(states(credential).ptr, ptr, label)
              assertEquals(nc.dState.accounts.ptrs.get(ptr), Some(credential), label)
            }
            assertEquals(nc.dState.accounts.ptrs.size, states.size, label)
            assert(oc.dState.accounts.ptrs.isEmpty, label)
            assertEquals(
              oc.dState.accounts.states.view.mapValues(_.copy(ptr = Ptr(0, 0, 0))).toMap,
              states.view.mapValues(_.copy(ptr = Ptr(0, 0, 0))).toMap,
              label
            )
            oc.copy(dState = oc.dState.copy(accounts = nc.dState.accounts))
              .asInstanceOf[CertState[e]]
          case _ => on.es.ls.cert
        val oldGov = on.es.ls.utxo.gov
        val params = EraLedger.withPParams(
          oldGov,
          EraLedger.curPParams(oldGov).copy(costModels = EraLedger.curPParams(gov).costModels),
          EraLedger.prevPParams(oldGov).copy(costModels = EraLedger.prevPParams(gov).costModels),
          FuturePParams.NoPParamsUpdate
        )
        val fixed = on.copy(
          ru = None,
          es = on.es.copy(ls =
            on.es.ls.copy[e](cert = accounts, utxo = on.es.ls.utxo.copy[e](gov = params))
          )
        )
        old.copy(ext =
          old.ext.copy(ledgerState =
            old.ext.ledgerState.withState(
              EraLedgerState.inEra[e](o.state.copy(nes = fixed))
            )
          )
        )
      case _ => old

  private def check(label: String, read: String => Option[Array[Byte]]): Unit =
    val setup =
      NodeSetup.fromFiles(read).fold(e => fail(s"$label native: ${e.message}"), identity)
    val (old, flat) = NodeSetup
      .legacyStart(config(read), read)
      .fold(e => fail(s"$label flat: ${e.message}"), identity)
    assertEquals(
      setup.handOver,
      setup.handOver.map(_ =>
        FromByron(flat.ledger.genDelegs, flat.ledger.params, setup.globals.maxSupply)
      ),
      label
    )
    setup.mirrorStart.extras match
      case _: MirrorExtras.InByron => assertEquals(setup.mirrorStart, old, label)
      case _: MirrorExtras.InShelley =>
        val fixed = reconciled(label, read, old, setup.mirrorStart)
        val difference = Diff[MirrorState].diff(fixed, setup.mirrorStart)
        assertEquals(difference, None, label)
        assertEquals(setup.mirrorStart, fixed, label)

  sets.foreach(dir =>
    test(s"the native start state of $dir is the flat path's") {
      check(dir, reader(Path.of(dir), (_, j) => j))
    }
  )

  skips.foreach((era, major) =>
    test(s"the native start state of the devnet genesis started in $era is the flat path's") {
      check(
        s"devnet from $era",
        reader(Path.of("fixtures/devnet/lsq/genesis"), startingIn(era, major))
      )
    }
  )
