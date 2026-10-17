// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.ledger.*

/** FROZEN. What the flat conversion ([[LedgerMirror]]) needs of the old Conway EPOCH rule: the enact
  * state of a flat ledger state and the pulser as the governance state reports it. */
object ConwayEpoch:

  /** Enact state of the ledger as it stands: the committee, constitution and parameters. */
  def enactState(s: LedgerState): EnactState =
    EnactState(
      committee = s.gov.committee,
      constitution = s.gov.constitution,
      params = s.params,
      prevParams = s.previousParams,
      treasury = s.treasury,
      withdrawals = Map.empty,
      prevIds = s.gov.proposals.roots
    )

  // ---- the pulser as the governance state reports it

  /** The language index of PlutusV2 in the cost models (0 is PlutusV1, 2 PlutusV3). */
  private val PlutusV2 = 1

  /** Before the first epoch boundary of a chain that starts in Conway the previous parameters, and
    * the ones in the genesis-seeded pulser state, are those of the era translation: they carry the
    * cost models of PlutusV1 and PlutusV3 but not the PlutusV2 model that the Alonzo genesis holds
    * in `extraConfig` (as the reference reports them).
    */
  def beforeUpgrade(p: ProtocolParams): ProtocolParams =
    p.copy(costModels = p.costModels - PlutusV2)

  /** The parameters `reported` by the pulser put back: `p` as it was before [[beforeUpgrade]],
    * whose PlutusV2 model was the one of `current`.
    */
  private def afterUpgrade(p: ProtocolParams, current: ProtocolParams): ProtocolParams =
    if p.costModels.contains(PlutusV2) then p
    else p.copy(costModels = p.costModels ++ current.costModels.get(PlutusV2).map(PlutusV2 -> _))

  /** The pulser state before the first epoch boundary: the ratification of nothing over the enact
    * state of the current ledger, with the parameters of the era translation ([[beforeUpgrade]])
    * and the treasury as it is.
    */
  def initialRatify(s: LedgerState): RatifyState =
    RatifyState(
      enactState(s).copy(
        params = beforeUpgrade(s.params),
        prevParams = beforeUpgrade(s.previousParams)
      ),
      Vector.empty,
      Set.empty,
      delayed = false
    )

  /** The pulser the governance state reports (`GetGovState`, `GetRatifyState`, and the pulser of
    * the mirror): the completed one, or the initial one when the state has none; until the first
    * epoch boundary (no previous parameters) the pulser keeps the parameters of the era
    * translation.
    */
  def reportedPulser(s: LedgerState): (PulsingSnapshot, RatifyState) =
    val (snapshot, ratify) = s.gov.pulsed.getOrElse((PulsingSnapshot.empty, initialRatify(s)))
    if s.prevParams.isEmpty then
      (
        snapshot,
        ratify.copy(enact =
          ratify.enact.copy(
            params = beforeUpgrade(ratify.enact.params),
            prevParams = beforeUpgrade(ratify.enact.prevParams)
          )
        )
      )
    else (snapshot, ratify)

  /** The previous parameters as the governance state reports them. */
  def reportedPrevious(s: LedgerState): ProtocolParams =
    if s.prevParams.isEmpty then beforeUpgrade(s.previousParams) else s.previousParams

  /** The inverse of [[reportedPulser]] for a completed pulser: the ratification as the node holds
    * it. `beforeBoundary` is that the state has no previous parameters, `current` its parameters.
    */
  def heldRatify(
      reported: RatifyState,
      current: ProtocolParams,
      beforeBoundary: Boolean
  ): RatifyState =
    if beforeBoundary then
      reported.copy(enact =
        reported.enact.copy(
          params = afterUpgrade(reported.enact.params, current),
          prevParams = afterUpgrade(reported.enact.prevParams, current)
        )
      )
    else reported

  /** The inverse of [[reportedPrevious]]: the previous parameters as the node holds them. */
  def heldPrevious(
      reported: ProtocolParams,
      current: ProtocolParams,
      beforeBoundary: Boolean
  ): ProtocolParams =
    if beforeBoundary then afterUpgrade(reported, current) else reported
