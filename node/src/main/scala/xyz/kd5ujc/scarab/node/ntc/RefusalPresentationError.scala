// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.PlutusFailure

/** A serving defect while presenting a ledger refusal, preserving its original typed cause. */
sealed trait RefusalPresentationError extends DomainError:
  def failure: PlutusFailure
  def message: String
  final override def toString: String = message

object RefusalPresentationError:
  private[ntc] final case class InvalidPlutusContext(
      failure: PlutusFailure,
      cause: PlutusContextText.Error
  ) extends RefusalPresentationError:
    def message: String = cause match
      case PlutusContextText.Error.Shape(expected, _) =>
        s"cannot present the Plutus refusal context: invalid $expected shape"
      case PlutusContextText.Error.Constructor(expected, tag) =>
        s"cannot present the Plutus refusal context: unknown $expected constructor $tag"
      case PlutusContextText.Error.Arguments(language, count) =>
        s"cannot present the Plutus refusal context: invalid $language argument count $count"
      case PlutusContextText.Error.LayoutArity(expected, actual) =>
        s"cannot present the Plutus refusal context: expected $expected layout fields, received $actual"
      case PlutusContextText.Error.Unported(language) =>
        s"cannot present the Plutus refusal context: no printer of the $language context"
