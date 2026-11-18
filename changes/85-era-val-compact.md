### Added (era types, #85)

- The era types' `Val` gains the reference's size and compact-form members, with each era value's compact form: a `Coin`'s word, and MaryValue's `CompactValue` over the packed representation the MemPack codec already writes. A value's size in words and the lovelace and kind of its compact form equal the plain value's, on the reference's worked example and on generated values; nothing yet calls them, so nothing changes in behaviour.
