Decode interning now keeps a typed table for each kind and document, without unchecked value
recovery. Equal values retain first-instance sharing; separate kinds and decodes stay isolated.
