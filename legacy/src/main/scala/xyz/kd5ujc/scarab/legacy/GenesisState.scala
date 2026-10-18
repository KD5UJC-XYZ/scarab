// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import cats.syntax.all.*
import io.circe.Json
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.GenesisState.{
  Files,
  credential,
  drep,
  get,
  hex28,
  injection,
  output,
  pool
}
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronOut}

/** The flat ledger state at the first Shelley-based slot of a chain: from the Shelley
  * `initialFunds` and staking injections, the Byron non-AVVM balances and the Conway genesis DReps
  * and vote delegations. FROZEN with the flat state: the node's start state is this, put through
  * [[LedgerMirror.to]] once, at the start (the genesis start is the one use of the flat state that
  * is not a reader of an old file; design `ledger-mirror-step2.md` J2.9, gaps).
  */
object GenesisState:
  /** The ledger state at the start of the first Shelley-based epoch. Its UTxO is the Shelley
    * `initialFunds` and the Byron UTxO: the one given (a Byron chain's UTxO when it enters Shelley)
    * or the Byron genesis one (AVVM balances at their redeem addresses, and the non-AVVM ones).
    */
  def initial(
      files: Files,
      globals: LedgerGlobals,
      byronUtxo: Option[Map[TxIn, ByronOut]] = None
  ): Either[ConfigError, LedgerState] =
    val s = files.shelley.hcursor
    val extra = s.downField("extraConfig")
    val startsInConway = s
      .downField("protocolParams")
      .downField("protocolVersion")
      .downField("major")
      .as[Int]
      .exists(_ >= 9)
    for
      params <- ProtocolParams.fromGenesis(
        files.shelley,
        files.alonzo,
        Option.when(startsInConway)(files.conway)
      )
      lite <- LiteLedger.fromGenesis(files.shelley)
      maxSupply <- get[BigInt](s, "maxLovelaceSupply")
      shelleyFunds <- injection[BigInt](s, extra, "initialFunds").flatMap(
        _.toVector.traverse { case (hex, coin) => output(hex, coin) }
      )
      byronEntries <- byronUtxo.fold(
        ByronGenesisFile
          .genesisBalances(files.byron)
          .flatMap((avvm, nonAvvm) =>
            ByronLedger
              .genesisUtxo(avvm, nonAvvm, files.byronNetworkMagic)
              .leftMap(e => ConfigError(ConfigProblem.LedgerValue(e)))
          )
      )(Right(_))
      // Some testnets (Preview) inject Byron outputs of zero lovelace; the ledger's Byron to Shelley
      // translation drops them (`translateUTxOByronToShelley`), so the Shelley UTxO never holds them.
      byronFunds <- byronEntries.toVector.filter(_._2.value != 0).traverse { (in, out) =>
        Address
          .decode(out.address)
          .leftMap(e => ConfigError(ConfigProblem.LedgerValue(e)))
          .map(a => in -> TxOut(a, Value.coin(out.value)))
      }
      stakeCreds <- injection[String](s, extra, "stakeCredentials")
      poolJson <- injection[Json](s, extra, "stakePools")
      pools <- poolJson.toVector.traverse { case (id, j) => pool(id, j) }.map(_.toMap)
      delegations <- stakeCreds.toVector.traverse { case (cred, poolId) =>
        (hex28(cred), hex28(poolId)).tupled.map { case (c, p) => Credential.Key(c) -> p }
      }
      conwayExtra =
        if startsInConway then files.conway.hcursor.downField("extraConfig")
        else Json.obj().hcursor.downField("extraConfig")
      drepJson <- conwayExtra
        .downField("initialDReps")
        .downField("data")
        .as[Option[Map[String, Json]]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis injection", "initialDReps", e)))
        .map(_.getOrElse(Map.empty))
      dreps <- drepJson.toVector.traverse { case (k, j) =>
        for
          cred <- credential(k)
          deposit <- get[BigInt](j.hcursor, "deposit")
          expiry <- get[BigInt](j.hcursor, "expiry")
        yield cred -> DRepState(deposit, expiry)
      }
      voteJson <- conwayExtra
        .downField("delegs")
        .downField("data")
        .as[Option[Map[String, Json]]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis injection", "delegs", e)))
        .map(_.getOrElse(Map.empty))
      votes <- voteJson.toVector.traverse { case (k, j) =>
        (
          credential(k),
          j.hcursor
            .downField("dRep")
            .as[String]
            .leftMap(e =>
              ConfigError(ConfigProblem.JsonField("genesis vote delegation", "dRep", e))
            )
            .flatMap(drep)
        ).tupled
      }
    yield
      val utxo = (shelleyFunds ++ byronFunds).toMap
      val voteMap = votes.toMap
      val stakers = delegations.map(_._1).toSet ++ voteMap.keySet
      val accounts = stakers.iterator.map { c =>
        c -> Account(0, params.keyDeposit, delegations.toMap.get(c), voteMap.get(c))
      }.toMap
      val poolStates = pools.map { case (id, p) => id -> PoolState(p, params.poolDeposit) }
      // Genesis vote delegations build the reverse index of each DRep.
      val drepsWithDelegs = dreps.toMap.map { case (c, d) =>
        c -> d.copy(delegs = voteMap.collect { case (s, DRep.Registered(`c`)) => s }.toSet)
      }
      val deposited = accounts.values.map(_.deposit).sum + poolStates.values.map(_.deposit).sum +
        dreps.map(_._2.deposit).sum
      val base = LedgerState(
        epoch = globals.firstEpoch,
        params = params,
        utxo = Utxo.fromMap(utxo),
        fees = 0,
        deposited = deposited,
        accounts = accounts,
        pools = poolStates,
        futurePools = Map.empty,
        retiring = Map.empty,
        dreps = drepsWithDelegs,
        dormantEpochs = 0,
        treasury = 0,
        reserves = 0,
        snapshots = Snapshots(Snapshot.empty, Snapshot.empty, Snapshot.empty, 0),
        blocksCurrent = Map.empty,
        blocksPrevious = Map.empty,
        rewardUpdate = None,
        poolDistribution = Snapshot.empty,
        genDelegs = lite.genDelegs,
        prevParams = None
      )
      // Genesis-injected deposits are recorded per account but not in `deposited` (and not taken
      // from reserves); the first EPOCH rule recomputes `deposited` as the total obligation. Only
      // the mark snapshot is seeded: set/go start empty, so epoch 0 earns no rewards.
      val genesisStake = Stake.snapshot(base)
      val seeded0 = base.copy(
        deposited = 0,
        reserves = maxSupply - utxo.valuesIterator.map(_.value.coin).sum,
        snapshots = Snapshots(genesisStake, Snapshot.empty, Snapshot.empty, 0),
        // Epochs 0 and 1 elect leaders from the genesis staking injection.
        poolDistribution = genesisStake
      )
      val seeded =
        if !startsInConway then seeded0
        else
          val withGov = seeded0.copy(gov =
            globals.conway.fold(GovState.empty)(c =>
              GovState.empty.copy(committee = c.committee, constitution = c.constitution)
            )
          )
          withGov.copy(gov =
            withGov.gov.copy(pulsed =
              Some(
                PulsingSnapshot.empty -> RatifyState(
                  ConwayEpoch.enactState(withGov),
                  Vector.empty,
                  Set.empty,
                  delayed = false
                )
              )
            )
          )
      // Epoch 0 has a (necessarily empty) reward update, like every other epoch.
      seeded.copy(rewardUpdate = Some(Rewards.update(seeded, globals)))
