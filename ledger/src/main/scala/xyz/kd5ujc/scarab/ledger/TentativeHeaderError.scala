// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.state.VocabError

/** A decoded header cannot be projected to the native tentative-header selection vocabulary. */
enum TentativeHeaderError extends DomainError:
  case Vocabulary(cause: VocabError)
  case IssuerKeyLength(actual: Int)
  case ShelleyHeaderByron
