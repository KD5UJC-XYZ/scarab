// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorExtras, MirrorProvenance}
import xyz.kd5ujc.scarab.ledger.{ConfigProblem, GenesisState, ProtocolParams, Ptr}
import xyz.kd5ujc.scarab.ledger.rules.{
  CertLens,
  GenesisInjections,
  GenesisStart,
  GenesisStartError,
  Globals,
  Handover,
  Translate
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.GenesisState.{FundAddress, InjectionSource}

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

  private val enterpriseFund = "60" + "19" * 28

  Files
    .readString(Path.of("fixtures/reference/genesis-initial-funds/cases.jsonl"))
    .linesIterator
    .foreach { line =>
      val row = parser.parse(line).fold(e => fail(e.message), identity).hcursor
      def field[A: io.circe.Decoder](name: String): A =
        row.get[A](name).fold(e => fail(e.message), identity)
      test(s"native initial-funds input: ${field[String]("name")}") {
        val decoded = GenesisState.address(field[String]("address")).toOption
        assertEquals(decoded.map(_.canonicalBytes.hex), field[Option[String]]("canonicalAddress"))
        val pseudo = decoded.map { address =>
          val (input, _) = GenesisState
            .initialOutput(address, Coin(0))
            .fold(e => fail(e.toString), identity)
          assertEquals(input.index, BigInt(0))
          "825820" + input.id.hex + "00"
        }
        assertEquals(pseudo, field[Option[String]]("pseudoInput"))
        val files = GenesisState.Files(
          Json.obj(
            "networkId" -> Json.fromString("Testnet"),
            "initialFunds" -> Json.obj(enterpriseFund -> field[Json]("amount"))
          ),
          Json.obj(),
          Json.obj(),
          Json.obj()
        )
        val coin = GenesisInjections
          .read(files)
          .toOption
          .flatMap(_.initialFunds.legacy.headOption)
          .map(_._2)
        assertEquals(coin.map(_.value.toString), field[Option[String]]("coin"))
        assertEquals(
          coin.flatMap(c => CompactCoin.fromCoin(c).toOption).map(_.toBigInt.toString),
          field[Option[String]]("compact")
        )
      }
    }

  private def fundAddress(hex: String): FundAddress =
    GenesisState.address(hex).fold(e => fail(e.message), identity)

  private def replaceExtra(value: Json, name: String, source: Json): Json =
    val extra = value.hcursor
      .downField("extraConfig")
      .focus
      .flatMap(_.asObject)
      .getOrElse(io.circe.JsonObject.empty)
    value.mapObject(_.add("extraConfig", Json.fromJsonObject(extra.add(name, source))))

  private def sourceFiles(shelley: Json): GenesisState.Files =
    GenesisState.Files(shelley, Json.obj(), Json.obj(), Json.obj())

  private def fundSources(extra: Json): Json = Json.obj(
    "networkId" -> Json.fromString("Testnet"),
    "initialFunds" -> Json.obj(enterpriseFund -> Json.fromInt(1)),
    "extraConfig" -> Json.obj("initialFunds" -> extra)
  )

  test("no injection preserves legacy funds, but embedded empty data conflicts with them") {
    for extra <- Vector(Json.obj(), Json.Null, Json.obj("data" -> Json.Null, "file" -> Json.Null))
    do
      val parsed = GenesisInjections
        .read(sourceFiles(fundSources(extra)))
        .fold(e => fail(e.message), identity)
      val next =
        GenesisStart.registerInitialFunds(parsed, nes).fold(e => fail(e.toString), identity)
      assertEquals(next.es.chainAccount.reserves.value, nes.es.chainAccount.reserves.value - 1)
    val embedded = GenesisInjections
      .read(sourceFiles(fundSources(Json.obj("data" -> Json.obj()))))
      .fold(e => fail(e.message), identity)
    assertEquals(
      GenesisStart.registerInitialFunds(embedded, nes),
      Left(GenesisStartError.ConflictingSources("initialFunds"))
    )
  }

  test(
    "a parsed funds conflict is resolved after Mainnet, while invalid unselected data is parsed first"
  ) {
    val extra = Json.obj("data" -> Json.obj(enterpriseFund -> Json.fromInt(2)))
    val parsed = GenesisInjections
      .read(sourceFiles(fundSources(extra)))
      .fold(e => fail(e.message), identity)
    assertEquals(
      GenesisStart.registerInitialFunds(parsed.copy(mainnet = true), nes),
      Left(GenesisStartError.InjectionNotAllowedOnMainnet)
    )
    assertEquals(
      GenesisStart.registerInitialFunds(parsed, nes),
      Left(GenesisStartError.ConflictingSources("initialFunds"))
    )
    val invalid = replaceExtra(
      fundSources(extra),
      "initialFunds",
      Json.obj("data" -> Json.obj(enterpriseFund -> Json.fromString("1")))
    )
    assert(
      GenesisInjections
        .read(sourceFiles(invalid))
        .left
        .toOption
        .exists(_.problem.isInstanceOf[ConfigProblem.JsonField])
    )
    val invalidKey = fundSources(extra).mapObject(
      _.add("initialFunds", Json.obj("not-hex" -> Json.fromString("also not a Coin")))
    )
    assert(
      GenesisInjections
        .read(sourceFiles(invalidKey))
        .left
        .toOption
        .exists(_.problem.isInstanceOf[ConfigProblem.Hex])
    )
  }

  test(
    "file metadata is parsed before injection; selection and consumption retain native ordering"
  ) {
    val file = Json.obj(
      "file" -> Json.arr(Json.fromString("funds.json")),
      "hash" -> Json.fromString("00" * 32)
    )
    val legacy = GenesisInjections
      .read(sourceFiles(fundSources(file)))
      .fold(e => fail(e.message), identity)
    assertEquals(
      GenesisStart.registerInitialFunds(legacy.copy(mainnet = true), nes),
      Left(GenesisStartError.InjectionNotAllowedOnMainnet)
    )
    assertEquals(
      GenesisStart.registerInitialFunds(legacy, nes),
      Left(GenesisStartError.ConflictingSources("initialFunds"))
    )
    val selected = legacy.copy(initialFunds = legacy.initialFunds.copy(legacy = Vector.empty))
    GenesisStart.registerInitialFunds(selected, nes) match
      case Left(GenesisStartError.UnsupportedFile("initialFunds", Vector("funds.json"), hash)) =>
        assertEquals(hash.bytes.hex, "00" * 32)
      case other => fail(s"unexpected selected file result: $other")
    for invalid <- Vector(
        Json.obj("file" -> Json.arr(Json.fromString("funds.json"))),
        file.mapObject(_.add("hash", Json.fromString("00"))),
        file.mapObject(_.add("hash", Json.fromString("not hex"))),
        file.mapObject(_.add("file", Json.fromString("funds.json"))),
        file.mapObject(_.add("data", Json.obj()))
      )
    do assert(GenesisInjections.read(sourceFiles(fundSources(invalid))).isLeft)
  }

  Vector("initialFunds", "stakePools", "stakeCredentials", "initialDReps", "delegs").foreach {
    source =>
      test(s"$source requires an object when supplied, even for a Byron start") {
        val result = NodeSetup.fromFiles(files { (name, value) =>
          val started = byronStart(name, value)
          val genesis =
            if Set("initialDReps", "delegs")(source) then "conway-genesis.json"
            else "shelley-genesis.json"
          if name == genesis then replaceExtra(started, source, Json.arr()) else started
        })
        assert(result.left.toOption.exists(_.problem.isInstanceOf[ConfigProblem.JsonField]))
      }
  }

  Vector("initialDReps", "delegs").foreach { source =>
    test(s"Conway resolves $source conflicts before the funds Mainnet guard") {
      val problem = refusal { (name, value) =>
        if name == "shelley-genesis.json" then
          value.mapObject(_.add("networkId", Json.fromString("Mainnet")))
        else if name == "conway-genesis.json" then
          val entries = value.hcursor
            .downField("extraConfig")
            .downField(source)
            .downField("data")
            .focus
            .getOrElse(fail(s"missing $source fixture"))
          value.mapObject(_.add(source, entries))
        else value
      }
      assertEquals(
        problem,
        ConfigProblem.GenesisStart(GenesisStartError.ConflictingSources(source))
      )
    }
  }

  Vector("stakePools", "stakeCredentials").foreach { source =>
    test(s"Shelley resolves $source conflicts after Mainnet and before funds compaction") {
      def edit(mainnet: Boolean)(name: String, value: Json): Json =
        if name != "shelley-genesis.json" then value
        else
          val field = if source == "stakePools" then "pools" else "stake"
          val entries = value.hcursor
            .downField("extraConfig")
            .downField(source)
            .downField("data")
            .focus
            .getOrElse(fail(s"missing $source fixture"))
          val staking =
            Json.obj("pools" -> Json.obj(), "stake" -> Json.obj()).mapObject(_.add(field, entries))
          replaceExtra(
            value.mapObject(
              _.add("staking", staking)
                .add("networkId", Json.fromString(if mainnet then "Mainnet" else "Testnet"))
            ),
            "initialFunds",
            Json.obj("data" -> Json.obj(enterpriseFund -> Json.fromInt(-1)))
          )
      assertEquals(
        refusal(edit(true)),
        ConfigProblem.GenesisStart(GenesisStartError.InjectionNotAllowedOnMainnet)
      )
      assertEquals(
        refusal(edit(false)),
        ConfigProblem.GenesisStart(GenesisStartError.ConflictingSources(source))
      )
    }
  }

  Vector(
    "invalid address" -> Json.obj("not-hex" -> Json.fromInt(1)),
    "invalid Coin" -> Json.obj(enterpriseFund -> Json.fromString("1"))
  ).foreach { (label, funds) =>
    test(s"a Byron start validates unused initial funds: $label") {
      val result = NodeSetup.fromFiles(files { (name, value) =>
        val started = byronStart(name, value)
        if name == "shelley-genesis.json" then
          replaceExtra(started, "initialFunds", Json.obj("data" -> funds))
        else started
      })
      assert(result.isLeft)
    }
  }

  test("a Byron start parses valid file metadata and conflicts without resolving either") {
    val result = NodeSetup.fromFiles(files { (name, value) =>
      val started = byronStart(name, value)
      if name == "shelley-genesis.json" then
        replaceExtra(
          started.mapObject(_.add("initialFunds", Json.obj(enterpriseFund -> Json.fromInt(-1)))),
          "initialFunds",
          Json.obj(
            "file" -> Json.arr(Json.fromString("unread.json")),
            "hash" -> Json.fromString("00" * 32)
          )
        )
      else started
    })
    assertEquals(
      result.map(_.mirrorStart.ext.ledgerState.era),
      Right(xyz.kd5ujc.scarab.protocol.Era.Byron)
    )
  }

  private def injections(
      funds: Vector[(FundAddress, Coin)],
      mainnet: Boolean = false
  ): GenesisInjections =
    GenesisInjections(
      mainnet,
      InjectionSource.legacy(funds),
      InjectionSource.legacy(Vector.empty),
      InjectionSource.legacy(Vector.empty),
      InjectionSource.legacy(Vector.empty),
      InjectionSource.legacy(Vector.empty),
      Map.empty
    )

  Vector(BigInt(-1), BigInt(1) << 64).foreach { amount =>
    test(s"the native public initial-funds operation checks Mainnet before compacting $amount") {
      val funds = injections(Vector(fundAddress(enterpriseFund) -> Coin(amount)), mainnet = true)
      assertEquals(
        GenesisStart.registerInitialFunds(funds, nes),
        Left(GenesisStartError.InjectionNotAllowedOnMainnet)
      )
    }

    test(s"a parsed Mainnet genesis retains signed Coin $amount until the injection guard") {
      val result = NodeSetup.fromFiles(files { (name, value) =>
        if name != "shelley-genesis.json" then value
        else
          value.deepMerge(
            Json.obj(
              "networkId" -> Json.fromString("Mainnet"),
              "extraConfig" -> Json.obj(
                "initialFunds" -> Json.obj(
                  "data" -> Json.obj(enterpriseFund -> Json.fromBigInt(amount))
                )
              )
            )
          )
      })
      assertEquals(
        result.left.toOption.map(_.problem),
        Some(ConfigProblem.GenesisStart(GenesisStartError.InjectionNotAllowedOnMainnet))
      )
    }

    test(s"the native public initial-funds operation compacts every output amount $amount") {
      val funds = injections(Vector(fundAddress(enterpriseFund) -> Coin(amount)))
      val expected = CompactCoin.fromBigInt(amount).left.map(GenesisStartError.Vocab(_))
      assertEquals(
        GenesisStart.registerInitialFunds(funds, nes).map(_ => ()),
        expected.map(_ => ())
      )
    }
  }

  test("initial funds retain an exact signed reserve total beyond Word64") {
    val maximum = (BigInt(1) << 64) - 1
    val addresses = Vector(fundAddress(enterpriseFund), fundAddress("60" + "1a" * 28))
    val start =
      nes.copy(es = nes.es.copy(chainAccount = nes.es.chainAccount.copy(reserves = Coin(0))))
    val funded = GenesisStart
      .registerInitialFunds(injections(addresses.map(_ -> Coin(maximum))), start)
      .fold(e => fail(e.toString), identity)
    assertEquals(funded.es.chainAccount.reserves.value, -2 * maximum)
    addresses.foreach { address =>
      val input =
        xyz.kd5ujc.scarab.ledger
          .TxIn(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(address.canonicalBytes), 0)
      assertEquals(funded.es.ls.utxo.utxo.utxo.get(input).map(_.value.coin), Some(maximum))
    }
  }

  test("ordered initial funds overwrite a repeated address but sum every Coin") {
    val address = fundAddress(enterpriseFund)
    val funded = GenesisStart
      .registerInitialFunds(injections(Vector(address -> Coin(1), address -> Coin(2))), nes)
      .fold(e => fail(e.toString), identity)
    val input =
      xyz.kd5ujc.scarab.ledger
        .TxIn(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(address.canonicalBytes), 0)
    assertEquals(funded.es.ls.utxo.utxo.utxo.get(input).map(_.value.coin), Some(BigInt(2)))
    assertEquals(funded.es.chainAccount.reserves.value, nes.es.chainAccount.reserves.value - 3)
    assertEquals(
      GenesisStart.registerInitialFunds(injections(Vector(address -> Coin(3))), funded),
      Left(GenesisStartError.InitialFundInUtxo(input))
    )
  }

  Vector(Json.fromString("1"), Json.fromBigDecimal(BigDecimal("1.5"))).foreach { amount =>
    test(s"Coin JSON syntax is checked before the Mainnet injection guard: ${amount.noSpaces}") {
      val result = NodeSetup.fromFiles(files { (name, value) =>
        if name != "shelley-genesis.json" then value
        else
          value.deepMerge(
            Json.obj(
              "networkId" -> Json.fromString("Mainnet"),
              "extraConfig" -> Json.obj(
                "initialFunds" -> Json.obj("data" -> Json.obj(enterpriseFund -> amount))
              )
            )
          )
      })
      result.left.toOption.map(_.problem) match
        case Some(_: ConfigProblem.JsonField) => ()
        case other                            => fail(s"expected Coin JSON refusal, got $other")
    }
  }

  test("genesis address parsing uses strict pointers and reserved header bits") {
    val reservedHeader = "62" + "19" * 28
    assertEquals(
      GenesisState.address(reservedHeader).left.toOption.map(_.problem),
      Some(
        ConfigProblem.LedgerValue(xyz.kd5ujc.scarab.ledger.LedgerDecodeError.AddressHeader(0x62))
      )
    )
    val overflowPointer = "40" + "19" * 28 + "90808080000000"
    assert(GenesisState.address(overflowPointer).isLeft)
    val result = NodeSetup.fromFiles(files { (name, value) =>
      if name != "shelley-genesis.json" then value
      else
        value.deepMerge(
          Json.obj(
            "networkId" -> Json.fromString("Mainnet"),
            "extraConfig" -> Json.obj(
              "initialFunds" -> Json.obj("data" -> Json.obj("not-hex" -> Json.fromInt(1)))
            )
          )
        )
    })
    assert(result.left.toOption.exists(_.problem.isInstanceOf[ConfigProblem.Hex]))
  }

  test("initial-fund pseudo inputs hash the decoded pointer address serialization") {
    val canonical = "40" + "19" * 28 + "000000"
    val noncanonical = "40" + "19" * 28 + "80000000"
    val address = fundAddress(noncanonical)
    assertEquals(address.canonicalBytes.hex, canonical)
    val output = GenesisState.output(noncanonical, 1).fold(e => fail(e.message), identity)
    assertEquals(
      output._1.id,
      xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(address.canonicalBytes)
    )
  }

  test("initial-fund Byron addresses use native serialization before pseudo-input hashing") {
    import xyz.kd5ujc.scarab.ledger.byron.ByronCbor
    val payload = ByronCbor.of(0x83, 0x58, 0x1c) ++ Bytes.fromArray(
      Array.fill[Byte](28)(0)
    ) ++ ByronCbor.of(0xa0, 0)
    val checksum = ByronCbor.uint(BigInt(ByronCbor.crc32(payload)))
    val canonical = ByronCbor.of(0x82, 0xd8, 0x18) ++ ByronCbor.bytes(payload) ++ checksum
    val noncanonical = ByronCbor.of(0x82, 0xd9, 0, 0x18) ++ ByronCbor.bytes(payload) ++ checksum
    val output = GenesisState.outputBytes(noncanonical, 1).fold(e => fail(e.message), identity)
    assertEquals(output._2.address.raw, canonical)
    assertEquals(output._1.id, xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(canonical))
  }

  // `registerInitialFunds` constructs `mkBasicTxOut`, whose value must compact even though the
  // chain account's reserve arithmetic uses signed Coin (f649f975 Shelley/Transition.hs:650-653;
  // Shelley/TxOut.hs:139-143, Alonzo/TxOut.hs:326-327, Babbage/TxOut.hs:449-450).
  Vector(BigInt(0), (BigInt(1) << 64) - 1).foreach { amount =>
    test(s"an initial-fund output retains compact boundary amount $amount") {
      val output = GenesisState.output(enterpriseFund, amount).fold(e => fail(e.message), _._2)
      assertEquals(output.value.coin, amount)
      assertEquals(output.address.raw.hex, enterpriseFund)
    }
  }

  Vector(
    BigInt(-1) -> VocabError.Negative(VocabError.Kind.CompactCoin, BigInt(-1)),
    (BigInt(1) << 64) -> VocabError.OutOfRange(VocabError.Kind.CompactCoin, BigInt(1) << 64)
  ).foreach { (amount, cause) =>
    test(s"an initial-fund output refuses noncompact amount $amount") {
      val result = GenesisState.output(enterpriseFund, amount).left.map(_.problem)
      assertEquals(result, Left(ConfigProblem.GenesisStart(GenesisStartError.Vocab(cause))))
    }
  }

  test("a negative initial fund without staking cannot enter the genesis UTxO") {
    val result = NodeSetup.fromFiles(files { (name, value) =>
      if name != "shelley-genesis.json" then value
      else
        value.deepMerge(
          Json.obj(
            "extraConfig" -> Json.obj(
              "initialFunds" -> Json.obj("data" -> Json.obj(enterpriseFund -> Json.fromInt(-1)))
            )
          )
        )
    })
    assertEquals(
      result.left.toOption.map(_.problem),
      Some(
        ConfigProblem.GenesisStart(
          GenesisStartError.Vocab(VocabError.Negative(VocabError.Kind.CompactCoin, BigInt(-1)))
        )
      )
    )
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

  private def byronStart(name: String, value: Json): Json = name match
    case "configuration.yaml" =>
      Vector("Shelley", "Allegra", "Mary", "Alonzo", "Babbage", "Conway").zipWithIndex
        .foldLeft(value) { case (config, (era, index)) =>
          config.deepMerge(Json.obj(s"Test${era}HardForkAtEpoch" -> Json.fromInt(index + 1)))
        }
    case "shelley-genesis.json" =>
      value.deepMerge(
        Json.obj(
          "protocolParams" -> Json.obj(
            "protocolVersion" -> Json.obj("major" -> Json.fromInt(2), "minor" -> Json.fromInt(0))
          )
        )
      )
    case _ => value

  test("a Byron hand-over keeps base cost models without the era-skipping injection") {
    val byron = NodeSetup.fromFiles(files(byronStart)).fold(e => fail(e.message), identity)
    val context = byron.handOver.getOrElse(fail("a Byron start has no hand-over context"))
    val utxo = byron.mirrorStart.ext.ledgerState.state match
      case EraLedgerState.ByronCurrent(state) =>
        state.ledger.getOrElse(fail("a Byron start has no ledger")).utxo
      case other => fail(s"not a Byron start: $other")
    val shelley = Handover
      .toShelley(context, EpochNo.fromUnsigned(1L), utxo)
      .fold(e => fail(e.toString), identity)
    val allegra = Translate.shelleyToAllegra(shelley).fold(e => fail(e.toString), identity)
    val alonzo = Translate.maryToAlonzo(
      Globals(byron.globals, byron.genesis.geometry),
      Translate.allegraToMary(allegra)
    )
    val baseModel = nes.es.ls.utxo.gov.prevPParams.costModels.get(0)
    assert(baseModel.nonEmpty)
    for params <- Vector(
        context.protocolParams,
        shelley.es.ls.utxo.gov.curPParams,
        shelley.es.ls.utxo.gov.prevPParams,
        alonzo.es.ls.utxo.gov.curPParams,
        alonzo.es.ls.utxo.gov.prevPParams
      )
    do
      assertEquals(params.costModels.get(0), baseModel)
      assertEquals(params.costModels.get(1), None)
  }

  test("a Byron start still refuses a malformed cost model in the unused injection") {
    val problem = refusal { (name, value) =>
      val started = byronStart(name, value)
      if name != "alonzo-genesis.json" then started
      else
        started.deepMerge(
          Json.obj(
            "extraConfig" -> Json.obj(
              "costModels" -> Json.obj("PlutusV2" -> Json.fromString("not a cost model"))
            )
          )
        )
    }
    problem match
      case ConfigProblem.JsonField("protocol parameters", "cost model", _) => ()
      case other => fail(s"unexpected refusal: $other")
  }

  test("a Byron start does not construct outputs from unused initial-fund injections") {
    val result = NodeSetup.fromFiles(files { (name, value) =>
      val started = byronStart(name, value)
      if name != "shelley-genesis.json" then started
      else
        started.deepMerge(
          Json.obj(
            "extraConfig" -> Json.obj(
              "initialFunds" -> Json.obj("data" -> Json.obj(enterpriseFund -> Json.fromInt(-1)))
            )
          )
        )
    })
    assertEquals(
      result.map(_.mirrorStart.ext.ledgerState.era),
      Right(xyz.kd5ujc.scarab.protocol.Era.Byron)
    )
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
        j.deepMerge(Json.obj("initialFunds" -> Json.obj(enterpriseFund -> Json.fromInt(1))))
      else j
    )
    assertEquals(
      both,
      ConfigProblem.GenesisStart(GenesisStartError.ConflictingSources("initialFunds"))
    )
    val file = refusal((name, j) =>
      if name == "conway-genesis.json" then
        replaceExtra(
          j,
          "delegs",
          Json.obj(
            "file" -> Json.arr(Json.fromString("d.json")),
            "hash" -> Json.fromString("00" * 32)
          )
        )
      else j
    )
    file match
      case ConfigProblem.GenesisStart(
            GenesisStartError.UnsupportedFile("delegs", Vector("d.json"), _)
          ) =>
        ()
      case other => fail(s"unexpected file refusal: $other")
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
