// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
import lab.Blake2b
import lab.cbor.Bytes
import lab.node.ConfigError

/** The ledger state at the first Shelley-based slot of a chain that forks to Conway at epoch 0.
  * Sources: Shelley `initialFunds` /staking injections, Byron non-AVVM balances and the Conway
  * genesis DReps and vote delegations.
  */
object GenesisState:
  final case class Files(shelley: Json, alonzo: Json, conway: Json, byron: Json)

  /** Network constants from the genesis files. */
  def globals(files: Files, genesis: lab.node.ShelleyGenesis): Either[ConfigError, LedgerGlobals] =
    val c = files.conway.hcursor
    val conway =
      (
        get[BigInt](c, "dRepDeposit"),
        get[BigInt](c, "dRepActivity"),
        get[BigInt](c, "govActionDeposit"),
        get[BigInt](c, "govActionLifetime"),
        get[BigDecimal](c, "minFeeRefScriptCostPerByte").map(Rational.fromDecimal)
      ).mapN(ConwayParams.apply).toOption
    files.shelley.hcursor
      .downField("updateQuorum")
      .as[Option[Int]]
      .leftMap(e => ConfigError(s"updateQuorum: ${e.message}"))
      .map { quorum =>
        LedgerGlobals(
          genesis.maxLovelaceSupply,
          genesis.geometry.epochLength,
          Rational.fromDecimal(genesis.geometry.activeSlotsCoeff),
          network = if genesis.networkMagic == 764824073L then 1 else 0,
          updateQuorum = quorum.getOrElse(5),
          stabilityWindow = genesis.geometry.stabilityWindow,
          startRewards = genesis.geometry.randomnessStabilisationWindow,
          firstEpoch = genesis.geometry.firstShelleyEpoch,
          conway = conway
        )
      }

  def initial(files: Files, globals: LedgerGlobals): Either[ConfigError, LedgerState] =
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
      byronFunds <- files.byron.hcursor
        .downField("nonAvvmBalances")
        .as[Map[String, String]]
        .leftMap(e => ConfigError(s"byron nonAvvmBalances: ${e.message}"))
        .flatMap(_.toVector.traverse { case (b58, coin) =>
          Base58
            .decode(b58)
            .toRight(ConfigError(s"byron address $b58"))
            .flatMap(raw =>
              Either
                .catchNonFatal(BigInt(coin))
                .leftMap(_ => ConfigError(s"byron balance $coin"))
                .flatMap(c => outputBytes(raw, c))
            )
        })
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
        .leftMap(e => ConfigError(s"initialDReps: ${e.message}"))
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
        .leftMap(e => ConfigError(s"delegs: ${e.message}"))
        .map(_.getOrElse(Map.empty))
      votes <- voteJson.toVector.traverse { case (k, j) =>
        (
          credential(k),
          j.hcursor.downField("dRep").as[String].leftMap(e => ConfigError(e.message)).flatMap(drep)
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
      val deposited = accounts.values.map(_.deposit).sum + poolStates.values.map(_.deposit).sum +
        dreps.map(_._2.deposit).sum
      val base = LedgerState(
        epoch = globals.firstEpoch,
        params = params,
        utxo = utxo,
        fees = 0,
        deposited = deposited,
        accounts = accounts,
        pools = poolStates,
        futurePools = Map.empty,
        retiring = Map.empty,
        dreps = dreps.toMap,
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
      val seeded = base.copy(
        deposited = 0,
        reserves = maxSupply - utxo.valuesIterator.map(_.value.coin).sum,
        snapshots = Snapshots(genesisStake, Snapshot.empty, Snapshot.empty, 0),
        // Epochs 0 and 1 elect leaders from the genesis staking injection.
        poolDistribution = genesisStake
      )
      // Epoch 0 has a (necessarily empty) reward update, like every other epoch.
      seeded.copy(rewardUpdate = Some(Rewards.update(seeded, globals)))

  private def output(hex: String, coin: BigInt): Either[ConfigError, (TxIn, TxOut)] =
    Bytes.fromHex(hex).leftMap(ConfigError(_)).flatMap(outputBytes(_, coin))

  /** Genesis outputs are spent from the pseudo transaction whose id is the address hash. */
  private def outputBytes(raw: Bytes, coin: BigInt): Either[ConfigError, (TxIn, TxOut)] =
    Address.decode(raw).leftMap(ConfigError(_)).map { address =>
      // Genesis outputs have no transaction encoding; `raw` is only consulted for new outputs.
      TxIn(Blake2b.hash256.hash(raw), 0) -> TxOut(address, Value.coin(coin))
    }

  private def pool(id: String, j: Json): Either[ConfigError, (Bytes, PoolParams)] =
    val c = j.hcursor
    for
      operator <- hex28(id)
      vrf <- get[String](c, "vrf").flatMap(h => Bytes.fromHex(h).leftMap(ConfigError(_)))
      pledge <- get[BigInt](c, "pledge")
      cost <- get[BigInt](c, "cost")
      margin <- get[BigDecimal](c, "margin").map(Rational.fromDecimal)
      account <- get[String](c.downField("accountAddress").downField("credential"), "keyHash")
        .flatMap(hex28)
      network <- get[String](c.downField("accountAddress"), "network")
      owners <- get[Vector[String]](c, "owners").flatMap(_.traverse(hex28))
    yield operator -> PoolParams(
      operator,
      vrf,
      pledge,
      cost,
      margin,
      RewardAccount(if network == "Mainnet" then 1 else 0, Credential.Key(account)),
      owners.toSet,
      Bytes.empty,
      None
    )

  private def credential(s: String): Either[ConfigError, Credential] =
    if s.startsWith("keyHash-") then hex28(s.drop(8)).map(Credential.Key(_))
    else if s.startsWith("scriptHash-") then hex28(s.drop(11)).map(Credential.Script(_))
    else Left(ConfigError(s"credential $s"))

  private def drep(s: String): Either[ConfigError, DRep] =
    s match
      case "drep-alwaysAbstain"       => Right(DRep.AlwaysAbstain)
      case "drep-alwaysNoConfidence"  => Right(DRep.AlwaysNoConfidence)
      case _ if s.startsWith("drep-") => credential(s.drop(5)).map(DRep.Registered(_))
      case _                          => Left(ConfigError(s"drep $s"))

  private def hex28(h: String): Either[ConfigError, Bytes] =
    Bytes
      .fromHex(h)
      .leftMap(ConfigError(_))
      .flatMap(b => Either.cond(b.size == 28, b, ConfigError(s"hash $h")))

  /** Genesis injections live under `extraConfig.<name>.data`; older files use a top-level map. */
  private def injection[V: Decoder](
      top: ACursor,
      extra: ACursor,
      name: String
  ): Either[ConfigError, Map[String, V]] =
    def at(c: ACursor) =
      c.as[Option[Map[String, V]]].leftMap(e => ConfigError(s"$name: ${e.message}"))
    (at(extra.downField(name).downField("data")), at(top.downField(name))).tupled.flatMap {
      case (Some(a), legacy) if legacy.forall(_.isEmpty) => Right(a)
      case (None, legacy)                                => Right(legacy.getOrElse(Map.empty))
      case _                                             => Left(ConfigError(s"$name given twice"))
    }

  private def get[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name).as[A].leftMap(e => ConfigError(s"genesis $name: ${e.message}"))

/** Bitcoin-alphabet base58, as used by Byron addresses. */
object Base58:
  private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
  def decode(s: String): Option[Bytes] =
    s.foldLeft(Option(BigInt(0))) { (acc, ch) =>
      val i = alphabet.indexOf(ch)
      acc.flatMap(a => Option.when(i >= 0)(a * 58 + i))
    }.map { n =>
      val leading = s.takeWhile(_ == '1').length
      val body = if n == 0 then Array.emptyByteArray else n.toByteArray.dropWhile(_ == 0)
      Bytes(Vector.fill(leading)(0.toByte) ++ body.toVector)
    }
