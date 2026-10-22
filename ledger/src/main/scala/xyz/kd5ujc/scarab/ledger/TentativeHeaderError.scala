// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.show.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.state.VocabError

/** A decoded header cannot be projected to the native tentative-header selection vocabulary. */
enum TentativeHeaderError extends DomainError:
  case Vocabulary(cause: VocabError)
  case IssuerKeyLength(actual: Int)
  case ShelleyHeaderByron

  def message: String = this match
    case Vocabulary(cause)       => s"invalid ledger vocabulary: ${cause.show}"
    case IssuerKeyLength(actual) => s"block issuer verification key is $actual bytes; expected 32"
    case ShelleyHeaderByron      => "Byron header has no Shelley tentative-header vocabulary"
