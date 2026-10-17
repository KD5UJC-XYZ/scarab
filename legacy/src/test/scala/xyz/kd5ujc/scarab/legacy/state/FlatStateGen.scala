// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import xyz.kd5ujc.scarab.legacy as flat

import java.time.Instant
import org.scalacheck.Gen
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as node

/** Generators of the node's flat ledger state that the mirror can hold: hashes of the right size,
  * non-negative amounts, snapshots that the node's own `Stake.snapshot` took (so they are
  * well-formed, idle pools included), accounts delegated to registered pools, and the era split
  * (pointers before Conway, DReps and the committee in it). A handful of credentials and pools, so
  * that delegations, owners that delegate to their own pool and re-registrations happen often.
  */
object FlatStateGen:
  val network: Int = 0

  val env: MirrorEnv = MirrorEnv(
    network,
    node.EpochGeometry
      .checked(Instant.EPOCH, 1000L, 100L, 2, BigDecimal("0.5"))
      .fold(e => sys.error(e.message), identity),
    quorum = 2
  )

  private def bytes(size: Int, tag: Int, i: Int): Bytes =
    Bytes.fromArray(Array.tabulate(size)(j => (tag * 31 + i * 7 + j).toByte))

  private def credential(i: Int): node.Credential =
    if i % 2 == 0 then node.Credential.Key(bytes(28, 1, i))
    else node.Credential.Script(bytes(28, 2, i))

  private def poolId(i: Int): Bytes = bytes(28, 3, i)
  private def vrf(i: Int): Bytes = bytes(32, 4, i)
  private def genesisKey(i: Int): Bytes = bytes(28, 5, i)

  private val credentials = 6
  private val poolCount = 4

  private val amount: Gen[BigInt] =
    Gen.oneOf(Gen.const(BigInt(0)), Gen.choose(1L, 5000L).map(BigInt(_)))

  private val positive: Gen[BigInt] = Gen.choose(1L, 5000L).map(BigInt(_))

  private def subset[A](xs: Seq[A]): Gen[Vector[A]] =
    Gen
      .sequence[Vector[Option[A]], Option[A]](
        xs.map(x => Gen.oneOf(true, false).map(keep => Option.when(keep)(x)))
      )
      .map(_.flatten)

  private def poolParams(i: Int, vrfIndex: Int): Gen[node.PoolParams] =
    for
      owners <- subset((0 until credentials).filter(_ % 2 == 0).map(j => bytes(28, 1, j)))
      reward <- Gen.choose(0, credentials - 1)
      // now and then another network, as a registration before protocol version 5 may have (B22)
      accountNetwork <- Gen.frequency(4 -> network, 1 -> (1 - network))
      metadata <- Gen.option(Gen.const(bytes(8, 6, i)))
      pledge <- amount
    yield node.PoolParams(
      poolId(i),
      vrf(vrfIndex),
      pledge,
      BigInt(340),
      Rational(1, 10 + i),
      node.RewardAccount(accountNetwork, credential(reward)),
      owners.toSet,
      bytes(5, 7, i),
      metadata
    )

  private def hashedParams(conway: Boolean): node.ProtocolParams =
    node.ProtocolParams
      .update(
        xyz.kd5ujc.scarab.ledger.TestLedger.params(if conway then 10 else 6),
        node.ParamUpdate(Map.empty)
      )
      .fold(e => sys.error(e), identity)

  /** A flat state of an era: Conway, or one of the earlier ones (which differ in nothing the
    * conversion looks at but the protocol version it reads).
    */
  def state(conway: Boolean): Gen[flat.LedgerState] =
    for
      registered <- subset(0 until poolCount)
      params <- Gen.sequence[Vector[node.PoolParams], node.PoolParams](
        registered.map(i => poolParams(i, i))
      )
      pools = params.map(p => p.operator -> node.PoolState(p, BigInt(500))).toMap
      futureParams <- Gen.sequence[Vector[Option[node.PoolParams]], Option[node.PoolParams]](
        registered.map(i => Gen.option(poolParams(i, i + 10)))
      )
      retiring <- Gen.sequence[Vector[Option[(Bytes, BigInt)]], Option[(Bytes, BigInt)]](
        registered.map(i => Gen.option(Gen.choose(6L, 9L).map(e => poolId(i) -> BigInt(e))))
      )
      accountIds <- subset(0 until credentials)
      accounts <- Gen
        .sequence[Vector[(node.Credential, node.Account)], (node.Credential, node.Account)](
          accountIds.map { i =>
            for
              balance <- amount
              pool <- Gen.option(Gen.oneOf(registered :+ 99).map(poolId))
              drep <- Gen.option(
                Gen.oneOf(
                  Gen.const(node.DRep.AlwaysAbstain),
                  Gen.const(node.DRep.AlwaysNoConfidence),
                  Gen.const(node.DRep.Registered(credential(i)))
                )
              )
            yield credential(i) -> node.Account(
              balance,
              BigInt(2000),
              pool.filter(p => pools.contains(p) || p == poolId(99)),
              Option.when(conway)(drep).flatten
            )
          }
        )
      utxoStake <- Gen.sequence[Vector[(node.Credential, BigInt)], (node.Credential, BigInt)](
        (0 until credentials).map(i => positive.map(credential(i) -> _))
      )
      utxoSome <- subset(utxoStake.indices)
      pointers <- Gen.sequence[Vector[(node.Ptr, BigInt)], (node.Ptr, BigInt)](
        (0 until 2).map(i => positive.map(node.Ptr(100 + i, 0, 0) -> _))
      )
      epoch <- Gen.choose(0L, 40L)
      treasury <- amount
      reserves <- amount
      fees <- amount
      deposited <- amount
      donation <- amount
      draft <- Gen.option(updateOf(accounts.map(_._1)))
      update <- Gen.option(updateOf(accounts.map(_._1)))
      blocksNow <- blocks(registered)
      blocksBefore <- blocks(registered)
      ppup <- proposals(conway)
      gov <- govState(conway, accounts.map(_._1))
      prev <- Gen.oneOf(
        None,
        Some(hashedParams(conway)),
        Some(hashedParams(conway).copy(minFeeA = 7))
      )
      plutus <- Gen.choose(0L, 9L).map(n => node.PlutusStats(n, n + 1, n + 2, n + 3))
      setDropped <- subset(accounts.indices)
      goDropped <- subset(accounts.indices)
      distributionIsSet <- Gen.oneOf(true, false)
      mir <- instantRewards(accounts.map(_._1))
      genesisKeys <- subset(0 until 3)
      futureGenesis <- Gen
        .choose(0, 3)
        .flatMap(n =>
          Gen
            .listOfN(
              n,
              for
                slot <- Gen.choose(1L, 3L)
                key <- Gen.choose(0, 2)
                pair <- Gen.choose(0, 2)
              yield node.FutureGenDeleg(
                slot,
                genesisKey(key),
                node.GenDelegPair(bytes(28, 8, pair), vrf(pair))
              )
            )
            .map(_.toVector)
        )
      pointerOwners <- subset(accounts.indices)
      rewardRegistered <- Gen.option(Gen.const(accounts.map(_._1).toSet))
    yield
      val protocol = hashedParams(conway)
      val ptrs: Map[node.Ptr, node.Credential] =
        if conway then Map.empty
        else
          pointerOwners.zipWithIndex.map((a, n) => node.Ptr(200 + n, 1, 2) -> accounts(a)._1).toMap
      val utxo = node.Utxo.empty.copy(
        stake = utxoSome.map(utxoStake(_)).toMap,
        ptrStake = pointers.toMap,
        coin = BigInt(1000),
        count = 3L
      )
      val base = flat
        .LedgerState(
          epoch = epoch,
          params = protocol,
          utxo = utxo,
          fees = fees,
          deposited = deposited,
          accounts = accounts.toMap,
          pools = pools,
          futurePools = futureParams.flatten.map(p => p.operator -> p).toMap,
          retiring = retiring.flatten.toMap,
          dreps = Map.empty,
          dormantEpochs = 0,
          treasury = treasury,
          reserves = reserves,
          snapshots =
            flat.Snapshots(flat.Snapshot.empty, flat.Snapshot.empty, flat.Snapshot.empty, 3),
          blocksCurrent = blocksNow,
          blocksPrevious = blocksBefore,
          rewardUpdate = update,
          poolDistribution = flat.Snapshot.empty,
          genDelegs =
            genesisKeys.map(i => genesisKey(i) -> node.GenDelegPair(bytes(28, 8, i), vrf(i))).toMap,
          futureGenDelegs = futureGenesis,
          ppup = ppup,
          mir = mir,
          ptrs = node.PtrMap(ptrs),
          prevParams = prev,
          rewardRegistered = rewardRegistered,
          plutus = plutus,
          gov = gov._1,
          committeeState = gov._2,
          donation = donation,
          rewardDraft = draft
        )
        .copy(dreps = gov._3)
      val mark = flat.Stake.snapshot(base)
      val set =
        flat.Stake.snapshot(base.copy(accounts = withoutIndices(base, accounts, setDropped)))
      val go = flat.Stake.snapshot(base.copy(accounts = withoutIndices(base, accounts, goDropped)))
      base.copy(
        snapshots = flat.Snapshots(mark, set, go, BigInt(3)),
        poolDistribution = if distributionIsSet then set else mark
      )

  private def withoutIndices(
      base: flat.LedgerState,
      accounts: Vector[(node.Credential, node.Account)],
      dropped: Vector[Int]
  ): Map[node.Credential, node.Account] =
    val gone = dropped.map(accounts(_)._1).toSet
    base.accounts.filterNot((c, _) => gone(c))

  private def blocks(registered: Vector[Int]): Gen[Map[Bytes, BigInt]] =
    Gen
      .sequence[Vector[Option[(Bytes, BigInt)]], Option[(Bytes, BigInt)]](
        registered.map(i => Gen.option(Gen.choose(1L, 30L).map(n => poolId(i) -> BigInt(n))))
      )
      .map(_.flatten.toMap)

  private def updateOf(credentials: Vector[node.Credential]): Gen[flat.RewardUpdate] =
    for
      deltaT <- Gen.choose(0L, 100L)
      deltaR <- Gen.choose(-100L, 0L)
      deltaF <- Gen.choose(-100L, 0L)
      paid <- subset(credentials.indices)
    yield flat.RewardUpdate(
      deltaT,
      deltaR,
      paid.map(i => credentials(i) -> BigInt(10 + i)).toMap,
      deltaF
    )

  private def instantRewards(credentials: Vector[node.Credential]): Gen[flat.InstantRewards] =
    for
      reserves <- subset(credentials.indices)
      treasury <- subset(credentials.indices)
      deltaR <- Gen.choose(-5L, 5L)
      deltaT <- Gen.choose(-5L, 5L)
    yield flat.InstantRewards(
      reserves.map(i => credentials(i) -> BigInt(i)).toMap,
      treasury.map(i => credentials(i) -> BigInt(i + 1)).toMap,
      deltaR,
      deltaT
    )

  private def proposals(conway: Boolean): Gen[flat.Ppup] =
    if conway then Gen.const(flat.Ppup.empty)
    else
      for
        keys <- subset(0 until 3)
        value <- Gen.choose(0L, 2L)
        future <- Gen.option(Gen.const(genesisKey(2)))
      yield flat.Ppup(
        keys.map(i => genesisKey(i) -> node.ParamUpdate(Map(0 -> bytes(1, 9, value.toInt)))).toMap,
        future.map(k => k -> node.ParamUpdate(Map.empty)).toMap
      )

  /** The governance state, the committee state and the DReps of an era: empty before Conway. */
  private def govState(
      conway: Boolean,
      credentials: Vector[node.Credential]
  ): Gen[
    (flat.GovState, Map[node.Credential, node.CommitteeAuth], Map[node.Credential, node.DRepState])
  ] =
    if !conway then Gen.const((flat.GovState.empty, Map.empty, Map.empty))
    else
      for
        members <- subset(credentials.indices)
        pulsed <- Gen.option(Gen.const(()))
        hot <- subset(credentials.indices)
        resigned <- subset(credentials.indices)
        dreps <- subset(credentials.indices)
      yield
        val committee = Some(
          node.Committee(members.map(i => credentials(i) -> BigInt(30 + i)).toMap, Rational(1, 2))
        )
        val constitution = node.Constitution(flat.GovState.noAnchor, None)
        val params = hashedParams(true)
        val ratify = node.RatifyState(
          node.EnactState(
            committee,
            constitution,
            params,
            params,
            BigInt(0),
            Map.empty,
            node.PrevIds()
          ),
          Vector.empty,
          Set.empty,
          delayed = false
        )
        (
          flat.GovState(
            node.Proposals.empty,
            committee,
            constitution,
            pulsed.map(_ => (node.PulsingSnapshot.empty, ratify))
          ),
          (hot.map(i =>
            credentials(i) -> node.CommitteeAuth.Hot(credentials((i + 1) % credentials.size))
          ) ++
            resigned.map(i => credentials(i) -> node.CommitteeAuth.Resigned(None))).toMap,
          dreps
            .map(i =>
              credentials(i) -> node.DRepState(
                BigInt(500),
                BigInt(40 + i),
                None,
                Set(credentials((i + 1) % credentials.size))
              )
            )
            .toMap
        )
