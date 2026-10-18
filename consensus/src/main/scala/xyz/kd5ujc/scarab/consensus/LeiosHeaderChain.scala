// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{PraosError, LeaderCheck, LeiosHeader, PoolDistribution, StakeView}
import xyz.kd5ujc.scarab.protocol.Era

/** Header-only validation of a chain of Leios headers (the shape of `dijkstra.cddl` 's `header`,
  * see [[LeiosHeader]]), with what a node can check before it has any ledger rule for the era:
  *
  *   - the chain: each header's `prev_hash` is the previous header's hash, block numbers rise by
  *     one, slots rise;
  *   - the Praos rules on the header, unchanged ([[Praos.validate]] on [[LeiosHeader.praosView]]):
  *     the operational certificate and the KES signature over the exact header body bytes, the VRF
  *     proof under the epoch nonce, the certificate counter, the evolution of the nonces, and the
  *     leader threshold wherever the stake view is known.
  *
  * What is not here: the Leios fields of the header are not interpreted (the announcement is not
  * checked against an endorser block, the certificate flag against a body), and the stake of a pool
  * the genesis does not seat is only known from a ledger that applies transactions, so for those
  * epochs the caller passes an [[StakeView.Unknown]] and every header is counted as
  * [[LeaderCheck.Unchecked]], never as passed.
  */
object LeiosHeaderChain:
  /** The last header accepted. */
  final case class Tip(hash: Bytes, blockNo: BigInt, slot: BigInt)

  /** How the leader check came out for the headers so far: `threshold` evaluated, `unchecked` not.
    */
  final case class Leadership(threshold: Long, unchecked: Long)

  /** Where the stake comes from. The genesis seats the pools that lead the first `genesisEpochs`
    * epochs (epoch `e` uses the snapshot of the end of epoch `e - 2`, and until a transaction has
    * changed the stake that is the genesis distribution). After them the stake is whatever the
    * ledger made of the transactions of the chain, which this validator does not apply: the stake
    * view is then [[StakeView.Unknown]] over the pools seen so far.
    */
  final case class StakePolicy(genesis: PoolDistribution, genesisEpochs: BigInt)

  /** `registry` maps each issuer to the VRF key hashes it has used. After the genesis epochs the
    * registration of a pool is a certificate in a transaction this validator does not see, so the
    * key a header carries is taken on trust (the VRF proof is still verified against it): whether
    * it is the key the pool registered is not checked.
    */
  final case class State(
      tip: Option[Tip],
      protocol: ProtocolState,
      leadership: Leadership,
      registry: Map[Bytes, Set[Bytes]] = Map.empty
  ):
    def length: Long = leadership.threshold + leadership.unchecked

  /** A header that breaks a rule, retaining the Praos rule failure. */
  enum Fault:
    case PrevHashDiffers(expected: Option[Bytes], actual: Option[Bytes])
    case BlockNumberNotSuccessor(expected: BigInt, actual: BigInt)
    case SlotNotAfterTip(tipSlot: BigInt, slot: BigInt)
    case Praos(error: PraosError)

  /** Epochs led by the genesis pools: the schedule of epoch `e` uses the stake snapshot of the end
    * of epoch `e - 2`, so until a transaction can have changed the stake the genesis distribution
    * is the one in force for epochs 0 and 1.
    */
  val GenesisEpochs: BigInt = 2

  /** The state before the first header: the protocol state of a chain that has applied no block
    * (`ProtocolState.initial` in the protocol of Conway, whose rules the Leios headers follow).
    */
  def initial(protocol: ProtocolState): State = State(None, protocol, Leadership(0, 0))

  /** Accept `header` on top of `state`. */
  def step(config: PraosConfig, stake: StakePolicy)(
      state: State,
      header: LeiosHeader
  ): Either[Fault, State] =
    val epoch = config.geometry.epochOf(header.slot)
    val pool = Blake2b.hash224.hash(header.issuerVkey)
    val vrfHash = Blake2b.hash256.hash(header.vrfVkey)
    val registry = state.registry.updated(pool, state.registry.getOrElse(pool, Set.empty) + vrfHash)
    val view =
      if epoch < stake.genesisEpochs then StakeView.Known(stake.genesis)
      else StakeView.Unknown(registry)
    for
      _ <- Either.cond(
        header.prevHash == state.tip.map(_.hash),
        (),
        Fault.PrevHashDiffers(state.tip.map(_.hash), header.prevHash)
      )
      expectedNo = state.tip.fold(BigInt(0))(_.blockNo + 1)
      _ <- Either.cond(
        header.blockNo == expectedNo,
        (),
        Fault.BlockNumberNotSuccessor(expectedNo, header.blockNo)
      )
      _ <- state.tip.traverse_(t =>
        Either.cond(header.slot > t.slot, (), Fault.SlotNotAfterTip(t.slot, header.slot))
      )
      validated <- Praos
        .validate(
          Praos.tick(state.protocol, Era.Conway, epoch),
          header.praosView,
          view,
          config
        )
        .leftMap(Fault.Praos(_))
      (protocol, leader) = validated
    yield State(
      Some(Tip(header.hash, header.blockNo, header.slot)),
      protocol,
      leader match
        case LeaderCheck.Threshold =>
          state.leadership.copy(threshold = state.leadership.threshold + 1)
        case _ => state.leadership.copy(unchecked = state.leadership.unchecked + 1),
      registry
    )
end LeiosHeaderChain
