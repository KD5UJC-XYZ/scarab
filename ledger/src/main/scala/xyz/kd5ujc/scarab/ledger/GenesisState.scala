// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import io.circe.{ACursor, Decoder, Json}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes

/** The genesis files and the network constants read from them, and the readers of their injections
  * (`initialFunds`, `stakePools`, `stakeCredentials`, Conway DReps and delegations). The ledger
  * state at the start of a chain is built from them by [[rules.GenesisStart]].
  */
object GenesisState:
  /** The genesis files; `byronNetworkMagic` is the magic Byron addresses carry on a network that
    * requires one (it decides the redeem addresses of the AVVM balances).
    */
  final case class Files(
      shelley: Json,
      alonzo: Json,
      conway: Json,
      byron: Json,
      byronNetworkMagic: Option[Long] = None
  )

  /** Network constants from the genesis files. */
  def globals(
      files: Files,
      genesis: xyz.kd5ujc.scarab.ledger.ShelleyGenesis
  ): Either[ConfigError, LedgerGlobals] =
    val c = files.conway.hcursor
    val conway =
      (
        get[BigInt](c, "dRepDeposit"),
        get[BigInt](c, "dRepActivity"),
        get[BigInt](c, "govActionDeposit"),
        get[BigInt](c, "govActionLifetime"),
        get[BigDecimal](c, "minFeeRefScriptCostPerByte").map(Rational.fromDecimal)
      ).mapN(ConwayParams(_, _, _, _, _))
        .toOption
        .flatMap { base =>
          (ConwayGenesis.votingParams(c), ConwayGenesis.committeeAndConstitution(c)).mapN {
            case ((pool, drep, minSize, maxTerm), (committee, constitution)) =>
              base.copy(
                plutusV3CostModel =
                  c.downField("plutusV3CostModel").as[Vector[BigInt]].getOrElse(Vector.empty),
                poolVotingThresholds = pool,
                drepVotingThresholds = drep,
                committeeMinSize = minSize,
                committeeMaxTermLength = maxTerm,
                committee = committee,
                constitution = constitution
              )
          }.toOption
        }
    files.shelley.hcursor
      .downField("updateQuorum")
      .as[Option[Int]]
      .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis", "updateQuorum", e)))
      .product(get[BigInt](files.alonzo.hcursor, "lovelacePerUTxOWord"))
      .map { (quorum, perWord) =>
        LedgerGlobals(
          genesis.maxLovelaceSupply,
          genesis.geometry.epochLength,
          Rational.fromDecimal(genesis.geometry.activeSlotsCoeff),
          network = if genesis.networkMagic == 764824073L then 1 else 0,
          updateQuorum = quorum.getOrElse(5),
          stabilityWindow = genesis.geometry.stabilityWindow,
          startRewards = genesis.geometry.randomnessStabilisationWindow,
          firstEpoch = genesis.geometry.firstShelleyEpoch,
          conway = conway,
          alonzoCoinsPerUTxOWord = perWord
        )
      }

  def output(hex: String, coin: BigInt): Either[ConfigError, (TxIn, TxOut)] =
    Bytes.fromHex(hex).leftMap(e => ConfigError(ConfigProblem.Hex(e))).flatMap(outputBytes(_, coin))

  /** Genesis outputs are spent from the pseudo transaction whose id is the address hash. */
  def outputBytes(raw: Bytes, coin: BigInt): Either[ConfigError, (TxIn, TxOut)] =
    for
      address <- Address.decode(raw).leftMap(e => ConfigError(ConfigProblem.LedgerValue(e)))
      // `registerInitialFunds` constructs `mkBasicTxOut`, which compacts each output value
      // (f649f975 Shelley/Transition.hs:650-653; Shelley/TxOut.hs:139-143;
      // Alonzo/TxOut.hs:326-327; Babbage/TxOut.hs:449-450). This is the output's bound;
      // the chain account's reserves and the sum of initial funds remain signed Coin.
      amount <- state.CompactCoin
        .fromBigInt(coin)
        .leftMap(e => ConfigError(ConfigProblem.GenesisStart(rules.GenesisStartError.Vocab(e))))
    // Genesis outputs have no transaction encoding; `raw` is only consulted for new outputs.
    yield TxIn(Blake2b.hash256.hash(raw), 0) -> TxOut(address, Value.coin(amount.toBigInt))

  def pool(id: String, j: Json): Either[ConfigError, (Bytes, PoolParams)] =
    val c = j.hcursor
    for
      operator <- hex28(id)
      vrf <- get[String](c, "vrf").flatMap(h =>
        Bytes.fromHex(h).leftMap(e => ConfigError(ConfigProblem.Hex(e)))
      )
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

  def credential(s: String): Either[ConfigError, Credential] =
    if s.startsWith("keyHash-") then hex28(s.drop(8)).map(Credential.Key(_))
    else if s.startsWith("scriptHash-") then hex28(s.drop(11)).map(Credential.Script(_))
    else Left(ConfigError(ConfigProblem.Credential(s)))

  def drep(s: String): Either[ConfigError, DRep] =
    s match
      case "drep-alwaysAbstain"       => Right(DRep.AlwaysAbstain)
      case "drep-alwaysNoConfidence"  => Right(DRep.AlwaysNoConfidence)
      case _ if s.startsWith("drep-") => credential(s.drop(5)).map(DRep.Registered(_))
      case _                          => Left(ConfigError(ConfigProblem.DRep(s)))

  def hex28(h: String): Either[ConfigError, Bytes] =
    Bytes
      .fromHex(h)
      .leftMap(e => ConfigError(ConfigProblem.Hex(e)))
      .flatMap(b =>
        Either
          .cond(b.size == 28, b, ConfigError(ConfigProblem.ByteLength("hash", 28, h.length / 2)))
      )

  /** `resolveInjectionSource` (`Shelley/Transition.hs:496-517`, cardano-ledger f649f975): with an
    * `extraConfig`, its `<name>` source when it names one, else the legacy field, and a conflict
    * when both are given; without one, the legacy field. The entries are in the order of the key,
    * the order in which the reference folds an embedded `ListMap` (`Data/ListMap.hs:109-125`, an
    * aeson key map). A `file` source (`InjectionFromFile`) is not read by this node and is refused
    * rather than taken as empty.
    *
    * @param legacy
    *   the legacy field itself (the Shelley genesis `initialFunds`, `staking.pools`,
    *   `staking.stake`; the Conway genesis `initialDReps`, `delegs`)
    * @param extraConfig
    *   the genesis file's `extraConfig`
    */
  def source[V: Decoder](
      legacy: ACursor,
      extraConfig: ACursor,
      name: String
  ): Either[ConfigError, Vector[(String, V)]] =
    def entries(c: ACursor) =
      c.as[Option[Map[String, V]]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis injection", name, e)))
        .map(_.getOrElse(Map.empty).toVector.sortBy(_._1))
    val extra = extraConfig.focus.filterNot(_.isNull).map(_ => extraConfig.downField(name))
    val file = extra.exists(_.downField("file").focus.exists(!_.isNull))
    val data = extra.flatMap(c => c.downField("data").focus.filterNot(_.isNull).map(_ => c))
    for
      _ <- Either.cond(!file, (), ConfigError(ConfigProblem.InjectionFile(name)))
      old <- entries(legacy)
      chosen <- data match
        case None                   => Right(old)
        case Some(c) if old.isEmpty => entries(c.downField("data"))
        case Some(_)                => Left(ConfigError(ConfigProblem.DuplicateInjection(name)))
    yield chosen

  def get[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name).as[A].leftMap(e => ConfigError(ConfigProblem.JsonField("genesis", name, e)))

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
