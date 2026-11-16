// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import io.circe.{ACursor, Json}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError, Rational}
import xyz.kd5ujc.scarab.consensus.HardForkConfig
import xyz.kd5ujc.scarab.ledger.{
  ByronDelegation,
  ByronGenesis,
  ConfigError,
  ConfigProblem,
  ConwayGenesis,
  CostModelProblem,
  EpochGeometry,
  GenesisCostModels,
  GenesisFile,
  GenesisSchema,
  LedgerDecodeError
}
import xyz.kd5ujc.scarab.ledger.GenesisState.{InjectionData, InjectionSource}
import xyz.kd5ujc.scarab.ledger.byron.{ByronAddress, ByronCbor}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraGenesis
import xyz.kd5ujc.scarab.ledger.json.BoundedRatio
import xyz.kd5ujc.scarab.ledger.rules.Delegatee
import xyz.kd5ujc.scarab.ledger.state.{
  Credential,
  DRepRole,
  DRepState,
  Hash32,
  ProtVer,
  ScriptHash,
  ShelleyBasedEra,
  StakePool,
  Staking,
  VocabError
}
import xyz.kd5ujc.scarab.models.runtime.{Codec, ConstraintViolation}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Versions}

/** The answer to `DebugLedgerConfig`: the ledger configuration of the hard-fork combinator, which
  * the reference answers from its configuration whatever the state (`topLevelConfigLedger`,
  * `Ouroboros/Consensus/Ledger/Query.hs:275-276`, ouroboros-consensus 82ecba32) and writes as
  * `[shape, per-era partial ledger configurations]` (`encodeNodeToClient` of
  * `HardForkLedgerConfig`,
  * `HardFork/Combinator/Serialisation/SerialiseNodeToClient.hs:193-200, 330-333`), each list over
  * the eight eras every node-to-client version enables (`:76-110`). Its parts are those
  * `protocolInfoCardano` builds (`Cardano/Node.hs:633-639, 662-666, 712-843, 849-898`):
  *   - the shape: Byron's era parameters (`byronEraParams`, `Byron/Ledger/Ledger.hs:323-333`) and
  *     the same Shelley ones for the seven Shelley-based eras (`shelleyEraParams`,
  *     `Shelley/Ledger/Ledger.hs:168-185`);
  *   - Byron's partial configuration, `[genesis configuration, trigger]`
  *     (`Byron/ByronHFC.hs:275-286`), the configuration as cardano-node reads it
  *     (`Cardano/Node/Protocol/Byron.hs:87-101`, cardano-node 11.1.3): the genesis data and hash,
  *     `RequiresNetworkMagic` and no asset-locked addresses;
  *   - each Shelley-based era's, 13 items (`Shelley/Node/Serialisation.hs:270-331`): the compact
  *     genesis, the ten genesis fields of `Globals`, the era's translation context and its trigger.
  *     The trigger of each era is the next era's (`toTriggerHardFork`, `Node.hs:473-483`: the
  *     configured epoch, or the next era's first major version); Conway's and Dijkstra's is
  *     `TriggerHardForkNotDuringThisExecution` whatever the configuration (`Node.hs:820-843`).
  */
object LedgerConfig:
  /** Why the answer cannot be written from the configuration. */
  enum Error extends DomainError:
    case Shelley(cause: GenesisConfig.Error)
    case Genesis(cause: ConfigError)
    case CostModels(cause: CostModelProblem)

    /** An unsigned integer outside the range of the word it is written as. */
    case OutOfRange(cause: WordOutOfRange)

    /** A ratio outside the range of the bounded ratio of two `Word64`s it is written as. */
    case RatioOutOfRange(cause: xyz.kd5ujc.scarab.node.ntc.RatioOutOfRange)

    /** A hash of the Conway genesis whose size is not its type's. */
    case Hash(cause: VocabError)

    /** A value the ledger state's codec refuses to write. */
    case Constraint(cause: ConstraintViolation)

    /** A Byron genesis balance's address that does not decode. */
    case UndecodableAddress(cause: LedgerDecodeError)

    /** A Byron genesis value outside the range of its type. */
    case Byron(cause: Unanswered)

    def message: String = this match
      case Shelley(e)            => e.message
      case Genesis(e)            => e.message
      case CostModels(e)         => e.message
      case OutOfRange(e)         => e.message
      case RatioOutOfRange(e)    => e.message
      case Hash(e)               => e.show
      case Constraint(e)         => e.toString
      case UndecodableAddress(e) => e.show
      case Byron(e)              => e.toString

  /** What the answer is made of: the configuration as the node read it at start.
    *
    * @param requiresMagic
    *   `RequiresNetworkMagic` of the configuration, `RequiresNoMagic` when it is absent
    *   (`Configuration/POM.hs:598-599`, cardano-node 11.1.3)
    * @param geometry
    *   the Shelley era's slots and stability windows
    * @param delegs
    *   the Conway genesis' delegations, as the ledger reads them
    * @param initialDReps
    *   the Conway genesis' DReps, as the ledger reads them
    * @param dijkstra
    *   the Dijkstra genesis, read only with `ExperimentalHardForksEnabled`; without it the
    *   reference takes `emptyDijkstraGenesis` (`Cardano/Node/Protocol/Cardano.hs:137-144`,
    *   `Protocol/Dijkstra.hs:38-46`, cardano-node 11.1.3)
    * @param epochs
    *   the `Test<Era>HardForkAtEpoch` settings
    */
  final case class Source(
      byron: ByronGenesis,
      requiresMagic: Boolean,
      geometry: EpochGeometry,
      shelley: Json,
      alonzo: Json,
      conway: Json,
      delegs: InjectionSource[(Credential[Staking], Delegatee)],
      initialDReps: InjectionSource[(Credential[DRepRole], DRepState)],
      dijkstra: Option[DijkstraGenesis],
      epochs: Map[Era, BigInt]
  )

  private type R[A] = Either[Error, A]

  /** The result item, or why there is none, as [[QueryEnv]] holds it. */
  def answer(encoded: R[Bytes]): Either[Unanswered, Bytes] =
    encoded.leftMap(Unanswered.UnwritableLedgerConfig(_))

  /** The result item. Every value is checked against the range of the type it is written as before
    * it is written, so that a configuration this node read writes an answer or says why it has
    * none.
    */
  def encode(s: Source): R[Bytes] =
    for
      shape <- shape(s)
      byron <- byronConfig(s.byron, s.requiresMagic)
      compact <- GenesisConfig.compact(s.shelley).leftMap(Error.Shelley(_))
      stability <- bounded(s.geometry.stabilityWindow.toBigInt, WordWidth.W64)
      randomness <- bounded(s.geometry.randomnessStabilisationWindow.toBigInt, WordWidth.W64)
      globals <- GenesisConfig.globals(s.shelley, stability, randomness).leftMap(Error.Shelley(_))
      fromByron <- GenesisConfig.fromByronContext(s.shelley).leftMap(Error.Shelley(_))
      alonzo <- alonzoGenesis(s.alonzo)
      conway <- conwayGenesis(s.conway, s.delegs, s.initialDReps)
      dijkstra <- dijkstraGenesis(s.dijkstra)
      triggers <- Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
        .traverse(trigger(s, _))
    yield
      def era(context: Bytes, trigger: Bytes): Bytes =
        CborOut.build(_.array(13).raw(compact).raw(globals).raw(context).raw(trigger))
      val noGenesis = CborOut.build(_.nul)
      CborOut.build { w =>
        w.array(2).raw(shape)
        w.array(Era.values.length)
        w.array(2).raw(byron).raw(triggers(0))
        w.raw(era(fromByron, triggers(1)))
        w.raw(era(noGenesis, triggers(2)))
        w.raw(era(noGenesis, triggers(3)))
        w.raw(era(alonzo, triggers(4)))
        w.raw(era(noGenesis, triggers(5)))
        w.raw(era(conway, notDuringThisExecution))
        w.raw(era(dijkstra, notDuringThisExecution))
      }

  private val word64 = BigInt(1) << 64

  /** `value`, as the word of `width` it is checked to be. */
  private def bounded(value: BigInt, width: WordWidth): R[BoundedWord] =
    BoundedWord.of(value, width).leftMap(Error.OutOfRange(_))

  /** `r`, as the bounded ratio of two `Word64`s it is checked to be. */
  private def ratio(r: Rational): R[WordRatio] =
    WordRatio.of(r).leftMap(Error.RatioOutOfRange(_))

  /** `History.Shape`: Byron's era parameters, then the Shelley ones seven times. Byron's safe zone
    * and genesis window are `2 * k` in `Word64` (`Byron/Ledger/Ledger.hs:323-333`,
    * ouroboros-consensus 82ecba32); the Shelley ones the stability window, `ceiling (3k/f)` as a
    * `Word64` ([[EpochGeometry.stabilityWindow]]).
    */
  private def shape(s: Source): R[Bytes] =
    for
      byronSize <- bounded(s.byron.epochSlots, WordWidth.W64)
      byronWindow <- bounded((2 * s.byron.securityParam).mod(word64), WordWidth.W64)
      shelleySize <- bounded(BigInt(s.geometry.epochLength), WordWidth.W64)
      window <- bounded(s.geometry.stabilityWindow.toBigInt, WordWidth.W64)
    yield CborOut.build { w =>
      w.array(Era.values.length)
      eraParams(w, byronSize, s.byron.slotLengthMillis, byronWindow)
      Era.values.toVector.drop(1).foreach { _ =>
        eraParams(w, shelleySize, BigInt(s.geometry.slotLengthMillis), window)
      }
    }

  /** `Serialise EraParams` as [[EraHistory.params]] writes it, of checked words: the safe zone and
    * the genesis window are the same word in every era of the shape.
    */
  private def eraParams(w: CborOut, size: BoundedWord, slotMillis: BigInt, window: BoundedWord) =
    w.array(4).word(size).integer(slotMillis)
    w.array(3).uint(0).word(window).array(1).uint(0)
    w.word(window)

  /** The trigger of the hard fork into `next` (`SerialiseNodeToClient TriggerHardFork`,
    * `HardFork/Simple.hs:41-45`, ouroboros-consensus 82ecba32): `[1, epoch]` for a configured
    * epoch, `[0, version]` for the era's first major version (`L.eraProtVerLow`).
    */
  private def trigger(s: Source, next: Era): R[Bytes] =
    s.epochs
      .get(next)
      .fold(
        Right(CborOut.build(_.array(2).uint(0).uint(HardForkConfig.eraProtVerLow(next))))
      )(e => bounded(e, WordWidth.W64).map(epoch => CborOut.build(_.array(2).uint(1).word(epoch))))

  private val notDuringThisExecution: Bytes = CborOut.build(_.array(1).uint(2))

  // ---- Byron

  import ByronUpdateInterface.{concat, hash, map, set, word}

  /** `Config` (`Cardano/Chain/Genesis/Config.hs:176-190`, cardano-ledger f649f975) at Byron's
    * version: the genesis data, its hash, `RequiresNetworkMagic` (`RequiresNoMagic` 0,
    * `RequiresMagic` 1, `Crypto/ProtocolMagic.hs:116-119`) and `defaultUTxOConfiguration`, an empty
    * set of asset-locked addresses (`UTxO/UTxOConfiguration.hs:42-45`).
    */
  private def byronConfig(b: ByronGenesis, requiresMagic: Boolean): R[Bytes] =
    for
      addresses <- b.nonAvvm.traverse((raw, lovelace) =>
        ByronAddress
          .decode(raw)
          .leftMap(Error.UndecodableAddress(_))
          .map(_ -> lovelace)
      )
      written <- concat(
        Right(CborOut.build(_.array(4))),
        genesisData(b, addresses),
        hash(32)(b.hash),
        Right(CborOut.build(_.uint(if requiresMagic then 1 else 0))),
        Right(CborOut.build(_.array(1).set(0)))
      ).leftMap(Error.Byron(_))
    yield written

  /** `GenesisData` (`Genesis/Data.hs:127-149`, cardano-ledger f649f975): the genesis key hashes,
    * the heavy delegation, the start time, the non-AVVM balances, the protocol parameters, `k`, the
    * protocol magic and the AVVM balances, each wrapper a list of one
    * (`Genesis/KeyHashes.hs:50-51`, `Genesis/Delegation.hs:66-69`,
    * `Genesis/NonAvvmBalances.hs:70-73`, `Genesis/AvvmBalances.hs:50-53`). The AVVM balances are
    * empty: the ledger configuration is the genesis configuration `compactGenesisConfig` empties of
    * them (`Byron/Ledger/Config.hs:98-114`, `Byron/Node.hs:205-221`, ouroboros-consensus 82ecba32).
    * The maps are in the order of their keys' `Ord`: a key hash by its bytes, an `Address` as
    * [[ByronAddress]] orders it (`Common/Address.hs:141-151`).
    */
  private def genesisData(
      b: ByronGenesis,
      nonAvvm: Vector[(ByronAddress, BigInt)]
  ): ByronUpdateInterface.Out =
    val delegations = b.delegations.map(d => ByronCbor.keyHash(d.issuer) -> d)
    concat(
      Right(CborOut.build(_.array(8).array(1))),
      set(b.genesisKeys.sorted)(hash(28)),
      Right(CborOut.build(_.array(1))),
      map(delegations)(hash(28), certificate)(identity),
      Right(CborOut.build(GenesisConfig.utcTime(_, BigInt(b.startTime), 0))),
      Right(CborOut.build(_.array(1))),
      map(nonAvvm)(a => Right(a.raw), lovelace => word(lovelace, 64))(identity),
      ByronUpdateInterface.params(b.params),
      word(b.securityParam, 64),
      word(BigInt(b.protocolMagic), 32),
      Right(CborOut.build(_.array(1).map(0)))
    )

  /** `Certificate` (`Delegation/Certificate.hs:195-201`, cardano-ledger f649f975): `[epoch, issuer,
    * delegate, signature]`.
    */
  private def certificate(d: ByronDelegation): ByronUpdateInterface.Out =
    concat(
      Right(CborOut.build(_.array(4))),
      word(d.epoch, 64),
      hash(64)(d.issuer),
      hash(64)(d.delegate),
      hash(64)(d.cert)
    )

  // ---- the translation contexts

  private def get[A: io.circe.Decoder](c: ACursor, name: String, file: String): R[A] =
    c.downField(name)
      .as[A]
      .leftMap(e => Error.Genesis(ConfigError(ConfigProblem.JsonField(file, name, e))))

  private def jsonWord(c: ACursor, name: String, file: String, width: WordWidth): R[BoundedWord] =
    get[BigInt](c, name, file).flatMap(bounded(_, width))

  /** `.:?`: a key that is absent or null has no value. */
  private def present(c: ACursor, name: String): Boolean =
    c.downField(name).focus.exists(!_.isNull)

  /** `ToCBOR AlonzoGenesis` (`Alonzo/Genesis.hs:207-220`, cardano-ledger f649f975) at Alonzo's
    * version: the coins per word, a `Coin`, an `Integer` (`CoinPerWord`,
    * `Alonzo/PParams.hs:401-403`; `Coin.hs:93-106`), the PlutusV1 model as a definite list
    * (`encodeCostModel`, `Plutus/CostModels.hs:488-489`), the prices, the transaction's and the
    * block's execution units, the maximum value size (`Word32`), the collateral percentage and
    * inputs (`Word16`), and the extra configuration: none `[]`, or `[[models]]` (`StrictMaybe` of
    * `AlonzoExtraConfig`, `:103-108`), its models `null` when it has none (`encodeNullMaybe`) and
    * otherwise `flattenCostModels`, a map of each language to a list
    * (`Plutus/CostModels.hs:470-471`; the map and the lists framed by their length, [[mapHead]] and
    * [[list]]).
    */
  private def alonzoGenesis(json: Json): R[Bytes] =
    val a = json.hcursor
    val file = "alonzo genesis"
    def price(first: String, second: String) =
      def one(name: String) =
        GenesisSchema.ratio(GenesisFile.Alonzo, BoundedRatio.NonNegative)(
          a.downField("executionPrices").downField(name),
          Vector("executionPrices", name)
        )
      one(first).orElse(one(second)).leftMap(Error.Genesis(_)).flatMap(ratio)
    def units(name: String) =
      (
        GenesisSchema.unit(GenesisFile.Alonzo)(
          a.downField(name),
          Vector(name),
          "memory",
          "exUnitsMem"
        ),
        GenesisSchema.unit(GenesisFile.Alonzo)(
          a.downField(name),
          Vector(name),
          "steps",
          "exUnitsSteps"
        )
      ).tupled
        .leftMap(Error.Genesis(_))
        .flatMap((memory, steps) =>
          (bounded(memory, WordWidth.W64), bounded(steps, WordWidth.W64)).tupled
        )
    for
      perWord <- get[BigInt](a, "lovelacePerUTxOWord", file)
      models <- GenesisCostModels.alonzo(json).leftMap(Error.CostModels(_))
      priceMemory <- price("priceMemory", "prMem")
      priceSteps <- price("priceSteps", "prSteps")
      tx <- units("maxTxExUnits")
      block <- units("maxBlockExUnits")
      maxValue <- jsonWord(a, "maxValueSize", file, WordWidth.W32)
      collateral <- jsonWord(a, "collateralPercentage", file, WordWidth.W16)
      maxCollateral <- jsonWord(a, "maxCollateralInputs", file, WordWidth.W16)
      languages <- models.extra
        .traverse(
          _.flatten.toVector
            .sortBy(_._1)
            .traverse((l, v) => bounded(BigInt(l), WordWidth.W8).map(_ -> v))
        )
    yield CborOut.build { w =>
      w.array(9).integer(perWord)
      w.array(models.plutusV1.size)
      models.plutusV1.foreach(p => w.int(BigInt(p)))
      w.array(2)
      w.ratio(priceMemory).ratio(priceSteps)
      w.array(2).word(tx._1).word(tx._2)
      w.array(2).word(block._1).word(block._2)
      w.word(maxValue).word(collateral).word(maxCollateral)
      if !present(a, "extraConfig") then w.array(0)
      else
        w.array(1).array(1)
        languages.fold(w.nul) { flat =>
          mapHead(w, flat.size)
          flat.foreach((language, values) => list(w.word(language), values)(w.int))
          if flat.sizeIs > 23 then w.break else w
        }
    }

  /** A list as `encCBOR` writes one (`encodeList`, `Binary/Encoding/Encoder.hs:442-443, 499-508`,
    * cardano-ledger-binary 048b78d2): definite up to 23 elements, indefinite beyond.
    */
  private def list[A](w: CborOut, items: Vector[A])(item: A => CborOut): CborOut =
    if items.sizeIs <= 23 then
      w.array(items.size)
      items.foreach(item)
      w
    else
      w.indefiniteArray
      items.foreach(item)
      w.break

  /** The head of a `Map` as `encodeMap` writes one from version 2
    * (`Binary/Encoding/Encoder.hs:391-408`, cardano-ledger-binary 048b78d2): definite up to 23
    * pairs, indefinite beyond, which the caller then ends with a break.
    */
  private def mapHead(w: CborOut, size: Int): CborOut =
    if size <= 23 then w.map(size) else w.indefiniteMap

  /** `ToCBOR ConwayGenesis` (`Conway/Genesis.hs:138-148`, cardano-ledger f649f975) at Conway's
    * version: the parameters the translation introduces (`UpgradeConwayPParams`,
    * `Conway/PParams.hs:746-757, 795-808`: the committee size a `Word16`, the intervals
    * `EpochInterval` s, `Word32`, the deposits `Coin` s, `Integer` s), the constitution, the
    * committee, the delegations and the DReps (`ListMap`s, written as definite maps in their order,
    * `Data/ListMap.hs:86-89`, which is the order of their keys, `:107-121`) and the extra
    * configuration: none `[]`, or `[[delegations, DReps]]` (`ConwayExtraConfig`, `:79-84`), each an
    * `InjectionData` (`Shelley/Genesis.hs:808-813`).
    */
  private def conwayGenesis(
      json: Json,
      delegs: InjectionSource[(Credential[Staking], Delegatee)],
      initialDReps: InjectionSource[(Credential[DRepRole], DRepState)]
  ): R[Bytes] =
    given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)
    val c = json.hcursor
    val file = "conway genesis"
    for
      voting <- ConwayGenesis.votingParams(c).leftMap(Error.Genesis(_))
      (pool, drep, minSize, maxTerm) = voting
      poolRatios <- pool.toVector.traverse(ratio)
      drepRatios <- drep.toVector.traverse(ratio)
      committeeMinSize <- bounded(minSize, WordWidth.W16)
      committeeMaxTerm <- bounded(maxTerm, WordWidth.W32)
      lifetime <- jsonWord(c, "govActionLifetime", file, WordWidth.W32)
      actionDeposit <- get[BigInt](c, "govActionDeposit", file)
      drepDeposit <- get[BigInt](c, "dRepDeposit", file)
      drepActivity <- jsonWord(c, "dRepActivity", file, WordWidth.W32)
      refScriptCost <- GenesisSchema
        .ratio(GenesisFile.Conway, BoundedRatio.NonNegative)(
          c.downField("minFeeRefScriptCostPerByte"),
          Vector("minFeeRefScriptCostPerByte")
        )
        .leftMap(Error.Genesis(_))
        .flatMap(ratio)
      v3 <- GenesisCostModels.conwayPlutusV3(json).leftMap(Error.CostModels(_))
      governance <- ConwayGenesis.committeeAndConstitution(c).leftMap(Error.Genesis(_))
      (committee, constitution) = governance
      committeeBytes <- committee.traverse(writeCodec(HaskellStateCodecs.committee, _))
      constitutionBytes <- constitutionOf(constitution)
      delegsBytes <- listMap(delegs.legacy)(credential[Staking], delegatee)
      drepsBytes <- listMap(initialDReps.legacy)(credential[DRepRole], drepState)
      extra <- (
        injection(delegs)(credential[Staking], delegatee),
        injection(initialDReps)(credential[DRepRole], drepState)
      ).tupled
    yield CborOut.build { w =>
      w.array(6).array(10)
      w.array(5)
      poolRatios.foreach(w.ratio)
      w.array(10)
      drepRatios.foreach(w.ratio)
      w.word(committeeMinSize).word(committeeMaxTerm).word(lifetime)
      w.integer(actionDeposit).integer(drepDeposit)
      w.word(drepActivity)
      w.ratio(refScriptCost)
      w.array(v3.size)
      v3.foreach(p => w.int(BigInt(p)))
      w.raw(constitutionBytes)
      committeeBytes.fold(w.array(0))(w.raw)
      w.raw(delegsBytes).raw(drepsBytes)
      if !present(c, "extraConfig") then w.array(0)
      else w.array(1).array(2).raw(extra._1).raw(extra._2)
    }

  private def writeCodec[A](codec: Codec[A], a: A): R[Bytes] =
    codec.write(a).leftMap(Error.Constraint(_))

  private def credential[K <: xyz.kd5ujc.scarab.ledger.state.KeyRole](
      c: Credential[K]
  ): R[Bytes] = writeCodec(HaskellStateCodecs.credential[K], c)

  /** `Anchor` (`BaseTypes.hs:1006-1011`, cardano-ledger f649f975): `[url, data hash]`, the url any
    * text, as the genesis' `FromJSON` reads it and `EncCBOR` writes it (`Url`, `:674-676`); only
    * the decoder bounds its length (`:678-684`), which is why the ledger state's codec, which
    * mirrors the decoder, is not used here.
    */
  private def anchorOf(a: xyz.kd5ujc.scarab.ledger.Anchor): R[Bytes] =
    Hash32
      .fromBytes(a.hash)
      .leftMap(Error.Hash(_))
      .map(hash => CborOut.build(_.array(2).text(a.url).bytes(hash.bytes)))

  /** `Constitution` (`Conway/Governance/Procedures.hs:997-1002`, cardano-ledger f649f975):
    * `[anchor, guardrails script hash or null]`.
    */
  private def constitutionOf(c: xyz.kd5ujc.scarab.ledger.Constitution): R[Bytes] =
    (anchorOf(c.anchor), c.script.traverse(ScriptHash.fromBytes(_).leftMap(Error.Hash(_))))
      .mapN((anchor, script) =>
        CborOut.build { w =>
          w.array(2).raw(anchor)
          script.fold(w.nul)(h => w.bytes(h.bytes))
        }
      )

  /** `DRepState` (`DRep.hs:166-171, 192-199`, cardano-ledger f649f975): `[expiry, anchor, deposit,
    * delegators]`, the anchor a `StrictMaybe` (`[]` or `[anchor]`, [[anchorOf]]) and the delegators
    * a set of credentials, as the ledger state's codec writes them.
    */
  private def drepState(d: DRepState)(using ProtVer): R[Bytes] =
    for
      expiry <- bounded(d.expiry.toBigInt, WordWidth.W64)
      deposit <- bounded(d.deposit.toBigInt, WordWidth.W64)
      anchor <- d.anchor.traverse(anchorOf)
      delegators <- writeCodec(
        xyz.kd5ujc.scarab.storage.haskell.state.Wire.setOf(HaskellStateCodecs.credential[Staking]),
        d.delegators
      )
    yield CborOut.build { w =>
      w.array(4).word(expiry)
      anchor.fold(w.array(0))(a => w.array(1).raw(a))
      w.word(deposit).raw(delegators)
    }

  /** `Delegatee` (`Conway/TxCert.hs:408-413`, cardano-ledger f649f975): `[0, pool]`, `[1, DRep]` or
    * `[2, pool, DRep]`.
    */
  private def delegatee(d: Delegatee): R[Bytes] =
    def pool(p: xyz.kd5ujc.scarab.ledger.state.KeyHash[StakePool]) =
      writeCodec(HaskellStateCodecs.keyHash[StakePool], p)
    def drep(r: xyz.kd5ujc.scarab.ledger.state.DRep) = writeCodec(HaskellStateCodecs.drep, r)
    d match
      case Delegatee.Stake(p) => pool(p).map(b => CborOut.build(_.array(2).uint(0).raw(b)))
      case Delegatee.Vote(r)  => drep(r).map(b => CborOut.build(_.array(2).uint(1).raw(b)))
      case Delegatee.StakeVote(p, r) =>
        (pool(p), drep(r)).mapN((pb, rb) => CborOut.build(_.array(3).uint(2).raw(pb).raw(rb)))

  /** A `ListMap`: a definite map of the entries in their order. */
  private def listMap[K, V](entries: Vector[(K, V)])(
      key: K => R[Bytes],
      value: V => R[Bytes]
  ): R[Bytes] =
    entries
      .traverse((k, v) => (key(k), value(v)).tupled)
      .map(written =>
        CborOut.build { w =>
          w.map(written.size)
          written.foreach((k, v) => w.raw(k).raw(v))
        }
      )

  /** `InjectionData` (`Shelley/Genesis.hs:808-813`, cardano-ledger f649f975): `[0]` for none,
    * `[1, path, hash]` for a file (its path segments a list of texts, [[list]]), `[2, entries]` for
    * embedded data.
    */
  private def injection[K, V](source: InjectionSource[(K, V)])(
      key: K => R[Bytes],
      value: V => R[Bytes]
  ): R[Bytes] = source.extra match
    case InjectionData.NoInjection => Right(CborOut.build(_.array(1).uint(0)))
    case InjectionData.FromFile(segments, digest) =>
      Right(CborOut.build(w => list(w.array(3).uint(1), segments)(w.text).bytes(digest.bytes)))
    case InjectionData.Embedded(entries) =>
      listMap(entries)(key, value).map(m => CborOut.build(_.array(2).uint(2).raw(m)))

  /** `ToCBOR DijkstraGenesis` (`Dijkstra/Genesis.hs`, `Dijkstra/PParams.hs:250-256, 296-303`,
    * cardano-ledger f649f975): `[[block and transaction reference-script limits, stride,
    * multiplier]]`, three `Word32` s and a positive interval; without a configured genesis,
    * `emptyDijkstraGenesis`: 1048576, 204800, 25600 and 1.2.
    */
  private def dijkstraGenesis(genesis: Option[DijkstraGenesis]): R[Bytes] =
    val (block, tx, stride, multiplier) = genesis.fold(
      (BigInt(1048576), BigInt(204800), BigInt(25600), Rational(6, 5))
    )(g =>
      (
        g.maxRefScriptSizePerBlock,
        g.maxRefScriptSizePerTx,
        g.refScriptCostStride,
        g.refScriptCostMultiplier
      )
    )
    for
      blockLimit <- bounded(block, WordWidth.W32)
      txLimit <- bounded(tx, WordWidth.W32)
      strideWord <- bounded(stride, WordWidth.W32)
      multiplierRatio <- ratio(multiplier)
    yield CborOut.build(
      _.array(1).array(4).word(blockLimit).word(txLimit).word(strideWord).ratio(multiplierRatio)
    )
