### Fixed (PeerSharing decoding, #1029)

- The PeerSharing decoder reads the declared length of a message's array and of a peer address's and
  ignores it, as the reference's codec does: the fields read are those the message key or address tag
  names, and an item declared beyond them is left to the next message. A message or address whose
  declared length is wrong was refused before; a length of 2^63 or more is still refused, as cborg's
  `decodeListLen` refuses it.
