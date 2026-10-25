// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.data.{NonEmptyChain, Validated, ValidatedNec}

/** A pure predicate of a rule: `Test failure = Validation (NonEmpty failure) ()` (cardano-ledger
  * `f649f975`, `libs/cardano-ledger-core/src/Cardano/Ledger/Rules/ValidationMode.hs`). Tests
  * combine applicatively, every failure kept in order (`sequenceA_`, `*>`), and a rule records them
  * with [[Sts.runTest]].
  */
type Test[Rec] = ValidatedNec[Rec, Unit]

object Test:
  /** The test that passes. */
  def pass[Rec]: Test[Rec] = Validated.validNec(())

  /** `failureUnless` (`libs/small-steps/src/Validation`): fails with `failure` unless `cond`. */
  def failureUnless[Rec](cond: Boolean, failure: => Rec): Test[Rec] =
    if cond then pass else Validated.invalidNec(failure)

  /** `failureOnJust` (`Extended.hs:409-414`): fails with the failure made of a present value. */
  def failureOnJust[A, Rec](cond: Option[A])(onJust: A => Rec): Test[Rec] =
    cond.fold(pass[Rec])(a => Validated.invalidNec(onJust(a)))

  /** `failureOnNonEmpty`, `failureOnNonEmptySet`, `failureOnNonEmptyMap` (`Extended.hs:423-453`):
    * fails with the failure made of the items, if there are any.
    */
  def failureOnNonEmpty[C, Rec](items: C)(isEmpty: C => Boolean)(onNonEmpty: C => Rec): Test[Rec] =
    if isEmpty(items) then pass else Validated.invalidNec(onNonEmpty(items))

  /** `sequenceA_` of tests: every one runs, and the failures are kept in order. */
  def all[Rec](tests: Test[Rec]*): Test[Rec] =
    tests.foldLeft(pass[Rec])((done, next) => done.product(next).map(_ => ()))

  /** The failures of a test, if it failed. */
  def failures[Rec](test: Test[Rec]): Option[NonEmptyChain[Rec]] = test.swap.toOption
