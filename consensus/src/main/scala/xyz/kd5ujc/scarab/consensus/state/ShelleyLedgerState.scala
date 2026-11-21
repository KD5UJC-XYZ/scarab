// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.ledger.state.Diff
import cats.{Eq, Order, Show}
import cats.derived.*
import cats.syntax.all.*
import monocle.{Focus, Lens}
import xyz.kd5ujc.scarab.ledger.state.{
  BlockNo,
  EraTag,
  Hash32,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo,
  Word64,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.{LayoutPart, StateLayout}

/** `Word32` (the width of `shelleyAfterVoting`): the two's-complement bits of an `Int`, rendered
  * and ordered as unsigned. Every bit pattern is a word, so there is no failure to report.
  */
opaque type Word32 = Int

object Word32:
  def fromUnsigned(bits: Int): Word32 = bits

  extension (w: Word32)
    def bits: Int = w
    def toLong: Long = Integer.toUnsignedLong(w)

  given Order[Word32] = Order.from(Integer.compareUnsigned(_, _))
  given Show[Word32] = Show.show(w => s"Word32(${Integer.toUnsignedString(w)})")

/** `PerasRoundNo` (`Block/SupportsPeras.hs:85` of ouroboros-consensus 82ecba32, a `Word64`
  * newtype): a Peras voting round.
  */
opaque type PerasRoundNo = Word64

object PerasRoundNo:
  def apply(round: Word64): PerasRoundNo = round

  extension (r: PerasRoundNo) def word: Word64 = r

  given Order[PerasRoundNo] = Word64.order
  given Show[PerasRoundNo] = Show.show(r => s"PerasRoundNo(${Word64.decimal(r)})")

/** `ShelleyTip` (`Shelley/Ledger/Ledger.hs:258-263`, ouroboros-consensus 82ecba32): the last block
  * applied. The Haskell `HeaderHash (ShelleyBlock proto era)` is the 32-byte header hash.
  */
final case class ShelleyTip(slot: SlotNo, blockNo: BlockNo, hash: Hash32) derives Eq, Show, Diff

object ShelleyTip:
  val slot: Lens[ShelleyTip, SlotNo] = Focus[ShelleyTip](_.slot)
  val blockNo: Lens[ShelleyTip, BlockNo] = Focus[ShelleyTip](_.blockNo)
  val hash: Lens[ShelleyTip, Hash32] = Focus[ShelleyTip](_.hash)

/** `ShelleyTransition` (`Ledger.hs:299-322`, ouroboros-consensus 82ecba32): the number of blocks in
  * this epoch past the voting deadline, from which the hard-fork combinator learns that a hard fork
  * is certain.
  */
final case class ShelleyTransition(afterVoting: Word32) derives Eq, Show, Diff

object ShelleyTransition:
  val afterVoting: Lens[ShelleyTransition, Word32] = Focus[ShelleyTransition](_.afterVoting)

/** `LedgerState (ShelleyBlock proto era)` (`Ledger.hs:280-286`, ouroboros-consensus 82ecba32),
  * minus the `LedgerTables` (the UTxO lives in `UtxoTables` inside the new-epoch state, as
  * everywhere in the mirror): the tip, the ledger's own state, the transition information and a
  * fourth field by layout ([[ShelleyLedgerState.Layout]]).
  */
final case class ShelleyLedgerState[E <: EraTag](
    tip: WithOrigin[ShelleyTip],
    nes: NewEpochState[E],
    transition: ShelleyTransition,
    layout: ShelleyLedgerState.Layout
)

object ShelleyLedgerState:
  /** The fourth field of the state, by layout. */
  enum Layout extends LayoutPart derives Eq, Show, Diff:
    /** `shelleyLedgerLatestPerasCertRound :: StrictMaybe PerasRoundNo` (ouroboros-consensus
      * 82ecba32 `Shelley/Ledger/Ledger.hs:866-912`): none until Peras is on.
      */
    case Node11_1(latestPerasCertRound: Option[PerasRoundNo])

    /** `shelleyCumulativeTxBytes :: Word64` (ouroboros-consensus 2416493b `Ledger.hs:308`, encoding
      * `:885-922`): the bytes of every transaction applied since the chain entered Shelley.
      */
    case Leios(cumulativeTxBytes: Word64)

    def stateLayout: StateLayout = this match
      case Node11_1(_) => StateLayout.Node11_1
      case Leios(_)    => StateLayout.Leios

    /** The field after `block` is applied (`applyHelper`, ouroboros-consensus 2416493b
      * `Shelley/Ledger/Ledger.hs:708-756`): the Leios layout adds the bytes of the block's
      * transactions ([[Block.Shelley.addTxBytes]]); the 11.1 layout has no certificate round, Peras
      * not being on.
      */
    def after(block: Block.Shelley): Layout = this match
      case Node11_1(_) => Node11_1(None)
      case Leios(b)    => Leios(block.addTxBytes(b))

    /** The field after a Dijkstra block whose transactions, its own and its certified endorser
      * block's closure's, take `txBytes` (`applyHelper`, ouroboros-consensus 2416493b
      * `Shelley/Ledger/Ledger.hs:749-756`, and the closure's `shelleyCumulativeTxBytes`,
      * `Shelley/Ledger/Leios.hs:223-230`), added modulo 2^64 as `Word64` 's `+` is.
      */
    def afterBytes(txBytes: Word64): Layout = this match
      case Node11_1(_) => Node11_1(None)
      // the sum of two words' bits as a `Long` is their sum modulo 2^64
      case Leios(b) => Leios(Word64.fromUnsigned(b.bits + txBytes.bits))

  object Layout:
    /** The field as a chain's first Shelley-based state holds it: no certificate round, or no
      * transaction bytes yet (`shelleyCumulativeTxBytes = 0`, the Byron to Shelley translation's,
      * ouroboros-consensus 2416493b `Cardano/CanHardFork.hs:377`, which the later translations
      * carry, `:669`, `:754`).
      */
    def atOrigin(layout: StateLayout): Layout = layout match
      case StateLayout.Node11_1 => Node11_1(None)
      case StateLayout.Leios    => Leios(Word64.zero)

  given [E <: EraTag](using ShelleyBasedEra[E]): Eq[ShelleyLedgerState[E]] = Eq.instance((a, b) =>
    StateEq.withOrigin(a.tip, b.tip) && a.nes === b.nes && a.transition === b.transition &&
      a.layout === b.layout
  )

  given [E <: EraTag](using ShelleyBasedEra[E]): Diff[ShelleyLedgerState[E]] = Diff.record(
    Diff.at("tip")(_.tip),
    Diff.at("nes")(_.nes),
    Diff.at("transition")(_.transition),
    Diff.at("layout")(_.layout)
  )

  given [E <: EraTag](using ShelleyBasedEra[E]): Show[ShelleyLedgerState[E]] = Show.show(s =>
    s"ShelleyLedgerState(tip = ${s.tip.show}, nes = ${s.nes.show}, " +
      s"transition = ${s.transition.show}, layout = ${s.layout.show})"
  )

  def tip[E <: EraTag]: Lens[ShelleyLedgerState[E], WithOrigin[ShelleyTip]] =
    Focus[ShelleyLedgerState[E]](_.tip)
  def nes[E <: EraTag]: Lens[ShelleyLedgerState[E], NewEpochState[E]] =
    Focus[ShelleyLedgerState[E]](_.nes)
  def transition[E <: EraTag]: Lens[ShelleyLedgerState[E], ShelleyTransition] =
    Focus[ShelleyLedgerState[E]](_.transition)
  def layout[E <: EraTag]: Lens[ShelleyLedgerState[E], Layout] =
    Focus[ShelleyLedgerState[E]](_.layout)

/** `Eq` of `WithOrigin a` from `Eq a` alone: the instances of `WithOrigin` are from `Order`, which
  * a tip (Haskell: `Eq` only) does not have.
  */
private[state] object StateEq:
  def withOrigin[A: Eq](x: WithOrigin[A], y: WithOrigin[A]): Boolean = (x, y) match
    case (WithOrigin.Origin, WithOrigin.Origin) => true
    case (WithOrigin.At(a), WithOrigin.At(b))   => a === b
    case _                                      => false
