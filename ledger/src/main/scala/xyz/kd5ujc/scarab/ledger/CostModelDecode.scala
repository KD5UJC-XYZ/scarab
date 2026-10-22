// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Node

/** The cost-model map of protocol-parameter field 18 as the reference decodes it from a transaction
  * (`CostModels.hs` `decodeCostModels`, cardano-ledger `f649f975`, lines 302-337 and 426-440). The
  * decoder version of the era selects the rule, and a map the reference refuses is refused here
  * when the transaction is decoded, not when the update is applied.
  *
  * The result is the language tag to the parameters in the order given, which is what
  * `ProtocolParams.costModels` holds: the reference keeps the original values of a known language
  * and the values of an unknown one, and `flattenCostModels` (`CostModels.hs:446-451`) puts both
  * back into one map.
  *
  * What is not mirrored: `mkCostModel` (`CostModels.hs:278-291`) hands the values to Plutus
  * `mkEvaluationContext` (plutus 1.70.0.0, `PlutusLedgerApi/Common/ParamName.hs`
  * `tagWithParamNames`), which pads a short list with `maxBound` and drops the extra values of a
  * long one, each with a warning only. For a list of `Int64` no further failure was found that can
  * be reached from the parameter names, so no length rule exists from decoder version 9 on.
  */
object CostModelDecode:

  /** The two rules of `decodeCostModels` (`CostModels.hs:311-317`). */
  enum Format:
    /** Decoder version below 9: Alonzo (5-6) and Babbage (7-8). Only the languages PlutusV1 and
      * PlutusV2 are read, each with exactly its initial number of parameters
      * (`decodeCostModelsFailing`, `decodeCostModelLegacy`, `CostModels.hs:306-337`). A duplicate
      * language is silently collapsed (`decodeMapByKey`, `Decoder.hs:810-824`).
      */
    case Legacy

    /** Decoder version 9 and above: Conway. A language is a `Word8`, every list is accepted at any
      * length, an unknown language is kept as it is and a duplicate language is refused
      * (`decodeCostModelsLenient`, `mkCostModelsLenient`, `CostModels.hs:302-304,426-440`;
      * `decodeMapByKey`, `Decoder.hs:810-824`).
      */
    case Open

  /** Parameters of a language when it was added (`costModelInitParamCount`,
    * `CostModels.hs:233-241`), for the languages the legacy rule reads.
    */
  private val initialCount: Map[Int, Int] = Map(0 -> 166, 1 -> 175)

  private val int64Min = BigInt(Long.MinValue)
  private val int64Max = BigInt(Long.MaxValue)

  def decode(n: Node, format: Format): Either[LedgerDecodeError, Map[Int, Vector[BigInt]]] =
    for
      pairs <- CborRead.map(n, "cost models")
      models <- pairs.traverse { case (k, v) =>
        language(k, format).flatMap(l => parameters(l, v, format).tupleLeft(l))
      }
      distinct = models.toMap
      _ <- Either.cond(
        format == Format.Legacy || distinct.size == models.size,
        (),
        LedgerDecodeError.DuplicateKey("cost models")
      )
    yield distinct

  /** The language key: `Language` through `decodeEnumBounded` before version 9 (0 to 3), then
    * refused above PlutusV2 (`CostModels.hs:319-323`); a `Word8` from version 9.
    */
  private def language(k: Node, format: Format): Either[LedgerDecodeError, Int] =
    CborRead.int(k, "cost model language").flatMap { l =>
      format match
        case Format.Open =>
          Either.cond(
            l >= 0 && l <= 255,
            l.toInt,
            LedgerDecodeError.NumberOutOfRange("cost model language", l, 0, 255)
          )
        case Format.Legacy if l < 0 || l > 3 =>
          Left(LedgerDecodeError.UnknownTag("cost model language", l))
        case Format.Legacy if l > 1 =>
          Left(LedgerDecodeError.UnsupportedCostModelLanguage(l.toInt))
        case Format.Legacy => Right(l.toInt)
    }

  private def parameters(
      language: Int,
      v: Node,
      format: Format
  ): Either[LedgerDecodeError, Vector[BigInt]] =
    CborRead
      .array(v, "cost model")
      .flatMap(_.traverse(int64))
      .flatMap { values =>
        format match
          case Format.Open => Right(values)
          case Format.Legacy =>
            val expected = initialCount(language)
            Either.cond(
              values.size == expected,
              values,
              LedgerDecodeError.CostModelLength(language, expected, values.size)
            )
      }

  private def int64(n: Node): Either[LedgerDecodeError, BigInt] =
    CborRead
      .int(n, "cost")
      .flatMap(c =>
        Either.cond(
          c >= int64Min && c <= int64Max,
          c,
          LedgerDecodeError.NumberOutOfRange("cost", c, int64Min, int64Max)
        )
      )
