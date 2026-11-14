// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.plutus.ExUnits

/** One protocol-parameter update: field index -> original CBOR bytes, which are what is stored and
  * hashed. Two proposals are "the same update" for the quorum vote when their fields have equal
  * values ([[ParamUpdate.winners]]), however each value is written.
  */
final case class ParamUpdate(fields: Map[Int, Bytes])

object ParamUpdate:
  private val CostModelsField = 18

  /** The update keys of the `PParamsUpdate` of a pre-Conway era: the `ppUpdate` tags of its
    * `eraPParams` (cardano-ledger f649f975). Shelley, Allegra and Mary have 0 to 16
    * (`Shelley/PParams.hs:344-496`); Alonzo drops the minimum UTxO value (15) and adds 17 to 24
    * (`Alonzo/PParams.hs:344-369, 664-727`); Babbage also drops `d` (12) and the extra entropy (13)
    * (`Babbage/PParams.hs:203-226, 411`).
    */
  enum Keys(val keys: Set[Int]):
    case ShelleyToMary extends Keys((0 to 16).toSet)
    case Alonzo extends Keys(((0 to 14) ++ (16 to 24)).toSet)
    case Babbage extends Keys(((0 to 11) ++ Seq(14) ++ (16 to 24)).toSet)

  /** What the update of an era is read against: its update keys, and the largest protocol major its
    * key 14 may propose. That is `succVersion (ProtVerHigh era)` in `decodeProtVer`
    * (`BaseTypes.hs:253-262`), the decoder of `ppProtocolVersion` (`Shelley/PParams.hs:472-479`),
    * where `ProtVerHigh` is the era's `ProtVerLow` unless the era names it
    * (`Internal/Definition/Era.hs:64, 92-125`, cardano-ledger f649f975): Shelley 2, Allegra 3, Mary
    * 4, Alonzo 6, Babbage 8.
    */
  final case class Rules(keys: Keys, maxMajor: BigInt)

  object Rules:
    /** The rules of the update of `era`; none for Byron, Conway and Dijkstra, which have no PPUP.
      */
    def of(era: Era): Option[Rules] = era match
      case Era.Shelley                           => Some(Rules(Keys.ShelleyToMary, 3))
      case Era.Allegra                           => Some(Rules(Keys.ShelleyToMary, 4))
      case Era.Mary                              => Some(Rules(Keys.ShelleyToMary, 5))
      case Era.Alonzo                            => Some(Rules(Keys.Alonzo, 7))
      case Era.Babbage                           => Some(Rules(Keys.Babbage, 9))
      case Era.Byron | Era.Conway | Era.Dijkstra => None

  /** The update of a transaction or proposal from its map of field index to value, as the shared
    * sparse decoder of `PParamsUpdate` reads it below decoder version 12 (`SparseKeyed`,
    * `Core/PParams.hs:257-293`; `applyField`, `Coders.hs:562-570`, cardano-ledger f649f975):
    * entries in order, the key a `Word`, a key read before refused (`duplicateKey`), a key that is
    * not one of the era's update keys refused (`invalidKey`), and each value read at the type of
    * its field, the first bad one failing the update. The original bytes of each value are kept
    * ([[CborRead.kept]]): they are what a proposal is stored by, and [[ProtocolParams.update]]
    * reads them again when the update is applied. The quorum vote compares the values
    * ([[winners]]).
    */
  def read(
      fields: Vector[(Node, Node)],
      rules: Rules,
      costModels: CostModelDecode.Format
  )(using ByteStrings): Either[LedgerDecodeError, ParamUpdate] =
    fields
      .foldLeftM(Map.empty[Int, Bytes]) { case (acc, (k, v)) =>
        for
          index <- Unsigned.read[Unsigned.Width.W64](k, DecodeField.ParameterIndex).map(_.value)
          _ <- Either.cond(
            !(index.isValidInt && acc.contains(index.toInt)),
            (),
            LedgerDecodeError.DuplicateKey(DecodeField.ParameterUpdate)
          )
          key <- Option
            .when(index.isValidInt && rules.keys.keys.contains(index.toInt))(index.toInt)
            .toRight(LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, index))
          _ <- value(key, v, rules, costModels)
        yield acc.updated(key, CborRead.kept(v))
      }
      .map(ParamUpdate(_))

  /** [[read]] at the rules of `era`; an era without PPUP has no update (key 6 is not a field of its
    * body).
    */
  def read(
      fields: Vector[(Node, Node)],
      era: Era,
      costModels: CostModelDecode.Format
  )(using ByteStrings): Either[LedgerDecodeError, ParamUpdate] =
    Rules
      .of(era)
      .toRight(LedgerDecodeError.UnknownFields(DecodeField.TransactionBody, Set(6)))
      .flatMap(read(fields, _, costModels))

  /** The value of `key`, one of the era's update keys, read at its field's type. The keys every
    * Shelley-based era shares are read as Conway reads them ([[ConwayParam.everyEra]]). The others
    * are the fields of `ShelleyPParams` that Conway does not have (`Shelley/PParams.hs`, f649f975):
    * `d` (12, `ppD`, 454-461), a `UnitInterval`; the extra entropy (13, `ppExtraEntropy`, 463-469),
    * a `Nonce` with its hash in the form `ByteStrings` gives; the protocol version (14,
    * `ppProtocolVersion`, 472-479), read by `decodeProtVer`; and the minimum UTxO value (15,
    * `ppMinUTxOValue`, 481-488), a `CompactForm Coin`, one `Word64`. The cost models of 18 are
    * checked by the rule of the decoder version of the era ([[CostModelDecode]]).
    */
  private def value(key: Int, v: Node, rules: Rules, costModels: CostModelDecode.Format)(using
      ByteStrings
  ): Either[LedgerDecodeError, Unit] = key match
    case 12              => CborRead.rational(v, DecodeField.D, CborRead.Bound.Unit).void
    case 13              => Nonce.decode(v, Nonce.NeutralFields.Exact).void
    case 14              => protocolVersion(v, rules.maxMajor).void
    case 15              => Unsigned.read[Unsigned.Width.W64](v, DecodeField.MinUTxOValue).void
    case CostModelsField => CostModelDecode.decode(v, costModels).void
    case _ =>
      ConwayParam.everyEra
        .get(BigInt(key))
        .toRight(LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, BigInt(key)))
        .flatMap(_(v))
        .void

  /** `decodeProtVer` (`BaseTypes.hs:253-263`, cardano-ledger f649f975): the `ProtVer` record,
    * `CBORGroup` over a list of two (`BaseTypes.hs:211-214, 243-244`; `groupRecord`,
    * `Binary/Group.hs:27-35`), read whole before the era's bound is checked. Its major is read by
    * `decodeVersion`, a `Word32` that `mkVersion32` takes up to [[DecoderVersion.MaxVersion]]
    * (`Decoder.hs:462-463`, `Binary/Version.hs:111-124`), then its minor, a `Word32`; only then is
    * a major above the era's `maxMajor`, `succVersion (ProtVerHigh era)`, refused. So of a major
    * and a minor both out of range, a major beyond `MaxVersion` is the failure, and a major within
    * it but over the era's bound fails at the minor. The pre-Conway update's key 14 reads it
    * ([[Rules]]), and so does Conway's hard fork action ([[Proposal.decode]]).
    */
  private[ledger] def protocolVersion(
      v: Node,
      maxMajor: BigInt
  ): Either[LedgerDecodeError, (BigInt, BigInt)] =
    for
      xs <- CborRead.arrayN(v, 2, DecodeField.ProtocolVersion)
      major <- Unsigned.read[Unsigned.Width.W32](xs(0), DecodeField.Major)
      _ <- Either.cond(
        major.value <= DecoderVersion.MaxVersion,
        (),
        LedgerDecodeError
          .NumberOutOfRange(DecodeField.Major, major.value, 0, DecoderVersion.MaxVersion)
      )
      minor <- Unsigned.read[Unsigned.Width.W32](xs(1), DecodeField.Minor)
      _ <- Either.cond(
        major.value <= maxMajor,
        (),
        LedgerDecodeError.NumberOutOfRange(DecodeField.Major, major.value, 0, maxMajor)
      )
    yield (major.value, minor.value)

  /** The value of a field of a pre-Conway update at the type of its field, as the derived
    * `Ord (PParamsUpdate era)` compares it (`Shelley/PParams.hs:156`, `Alonzo/PParams.hs:306`,
    * `Babbage/PParams.hs:159`, f649f975): a word, coin or `EpochInterval` by its number; a
    * `UnitInterval` or `NonNegativeInterval` by its reduced rational (`Ord (BoundedRatio b a)`
    * through `promoteRatio`, `BaseTypes.hs:299-300`, of the `n % d` that `decodeRationalWithTag`
    * builds, `Plain.hs:159-167`); a `Nonce` (`BaseTypes.hs:566-570`) and a `ProtVer`
    * (`BaseTypes.hs:211-212`) by their fields; `Prices` by its two intervals (`ExUnits.hs:159-163`)
    * and an `OrdExUnits` by its memory and steps (`Alonzo/PParams.hs:413-418`); and `CostModels` by
    * the parameters of each language (`CostModels.hs:121-131, 378-382`), the known and the unknown
    * languages being one map here, as the language decides which of the two holds it.
    */
  enum Typed:
    case Count(value: BigInt)
    case Interval(value: Rational)
    case Entropy(value: Nonce)
    case Version(major: BigInt, minor: BigInt)
    case Models(models: Map[Int, Vector[BigInt]])
    case Prices(memory: Rational, steps: Rational)
    case Units(units: ExUnits)

    /** A value this build cannot read at its type. A body never gives one, as its values are read
      * when it is decoded ([[read]]); a state stored by an earlier build that kept the values of an
      * update unread may hold one (ADR 0006), and it is compared by its bytes, as that build
      * compared it.
      */
    case Unread(original: Bytes)

  /** The values of the update at their types, read as [[ProtocolParams.update]] reads them when the
    * update is applied: in any form that a body or a stored state gave them.
    */
  def typed(u: ParamUpdate): Map[Int, Typed] =
    given ByteStrings = ByteStrings.Chunked
    def count(site: DecodeSite)(n: Node) = CborRead.uint(n, site).map(Typed.Count(_))
    def interval(site: DecodeSite, bound: CborRead.Bound)(n: Node) =
      CborRead.rational(n, site, bound).map(Typed.Interval(_))
    def units(site: DecodeSite)(n: Node) =
      CborRead
        .arrayN(n, 2, site)
        .flatMap(xs => (CborRead.uint(xs(0), site), CborRead.uint(xs(1), site)).tupled)
        .map((mem, steps) => Typed.Units(ExUnits(mem, steps)))
    val readers: Map[Int, Node => Either[LedgerDecodeError, Typed]] = Map(
      0 -> count(DecodeField.MinFeeA),
      1 -> count(DecodeField.MinFeeB),
      2 -> count(DecodeField.MaxBlockBodySize),
      3 -> count(DecodeField.MaxTxSize),
      4 -> count(DecodeField.MaxBlockHeaderSize),
      5 -> count(DecodeField.KeyDeposit),
      6 -> count(DecodeField.PoolDeposit),
      7 -> count(DecodeField.EMax),
      8 -> count(DecodeField.NOpt),
      9 -> interval(DecodeField.A0, CborRead.Bound.NonNegative),
      10 -> interval(DecodeField.Rho, CborRead.Bound.Unit),
      11 -> interval(DecodeField.Tau, CborRead.Bound.Unit),
      12 -> interval(DecodeField.D, CborRead.Bound.Unit),
      13 -> (n => Nonce.decode(n, Nonce.NeutralFields.Stored).map(Typed.Entropy(_))),
      14 -> (n =>
        CborRead
          .arrayN(n, 2, DecodeField.ProtocolVersion)
          .flatMap(xs =>
            (CborRead.uint(xs(0), DecodeField.Major), CborRead.uint(xs(1), DecodeField.Minor))
              .mapN(Typed.Version(_, _))
          )
      ),
      15 -> count(DecodeField.MinUTxOValue),
      16 -> count(DecodeField.MinPoolCost),
      17 -> count(DecodeField.CoinsPerUTxO),
      CostModelsField -> (n => ProtocolParams.costModels(n).map(Typed.Models(_))),
      19 -> (n =>
        CborRead
          .arrayN(n, 2, DecodeField.Prices)
          .flatMap(xs =>
            (
              CborRead.rational(xs(0), DecodeField.PriceMem, CborRead.Bound.NonNegative),
              CborRead.rational(xs(1), DecodeField.PriceSteps, CborRead.Bound.NonNegative)
            ).mapN(Typed.Prices(_, _))
          )
      ),
      20 -> units(DecodeField.MaxTxExUnits),
      21 -> units(DecodeField.MaxBlockExUnits),
      22 -> count(DecodeField.MaxValueSize),
      23 -> count(DecodeField.CollateralPercentage),
      24 -> count(DecodeField.MaxCollateralInputs)
    )
    u.fields.map { (key, raw) =>
      val value = for
        read <- readers
          .get(key)
          .toRight(LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, key))
        n <- CborRead.decode(raw)
        v <- read(n)
      yield v
      key -> value.getOrElse(Typed.Unread(raw))
    }

  /** `upgradePParamsUpdate ()` at the Alonzo-to-Babbage boundary (`Babbage/Translation.hs:155-168`,
    * `Babbage/PParams.hs:180,316-345,389-391`, cardano-ledger f649f975): drop the removed fields
    * and divide the cost per UTxO word by eight, rounded down. Other fields keep their original
    * bytes. A legacy stored key-17 value that [[typed]] cannot read stays unread and cannot be
    * adopted, as before (ADR 0006); a reference-decoded proposal always has a `Count` at key 17 if
    * it has that key.
    */
  def toBabbage(u: ParamUpdate): ParamUpdate =
    val kept = u.fields -- Set(12, 13, 15)
    typed(u).get(17) match
      case Some(Typed.Count(words)) =>
        ParamUpdate(kept.updated(17, byron.ByronCbor.uint(words / 8)))
      case _ => ParamUpdate(kept)

  /** The updates that at least `quorum` of `proposals` vote for: `votedFuturePParams`
    * (`Rules/Ppup.hs:214-220`, f649f975) counts the proposals in a
    * `Map (PParamsUpdate era) Word64`, so proposals whose fields have equal values ([[typed]]) are
    * one update, however each value is written. Of each such update the first proposal in
    * `proposals` is returned; the proposals of one update apply alike, as they set the same fields
    * to the same values.
    */
  def winners(proposals: Vector[ParamUpdate], quorum: Int): Vector[ParamUpdate] =
    proposals
      .groupBy(typed)
      .valuesIterator
      .collect { case vs @ (first +: _) if vs.size >= quorum => first }
      .toVector
