// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.protocol.Era

/** The decoder version a part of an era is read at. The reference decodes every part of an era's
  * blocks at `eraProtVerLow` (`eraDecoder`, consensus 82ecba32 `Shelley/Ledger/Block.hs:340-350`;
  * `Cardano/Ledger/Core/Internal/Definition/Era.hs` in cardano-ledger at the pinned commit
  * f649f975), the version [[DecoderVersion.of]] gives and every decoder of an era takes by default.
  * cardano-ledger-binary's `decodeFull'` takes any `Version`, with no check against the type it
  * reads (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding.hs:93-100`), and so do the
  * decoders that take one here; [[DecoderVersion.at]] builds one of an era's range for a reader
  * that names it. Collection and field decoders branch on it with `ifDecoderVersionAtLeast`.
  */
opaque type DecoderVersion = Int

object DecoderVersion:
  /** `MaxVersion` (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Version.hs:63`,
    * cardano-ledger f649f975): the largest `Version` the reference constructs, and so the largest
    * major protocol version it decodes (`mkVersion32`, `:111-124`).
    */
  val MaxVersion: Int = 13

  /** The major protocol versions an era is read at, `eraProtVerLow` to `eraProtVerHigh`. */
  final case class Range(low: Int, high: Int):
    def contains(major: Int): Boolean = low <= major && major <= high

    /** Every version of the range, the lowest first. */
    def versions: Vector[DecoderVersion] = (low to high).toVector

  /** A major protocol version that is not one of `era`'s ([[Range]]). */
  final case class OutOfRange(era: Era, major: Int, range: Range) extends DomainError

  /** `eraProtVerLow`, the version every decoder of an era reads at by default. Byron has no parts
    * of the shapes these decoders read, which read a Byron era as Shelley, at 2: outside Byron's
    * own [[range]], and Shelley's `eraProtVerLow`.
    */
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
    * cardano-ledger f649f975): Byron 0 to 1, Shelley 2, Allegra 3, Mary 4, Alonzo 5 to 6, Babbage 7
    * to 8, Conway 9 to 11, Dijkstra 12. From Shelley on its lowest is [[of]].
    */
  def range(era: Era): Range = era match
    case Era.Byron    => Range(0, 1)
    case Era.Shelley  => Range(2, 2)
    case Era.Allegra  => Range(3, 3)
    case Era.Mary     => Range(4, 4)
    case Era.Alonzo   => Range(5, 6)
    case Era.Babbage  => Range(7, 8)
    case Era.Conway   => Range(9, 11)
    case Era.Dijkstra => Range(12, 12)

  /** `eraProtVerHigh`, the version the cardano-cbor-dataset reads an era's samples at. */
  def high(era: Era): DecoderVersion = range(era).high

  /** The decoder version `major` of `era`, which must be in the era's [[range]]. */
  def at(era: Era, major: Int): Either[OutOfRange, DecoderVersion] =
    val r = range(era)
    Either.cond(r.contains(major), major, OutOfRange(era, major, r))

  /** The decoder version of a major protocol version, one of the versions the reference constructs
    * (`mkVersion`, 0 to [[MaxVersion]],
    * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Version.hs:111-124`, cardano-ledger
    * f649f975): the version a stored record written at that version is read at.
    */
  def ofMajor(major: BigInt): Option[DecoderVersion] =
    Option.when(major >= 0 && major <= MaxVersion)(major.toInt)

  /** A protocol version's major as the decoder version (`natVersion`, `Binary/Version.hs:91-97`,
    * cardano-ledger f649f975, where both are one type, `Version`): every
    * [[xyz.kd5ujc.scarab.ledger.era.Version]] is at most [[MaxVersion]], its own bound.
    */
  def ofVersion(v: xyz.kd5ujc.scarab.ledger.era.Version): DecoderVersion = v.value

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
