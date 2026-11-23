// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, ReferenceBuildNotYet}

/** The voting key of a pool that POOL registers before Dijkstra, by the layout of the build the
  * node mirrors: none in the Leios layout (`mkStakePoolState cEpoch SNothing` of parameters with
  * `sppBlsKey = SNothing`, cardano-ledger 9df9f98c `Shelley/Rules/Pool.hs:262-266`), no field in
  * the 11.1 layout. POOLREAP's re-registration is compared with the native tick in
  * `LeiosEpochReferenceSuite`.
  */
class PoolBlsKeyLayoutSuite extends FunSuite:
  private val owner = TestLedger.key()
  private val id = TestLedger.txIn(93).id
  private val params = PoolParams(
    owner.hash,
    Bytes(Vector.fill(32)(3.toByte)),
    0,
    1,
    Rational.zero,
    RewardAccount(0, owner.credential),
    Set(owner.hash),
    Bytes.empty,
    None
  )
  private val pp = TestLedger.params(10)
  private val initial = Empty.ledgerState[Conway](pp).cert.pState

  private def judged(build: ReferenceBuild) =
    val globals =
      TestLedger.globals.copy(ledger = TestLedger.globals.ledger.copy(referenceBuild = build))
    JudgedSts(Pool)(LedgerFailure.Rejected(id, _))(
      globals,
      CertEnv(id, pp, 5, None, Map.empty),
      initial,
      PoolCert.RegPool(params)
    )

  private def registered(build: ReferenceBuild): Vector[PoolBlsKey] =
    judged(build).fold(e => fail(e.toString), _.stakePools.values.toVector.map(_.blsKey))

  test("a pool POOL registers in Conway holds no voting key in the Leios layout") {
    assertEquals(registered(ReferenceBuild.Leios), Vector(PoolBlsKey.Leios(None)))
  }

  test("a pool POOL registers in the 11.1 layout has no voting-key field") {
    assertEquals(registered(ReferenceBuild.Default), Vector(PoolBlsKey.Node11_1))
  }

  test(
    "a build whose layout is not implemented yet is a defect, not a verdict, which setup refuses"
  ) {
    assertEquals(
      judged(ReferenceBuild.Release11_2).map(_.stakePools.size),
      Left(LedgerFailure.LayoutNotYet(ReferenceBuildNotYet.Layout(ReferenceBuild.Release11_2)))
    )
  }
