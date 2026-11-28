// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.show.*
import xyz.kd5ujc.scarab.codec.DomainError
import cats.Id
import cats.data.NonEmptyChain
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.VocabError
import xyz.kd5ujc.scarab.ledger.rules.Test
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, ReferenceBuildNotYet, StateLayout}

/** What a decoded transaction holds that its era cannot have. The reference ledger refuses all of
  * these in its transaction decoder (a `DecoderError`), before any rule runs, so none is a
  * predicate failure there; the node decodes one transaction type for every era and meets them in
  * the rules.
  */
enum TxDefect:
  /** A certificate of Conway (a DRep or committee certificate) in a transaction of an earlier era.
    */
  case ConwayCertificate(certificate: Certificate)

  /** Votes, proposals or a donation in a transaction before Conway. */
  case GovernanceFieldsBeforeConway

  /** Redeemers, datums or a script data hash in a transaction before Alonzo. */
  case PlutusFieldsBeforeAlonzo

  /** Auxiliary data is not admitted by the era's decoder. */
  case AuxiliaryData(cause: LedgerDecodeError)

  /** The protocol version of an update proposal is not a pair of numbers. */
  case UnreadableProtocolVersion

  case PoolMetadata(cause: LedgerDecodeError)

  def message: String = this match
    case ConwayCertificate(c)         => s"certificate $c before Conway"
    case GovernanceFieldsBeforeConway => "governance fields before Conway"
    case PlutusFieldsBeforeAlonzo     => "Plutus fields before Alonzo"
    case AuxiliaryData(cause)         => s"malformed auxiliary data: ${cause.show}"
    case UnreadableProtocolVersion    => "an update with an unreadable protocol version"
    case PoolMetadata(cause)          => s"invalid pool metadata: ${cause.message}"

/** Where this implementation could not supply a script verdict. */
enum ScriptLocation:
  case Phase1(scriptHash: Bytes)

  /** A phase-2 script of the transaction itself, by its redeemer. */
  case Phase2(redeemer: Redeemer)

  /** A phase-2 script of one of a Dijkstra transaction's sub-transactions, by the sub-transaction's
    * id and its redeemer, which its own witnesses hold (#85): its top level's UTXOS runs it with
    * the batch's (`plutusScriptsWithContextStAnnTx`, cardano-ledger 9df9f98c
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/UTxO.hs:258-276`), so the transaction the
    * failure names is the top level, which the block and its peers refer to.
    */
  case SubPhase2(subTxId: Bytes, redeemer: Redeemer)

/** Why the ledger refused a transaction: the predicate failures of the rules
  * ([[LedgerFailure.Rejected]]), or a defect ([[LedgerDefect]]) that leaves no verdict of the
  * rules.
  */
sealed trait LedgerFailure extends DomainError:
  def message: String = this match
    case LedgerFailure.Rejected(id, fs) =>
      s"tx ${id.hex.take(16)} violates ${fs.iterator.map(_.detail).mkString("; ")}"
    case LedgerFailure.Undecodable(id, d) =>
      s"tx ${id.hex.take(16)} cannot be decoded: ${d.message}"
    case LedgerFailure.Unavailable(id, location, why) =>
      val where = location match
        case ScriptLocation.Phase1(hash) => s"phase 1 script ${hash.hex.take(16)}"
        case ScriptLocation.Phase2(r)    => s"phase 2 redeemer ${r.tag}.${r.index}"
        case ScriptLocation.SubPhase2(sub, r) =>
          s"phase 2 redeemer ${r.tag}.${r.index} of sub-transaction ${sub.hex.take(16)}"
      s"tx ${id.hex.take(16)} evaluation unavailable at $where: ${why.message}"
    case LedgerFailure.Unverifiable(id, d) => s"tx ${id.hex.take(16)} has no verdict: ${d.message}"
    case LedgerFailure.Vocab(e)            => s"transaction outside the state's vocabulary: $e"
    case LedgerFailure.StateOutOfRange(id, e) =>
      s"tx ${id.hex.take(16)}: a quantity of the ledger state is out of range: $e"
    case LedgerFailure.LayoutNotYet(cause) => cause.message
    case LedgerFailure.PoolParamsLayout(pool, params, state) =>
      s"pool ${pool.hex.take(16)}: parameters of the $params layout for a state of the $state layout"
    case LedgerFailure.NativeScriptUnread(id, hash) =>
      s"tx ${id.hex.take(16)}: native script ${hash.hex.take(16)} has no tree to evaluate here"

/** What ends a judgment of the rules without a verdict: the transaction cannot be what its era
  * decodes, a script cannot be evaluated here, a verifier failed, or a value is outside the state's
  * vocabulary. The reference has no predicate failure for any of them, so none is part of a
  * refusal's list.
  */
sealed trait LedgerDefect extends LedgerFailure

object LedgerFailure:
  /** The rules refused the transaction; `failures` are the ledger's predicate failures for it, in
    * the order the reference reports them (`ApplyTxError (NonEmpty …)`).
    */
  final case class Rejected(txId: Bytes, failures: NonEmptyChain[PredicateFailure])
      extends LedgerFailure

  object Rejected:
    /** A refusal for one predicate failure. */
    def apply(txId: Bytes, failure: PredicateFailure): Rejected =
      Rejected(txId, NonEmptyChain.one(failure))

    /** A refusal whose list is exactly one predicate failure. */
    object Single:
      def unapply(rejected: Rejected): Option[(Bytes, PredicateFailure)] =
        rejected.failures.uncons match
          case (failure, rest) if rest.isEmpty => Some((rejected.txId, failure))
          case _                               => None

  /** The transaction holds what its era's decoder would not have admitted (see [[TxDefect]]). */
  final case class Undecodable(txId: Bytes, defect: TxDefect) extends LedgerDefect

  /** This implementation could not evaluate a script; no consensus verdict exists. */
  final case class Unavailable(
      txId: Bytes,
      location: ScriptLocation,
      reason: xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported
  ) extends LedgerDefect

  /** The Ed25519 verifier failed on a witness of the transaction ([[ProviderDefect]]): no verdict
    * on it, and not a predicate failure.
    */
  final case class Unverifiable(txId: Bytes, defect: ProviderDefect) extends LedgerDefect

  /** A value of the transaction is not a member of the vocabulary type that the era-indexed state
    * holds it in (a credential of another length, a quantity outside its word). The decoder admits
    * none, so a decoded transaction does not reach it; it is the total form of a conversion the
    * rules of the era-indexed ledger need (design `ledger-mirror-step2.md` §4, J2.4).
    */
  final case class Vocab(error: VocabError) extends LedgerDefect

  /** A quantity of the ledger state, its parameters included, that a rule of the transaction
    * converts is outside the range of its vocabulary type: a deposit of the parameters that is not
    * a `CompactForm Coin` (`compactCoinOrError`, cardano-ledger f649f975 `Coin.hs:184-188`, in
    * `Shelley/Rules/Deleg.hs:268` and `Conway/Rules/Deleg.hs:197`). The reference aborts with an
    * exception there, so this is no verdict on the transaction: on the block path the node stops
    * ([[BlockFailure.stateOutOfRange]]). A value of the transaction itself is [[Vocab]].
    */
  final case class StateOutOfRange(txId: Bytes, error: VocabError) extends LedgerDefect

  /** A rule that builds a record in the layout of the build the ledger follows, for a build whose
    * `state` layout is not implemented yet ([[ReferenceBuildNotYet.Layout]]). The node's setup
    * refuses such a build before any rule runs (`NodeSetup`), so the rules never meet it.
    */
  final case class LayoutNotYet(cause: ReferenceBuildNotYet) extends LedgerDefect

  /** A pool's registration parameters of one build's layout (`sppBlsKey`'s place,
    * [[xyz.kd5ujc.scarab.ledger.state.PoolParamsBlsKey]]) for a pool state of the other's: a
    * certificate decoded for one build applied to a state of the other. The reference is one build,
    * whose decoder gives its own `StakePoolParams`, so it never meets it; POOL refuses it rather
    * than store parameters the state's codec does not write.
    */
  final case class PoolParamsLayout(pool: Bytes, params: StateLayout, state: StateLayout)
      extends LedgerDefect

  /** A native script the transaction can use, among those it needs, whose time-lock tree the flat
    * output does not hold: a Dijkstra native script with a guard, which the flat output keeps by
    * its kind and hash only (`DijkstraTxOut.toStored`, #54). The flat rules cannot evaluate it, and
    * do not take it as holding; Dijkstra's rule evaluates it in Dijkstra's form (#1126).
    */
  final case class NativeScriptUnread(txId: Bytes, hash: Bytes) extends LedgerDefect

/** Block-level context of a transaction.
  *
  * @param clock
  *   The slot to POSIX time translation through the era history at the ledger tip, with its
  *   forecast horizon (needed for the script context).
  * @param evaluator
  *   The Plutus evaluator; a pure function of the request (see `ScriptEvaluator`). No default: the
  *   node passes its backend (`PlutusBackend`) on every path.
  * @param decoder
  *   Decides whether a Plutus script deserialises (phase 1 from Babbage on); the same backend's.
  * @param observer
  *   Tooling hook receiving the transaction's collected phase-2 outcome. Its return value does not
  *   supply a verdict; callbacks must complete normally.
  * @param verdicts
  *   Signature verdicts (memoised ahead of time in the node, computed in place otherwise).
  * @param validation
  *   Whether the static checks run ([[TxEnv.Validation]]).
  * @param referenceBuild
  *   The build whose rules the transaction is judged by ([[LedgerGlobals.referenceBuild]]).
  * @param ahead
  *   The collections of scripts made ahead of the rules (#1499), which UTXOS takes for a
  *   transaction only where one is its own collection ([[Phase2.collected]]); none by default.
  */
final case class TxEnv(
    slot: BigInt,
    epoch: BigInt,
    network: Int,
    firstSlotNextEpoch: BigInt = 0,
    stabilityWindow: BigInt = 0,
    txIndex: Int = 0,
    updateQuorum: Int = 5,
    clock: Option[PosixClock] = None,
    evaluator: ScriptEvaluator[Id],
    decoder: ScriptDecoder[Id],
    observer: Option[Phase2.Observation => Unit] = None,
    verdicts: xyz.kd5ujc.scarab.ledger.Verdicts = xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
    private[ledger] val preparation: Option[Phase2.Preparation] = None,
    validation: TxEnv.Validation = TxEnv.Validation.All,
    ahead: Phase2.Ahead = Phase2.Ahead.none,
    referenceBuild: ReferenceBuild = ReferenceBuild.Default
):
  /** `check` when the static checks run, and a pass otherwise: `runTestOnSignal`
    * (`Cardano/Ledger/Rules/ValidationMode.hs`), which skips a test labelled static unless the
    * policy validates it.
    */
  private[ledger] def static(
      check: => Test[PredicateFailure]
  ): Test[PredicateFailure] = validation match
    case TxEnv.Validation.All       => check
    case TxEnv.Validation.NonStatic => Test.pass

  /** [[static]] of a check that may end in a defect: a skipped check meets no defect either. */
  private[ledger] def staticOrDefect(
      check: => Either[LedgerDefect, Test[PredicateFailure]]
  ): Either[LedgerDefect, Test[PredicateFailure]] = validation match
    case TxEnv.Validation.All       => check
    case TxEnv.Validation.NonStatic => Right(Test.pass)

object TxEnv:
  type Result[A] = Either[LedgerFailure, A]

  /** Which checks the rules run (the ledger's `ValidationPolicy`, cardano-ledger f649f975
    * `Cardano/Ledger/Rules/ValidationMode.hs`). A transaction is applied with every check
    * (`ValidateAll`); a transaction validated before is applied again without the static ones
    * (`reapplyTx`, `ValidateSuchThat (notElem lblStatic)`, `Shelley/API/Mempool.hs` 472–473). The
    * static checks are those under `runTestOnSignal` and `when2Phase` (`Alonzo/Rules/Utxos.hs`
    * 420–421): for Conway the input set not empty, the bootstrap attribute size, the networks of
    * the outputs, withdrawals and body and the transaction size (`Babbage/Rules/Utxo.hs` 368–406);
    * the witness signatures, the auxiliary data and the well-formed scripts
    * (`Babbage/Rules/Utxow.hs` 366–379); and the evaluation of the Plutus scripts, with the
    * validity flag it is compared with (`Babbage/Rules/Utxos.hs` 145, 208).
    */
  enum Validation:
    case All, NonStatic
