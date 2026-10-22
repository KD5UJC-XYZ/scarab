### Fixed (multi-asset decoding, #328)

- Read output and mint multi-asset maps at the decoder version of the block's era, as the reference does (`decodeMultiAsset`): from Conway a zero quantity, an empty asset map, a duplicate policy or a duplicate asset name fails the decode; Mary to Babbage merge duplicates (the last entry wins) and then prune zeros and empty policies. Mint quantities are bounded to `Int64`, and every version refuses a value over the compact-form bound.
