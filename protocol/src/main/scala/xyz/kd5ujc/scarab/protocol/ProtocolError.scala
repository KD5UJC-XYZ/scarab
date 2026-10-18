// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.DomainError

/** Semantic failures of mini-protocol framing, agency and payload validation. */
enum ProtocolError extends DomainError:
  case AckExceedsOutstandingInventory(acknowledged: Int, outstanding: Int)
  case ArrayLimitExceeded
  case BatchLimitExceeded(actual: Int, maximum: Int)
  case BlockFetchIsTerminal
  case BlockPointOrderMismatch
  case ByronHeaderRequiresItsWrapper
  case CBORArgumentOutsideUint64
  case CBORDepthLimitExceeded
  case CBORIntegerOutsideUint64
  case CBORItemLimitExceeded
  case CBORResourceLimitExceeded
  case CandidateLimitExceeded
  case CardanoPointHashMustContain32Bytes(actual: Int)
  case ClientIsNotAwaitingConfirmation
  case CountOutsideUint16(value: BigInt)
  case DuplicateBodyRequest
  case DuplicateExpectedPoints
  case DuplicateMeasureName
  case DuplicateOfferID
  case DuplicateVersion
  case EarlyBatchDoneIncompleteRequestedRange
  case ExpectedBlockingBoolean
  case ExpectedBoolean
  case ExpectedBreakAfterFourthTransactionMember
  case ExpectedByronSubtagSize
  case ExpectedByronSubtagSizeHeader
  case ExpectedCandidateArray
  case ExpectedConwayGenTx
  case ExpectedConwayTransactionID
  case ExpectedDefiniteArray
  case ExpectedDefiniteByteString
  case ExpectedDefiniteMap
  case ExpectedDefiniteTextString
  case ExpectedDefiniteVersionMap
  case ExpectedEraHeader
  case ExpectedFourMemberConwayTransaction
  case ExpectedIndefiniteProtocolList
  case ExpectedKeepAliveResponse
  case ExpectedPointCountOutsideRequestLimits(actual: Int, maximum: Int)
  case ExpectedRequestRange
  case ExpectedSerializedBlockTag24
  case ExpectedTag
  case ExpectedTag24Header
  case ExpectedUint64
  case ExtraBlockInRequestedBatch
  case HandshakeAlreadyDone
  case HandshakeByteLimitExceeded
  case HashByteLimitExceeded
  case IncompleteBlockFetchRequestAtEndOfInput
  case IncompleteCbor
  case InvalidBlockFetchLimits
  case InvalidBlockingInventoryRequest
  case InvalidExtendedCBORSimpleValue
  case InvalidExtendedSimpleValue
  case InvalidFloatBits
  case InvalidIndefiniteStringChunk
  case InvalidKeepAliveLimits
  case InvalidLocalLimits
  case InvalidLocalProposal
  case InvalidNegotiationResult
  case InvalidNonblockingInventoryRequest
  case InvalidOrOversizedRefusalText
  case InvalidRawBlockLimit(value: Int)
  case InvalidRefusalTagOrArity
  case InvalidRequestAccountingLimitsBytes
  case InvalidRequestLimits
  case InvalidSimpleValue
  case InvalidStringChunk
  case InvalidUTF8
  case InvalidUnicodeText
  case ItemLimitExceeded
  case KeepAliveCookieMismatch(expected: KeepAlive.Cookie, actual: KeepAlive.Cookie)
  case KeepAliveCookieOutsideUint16(value: BigInt)
  case KeepAliveIsTerminal
  case MapLimitExceeded
  case MeasureArity
  case MessageByteLimitExceeded
  case NetworkMagicMismatch
  case NetworkMagicOutsideUint32
  case NtCDataArity
  case NtCHasNoNtNFeatures
  case NtNDataArity
  case OfferArity
  case OnlyByronHeadersCarryAWrapper
  case OriginIsNotAnAddressableCardanoBlock
  case OriginalSizeOutsidePolicy(actual: Int, maximum: Int)
  case OutsideUint64(value: BigInt)
  case PeerSharingMustBe0Or1
  case PerasRequiresVersion16
  case PointArity
  case RawBlockByteLimitExceeded(actual: Int, maximum: Int)
  case RecognizedVersionsMustBeDistinctAndIncreasing
  case RequestAggregateRawByteLimitExceeded
  case RequestAlreadyCompleted
  case RequestBlockLimitExceeded
  case ReservedCBORArgument
  case ReservedCBORSimpleValue
  case ReservedOrIndefiniteArgument
  case ResponderExpectsProposal
  case ReversedExpectedSlotOrder
  case ServerSelectedAVersionNotOffered
  case SizeHint(value: BigInt)
  case SizeOutsideUint32
  case SizesArity
  case StringByteLimitExceeded
  case StringLimitExceeded
  case TipArity
  case TrailingBlockFetchBytes
  case TrailingBytes
  case TrailingHeaderBytes
  case TrailingKeepAliveBytes
  case TrailingOriginalTransactionBytes
  case TrailingPayloadBytes
  case TrailingSerializedBlockBytes
  case TrailingTxSubmission2Bytes
  case TransactionBodyIsNotAMap
  case TransactionIDMustBe32Bytes(actual: Int)
  case TransactionValidityIsNotABoolean
  case TruncatedBlockFetchMessage
  case TruncatedCbor
  case TruncatedKeepAliveMessage
  case TruncatedList
  case TruncatedTxSubmission2Message
  case UnadvertisedAcknowledgedOrRepeatedBodyRequest
  case UnexpectedBreak
  case UnexpectedBreakOrReservedSimpleValue
  case UnexpectedClientMessageInServerBatch
  case UnexpectedHandshakeTagArityOrState
  case UnknownBlockFetchTag
  case UnknownByronSubtag
  case UnknownEraIndex
  case UnknownKeepAliveTagStateOrExactArity
  case UnknownLocalStateQueryFailure
  case UnknownLocalStateQueryTagOrArity
  case UnknownLocalTxMonitorTagOrArity
  case UnknownLocalTxSubmissionTagOrArity
  case UnknownTagOrWrongExactOuterArity
  case UnknownTxSubmission2TagStateArity
  case UnrecognizedSelectedVersion
  case UnsolicitedKeepAliveResponse
  case UnsupportedLogicalVersion
  case WrongBlockFetchAgency
  case WrongBlockFetchOuterArityForState
  case WrongBlockFetchState
  case WrongExactBlockFetchOuterArity
  case WrongKeepAliveAgency
  case WrongKeepAliveOuterArityForState
  case WrongLocalStateQueryAgency
  case WrongLocalTxMonitorAgency
  case WrongLocalTxSubmissionAgency
  case WrongTxSubmission2Agency
  case ChainSyncAgency(state: ChainSync.State, sender: ChainSync.Role)
  case BlockFetchAgency(state: BlockFetch.State, sender: BlockFetch.Role)
  case KeepAliveAgency(state: KeepAlive.State, sender: KeepAlive.Role)
  case LocalTxSubmissionAgency(state: LocalTxSubmission.State, sender: ChainSync.Role)
  case LocalTxMonitorAgency(state: LocalTxMonitor.State, sender: ChainSync.Role)
  case LocalStateQueryAgency(state: LocalStateQuery.State, sender: ChainSync.Role)
  case TxSubmission2Agency(state: TxSubmission2.State, sender: ChainSync.Role)
  case Word32Range(field: ProtocolError.MonitorField, value: BigInt)
  case Codec(cause: xyz.kd5ujc.scarab.codec.Cbor.Error)
  case HfcHeader(cause: HfcHeaderError)
  case PeerSharing(cause: xyz.kd5ujc.scarab.protocol.PeerSharing.Error)
  case Leios(cause: LeiosWireError)

  def incomplete: Boolean = this match
    case TruncatedCbor | IncompleteCbor | TruncatedList => true
    case _                                              => false

  def message: String = this match
    case AckExceedsOutstandingInventory(_, _)   => "ack exceeds outstanding inventory"
    case ArrayLimitExceeded                     => "array limit exceeded"
    case BatchLimitExceeded(_, _)               => "batch limit exceeded"
    case BlockFetchIsTerminal                   => "BlockFetch is terminal"
    case BlockPointOrderMismatch                => "block point/order mismatch"
    case ByronHeaderRequiresItsWrapper          => "Byron header requires its wrapper"
    case CBORArgumentOutsideUint64              => "CBOR argument outside uint64"
    case CBORDepthLimitExceeded                 => "CBOR depth limit exceeded"
    case CBORIntegerOutsideUint64               => "CBOR integer outside uint64"
    case CBORItemLimitExceeded                  => "CBOR item limit exceeded"
    case CBORResourceLimitExceeded              => "CBOR resource limit exceeded"
    case CandidateLimitExceeded                 => "candidate limit exceeded"
    case CardanoPointHashMustContain32Bytes(_)  => "Cardano point hash must contain 32 bytes"
    case ClientIsNotAwaitingConfirmation        => "client is not awaiting confirmation"
    case CountOutsideUint16(_)                  => "count outside uint16"
    case DuplicateBodyRequest                   => "duplicate body request"
    case DuplicateExpectedPoints                => "duplicate expected points"
    case DuplicateMeasureName                   => "duplicate measure name"
    case DuplicateOfferID                       => "duplicate offer ID"
    case DuplicateVersion                       => "duplicate version"
    case EarlyBatchDoneIncompleteRequestedRange => "early BatchDone: incomplete requested range"
    case ExpectedBlockingBoolean                => "expected blocking Boolean"
    case ExpectedBoolean                        => "expected boolean"
    case ExpectedBreakAfterFourthTransactionMember =>
      "expected break after fourth transaction member"
    case ExpectedByronSubtagSize             => "expected Byron [subtag, size]"
    case ExpectedByronSubtagSizeHeader       => "expected Byron [[subtag, size], header]"
    case ExpectedCandidateArray              => "expected candidate array"
    case ExpectedConwayGenTx                 => "expected Conway GenTx"
    case ExpectedConwayTransactionID         => "expected Conway transaction ID"
    case ExpectedDefiniteArray               => "expected definite array"
    case ExpectedDefiniteByteString          => "expected definite byte string"
    case ExpectedDefiniteMap                 => "expected definite map"
    case ExpectedDefiniteTextString          => "expected definite text string"
    case ExpectedDefiniteVersionMap          => "expected definite version map"
    case ExpectedEraHeader                   => "expected [era, header]"
    case ExpectedFourMemberConwayTransaction => "expected four-member Conway transaction"
    case ExpectedIndefiniteProtocolList      => "expected indefinite protocol list"
    case ExpectedKeepAliveResponse           => "expected KeepAlive response"
    case ExpectedPointCountOutsideRequestLimits(_, _) =>
      "expected point count outside request limits"
    case ExpectedRequestRange                    => "expected RequestRange"
    case ExpectedSerializedBlockTag24            => "expected serialized block tag24"
    case ExpectedTag                             => "expected tag"
    case ExpectedTag24Header                     => "expected tag24 header"
    case ExpectedUint64                          => "expected uint64"
    case ExtraBlockInRequestedBatch              => "extra block in requested batch"
    case HandshakeAlreadyDone                    => "handshake already done"
    case HandshakeByteLimitExceeded              => "handshake byte limit exceeded"
    case HashByteLimitExceeded                   => "hash byte limit exceeded"
    case IncompleteBlockFetchRequestAtEndOfInput => "incomplete BlockFetch request at end of input"
    case IncompleteCbor                          => "incomplete CBOR"
    case InvalidBlockFetchLimits                 => "invalid BlockFetch limits"
    case InvalidBlockingInventoryRequest         => "invalid blocking inventory request"
    case InvalidExtendedCBORSimpleValue          => "invalid extended CBOR simple value"
    case InvalidExtendedSimpleValue              => "invalid extended simple value"
    case InvalidFloatBits                        => "invalid float bits"
    case InvalidIndefiniteStringChunk            => "invalid indefinite string chunk"
    case InvalidKeepAliveLimits                  => "invalid KeepAlive limits"
    case InvalidLocalLimits                      => "invalid local limits"
    case InvalidLocalProposal                    => "invalid local proposal"
    case InvalidNegotiationResult                => "invalid negotiation result"
    case InvalidNonblockingInventoryRequest      => "invalid nonblocking inventory request"
    case InvalidOrOversizedRefusalText           => "invalid or oversized refusal text"
    case InvalidRawBlockLimit(_)                 => "invalid raw block limit"
    case InvalidRefusalTagOrArity                => "invalid refusal tag or arity"
    case InvalidRequestAccountingLimitsBytes     => "invalid request accounting limits/bytes"
    case InvalidRequestLimits                    => "invalid request limits"
    case InvalidSimpleValue                      => "invalid simple value"
    case InvalidStringChunk                      => "invalid string chunk"
    case InvalidUTF8                             => "invalid UTF-8"
    case InvalidUnicodeText                      => "invalid Unicode text"
    case ItemLimitExceeded                       => "item limit exceeded"
    case KeepAliveCookieMismatch(_, _)           => "KeepAlive cookie mismatch"
    case KeepAliveCookieOutsideUint16(_)         => "KeepAlive cookie outside uint16"
    case KeepAliveIsTerminal                     => "KeepAlive is terminal"
    case MapLimitExceeded                        => "map limit exceeded"
    case MeasureArity                            => "measure arity"
    case MessageByteLimitExceeded                => "message byte limit exceeded"
    case NetworkMagicMismatch                    => "network magic mismatch"
    case NetworkMagicOutsideUint32               => "network magic outside uint32"
    case NtCDataArity                            => "NtC data arity"
    case NtCHasNoNtNFeatures                     => "NtC has no NtN features"
    case NtNDataArity                            => "NtN data arity"
    case OfferArity                              => "offer arity"
    case OnlyByronHeadersCarryAWrapper           => "only Byron headers carry a wrapper"
    case OriginIsNotAnAddressableCardanoBlock    => "Origin is not an addressable Cardano block"
    case OriginalSizeOutsidePolicy(_, _)         => "original size outside policy"
    case OutsideUint64(_)                        => "outside uint64"
    case PeerSharingMustBe0Or1                   => "peer sharing must be 0 or 1"
    case PerasRequiresVersion16                  => "Peras requires version 16"
    case PointArity                              => "point arity"
    case RawBlockByteLimitExceeded(_, _)         => "raw block byte limit exceeded"
    case RecognizedVersionsMustBeDistinctAndIncreasing =>
      "recognized versions must be distinct and increasing"
    case RequestAggregateRawByteLimitExceeded => "request aggregate raw byte limit exceeded"
    case RequestAlreadyCompleted              => "request already completed"
    case RequestBlockLimitExceeded            => "request block limit exceeded"
    case ReservedCBORArgument                 => "reserved CBOR argument"
    case ReservedCBORSimpleValue              => "reserved CBOR simple value"
    case ReservedOrIndefiniteArgument         => "reserved or indefinite argument"
    case ResponderExpectsProposal             => "responder expects proposal"
    case ReversedExpectedSlotOrder            => "reversed expected slot order"
    case ServerSelectedAVersionNotOffered     => "server selected a version not offered"
    case SizeHint(_)                          => "size hint"
    case SizeOutsideUint32                    => "size outside uint32"
    case SizesArity                           => "sizes arity"
    case StringByteLimitExceeded              => "string byte limit exceeded"
    case StringLimitExceeded                  => "string limit exceeded"
    case TipArity                             => "tip arity"
    case TrailingBlockFetchBytes              => "trailing BlockFetch bytes"
    case TrailingBytes                        => "trailing bytes"
    case TrailingHeaderBytes                  => "trailing header bytes"
    case TrailingKeepAliveBytes               => "trailing KeepAlive bytes"
    case TrailingOriginalTransactionBytes     => "trailing original transaction bytes"
    case TrailingPayloadBytes                 => "trailing payload bytes"
    case TrailingSerializedBlockBytes         => "trailing serialized block bytes"
    case TrailingTxSubmission2Bytes           => "trailing TxSubmission2 bytes"
    case TransactionBodyIsNotAMap             => "transaction body is not a map"
    case TransactionIDMustBe32Bytes(_)        => "transaction ID must be 32 bytes"
    case TransactionValidityIsNotABoolean     => "transaction validity is not a Boolean"
    case TruncatedBlockFetchMessage           => "truncated BlockFetch message"
    case TruncatedCbor                        => "truncated CBOR"
    case TruncatedKeepAliveMessage            => "truncated KeepAlive message"
    case TruncatedList                        => "truncated list"
    case TruncatedTxSubmission2Message        => "truncated TxSubmission2 message"
    case UnadvertisedAcknowledgedOrRepeatedBodyRequest =>
      "unadvertised, acknowledged or repeated body request"
    case UnexpectedBreak                      => "unexpected break"
    case UnexpectedBreakOrReservedSimpleValue => "unexpected break or reserved simple value"
    case UnexpectedClientMessageInServerBatch => "unexpected client message in server batch"
    case UnexpectedHandshakeTagArityOrState   => "unexpected handshake tag, arity, or state"
    case UnknownBlockFetchTag                 => "unknown BlockFetch tag"
    case UnknownByronSubtag                   => "unknown Byron subtag"
    case UnknownEraIndex                      => "unknown era index"
    case UnknownKeepAliveTagStateOrExactArity => "unknown KeepAlive tag, state or exact arity"
    case UnknownLocalStateQueryFailure        => "unknown LocalStateQuery failure"
    case UnknownLocalStateQueryTagOrArity     => "unknown LocalStateQuery tag or arity"
    case UnknownLocalTxMonitorTagOrArity      => "unknown LocalTxMonitor tag or arity"
    case UnknownLocalTxSubmissionTagOrArity   => "unknown LocalTxSubmission tag or arity"
    case UnknownTagOrWrongExactOuterArity     => "unknown tag or wrong exact outer arity"
    case UnknownTxSubmission2TagStateArity    => "unknown TxSubmission2 tag/state/arity"
    case UnrecognizedSelectedVersion          => "unrecognized selected version"
    case UnsolicitedKeepAliveResponse         => "unsolicited KeepAlive response"
    case UnsupportedLogicalVersion            => "unsupported logical version"
    case WrongBlockFetchAgency                => "wrong BlockFetch agency"
    case WrongBlockFetchOuterArityForState    => "wrong BlockFetch outer arity for state"
    case WrongBlockFetchState                 => "wrong BlockFetch state"
    case WrongExactBlockFetchOuterArity       => "wrong exact BlockFetch outer arity"
    case WrongKeepAliveAgency                 => "wrong KeepAlive agency"
    case WrongKeepAliveOuterArityForState     => "wrong KeepAlive outer arity for state"
    case WrongLocalStateQueryAgency           => "wrong LocalStateQuery agency"
    case WrongLocalTxMonitorAgency            => "wrong LocalTxMonitor agency"
    case WrongLocalTxSubmissionAgency         => "wrong LocalTxSubmission agency"
    case WrongTxSubmission2Agency             => "wrong TxSubmission2 agency"
    case ChainSyncAgency(state, sender) =>
      s"illegal ChainSync message/agency in $state from $sender"
    case BlockFetchAgency(state, sender) =>
      s"illegal BlockFetch message/agency in $state from $sender"
    case KeepAliveAgency(state, sender) =>
      s"illegal KeepAlive message/agency in $state from $sender"
    case LocalTxSubmissionAgency(state, sender) =>
      s"illegal LocalTxSubmission message/agency in $state from $sender"
    case LocalTxMonitorAgency(state, sender) =>
      s"illegal LocalTxMonitor message/agency in $state from $sender"
    case LocalStateQueryAgency(state, sender) =>
      s"illegal LocalStateQuery message/agency in $state from $sender"
    case TxSubmission2Agency(state, sender) =>
      s"illegal TxSubmission2 message/agency in $state from $sender"
    case Word32Range(field, _) =>
      s"${field.toString.head.toLower}${field.toString.tail} does not fit a Word32"
    case Codec(cause)       => cause.message
    case HfcHeader(cause)   => cause.toString
    case PeerSharing(cause) => cause.message
    case Leios(cause)       => cause.toString

object ProtocolError:
  enum MonitorField:
    case CapacityInBytes, SizeInBytes, NumberOfTxs, TxCount
