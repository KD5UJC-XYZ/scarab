// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.protocol.Era

/** The decoder version a part of an era is read at. The reference decodes every part of an era's
  * blocks at `eraProtVerLow` (`eraDecoder`, consensus 82ecba32 `Shelley/Ledger/Block.hs:340-350`;
  * `Cardano/Ledger/Core/Internal/Definition/Era.hs` in cardano-ledger at the pinned commit
  * f649f975), the version [[DecoderVersion.of]] gives and every decoder of an era takes by default.
  * cardano-ledger-binary's `decodeFull'` takes any version, and a reader of an era's parts may name
  * one of the era's range ([[DecoderVersion.at]]); the decoders that take it refuse one outside the
  * era they read ([[LedgerDecodeError.VersionOutsideEra]]). Collection and field decoders branch on
  * it with `ifDecoderVersionAtLeast`.
  */
opaque type DecoderVersion = Int

object DecoderVersion:
  /** `MaxVersion` (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Version.hs:63`): the
    * largest `Version` the reference constructs, and so the largest major protocol version it
    * decodes (`mkVersion32`, `:111-124`).
    */
  val MaxVersion: Int = 13

  /** The major protocol versions an era is read at, `eraProtVerLow` to `eraProtVerHigh`. */
  final case class Range(low: Int, high: Int):
    def contains(major: Int): Boolean = low <= major && major <= high

    /** Every version of the range, the lowest first. */
    def versions: Vector[DecoderVersion] = (low to high).toVector

  /** A major protocol version that is not one of `era`'s ([[Range]]). */
  final case class OutOfRange(era: Era, major: Int, range: Range) extends DomainError

  /** `eraProtVerLow`. Byron has no transactions of this shape and reads as Shelley. */
  def of(era: Era): DecoderVersion = era match
    case Era.Byron | Era.Shelley => 2
    case Era.Allegra             => 3
    case Era.Mary                => 4
    case Era.Alonzo              => 5
    case Era.Babbage             => 7
    case Era.Conway              => 9
    case Era.Dijkstra            => Dijkstra

  /** `ProtVerLow` and `ProtVerHigh` of the era
    * (`libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:87-141`,
    * cardano-ledger f649f975): Alonzo 5 to 6, Babbage 7 to 8, Conway 9 to 11, and one version for
    * each other era. Byron reads as Shelley ([[of]]), so its range is Shelley's, not the
    * reference's 0 to 1, which no decoder of these shapes is read at.
    */
  def range(era: Era): Range =
    val low = of(era)
    era match
      case Era.Alonzo  => Range(low, 6)
      case Era.Babbage => Range(low, 8)
      case Era.Conway  => Range(low, 11)
      case _           => Range(low, low)

  /** `eraProtVerHigh`, the version the cardano-cbor-dataset reads an era's samples at. */
  def high(era: Era): DecoderVersion = range(era).high

  /** The decoder version `major` of `era`, which must be in the era's [[range]]. */
  def at(era: Era, major: Int): Either[OutOfRange, DecoderVersion] =
    val r = range(era)
    Either.cond(r.contains(major), major, OutOfRange(era, major, r))

  /** `version` when it is one of `era`'s ([[at]]). */
  def within(era: Era, version: DecoderVersion): Either[OutOfRange, DecoderVersion] =
    at(era, version)

  /** Dijkstra's `eraProtVerLow`, 12 (`type ProtVerLow DijkstraEra = 12`,
    * `libs/cardano-ledger-core/internal/Cardano/Ledger/Internal/Definition/Era.hs:140`,
    * cardano-ledger f649f975). The node has no Dijkstra ledger: the arguments of a node-to-client
    * query of that era are read at it, as the reference reads them (#720), and the transactions of
    * a Leios block by the readers of [[dijkstra.V12]], which follow the Leios testnet's build
    * (#750).
    */
  val Dijkstra: DecoderVersion = 12

  extension (v: DecoderVersion)
    /** `ifDecoderVersionAtLeast (natVersion @n)`. */
    def atLeast(n: Int): Boolean = v >= n

    def value: Int = v
