### Fixed

- ChainSync encodes every candidate list without an outgoing count or byte policy. Node-to-node receiving retains the reference's 65,535-byte message limit. Node-to-client receiving and serialized blocks no longer inherit the node-to-node byte, item, nesting or hash budgets (#749, #755). Its live mux receives without a protocol byte cap. The existing contiguous byte representation still limits capacity; an exhausted live receive buffer identifies that capacity separately, without claiming it is a reference protocol limit.
