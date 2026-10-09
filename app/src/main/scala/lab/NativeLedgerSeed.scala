// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosCertificateState.Point
import lab.ledger.{ConwayEmptyGovernance as G, ConwayStake as S, FeeSize, MinimumOutput}
import scala.util.control.NonFatal

/** Join already decoded ledger-side components under one externally expected source identity. This
  * is diagnostic evidence, not an admitted ledger seed or a runtime constructor.
  */
private[lab] object NativeLedgerSeed:
  enum Blocker:
    case MissingPointBoundProtocolV2, IncompatibleCrossingGeometry

  final class Checked private[NativeLedgerSeed] (
      val id: Bytes,
      val epochComponents: NativeEpochComponents.Checked,
      val governance: NativeGovernanceComponents.Checked,
      val globals: GovernanceGlobals.Checked,
      val parameterRoles: GovernanceParameterPayload.Roles,
      val historicalCurrent: GovernanceParameterPayload.Checked,
      val historicalPrevious: GovernanceParameterPayload.Checked,
      val pools: Map[Bytes, GovernancePoolPayload.Checked],
      val governanceInput: G.Input,
      val crossingBlockers: Set[Blocker]
  ):
    val point = epochComponents.point
    val sourceId = epochComponents.id
    val ledgerSideJoinChecked = true
    val runtimeImport = false
    val rewardSeedAdmission = false
    val authenticatedSnapshot = false
    val actualAcquisitionVerified = false
    val fullLedgerValidated = false
    val nativeConformance = false
    val fullParameterValidity = false
    val liveGovernanceCursorRecoverable = false
    val limitations = Set(
      "source pins do not authenticate the acquisition",
      "parameter decoding is not full parameter or cost-model evaluation-context validity",
      "normalized completed governance does not recover its original live cursor",
      "the pure governance check is not execution of a native or full ledger boundary",
      "no runtime import or checkpoint capability is constructed"
    )

  private def get[A](e: Either[?, A]): A =
    e.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def sha(b: Bytes): Bytes = ClusterHeaderObservation.sha256(b)
  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))

  def bind(
      epoch: NativeEpochComponents.Checked,
      governance: NativeGovernanceComponents.Checked,
      globals: GovernanceGlobals.Checked,
      expectedPoint: Point,
      expectedSourceId: Bytes
  ): Either[String, Checked] = checked {
    require(
      epoch != null && governance != null && globals != null,
      "ledger-side decoded components required"
    )
    require(
      expectedPoint != null && expectedSourceId != null && expectedSourceId.size == 32 &&
        epoch.point == expectedPoint && epoch.id == expectedSourceId,
      "ledger-side expected source/point mismatch"
    )
    require(
      governance.originalSeed == epoch.originals("derived-full-epoch-seed.cbor") &&
        governance.originalEpoch == epoch.originals("original-debug-epoch.cbor"),
      "ledger-side original seed/epoch splice"
    )
    require(
      sha(governance.originalSeed) == epoch.pins("derived-full-epoch-seed.cbor") &&
        sha(governance.originalEpoch) == epoch.pins("original-debug-epoch.cbor"),
      "ledger-side original pins mismatch"
    )
    NativeGovernanceComponents.ComponentNames.foreach { name =>
      require(
        governance.componentOriginals(name) == epoch.components(name) &&
          governance.componentSHA256(name) == sha(epoch.components(name)),
        "ledger-side shared component mismatch: " + name
      )
    }
    val parameters = epoch.parameters
    val stake = epoch.stake
    require(
      governance.epoch == stake.epoch && parameters.epoch == stake.epoch &&
        parameters.pointSlot == expectedPoint.slot && stake.sourceId == epoch.id &&
        stake.context.epochLength == parameters.globals.epochLength &&
        stake.utxo == epoch.originals("original-whole-utxo.cbor") &&
        sha(stake.utxo) == epoch.pins("original-whole-utxo.cbor"),
      "ledger-side stake/UTxO/epoch identity"
    )
    require(
      globals.sourceBindingId == parameters.bindingId && globals.epoch == governance.epoch &&
        globals.pointSlot == expectedPoint.slot && globals.networkMagic == parameters.networkMagic &&
        globals.genesisOriginal == parameters.genesisOriginal && globals.genesisSHA256 == parameters.genesisSHA256 &&
        globals.genesisOriginal == epoch.originals("effective-shelley-genesis.json") &&
        globals.genesisSHA256 == epoch.pins("effective-shelley-genesis.json") &&
        globals.previousParameterSHA256 == parameters.previous.sha256 &&
        globals.currentParameterSHA256 == parameters.current.sha256,
      "ledger-side globals source/temporal roles splice"
    )
    get(
      GovernanceGlobals.checkProjection(
        globals,
        parameters.globals,
        parameters.slotLength,
        parameters.stabilityWindow,
        parameters.randomnessWindow
      )
    )

    require(
      governance.currentParameters == parameters.current.original &&
        governance.previousParameters == parameters.previous.original,
      "ledger-side parameter originals mismatch"
    )
    val previous = get(
      GovernanceParameterPayload.decode(governance.previousParameters, parameters.previous.sha256)
    )
    val current = get(
      GovernanceParameterPayload.decode(governance.currentParameters, parameters.current.sha256)
    )
    val historicalCurrent = get(
      GovernanceParameterPayload.decode(
        governance.historical.enact.currentParameters,
        sha(governance.historical.enact.currentParameters)
      )
    )
    val historicalPrevious = get(
      GovernanceParameterPayload.decode(
        governance.historical.enact.previousParameters,
        sha(governance.historical.enact.previousParameters)
      )
    )
    val fees = get(
      FeeSize.Parameters.create(
        "Conway",
        9,
        parameters.current.feePerByte,
        parameters.current.feeFixed,
        parameters.current.maxTxSize
      )
    )
    val minimum =
      get(MinimumOutput.Parameters.checked("Conway", 9, 0, parameters.current.coinsPerUTxOByte))
    val roles = get(
      GovernanceParameterPayload.bindRoles(
        previous,
        current,
        parameters.previous.sha256,
        parameters.current.sha256,
        parameters.previous.rewards,
        parameters.current.rewards,
        fees,
        minimum
      )
    )

    require(
      governance.accounts.map((c, a) => c -> S.Account(a.rewards, a.deposit, a.pool)) ==
        stake.context.accounts,
      "ledger-side account reward/deposit/pool overlap mismatch"
    )
    governance.accounts.foreach { (_, a) =>
      require(
        a.vote.exists {
          case G.Vote.Credential(c) => governance.dreps.contains(c)
          case _                    => false
        },
        "ledger-side registered credential vote required"
      )
    }
    governance.dreps.foreach { (c, d) =>
      require(
        d.delegators == governance.accounts.collect {
          case (who, account) if account.vote.contains(G.Vote.Credential(c)) => who
        }.toSet,
        "ledger-side reverse vote overlap mismatch"
      )
    }
    val poolRows = get(Cbor.decode(epoch.components("pools"))).value match
      case V.Map(rows) => rows
      case _           => throw new IllegalArgumentException("ledger-side pool map shape")
    val pools = poolRows.map { (key, value) =>
      val pool = key.value match
        case V.ByteString(b) if b.size == 28 => b
        case _ => throw new IllegalArgumentException("ledger-side pool key width")
      val checked = get(GovernancePoolPayload.decode(value.original, sha(value.original)))
      get(
        GovernancePoolPayload.checkProjection(
          checked,
          stake.context.pools
            .getOrElse(pool, throw new IllegalArgumentException("ledger-side unknown pool"))
        )
      )
      pool -> checked
    }.toMap
    require(
      pools.size == poolRows.size && pools.keySet == stake.context.pools.keySet,
      "ledger-side exact pool domain"
    )
    val mark = get(S.snapshot(stake.context, stake.instantaneous))
    def empty(snapshot: S.Snapshot): Boolean =
      snapshot.active.isEmpty && snapshot.pools.isEmpty && snapshot.total == 1
    require(
      mark.active == stake.snapshots.mark.active && mark.pools == stake.snapshots.mark.pools &&
        mark.total == stake.snapshots.mark.total && empty(stake.snapshots.set) &&
        empty(stake.snapshots.go) && stake.snapshots.fees == 0 && stake.fees == epoch.pots.fees,
      "ledger-side early snapshots/fees mismatch"
    )
    require(
      epoch.reward.bindingId == epoch.id && epoch.reward.point == expectedPoint &&
        epoch.reward.seedSHA256 == epoch.pins("derived-full-epoch-seed.cbor") &&
        epoch.reward.original == epoch.components(
          "rewardState"
        ) && epoch.reward.original.hex == "80" &&
        epoch.reward.componentSHA256 == sha(epoch.reward.original),
      "ledger-side absent reward identity mismatch"
    )
    require(
      epoch.nonMyopic.likelihoods.isEmpty && epoch.nonMyopic.rewardPot == 0 &&
        epoch.pots.maxSupply == globals.maxLovelaceSupply &&
        epoch.utxoCoin + epoch.pots.reserves + epoch.pots.treasury + epoch.pots.fees == globals.maxLovelaceSupply,
      "ledger-side early non-myopic/supply mismatch"
    )
    val deposits = G.Deposits(
      governance.accounts.map((c, a) => c -> a.deposit),
      pools.map((p, value) => p -> value.pool.deposit),
      governance.dreps.map((c, d) => c -> d.deposit),
      Map.empty,
      0
    )
    require(
      deposits.stake.values.sum + deposits.pools.values.sum + deposits.dreps.values.sum == 0,
      "ledger-side zero deposit obligation mismatch"
    )
    val h = governance.historical
    val enact = G.Enact(
      h.enact.committee,
      h.enact.constitution,
      historicalCurrent.payload,
      historicalPrevious.payload,
      h.enact.treasury,
      h.enact.withdrawals,
      h.enact.roots
    )
    val input = G.Input(
      governance.epoch,
      governance.dormant,
      governance.dreps,
      governance.committee,
      governance.committeeState,
      governance.constitution,
      G.Parameters(current.payload, previous.payload, G.FutureParameters.NoUpdate),
      governance.roots,
      Map.empty,
      G.OldDRep.Complete(h.snapshot, G.Ratify(enact, h.enacted, h.expired, h.delayed)),
      governance.accounts,
      stake.instantaneous,
      G.PoolDistribution(
        mark.total,
        mark.distribution.map((p, value) => p -> G.PoolShare(value.coin, value.ratio, value.vrf))
      ),
      pools.map((p, value) => p -> G.Pool(value.payload, value.pool.deposit)),
      Map.empty,
      Map.empty,
      Map.empty,
      0,
      epoch.pots.treasury,
      deposits,
      get(G.suppliedFixedGlobals(globals.securityParameter, globals.id))
    )
    // Validate the established finite historical-role/deposit profile only. Do not
    // publish this hypothetical successor or claim native boundary execution.
    get(G.applyBoundary(input, governance.epoch + 1))
    val blockers = Set(Blocker.MissingPointBoundProtocolV2) ++
      Option.when(!parameters.timingProfileCompatible)(Blocker.IncompatibleCrossingGeometry)
    val identity = get(
      Cbor.encode(
        V.Arr(
          Vector(
            V.Text("native-ledger-side-seed-v1"),
            V.ByteString(epoch.id),
            V.ByteString(governance.sourceId),
            V.ByteString(globals.id),
            V.ByteString(roles.id),
            V.ByteString(historicalCurrent.sha256),
            V.ByteString(historicalPrevious.sha256),
            V.ByteString(expectedPoint.hash),
            V.UInt(expectedPoint.slot),
            V.UInt(expectedPoint.blockNo)
          ).map(Node(_, Bytes.empty))
        )
      )
    )
    new Checked(
      sha(identity),
      epoch,
      governance,
      globals,
      roles,
      historicalCurrent,
      historicalPrevious,
      pools,
      input,
      blockers
    )
  }
