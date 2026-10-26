// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorExtras, MirrorProvenance}
import xyz.kd5ujc.scarab.ledger.{ConfigProblem, ProtocolParams, Ptr}
import xyz.kd5ujc.scarab.ledger.rules.{CertLens, GenesisStartError}
import xyz.kd5ujc.scarab.ledger.state.*

/** The start state of a chain, made from its genesis files as the reference makes it
  * (`GenesisStart`, `GenesisMirror`): the devnet genesis of `devnet.py up` starts in Conway with
  * injected funds, pools, staking keys, DReps and vote delegations.
  */
class GenesisStartSuite extends FunSuite:
  private val devnet = Path.of("fixtures/devnet/lsq/genesis")

  private def files(edit: (String, Json) => Json): String => Option[Array[Byte]] =
    name =>
      Option.when(Files.exists(devnet.resolve(name))) {
        val bytes = Files.readAllBytes(devnet.resolve(name))
        parser
          .parse(new String(bytes, "UTF-8"))
          .toOption
          .fold(bytes)(j =>
            val e = edit(name, j)
            if e == j then bytes else e.noSpaces.getBytes("UTF-8")
          )
      }

  private def json(name: String): Json =
    parser
      .parse(new String(Files.readAllBytes(devnet.resolve(name)), "UTF-8"))
      .fold(e => fail(e.message), identity)

  private def conwayOf(setup: NodeSetup): NewEpochState[Conway] =
    setup.mirrorStart.ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state) => state.nes
      case other => fail(s"not a Conway start: $other")

  private val unchanged = files((_, j) => j)
  private val setup = NodeSetup.fromFiles(unchanged).fold(e => fail(e.message), identity)
  private val nes = conwayOf(setup)

  private def hash[R <: KeyRole](hex: String): KeyHash[R] =
    Bytes
      .fromHex(hex)
      .toOption
      .flatMap(b => KeyHash.fromBytes[R](b).toOption)
      .getOrElse(fail(s"not a hash: $hex"))

  private def staking(hex: String): Credential[Staking] = Credential.KeyHashObj(hash[Staking](hex))

  /** The governance state of the start is `emptyGovState` 's (cardano-ledger f649f975
    * `Shelley/Governance.hs:185-192`, `State/Governance.hs:104-105`): no update until the first
    * boundary, which nothing at the origin may predict. The reward update is empty
    * (`ByronTranslation.hs:109`).
    */
  test("the start holds no parameter update and no reward update until the first boundary") {
    assertEquals(nes.es.ls.utxo.gov.futurePParams, FuturePParams.NoPParamsUpdate)
    assertEquals(nes.ru, None)
    assertEquals(setup.mirrorStart.provenance, MirrorProvenance.complete)
    assert(setup.mirrorStart.extras.isInstanceOf[MirrorExtras.InShelley])
  }

  test("the initial funds join the Byron genesis UTxO and the reserves give them up") {
    val funds = json("shelley-genesis.json").hcursor
      .downField("extraConfig")
      .downField("initialFunds")
      .downField("data")
      .as[Map[String, BigInt]]
      .fold(e => fail(e.message), identity)
    val byron = json("byron-genesis.json").hcursor
      .downField("nonAvvmBalances")
      .as[Map[String, String]]
      .fold(e => fail(e.message), identity)
    val utxo = nes.es.ls.utxo.utxo.utxo
    assertEquals(utxo.size, funds.size + byron.size)
    assertEquals(utxo.coin, funds.values.sum + byron.values.map(BigInt(_)).sum)
    assertEquals(nes.es.chainAccount.reserves.value, setup.globals.maxSupply - utxo.coin)
    assertEquals(nes.es.chainAccount.treasury.value, BigInt(0))
    assertEquals(nes.es.ls.utxo.deposited.value, BigInt(0))
  }

  test("each staking key is registered with the key deposit and delegates to its pool") {
    val stake = json("shelley-genesis.json").hcursor
      .downField("extraConfig")
      .downField("stakeCredentials")
      .downField("data")
      .as[Map[String, String]]
      .fold(e => fail(e.message), identity)
    val accounts = CertLens.accounts.get(nes.es.ls.cert)
    val pools = CertLens.pools.get(nes.es.ls.cert)
    assertEquals(accounts.keySet, stake.keySet.map(staking))
    stake.foreach { (key, pool) =>
      val account = accounts(staking(key))
      assertEquals(account.deposit.toBigInt, BigInt(400000))
      assertEquals(account.pool, Some(hash[StakePool](pool)))
      assert(pools(hash[StakePool](pool)).delegators.contains(staking(key)))
    }
    assert(pools.values.forall(_.deposit.toBigInt == 0))
    assert(nes.es.ls.cert.pState.vrfKeyHashes.isEmpty)
  }

  test("the genesis DReps hold the vote delegations, and the delegators' accounts name them") {
    val dreps = CertLens.dreps.get(nes.es.ls.cert)
    val accounts = CertLens.accounts.get(nes.es.ls.cert)
    assertEquals(dreps.size, 3)
    dreps.foreach { (drep, state) =>
      assertEquals(state.deposit.toBigInt, BigInt(1000000))
      assertEquals(state.expiry, EpochNo.fromUnsigned(1000L))
      assertEquals(state.delegators.size, 1)
      state.delegators.foreach(d =>
        assertEquals(
          accounts(d).drep.flatMap {
            case DRep.DRepKeyHash(h) =>
              Some(Credential.KeyHashObj[DRepRole](h): Credential[DRepRole])
            case _ => None
          },
          Some(drep)
        )
      )
    }
  }

  test("the mark snapshot and the pool distribution are taken from the injected stake") {
    val mark = nes.es.snapshots.mark
    assertEquals(nes.pd, PoolDistr.of(mark))
    assertEquals(mark.pools.keySet, CertLens.pools.get(nes.es.ls.cert).keySet)
    assert(mark.activeStake.entries.nonEmpty)
    assertEquals(nes.es.snapshots.set, SnapShot.empty)
    assertEquals(nes.es.snapshots.go, SnapShot.empty)
  }

  test("the injected cost models override the current parameters' only") {
    val injected = ProtocolParams
      .injectedCostModels(json("alonzo-genesis.json"))
      .fold(e => fail(e.message), identity)
    assert(injected.contains(1))
    assertEquals(nes.es.ls.utxo.gov.curPParams.costModels.get(1), injected.get(1))
    assertEquals(nes.es.ls.utxo.gov.prevPParams.costModels.get(1), None)
    assertEquals(nes.es.ls.utxo.gov.drepPulsing.ratify.enact.params.costModels.get(1), None)
  }

  /** The devnet genesis started in Shelley: the staking injection of the eras before Conway. */
  test("before Conway each injected staking key has the pointer of its position") {
    val shelley = NodeSetup
      .fromFiles(files { (name, j) =>
        name match
          case "configuration.yaml" =>
            Vector("Allegra", "Mary", "Alonzo", "Babbage", "Conway")
              .foldLeft(j)((c, e) => c.mapObject(_.remove(s"Test${e}HardForkAtEpoch")))
          case "shelley-genesis.json" =>
            j.deepMerge(
              Json.obj(
                "protocolParams" -> Json.obj(
                  "protocolVersion" -> Json
                    .obj("major" -> Json.fromInt(2), "minor" -> Json.fromInt(0))
                )
              )
            )
          case _ => j
      })
      .fold(e => fail(e.message), identity)
    shelley.mirrorStart.ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SShelley, state) =>
        val accounts = state.nes.es.ls.cert.dState.accounts
        assertEquals(accounts.states.size, 3)
        accounts.states.keys.zipWithIndex.foreach { (credential, index) =>
          assertEquals(accounts.states(credential).ptr, Ptr(0, 0, index))
          assertEquals(accounts.ptrs.get(Ptr(0, 0, index)), Some(credential))
        }
        assertEquals(state.nes.es.ls.utxo.gov.curPParams.costModels.get(1), None)
      case other => fail(s"not a Shelley start: $other")
  }

  private def refusal(edit: (String, Json) => Json): ConfigProblem =
    NodeSetup.fromFiles(files(edit)).fold(_.problem, s => fail(s"accepted: $s"))

  test("a staking key delegated to a pool the genesis does not register is refused") {
    val unknown = "ab" * 28
    val problem = refusal { (name, j) =>
      if name != "shelley-genesis.json" then j
      else
        j.deepMerge(
          Json.obj(
            "extraConfig" -> Json.obj(
              "stakeCredentials" -> Json.obj(
                "data" -> Json.obj("cd" * 28 -> Json.fromString(unknown))
              )
            )
          )
        )
    }
    problem match
      case ConfigProblem.GenesisStart(GenesisStartError.DelegationToUnregisteredPool(_, pool)) =>
        assertEquals(pool, hash[StakePool](unknown))
      case other => fail(s"$other")
  }

  test("a chain on mainnet does not start in a Shelley-based era") {
    val problem = refusal((name, j) =>
      if name == "shelley-genesis.json" then
        j.deepMerge(Json.obj("networkId" -> Json.fromString("Mainnet")))
      else j
    )
    assertEquals(
      problem,
      ConfigProblem.GenesisStart(GenesisStartError.InjectionNotAllowedOnMainnet)
    )
  }

  test("an injection both in extraConfig and in the legacy field, or from a file, is refused") {
    val both = refusal((name, j) =>
      if name == "shelley-genesis.json" then
        j.deepMerge(Json.obj("initialFunds" -> Json.obj("00" * 29 -> Json.fromInt(1))))
      else j
    )
    assertEquals(both, ConfigProblem.DuplicateInjection("initialFunds"))
    val file = refusal((name, j) =>
      if name == "conway-genesis.json" then
        j.deepMerge(
          Json.obj(
            "extraConfig" -> Json.obj(
              "delegs" -> Json.obj("file" -> Json.arr(Json.fromString("d.json")))
            )
          )
        )
      else j
    )
    assertEquals(file, ConfigProblem.InjectionFile("delegs"))
  }

  test("a vote delegation of a key with no account adds the DRep's delegator only") {
    val loner = "ef" * 28
    val drep = "6902ee7cec9b7848293e11580953e11dce23f36289243163b476035f"
    val delegated = NodeSetup
      .fromFiles(files { (name, j) =>
        if name != "conway-genesis.json" then j
        else
          j.deepMerge(
            Json.obj(
              "extraConfig" -> Json.obj(
                "delegs" -> Json.obj(
                  "data" -> Json.obj(
                    s"keyHash-$loner" -> Json.obj("dRep" -> Json.fromString(s"drep-keyHash-$drep"))
                  )
                )
              )
            )
          )
      })
      .fold(e => fail(e.message), identity)
    val state = conwayOf(delegated)
    assert(!CertLens.accounts.get(state.es.ls.cert).contains(staking(loner)))
    val drepState = CertLens.dreps.get(state.es.ls.cert)(
      Credential.KeyHashObj[DRepRole](hash[DRepRole](drep))
    )
    assert(drepState.delegators.contains(staking(loner)))
  }
