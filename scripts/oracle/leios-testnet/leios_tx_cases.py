# SPDX-License-Identifier: Apache-2.0
"""The transaction cases of the Leios testnet oracle (#750).

A transaction of a Dijkstra block is read by `decodeDijkstraTopTxInBlock` (cardano-ledger 9df9f98c,
`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:175-182`) at decoder version 12: the body
(`Dijkstra/TxBody.hs:340-479`), the witness set (`Alonzo/TxWits.hs:620-700`), the auxiliary data
(`Alonzo/TxAuxData.hs:214-313`) and every record and collection they hold. The cases are the
testnet's own transactions, and a transaction that the build accepts with one field changed: each
required body field missing, a field of the wrong type, a collection empty where it must not be, a
duplicate, a form (definite, indefinite, tagged, chunked) at each decoder's boundary, and
sub-transactions. The encodings are written by hand here; the oracle decides what the build does
with each, and the Scala suite requires the same of `DijkstraTx.inBlock`.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "conway-decoding"))
import cases as c  # noqa: E402  (the CBOR helpers)

ROOT = os.path.join(HERE, "..", "..", "..")
SYNTHETIC = "scripts/oracle/leios-testnet/leios_tx_cases.py"

h, uint, bstr, text, array, cmap, tag = c.h, c.uint, c.bstr, c.text, c.array, c.cmap, c.tag
NULL, TRUE, FALSE = c.NULL, c.TRUE, b"\xf4"


def nint(v):
    return c.nint(v)


def chunked(b, at):
    """A chunked byte string: `b` split at `at`."""
    return b"\x5f" + bstr(b[:at]) + bstr(b[at:]) + b"\xff"


def chunked_text(s, at):
    raw = s.encode()
    return b"\x7f" + c.head(3, at) + raw[:at] + c.head(3, len(raw) - at) + raw[at:] + b"\xff"


def detached(items):
    return b"\x9f\xff" + b"".join(items)


def ratio(n, d, indef=False):
    return tag(30, array([uint(n), uint(d)], indef=indef))


def cred(kind=0, byte=0x33):
    return array([uint(kind), bstr(h(byte, 28))])


TXIN = array([bstr(h(0x11, 32)), uint(0)])
TXIN_2 = array([bstr(h(0x12, 32)), uint(1)])
ADDRESS = bstr(b"\x61" + h(0x22, 28))
OUTPUT = array([ADDRESS, uint(2_000_000)])
ACCOUNT = bstr(b"\xe1" + h(0x77, 28))
ACCOUNT_2 = bstr(b"\xf0" + h(0x78, 28))
ANCHOR = array([text("https://example.invalid/a"), bstr(h(0x44, 32))])
POLICY = bstr(h(0x88, 28))
DATA = uint(42)
DATA_2 = array([uint(1), uint(2)])
SCRIPT = bstr(b"\x4e\x4d\x01\x00\x00\x22\x22\x00\x11")
NATIVE = array([uint(0), bstr(h(0x33, 28))])
VKEY_WIT = array([bstr(h(0x91, 32)), bstr(h(0xA1, 64))])
BOOTSTRAP = array([bstr(h(0x91, 32)), bstr(h(0xA1, 64)), bstr(h(0x92, 32)), bstr(cmap([]))])

POOLS = [line.split("\t") for line in open(os.path.join(ROOT, "fixtures/leios/bls/devnet-pools.tsv"))
         if not line.startswith("#")]
BLS_KEY, BLS_POP = bytes.fromhex(POOLS[0][2]), bytes.fromhex(POOLS[0][3].strip())
SERDE = [bytes.fromhex(line.strip())
         for line in open(os.path.join(ROOT, "fixtures/leios/bls/cardano-base-serde-test-vectors"))]


def body(extra=None, drop=(), indef=False, sub=False):
    """A body that the build accepts, with `extra` fields added or replaced and `drop` removed."""
    fields = {0: array([TXIN]), 1: array([OUTPUT])}
    if not sub:
        fields[2] = uint(200_000)
    fields.update(extra or {})
    for k in drop:
        fields.pop(k, None)
    return cmap([(uint(k), v) for k, v in sorted(fields.items())], indef=indef)


def tx(b=None, wits=None, aux=NULL, valid=TRUE):
    return array([b if b is not None else body(), wits if wits is not None else cmap([]), aux, valid])


def subtx(b=None, wits=None, aux=NULL, items=None):
    parts = [b if b is not None else body(sub=True), wits if wits is not None else cmap([]), aux]
    return array(parts if items is None else items)


def pool_params(bls=None, owners=None, relays=None, metadata=NULL, margin=None):
    head = [bstr(h(0x61, 28)), bstr(h(0x62, 32))]
    if bls is not None:
        head.append(bls)
    return head + [uint(500_000_000), uint(340_000_000), margin or ratio(1, 2), ACCOUNT,
                   owners if owners is not None else array([bstr(h(0x63, 28))]),
                   relays if relays is not None else array([]), metadata]


def pool_cert(**kw):
    return array([uint(3)] + pool_params(**kw))


def bls_key(key=BLS_KEY, pop=BLS_POP):
    return array([bstr(key), bstr(pop)])


def proposal(action, deposit=1_000_000):
    return array([uint(deposit), ACCOUNT, action, ANCHOR])


def param_change(update, previous=NULL, policy=NULL):
    return proposal(array([uint(0), previous, update, policy]))


def update(*pairs, indef=False):
    return cmap([(uint(k), v) for k, v in pairs], indef=indef)


def wits(*pairs):
    return cmap([(uint(k), v) for k, v in pairs])


def auxiliary_alonzo(*pairs, number=259):
    return tag(number, cmap([(uint(k), v) for k, v in pairs]))


def build(case, blocks):
    """Add every transaction case through `case(name, raw, source)`. `blocks` are the captured
    blocks with transactions, `(source, [transaction bytes])`."""
    for source, txs in blocks:
        for i, raw in enumerate(txs):
            case(f"{source} transaction {i}", raw, source)

    def t(name, raw):
        case(f"tx {name}", raw, SYNTHETIC)

    def b(name, **fields):
        """A transaction whose body has the fields given (key `kN`) or lacks them (`drop`)."""
        drop = fields.pop("drop", ())
        indef = fields.pop("indef", False)
        t(name, tx(body({int(k[1:]): v for k, v in fields.items()}, drop=drop, indef=indef)))

    # The framing of the issue (#750): a body and a witness set that are not maps.
    t("transaction of a zero body and zero witnesses", array([uint(0), uint(0), NULL, TRUE]))
    t("transaction minimal", tx())
    t("transaction marked invalid", tx(valid=FALSE))
    t("transaction indefinite", array([body(), cmap([]), NULL, TRUE], indef=True))
    t("transaction detached", detached([body(), cmap([]), NULL, TRUE]))
    t("transaction of three items", array([body(), cmap([]), NULL]))
    t("transaction of the mempool form with the flag third", array([body(), cmap([]), TRUE, NULL]))

    # The body map: required, unknown and repeated keys, forms.
    b("body without inputs", drop=(0,))
    b("body without outputs", drop=(1,))
    b("body without a fee", drop=(2,))
    b("body indefinite", indef=True)
    t("body with its keys out of order",
      tx(cmap([(uint(2), uint(200_000)), (uint(1), array([OUTPUT])), (uint(0), array([TXIN]))])))
    for k in [6, 10, 12, 28, 2**32, 2**64 - 1]:
        t(f"body with the unknown key {k}",
          tx(cmap([(uint(0), array([TXIN])), (uint(1), array([OUTPUT])), (uint(2), uint(1)), (uint(k), uint(0))])))
    t("body with the fee twice",
      tx(cmap([(uint(0), array([TXIN])), (uint(1), array([OUTPUT])), (uint(2), uint(1)), (uint(2), uint(1))])))
    t("body with a negative key",
      tx(cmap([(uint(0), array([TXIN])), (uint(1), array([OUTPUT])), (uint(2), uint(1)), (nint(-1), uint(0))])))
    t("body with a key of a wide head", tx(cmap([(uint(0, 8), array([TXIN])), (uint(1), array([OUTPUT])),
                                                  (uint(2), uint(1))])))
    t("body a list", tx(array([array([TXIN]), array([OUTPUT]), uint(1)])))

    # Inputs.
    b("inputs empty", k0=array([]))
    b("inputs tagged 258", k0=tag(258, array([TXIN])))
    b("inputs tagged 259", k0=tag(259, array([TXIN])))
    b("inputs indefinite", k0=array([TXIN], indef=True))
    b("inputs with one input twice", k0=array([TXIN, TXIN]))
    b("inputs with one input in two encodings", k0=array([TXIN, array([bstr(h(0x11, 32)), uint(0, 8)])]))
    b("inputs two", k0=array([TXIN, TXIN_2]))
    b("input indefinite", k0=array([array([bstr(h(0x11, 32)), uint(0)], indef=True)]))
    b("input detached", k0=array([detached([bstr(h(0x11, 32)), uint(0)])]))
    b("input index 65535", k0=array([array([bstr(h(0x11, 32)), uint(65535)])]))
    b("input index 65536", k0=array([array([bstr(h(0x11, 32)), uint(65536)])]))
    b("input id of 31 bytes", k0=array([array([bstr(h(0x11, 31)), uint(0)])]))
    b("input id chunked", k0=array([array([chunked(h(0x11, 32), 7), uint(0)])]))
    b("input of three items", k0=array([array([bstr(h(0x11, 32)), uint(0), uint(0)])]))

    # Outputs and their parts.
    b("outputs empty", k1=array([]))
    b("outputs indefinite", k1=array([OUTPUT], indef=True))
    b("outputs tagged 258", k1=tag(258, array([OUTPUT])))
    b("outputs with one output twice", k1=array([OUTPUT, OUTPUT]))
    b("output list indefinite", k1=array([array([ADDRESS, uint(2_000_000)], indef=True)]))
    b("output list with a datum hash", k1=array([array([ADDRESS, uint(1), bstr(h(0x45, 32))])]))
    b("output list indefinite with a datum hash", k1=array([array([ADDRESS, uint(1), bstr(h(0x45, 32))], indef=True)]))
    b("output list with a datum hash of 31 bytes", k1=array([array([ADDRESS, uint(1), bstr(h(0x45, 31))])]))
    b("output list of one item", k1=array([array([ADDRESS])]))
    b("output list of four items", k1=array([array([ADDRESS, uint(1), bstr(h(0x45, 32)), NULL])]))
    b("output list detached", k1=array([detached([ADDRESS, uint(1)])]))

    def out(*pairs, indef=False, width=None):
        return array([cmap([(uint(k), v) for k, v in pairs], indef=indef, width=width)])

    b("output map", k1=out((0, ADDRESS), (1, uint(5))))
    b("output map indefinite", k1=out((0, ADDRESS), (1, uint(5)), indef=True))
    b("output map with a wide length head", k1=out((0, ADDRESS), (1, uint(5)), width=8))
    b("output map with a two-byte length head", k1=out((0, ADDRESS), (1, uint(5)), width=2))
    b("output map without a value", k1=out((0, ADDRESS)))
    b("output map without an address", k1=out((1, uint(5))))
    b("output map with the key 4", k1=out((0, ADDRESS), (1, uint(5)), (4, uint(0))))
    b("output map with the address twice", k1=out((0, ADDRESS), (0, ADDRESS), (1, uint(5))))
    b("output datum hash", k1=out((0, ADDRESS), (1, uint(5)), (2, array([uint(0), bstr(h(0x45, 32))]))))
    b("output datum hash indefinite",
      k1=out((0, ADDRESS), (1, uint(5)), (2, array([uint(0), bstr(h(0x45, 32))], indef=True))))
    b("output inline datum", k1=out((0, ADDRESS), (1, uint(5)), (2, array([uint(1), tag(24, bstr(DATA_2))]))))
    b("output inline datum chunked",
      k1=out((0, ADDRESS), (1, uint(5)), (2, array([uint(1), tag(24, chunked(DATA_2, 1))]))))
    b("output inline datum not data",
      k1=out((0, ADDRESS), (1, uint(5)), (2, array([uint(1), tag(24, bstr(text("x")))]))))
    b("output inline datum with a trailing byte",
      k1=out((0, ADDRESS), (1, uint(5)), (2, array([uint(1), tag(24, bstr(DATA + b"\x00"))]))))
    b("output inline datum without its tag 24",
      k1=out((0, ADDRESS), (1, uint(5)), (2, array([uint(1), bstr(DATA)]))))
    b("output inline datum under tag 25",
      k1=out((0, ADDRESS), (1, uint(5)), (2, array([uint(1), tag(25, bstr(DATA))]))))
    b("output datum of tag 2", k1=out((0, ADDRESS), (1, uint(5)), (2, array([uint(2), bstr(h(0x45, 32))]))))
    b("output inline datum with a 65-byte string",
      k1=out((0, ADDRESS), (1, uint(5)), (2, array([uint(1), tag(24, bstr(bstr(h(1, 65))))]))))
    for lang in range(5):
        script = array([uint(0), NATIVE]) if lang == 0 else array([uint(lang), SCRIPT])
        b(f"output reference script of tag {lang}", k1=out((0, ADDRESS), (1, uint(5)), (3, tag(24, bstr(script)))))
    b("output reference script of tag 5",
      k1=out((0, ADDRESS), (1, uint(5)), (3, tag(24, bstr(array([uint(5), SCRIPT]))))))
    b("output reference script without its tag 24",
      k1=out((0, ADDRESS), (1, uint(5)), (3, array([uint(1), SCRIPT]))))
    b("output reference script indefinite",
      k1=out((0, ADDRESS), (1, uint(5)), (3, tag(24, bstr(array([uint(1), SCRIPT], indef=True))))))
    b("output reference script with a guard native script",
      k1=out((0, ADDRESS), (1, uint(5)), (3, tag(24, bstr(array([uint(0), array([uint(6), cred(1)])]))))))
    b("output reference script chunked program",
      k1=out((0, ADDRESS), (1, uint(5)), (3, tag(24, bstr(array([uint(3), chunked(c.h(0x4d, 9), 4)]))))))
    b("output address chunked", k1=array([array([chunked(b"\x61" + h(0x22, 28), 5), uint(5)])]))
    b("output address with a trailing byte", k1=array([array([bstr(b"\x61" + h(0x22, 29)), uint(5)])]))
    b("output address with header bit 1", k1=array([array([bstr(b"\x63" + h(0x22, 28)), uint(5)])]))
    b("output address a reward account", k1=array([array([ACCOUNT, uint(5)])]))
    b("output address text", k1=array([array([text("addr"), uint(5)])]))
    b("output value of 2^64 - 1", k1=array([array([ADDRESS, uint(2**64 - 1)])]))
    b("output value a bignum", k1=array([array([ADDRESS, c.bignum(5)])]))
    b("output value negative", k1=array([array([ADDRESS, nint(-5)])]))
    b("output value a map", k1=array([array([ADDRESS, cmap([])])]))

    def value(assets, coin=uint(5), indef=False):
        return array([array([ADDRESS, array([coin, assets], indef=indef)])])

    one = cmap([(POLICY, cmap([(bstr(b"a"), uint(5))]))])
    b("output value with assets", k1=value(one))
    b("output value with assets indefinite", k1=value(one, indef=True))
    b("output value with no assets", k1=value(cmap([])))
    b("output value with a policy of no assets", k1=value(cmap([(POLICY, cmap([]))])))
    b("output value with a zero quantity", k1=value(cmap([(POLICY, cmap([(bstr(b"a"), uint(0))]))])))
    b("output value with a negative quantity", k1=value(cmap([(POLICY, cmap([(bstr(b"a"), nint(-1))]))])))
    b("output value with a quantity of 2^64 - 1", k1=value(cmap([(POLICY, cmap([(bstr(b"a"), uint(2**64 - 1))]))])))
    b("output value with an asset name of 32 bytes", k1=value(cmap([(POLICY, cmap([(bstr(h(1, 32)), uint(1))]))])))
    b("output value with an asset name of 33 bytes", k1=value(cmap([(POLICY, cmap([(bstr(h(1, 33)), uint(1))]))])))
    b("output value with an asset name chunked", k1=value(cmap([(POLICY, cmap([(chunked(b"abc", 1), uint(1))]))])))
    b("output value with a policy of 27 bytes", k1=value(cmap([(bstr(h(0x88, 27)), cmap([(bstr(b"a"), uint(1))]))])))
    b("output value with a policy twice",
      k1=value(cmap([(POLICY, cmap([(bstr(b"a"), uint(1))])), (POLICY, cmap([(bstr(b"b"), uint(1))]))])))
    b("output value with an asset name twice",
      k1=value(cmap([(POLICY, cmap([(bstr(b"a"), uint(1)), (bstr(b"a"), uint(2))]))])))
    b("output value with an asset name in two encodings",
      k1=value(cmap([(POLICY, cmap([(bstr(b"ab"), uint(1)), (chunked(b"ab", 1), uint(2))]))])))
    many = lambda n: cmap([(POLICY, cmap([(bstr(i.to_bytes(2, "big")), uint(1)) for i in range(n)]))])  # noqa: E731
    b("output value with 1488 assets", k1=value(many(1488)))
    b("output value with 1489 assets", k1=value(many(1489)))
    b("output value a coin of a wide head", k1=array([array([ADDRESS, uint(5, 8)])]))

    # Fee, validity interval, hashes, network.
    b("fee of 2^64 - 1", k2=uint(2**64 - 1))
    b("fee a bignum", k2=c.bignum(2**64))
    b("fee negative", k2=nint(-1))
    b("time to live", k3=uint(1000))
    b("validity start", k8=uint(10))
    b("validity start of 2^64", k8=c.bignum(2**64))
    b("auxiliary data hash", k7=bstr(h(0x47, 32)))
    b("auxiliary data hash of 31 bytes", k7=bstr(h(0x47, 31)))
    b("script integrity hash", k11=bstr(h(0x48, 32)))
    b("script integrity hash chunked", k11=chunked(h(0x48, 32), 16))
    b("network id 0", k15=uint(0))
    b("network id 1", k15=uint(1))
    b("network id 2", k15=uint(2))
    b("current treasury value", k21=uint(1))
    b("donation of 1", k22=uint(1))
    b("donation of zero", k22=uint(0))
    b("collateral", k13=array([TXIN_2]))
    b("collateral empty", k13=array([]))
    b("collateral return", k16=OUTPUT)
    b("total collateral", k17=uint(5))
    b("reference inputs", k18=array([TXIN_2]))
    b("reference inputs empty", k18=array([]))
    b("reference inputs tagged", k18=tag(258, array([TXIN_2])))

    # Certificates.
    b("certificates empty", k4=array([]))
    b("certificates tagged 258", k4=tag(258, array([array([uint(7), cred(), uint(2)])])))
    for kind in [0, 1, 5, 6, 19]:
        b(f"certificate of tag {kind}", k4=array([array([uint(kind), cred()])]))
    b("certificate stake delegation", k4=array([array([uint(2), cred(), bstr(h(0x61, 28))])]))
    b("certificate pool retirement", k4=array([array([uint(4), bstr(h(0x61, 28)), uint(9)])]))
    b("certificate registration", k4=array([array([uint(7), cred(), uint(2)])]))
    b("certificate registration of a script", k4=array([array([uint(7), cred(1), uint(2)])]))
    b("certificate registration indefinite", k4=array([array([uint(7), cred(), uint(2)], indef=True)]))
    b("certificate registration detached", k4=array([detached([uint(7), cred(), uint(2)])]))
    b("certificate registration of four items", k4=array([array([uint(7), cred(), uint(2), uint(0)])]))
    b("certificate credential indefinite", k4=array([array([uint(7), array([uint(0), bstr(h(0x33, 28))], indef=True),
                                                           uint(2)])]))
    b("certificate credential of tag 2", k4=array([array([uint(7), cred(2), uint(2)])]))
    b("certificate unregistration", k4=array([array([uint(8), cred(), uint(2)])]))
    b("certificate vote delegation", k4=array([array([uint(9), cred(), array([uint(2)])])]))
    b("certificate vote delegation to a key", k4=array([array([uint(9), cred(), cred(0, 0x51)])]))
    b("certificate vote delegation to drep tag 4", k4=array([array([uint(9), cred(), array([uint(4)])])]))
    b("certificate vote delegation to abstain with a hash",
      k4=array([array([uint(9), cred(), array([uint(2), bstr(h(1, 28))])])]))
    b("certificate stake and vote delegation", k4=array([array([uint(10), cred(), bstr(h(0x61, 28)), array([uint(3)])])]))
    b("certificate registration and stake delegation",
      k4=array([array([uint(11), cred(), bstr(h(0x61, 28)), uint(2)])]))
    b("certificate registration and vote delegation", k4=array([array([uint(12), cred(), array([uint(2)]), uint(2)])]))
    b("certificate registration and both delegations",
      k4=array([array([uint(13), cred(), bstr(h(0x61, 28)), array([uint(2)]), uint(2)])]))
    b("certificate committee hot key", k4=array([array([uint(14), cred(), cred(1, 0x52)])]))
    b("certificate committee resignation", k4=array([array([uint(15), cred(), ANCHOR])]))
    b("certificate committee resignation without an anchor", k4=array([array([uint(15), cred(), NULL])]))
    b("certificate drep registration", k4=array([array([uint(16), cred(), uint(5), NULL])]))
    b("certificate drep unregistration", k4=array([array([uint(17), cred(), uint(5)])]))
    b("certificate drep update", k4=array([array([uint(18), cred(), ANCHOR])]))
    b("certificate anchor url of 128 bytes", k4=array([array([uint(18), cred(), array([text("u" * 128), bstr(h(1, 32))])])]))
    b("certificate anchor url of 129 bytes", k4=array([array([uint(18), cred(), array([text("u" * 129), bstr(h(1, 32))])])]))
    b("certificate anchor url chunked",
      k4=array([array([uint(18), cred(), array([chunked_text("https://a.b/c", 4), bstr(h(1, 32))])])]))
    b("certificate anchor hash of 31 bytes", k4=array([array([uint(18), cred(), array([text("u"), bstr(h(1, 31))])])]))
    b("certificate anchor indefinite", k4=array([array([uint(18), cred(), array([text("u"), bstr(h(1, 32))],
                                                                                   indef=True)])]))
    b("certificates with one twice", k4=array([array([uint(7), cred(), uint(2)])] * 2))
    b("certificates with one in two encodings",
      k4=array([array([uint(7), cred(), uint(2)]), array([uint(7), cred(), uint(2)], indef=True)]))
    b("certificates with a deposit in two widths",
      k4=array([array([uint(7), cred(), uint(2)]), array([uint(7), cred(), uint(2, 8)])]))
    b("certificates two", k4=array([array([uint(7), cred(), uint(2)]), array([uint(8), cred(), uint(2)])]))

    # Pool registrations: the voting key slot, the parameters, relays and metadata.
    b("pool registration without a voting key", k4=array([pool_cert()]))
    b("pool registration with a voting key", k4=array([pool_cert(bls=bls_key())]))
    b("pool registration with a null voting key", k4=array([pool_cert(bls=NULL)]))
    b("pool registration with a voting key indefinite", k4=array([pool_cert(bls=array([bstr(BLS_KEY), bstr(BLS_POP)],
                                                                                         indef=True))]))
    b("pool registration with a voting key chunked", k4=array([pool_cert(bls=array([chunked(BLS_KEY, 40),
                                                                                   chunked(BLS_POP, 20)]))]))
    b("pool registration with a voting key of 95 bytes", k4=array([pool_cert(bls=bls_key(key=BLS_KEY[:95]))]))
    b("pool registration with a voting key that is a G1 point", k4=array([pool_cert(bls=bls_key(key=BLS_POP))]))
    b("pool registration with the identity as voting key",
      k4=array([pool_cert(bls=bls_key(key=b"\xc0" + bytes(95)))]))
    b("pool registration with the identity as proof", k4=array([pool_cert(bls=bls_key(pop=b"\xc0" + bytes(47)))]))
    b("pool registration with a proof off the curve", k4=array([pool_cert(bls=bls_key(pop=SERDE[1]))]))
    b("pool registration with a proof outside the subgroup", k4=array([pool_cert(bls=bls_key(pop=SERDE[2]))]))
    b("pool registration with a voting key of serde vector 1", k4=array([pool_cert(bls=bls_key(key=SERDE[0]))]))
    b("pool registration with a voting key of serde vector 4", k4=array([pool_cert(bls=bls_key(key=SERDE[3]))]))
    b("pool registration with a proof not compressed", k4=array([pool_cert(bls=bls_key(pop=b"\x00" + BLS_POP[1:]))]))
    b("pool registration with a voting key of one item", k4=array([pool_cert(bls=array([bstr(BLS_KEY)]))]))
    b("pool registration with a voting key slot of bytes",
      k4=array([array([uint(3)] + pool_params()[:2] + [bstr(BLS_KEY)] + pool_params()[2:])]))
    b("pool registration indefinite", k4=array([array([uint(3)] + pool_params(), indef=True)]))
    b("pool registration indefinite with a voting key", k4=array([array([uint(3)] + pool_params(bls=bls_key()),
                                                                       indef=True)]))
    b("pool registration one item short", k4=array([array([uint(3)] + pool_params()[:-1])]))
    b("pool registration with a margin above one", k4=array([pool_cert(margin=ratio(3, 2))]))
    b("pool registration with a margin of one", k4=array([pool_cert(margin=ratio(2, 2))]))
    b("pool registration with a margin indefinite", k4=array([pool_cert(margin=ratio(1, 3, indef=True))]))
    b("pool registration with a margin without its tag", k4=array([pool_cert(margin=array([uint(1), uint(2)]))]))
    b("pool registration with a margin of a zero denominator", k4=array([pool_cert(margin=ratio(0, 0))]))
    b("pool registration with a margin of negative parts",
      k4=array([pool_cert(margin=tag(30, array([nint(-1), nint(-2)])))]))
    b("pool registration with a margin of bignums",
      k4=array([pool_cert(margin=tag(30, array([c.bignum(1), c.bignum(2)])))]))
    b("pool registration with a margin of three parts",
      k4=array([pool_cert(margin=tag(30, array([uint(1), uint(2), uint(3)])))]))
    b("pool registration with no owners", k4=array([pool_cert(owners=array([]))]))
    b("pool registration with owners tagged", k4=array([pool_cert(owners=tag(258, array([bstr(h(0x63, 28))])))]))
    b("pool registration with an owner twice", k4=array([pool_cert(owners=array([bstr(h(0x63, 28))] * 2))]))
    b("pool registration with relays",
      k4=array([pool_cert(relays=array([array([uint(0), uint(3001), bstr(b"\x7f\x00\x00\x01"), NULL]),
                                        array([uint(0), NULL, NULL, bstr(h(0x20, 16))]),
                                        array([uint(1), uint(3001), text("relay.invalid")]),
                                        array([uint(2), text("relays.invalid")])]))]))
    b("pool relay ipv4 of 3 bytes", k4=array([pool_cert(relays=array([array([uint(0), NULL, bstr(b"\x7f\x00\x00"),
                                                                             NULL])]))]))
    b("pool relay ipv4 of 5 bytes", k4=array([pool_cert(relays=array([array([uint(0), NULL, bstr(h(1, 5)), NULL])]))]))
    b("pool relay ipv6 of 15 bytes", k4=array([pool_cert(relays=array([array([uint(0), NULL, NULL, bstr(h(1, 15))])]))]))
    b("pool relay port 65536", k4=array([pool_cert(relays=array([array([uint(0), uint(65536), NULL, NULL])]))]))
    b("pool relay dns name of 128 bytes", k4=array([pool_cert(relays=array([array([uint(2), text("d" * 128)])]))]))
    b("pool relay dns name of 129 bytes", k4=array([pool_cert(relays=array([array([uint(2), text("d" * 129)])]))]))
    b("pool relay dns name chunked", k4=array([pool_cert(relays=array([array([uint(2), chunked_text("a.b", 1)])]))]))
    b("pool relay of tag 3", k4=array([pool_cert(relays=array([array([uint(3), text("d")])]))]))
    b("pool relays tagged 258", k4=array([pool_cert(relays=tag(258, array([array([uint(2), text("d")])])))]))
    b("pool metadata", k4=array([pool_cert(metadata=array([text("https://m.invalid"), bstr(h(1, 32))]))]))
    b("pool metadata with a hash of 70 bytes", k4=array([pool_cert(metadata=array([text("u"), bstr(h(1, 70))]))]))
    b("pool registrations with relays in two encodings",
      k4=array([pool_cert(relays=array([array([uint(2), text("d")])])),
                pool_cert(relays=array([array([uint(2), text("d")], indef=True)]))]))
    b("pool registrations with owners in two orders",
      k4=array([pool_cert(owners=array([bstr(h(0x63, 28)), bstr(h(0x64, 28))])),
                pool_cert(owners=array([bstr(h(0x64, 28)), bstr(h(0x63, 28))]))]))
    b("pool registrations with margins in two forms",
      k4=array([pool_cert(margin=ratio(1, 2)), pool_cert(margin=ratio(2, 4))]))

    # Withdrawals, mint, guards, deposits, balance intervals.
    b("withdrawals", k5=cmap([(ACCOUNT, uint(0))]))
    b("withdrawals empty", k5=cmap([]))
    b("withdrawals with one account twice", k5=cmap([(ACCOUNT, uint(1)), (ACCOUNT, uint(2))]))
    b("withdrawals from a script account", k5=cmap([(ACCOUNT_2, uint(1))]))
    b("withdrawals from an account with header bit 1", k5=cmap([(bstr(b"\xe3" + h(0x77, 28)), uint(1))]))
    b("withdrawals from an account chunked", k5=cmap([(chunked(b"\xe1" + h(0x77, 28), 9), uint(1))]))
    b("withdrawals indefinite", k5=cmap([(ACCOUNT, uint(1))], indef=True))

    def mint(q):
        return cmap([(POLICY, cmap([(bstr(b"a"), q)]))])

    b("mint", k9=mint(uint(5)))
    b("mint negative", k9=mint(nint(-5)))
    b("mint of minus 2^63", k9=mint(nint(-(2**63))))
    b("mint of minus 2^63 - 1", k9=mint(nint(-(2**63) - 1)))
    b("mint of 2^63 - 1", k9=mint(uint(2**63 - 1)))
    b("mint of 2^63", k9=mint(uint(2**63)))
    b("mint of a bignum", k9=mint(c.bignum(5)))
    b("mint of zero", k9=mint(uint(0)))
    b("mint empty", k9=cmap([]))
    b("mint of a policy of no assets", k9=cmap([(POLICY, cmap([]))]))
    b("guards empty", k14=array([]))
    b("guards of key hashes", k14=array([bstr(h(0x33, 28)), bstr(h(0x34, 28))]))
    b("guards of credentials", k14=array([cred(0), cred(1, 0x34)]))
    b("guards tagged", k14=tag(258, array([cred(1)])))
    b("guards of a key hash then a credential", k14=array([bstr(h(0x33, 28)), cred(1)]))
    b("guards of a credential then a key hash", k14=array([cred(1), bstr(h(0x33, 28))]))
    b("guards with one twice", k14=array([cred(0), cred(0)]))
    b("guards with a key hash and its credential", k14=array([cred(0), array([uint(0), bstr(h(0x33, 28))],
                                                                              indef=True)]))
    b("guards of a key hash of 27 bytes", k14=array([bstr(h(0x33, 27))]))
    b("required guards", k24=cmap([(cred(1), NULL), (cred(0), DATA)]))
    b("required guards empty", k24=cmap([]))
    b("required guards with one twice", k24=cmap([(cred(1), NULL), (cred(1), DATA)]))
    b("required guards with data that is not data", k24=cmap([(cred(1), text("x"))]))
    b("required guards keyed by a key hash", k24=cmap([(bstr(h(0x33, 28)), NULL)]))
    b("direct deposits", k25=cmap([(ACCOUNT, uint(5))]))
    b("direct deposits empty", k25=cmap([]))
    b("direct deposits with one account twice", k25=cmap([(ACCOUNT, uint(5)), (ACCOUNT, uint(6))]))
    for label, iv in [("exact", uint(5)), ("lower", array([uint(5), NULL])), ("upper", array([NULL, uint(5)])),
                      ("both", array([uint(1), uint(9)])), ("neither", array([NULL, NULL])),
                      ("indefinite", array([uint(1), NULL], indef=True)), ("negative", nint(-1)),
                      ("of three items", array([uint(1), uint(2), uint(3)])), ("a bignum", c.bignum(5))]:
        b(f"balance interval {label}", k26=cmap([(ACCOUNT, iv)]))
    b("balance intervals empty", k26=cmap([]))
    b("starting balance intervals", k27=cmap([(ACCOUNT, uint(5))]))
    b("starting balance intervals empty", k27=cmap([]))

    # Votes.
    voter, action, vote = array([uint(2), bstr(h(0x33, 28))]), array([bstr(h(0x66, 32)), uint(0)]), array([uint(1), NULL])
    b("votes", k19=cmap([(voter, cmap([(action, vote)]))]))
    b("votes empty", k19=cmap([]))
    b("votes of a voter with none", k19=cmap([(voter, cmap([]))]))
    for kind in range(7):
        b(f"votes of voter tag {kind}", k19=cmap([(array([uint(kind), bstr(h(0x33, 28))]), cmap([(action, vote)]))]))
    for v in [0, 2, 3]:
        b(f"votes with the vote {v}", k19=cmap([(voter, cmap([(action, array([uint(v), NULL]))]))]))
    b("votes with the vote -1", k19=cmap([(voter, cmap([(action, array([nint(-1), NULL]))]))]))
    b("votes with an anchor", k19=cmap([(voter, cmap([(action, array([uint(1), ANCHOR]))]))]))
    b("votes with a procedure indefinite", k19=cmap([(voter, cmap([(action, array([uint(1), NULL], indef=True))]))]))
    b("votes with a voter twice", k19=cmap([(voter, cmap([(action, vote)])), (voter, cmap([(action, vote)]))]))
    b("votes with an action twice", k19=cmap([(voter, cmap([(action, vote), (action, vote)]))]))
    b("votes with an action index of 65536",
      k19=cmap([(voter, cmap([(array([bstr(h(0x66, 32)), uint(65536)]), vote)]))]))

    # Proposals and their parameter updates.
    info = array([uint(6)])
    b("proposal of information", k20=array([proposal(info)]))
    b("proposals empty", k20=array([]))
    b("proposals with one twice", k20=array([proposal(info), proposal(info)]))
    b("proposals with deposits in two widths", k20=array([proposal(info), proposal(info, deposit=1_000_000)
                                                          .replace(uint(1_000_000), uint(1_000_000, 8), 1)]))
    b("proposals two", k20=array([proposal(info), proposal(info, deposit=2)]))
    b("proposal indefinite", k20=array([array([uint(1), ACCOUNT, info, ANCHOR], indef=True)]))
    b("proposal of information with a field", k20=array([proposal(array([uint(6), NULL]))]))
    b("proposal of tag 7", k20=array([proposal(array([uint(7)]))]))
    prev = array([bstr(h(0x66, 32)), uint(0)])
    b("proposal of no confidence", k20=array([proposal(array([uint(3), prev]))]))
    for major in [12, 13, 14]:
        b(f"proposal of a hard fork to {major}",
          k20=array([proposal(array([uint(1), NULL, array([uint(major), uint(0)])]))]))
    b("proposal of a hard fork with a minor of 2^32",
      k20=array([proposal(array([uint(1), NULL, array([uint(12), uint(2**32)])]))]))
    b("proposal of treasury withdrawals", k20=array([proposal(array([uint(2), cmap([(ACCOUNT, uint(5))]), NULL]))]))
    b("proposal of treasury withdrawals with an account twice",
      k20=array([proposal(array([uint(2), cmap([(ACCOUNT, uint(5)), (ACCOUNT, uint(6))]), NULL]))]))
    b("proposals of treasury withdrawals in two orders",
      k20=array([proposal(array([uint(2), cmap([(ACCOUNT, uint(5)), (ACCOUNT_2, uint(6))]), NULL])),
                 proposal(array([uint(2), cmap([(ACCOUNT_2, uint(6)), (ACCOUNT, uint(5))]), NULL]))]))
    committee = lambda removed, added, q: proposal(array([uint(4), NULL, removed, added, q]))  # noqa: E731
    b("proposal of a committee update", k20=array([committee(array([cred(0)]), cmap([(cred(1), uint(9))]), ratio(2, 3))]))
    b("proposal of a committee update with a member removed twice",
      k20=array([committee(array([cred(0), cred(0)]), cmap([]), ratio(2, 3))]))
    b("proposal of a committee update with removals tagged",
      k20=array([committee(tag(258, array([cred(0)])), cmap([]), ratio(2, 3))]))
    b("proposal of a committee update with a member added twice",
      k20=array([committee(array([]), cmap([(cred(1), uint(9)), (cred(1), uint(8))]), ratio(2, 3))]))
    b("proposal of a committee update with a quorum above one",
      k20=array([committee(array([]), cmap([]), ratio(4, 3))]))
    b("proposal of a constitution",
      k20=array([proposal(array([uint(5), NULL, array([ANCHOR, bstr(h(0x5c, 28))])]))]))
    b("proposal of a constitution without a policy", k20=array([proposal(array([uint(5), NULL, array([ANCHOR, NULL])]))]))
    b("proposal of a constitution of one item", k20=array([proposal(array([uint(5), NULL, array([ANCHOR])]))]))
    b("proposal with a return account of header bit 1",
      k20=array([array([uint(1), bstr(b"\xe3" + h(0x77, 28)), info, ANCHOR])]))

    def pc(name, upd):
        b(f"parameter change {name}", k20=array([param_change(upd)]))

    pc("empty", update())
    pc("indefinite", update((0, uint(44)), indef=True))
    pc("with a policy", update((0, uint(44))))
    b("parameter change with a previous action and a policy",
      k20=array([param_change(update((0, uint(44))), previous=prev, policy=bstr(h(0x5c, 28)))]))
    every = [(0, uint(44)), (1, uint(155381)), (2, uint(90112)), (3, uint(16384)), (4, uint(1100)),
             (5, uint(2_000_000)), (6, uint(500_000_000)), (7, uint(18)), (8, uint(500)), (9, ratio(3, 10)),
             (10, ratio(3, 1000)), (11, ratio(1, 5)), (16, uint(170_000_000)), (17, uint(4310)),
             (18, cmap([(uint(0), array([uint(1), uint(2)])), (uint(3), array([nint(-5)])),
                        (uint(9), array([]))])),
             (19, array([ratio(577, 10000), ratio(721, 10_000_000)])), (20, array([uint(14_000_000), uint(10**10)])),
             (21, array([uint(62_000_000), uint(4 * 10**10)])), (22, uint(5000)), (23, uint(150)), (24, uint(3)),
             (25, array([ratio(1, 2)] * 5)), (26, array([ratio(2, 3)] * 10)), (27, uint(7)), (28, uint(146)),
             (29, uint(6)), (30, uint(10**11)), (31, uint(5 * 10**8)), (32, uint(20)), (33, ratio(15, 1)),
             (34, uint(1_048_576)), (35, uint(204_800)), (36, uint(25_600)), (37, ratio(6, 5)),
             (38, ratio(10, 1)), (39, ratio(1, 100)), (40, uint(1000)), (41, uint(4000)), (42, uint(7000)),
             (43, uint(600)), (44, ratio(3, 4)), (45, uint(12_000)), (46, uint(12_500_000)),
             (47, array([uint(10**9), uint(10**12)])), (48, uint(1_048_576)), (49, uint(30)), (50, ratio(3, 2)),
             (51, uint(5)), (52, uint(500)), (53, uint(7)), (54, ratio(1, 10)), (55, uint(100)), (56, uint(15))]
    pc("of every key", update(*every))
    for k in [12, 13, 14, 15, 57, 2**32]:
        pc(f"with the key {k}", update((k, uint(0))))
    pc("with a key twice", update((0, uint(44)), (0, uint(45))))
    pc("with a word16 of 65536", update((4, uint(65536))))
    pc("with a word32 of 2^32", update((2, uint(2**32))))
    pc("with a word64 of 2^64", update((0, c.bignum(2**64))))
    pc("with a negative coin", update((1, nint(-1))))
    pc("with a unit interval above one", update((10, ratio(3, 2))))
    pc("with a unit interval indefinite", update((10, ratio(1, 2, indef=True))))
    pc("with a unit interval of word64 parts", update((10, ratio(2**64 - 2, 2**64 - 1))))
    pc("with a unit interval without its tag", update((10, array([uint(1), uint(2)]))))
    pc("with a unit interval of negative parts", update((10, tag(30, array([nint(-1), nint(-2)])))))
    pc("with a rational under tag 30 written wide", update((10, c.head(6, 30, 2) + array([uint(1), uint(2)]))))
    pc("with a positive interval of zero", update((37, ratio(0, 1))))
    pc("with a positive interval of 10^-19", update((37, ratio(1, 10**19))))
    pc("with a positive interval below 10^-19", update((37, ratio(1, 10**19 + 1))))
    pc("with a non-negative interval of 2^64 - 1", update((9, ratio(2**64 - 1, 1))))
    pc("with a pledge leverage of null", update((38, NULL)))
    pc("with a pledge leverage above one", update((38, ratio(7, 2))))
    pc("with a bootstrap round of null", update((53, NULL)))
    pc("with a bootstrap round of 2^32", update((53, uint(2**32))))
    pc("with a cost stride of zero", update((36, uint(0))))
    pc("with execution units at the int64 maximum", update((20, array([uint(2**63 - 1), uint(2**63 - 1)]))))
    pc("with execution units above the int64 maximum", update((20, array([uint(2**63), uint(1)]))))
    pc("with execution units indefinite", update((47, array([uint(1), uint(2)], indef=True))))
    pc("with prices of one item", update((19, array([ratio(1, 2)]))))
    pc("with pool thresholds of four", update((25, array([ratio(1, 2)] * 4))))
    pc("with pool thresholds above one", update((25, array([ratio(3, 2)] + [ratio(1, 2)] * 4))))
    pc("with drep thresholds indefinite", update((26, array([ratio(2, 3)] * 10, indef=True))))
    pc("with cost models of a language 256", update((18, cmap([(uint(256), array([]))]))))
    pc("with cost models of a language twice", update((18, cmap([(uint(1), array([])), (uint(1), array([]))]))))
    pc("with cost models of an int64 minimum", update((18, cmap([(uint(2), array([nint(-(2**63))]))]))))
    pc("with cost models below the int64 minimum", update((18, cmap([(uint(2), array([nint(-(2**63) - 1)]))]))))
    pc("with cost models of 2^63", update((18, cmap([(uint(3), array([uint(2**63)]))]))))
    pc("with cost models of a bignum", update((18, cmap([(uint(0), array([c.bignum(1)]))]))))
    pc("with cost models of 400 parameters", update((18, cmap([(uint(3), array([uint(1)] * 400))]))))
    pc("with cost models tagged", update((18, cmap([(uint(0), tag(258, array([uint(1)])))]))))
    pc("with cost models indefinite", update((18, cmap([(uint(1), array([uint(1)], indef=True))], indef=True))))
    pc("with protocol parameters in two encodings and twice",
       update((0, uint(44)), (1, uint(1))))
    b("proposals of parameter changes in two orders",
      k20=array([param_change(update((0, uint(44)), (1, uint(1)))), param_change(update((1, uint(1)), (0, uint(44))))]))
    b("proposals of parameter changes in two widths",
      k20=array([param_change(update((0, uint(44)))), param_change(update((0, uint(44, 8))))]))
    b("proposals of cost models in two forms",
      k20=array([param_change(update((18, cmap([(uint(1), array([uint(1)])), (uint(2), array([uint(2)]))])))),
                 param_change(update((18, cmap([(uint(2), array([uint(2)])), (uint(1), array([uint(1)], indef=True))]))))]))

    # Sub-transactions.
    b("sub-transaction", k23=array([subtx()]))
    b("sub-transactions tagged", k23=tag(258, array([subtx()])))
    b("sub-transactions indefinite", k23=array([subtx()], indef=True))
    b("sub-transactions empty", k23=array([]))
    b("sub-transactions two", k23=array([subtx(), subtx(body({3: uint(9)}, sub=True))]))
    b("sub-transactions with one twice", k23=array([subtx(), subtx()]))
    b("sub-transactions with one body in two encodings",
      k23=array([subtx(), subtx(body(sub=True, indef=True))]))
    b("sub-transactions with one body and two witness sets",
      k23=array([subtx(), subtx(wits=wits((0, array([VKEY_WIT]))))]))
    b("sub-transaction indefinite", k23=array([array([body(sub=True), cmap([]), NULL], indef=True)]))
    b("sub-transaction detached", k23=array([detached([body(sub=True), cmap([]), NULL])]))
    b("sub-transaction of four items", k23=array([subtx(items=[body(sub=True), cmap([]), NULL, TRUE])]))
    b("sub-transaction of two items", k23=array([subtx(items=[body(sub=True), cmap([])])]))
    b("sub-transaction with a fee", k23=array([subtx(body({2: uint(1)}, sub=True))]))
    b("sub-transaction without outputs", k23=array([subtx(body(drop=(1,), sub=True))]))
    b("sub-transaction without inputs", k23=array([subtx(body(drop=(0,), sub=True))]))
    for k, v in [(13, array([TXIN_2])), (16, OUTPUT), (17, uint(5)), (23, array([subtx()])),
                 (27, cmap([(ACCOUNT, uint(5))]))]:
        b(f"sub-transaction with the key {k}", k23=array([subtx(body({k: v}, sub=True))]))
    for k, v in [(3, uint(9)), (4, array([array([uint(7), cred(), uint(2)])])), (5, cmap([(ACCOUNT, uint(0))])),
                 (9, mint(uint(1))), (14, array([cred(1)])), (18, array([TXIN_2])), (22, uint(1)),
                 (24, cmap([(cred(1), NULL)])), (25, cmap([(ACCOUNT, uint(1))])), (26, cmap([(ACCOUNT, uint(1))]))]:
        b(f"sub-transaction with the key {k}", k23=array([subtx(body({k: v}, sub=True))]))
    b("sub-transaction with witnesses and auxiliary data",
      k23=array([subtx(wits=wits((0, array([VKEY_WIT])), (1, array([NATIVE]))), aux=cmap([(uint(1), uint(2))]))]))
    b("sub-transaction with witnesses that are not a map", k23=array([subtx(wits=array([]))]))
    b("sub-transaction with auxiliary data that is not", k23=array([subtx(aux=uint(1))]))

    # Witness sets.
    def w(name, *pairs):
        t(f"witnesses {name}", tx(wits=wits(*pairs)))

    w("empty")
    t("witnesses indefinite", tx(wits=cmap([(uint(0), array([VKEY_WIT]))], indef=True)))
    t("witnesses a list", tx(wits=array([])))
    w("with the key 9", (9, array([])))
    w("with a key twice", (0, array([VKEY_WIT])), (0, array([VKEY_WIT])))
    w("vkey", (0, array([VKEY_WIT])))
    w("vkey tagged", (0, tag(258, array([VKEY_WIT]))))
    w("vkey indefinite", (0, array([VKEY_WIT], indef=True)))
    w("vkey empty", (0, array([])))
    w("vkey twice", (0, array([VKEY_WIT, VKEY_WIT])))
    w("vkey with two signatures", (0, array([VKEY_WIT, array([bstr(h(0x91, 32)), bstr(h(0xA2, 64))])])))
    w("vkey in two encodings", (0, array([VKEY_WIT, array([bstr(h(0x91, 32)), bstr(h(0xA1, 64))], indef=True)])))
    w("vkey of 31 bytes", (0, array([array([bstr(h(0x91, 31)), bstr(h(0xA1, 64))])])))
    w("vkey signature of 65 bytes", (0, array([array([bstr(h(0x91, 32)), bstr(h(0xA1, 65))])])))
    w("vkey chunked", (0, array([array([chunked(h(0x91, 32), 3), bstr(h(0xA1, 64))])])))
    w("vkey of three items", (0, array([array([bstr(h(0x91, 32)), bstr(h(0xA1, 64)), NULL])])))
    w("bootstrap", (2, array([BOOTSTRAP])))
    w("bootstrap with a chain code of 31 bytes",
      (2, array([array([bstr(h(0x91, 32)), bstr(h(0xA1, 64)), bstr(h(0x92, 31)), bstr(b"")])])))
    w("bootstrap twice with two signatures",
      (2, array([BOOTSTRAP, array([bstr(h(0x91, 32)), bstr(h(0xA2, 64)), bstr(h(0x92, 32)), bstr(cmap([]))])])))
    w("bootstrap twice with two attributes",
      (2, array([BOOTSTRAP, array([bstr(h(0x91, 32)), bstr(h(0xA1, 64)), bstr(h(0x92, 32)), bstr(b"\x01")])])))
    w("bootstrap empty", (2, array([])))
    w("bootstrap of three items", (2, array([array([bstr(h(0x91, 32)), bstr(h(0xA1, 64)), bstr(h(0x92, 32))])])))
    w("native scripts", (1, array([NATIVE, array([uint(1), array([NATIVE, array([uint(4), uint(3)])])])])))
    w("native scripts empty", (1, array([])))
    w("native scripts with one twice", (1, array([NATIVE, NATIVE])))
    w("native scripts with one in two encodings", (1, array([NATIVE, array([uint(0), bstr(h(0x33, 28))], indef=True)])))
    w("native scripts tagged", (1, tag(258, array([NATIVE]))))
    for kind, script in [(1, array([uint(1), array([])])), (2, array([uint(2), array([NATIVE], indef=True)])),
                         (3, array([uint(3), nint(-1), array([NATIVE])])), (3, array([uint(3), uint(2**63), array([])])),
                         (5, array([uint(5), uint(2**64 - 1)])), (6, array([uint(6), cred(1)])),
                         (6, array([uint(6), bstr(h(0x33, 28))])), (7, array([uint(7), uint(0)])),
                         (1, array([uint(1), tag(258, array([NATIVE]))])), (4, array([uint(4)]))]:
        w(f"native script {script.hex()}", (1, array([script])))
    deep = NATIVE
    for _ in range(1000):
        deep = array([uint(1), array([deep])])
    w("native script nested 1000 deep", (1, array([deep])))
    for key, lang in [(3, 1), (6, 2), (7, 3), (8, 4)]:
        w(f"plutus V{lang} script", (key, array([SCRIPT])))
        w(f"plutus V{lang} scripts empty", (key, array([])))
        w(f"plutus V{lang} script twice", (key, array([SCRIPT, SCRIPT])))
    w("plutus script chunked and not", (7, array([SCRIPT, chunked(c.h(0x4d, 0) + SCRIPT[1:], 3)])))
    w("plutus script tagged", (8, tag(258, array([SCRIPT]))))
    w("plutus script not bytes", (3, array([uint(1)])))
    w("plutus script under two languages", (3, array([SCRIPT])), (6, array([SCRIPT])))
    w("datums", (4, array([DATA, DATA_2])))
    w("datums empty", (4, array([])))
    w("datums with one twice", (4, array([DATA, DATA])))
    w("datums with one in two encodings", (4, array([DATA, uint(42, 8)])))
    w("datums tagged", (4, tag(258, array([DATA]))))
    w("datums with one that is not data", (4, array([text("x")])))
    w("datums with a bytes of 65", (4, array([bstr(h(1, 65))])))
    w("datums with a chunked bytes of 64 chunks", (4, array([b"\x5f" + bstr(h(1, 64)) + bstr(h(1, 64)) + b"\xff"])))
    rd = array([DATA, array([uint(1), uint(2)])])
    w("redeemers", (5, cmap([(array([uint(0), uint(0)]), rd)])))
    w("redeemers indefinite", (5, cmap([(array([uint(0), uint(0)]), rd)], indef=True)))
    w("redeemers with a wide length head", (5, cmap([(array([uint(0), uint(0)]), rd)], width=8)))
    w("redeemers a list", (5, array([array([uint(0), uint(0), DATA, array([uint(1), uint(2)])])])))
    w("redeemers empty", (5, cmap([])))
    w("redeemers with a purpose twice", (5, cmap([(array([uint(0), uint(0)]), rd), (array([uint(0), uint(0)]), rd)])))
    replacement = array([uint(42), array([uint(3), uint(4)])])
    w("redeemers with a purpose twice keep the last value", (5, cmap([
        (array([uint(0), uint(0)]), rd),
        (array([uint(0), uint(0)]), replacement)])))
    w("redeemers in reverse purpose order", (5, cmap([
        (array([uint(1), uint(0)]), rd),
        (array([uint(0), uint(0)]), replacement)])))
    for purpose in range(8):
        w(f"redeemers of purpose {purpose}", (5, cmap([(array([uint(purpose), uint(0)]), rd)])))
    w("redeemers with an index of 2^32", (5, cmap([(array([uint(0), uint(2**32)]), rd)])))
    w("redeemers with a key indefinite", (5, cmap([(array([uint(0), uint(0)], indef=True), rd)])))
    w("redeemers with a value indefinite", (5, cmap([(array([uint(0), uint(0)]), array([DATA, array([uint(1), uint(2)])],
                                                                                         indef=True))])))
    w("redeemers with units above the int64 maximum",
      (5, cmap([(array([uint(0), uint(0)]), array([DATA, array([uint(2**63), uint(2)])]))])))
    w("redeemers with units indefinite",
      (5, cmap([(array([uint(0), uint(0)]), array([DATA, array([uint(1), uint(2)], indef=True)]))])))
    w("redeemers with data that is not data", (5, cmap([(array([uint(0), uint(0)]), array([text("x"),
                                                                                             array([uint(1), uint(2)])]))])))

    # Auxiliary data.
    def a(name, raw):
        t(f"auxiliary data {name}", tx(aux=raw))

    a("metadata", cmap([(uint(1), text("hello")), (uint(2), array([uint(1), nint(-1), bstr(b"x"),
                                                                    cmap([(uint(1), uint(2)), (uint(1), uint(3))])]))]))
    a("metadata empty", cmap([]))
    a("metadata with a label twice", cmap([(uint(1), uint(1)), (uint(1), uint(2))]))
    a("metadata with a label of 2^64 - 1", cmap([(uint(2**64 - 1), uint(1))]))
    a("metadata with a negative label", cmap([(nint(-1), uint(1))]))
    a("metadata with a text of 64 bytes", cmap([(uint(1), text("t" * 64))]))
    a("metadata with a text of 65 bytes", cmap([(uint(1), text("t" * 65))]))
    a("metadata with bytes chunked to 64", cmap([(uint(1), chunked(h(1, 64), 30))]))
    a("metadata with bytes chunked to 65", cmap([(uint(1), chunked(h(1, 65), 30))]))
    a("metadata with a text chunked", cmap([(uint(1), chunked_text("abcdef", 2))]))
    a("metadata with a bignum", cmap([(uint(1), c.bignum(5))]))
    a("metadata with an integer of -2^64", cmap([(uint(1), nint(-(2**64)))]))
    a("metadata with a tag", cmap([(uint(1), tag(5, uint(1)))]))
    a("metadata with null", cmap([(uint(1), NULL)]))
    a("list of metadata and scripts", array([cmap([(uint(1), uint(1))]), array([NATIVE])]))
    a("list indefinite", array([cmap([]), array([])], indef=True))
    a("list of three items", array([cmap([]), array([]), array([])]))
    a("list with scripts tagged", array([cmap([]), tag(258, array([NATIVE]))]))
    a("tag 259", auxiliary_alonzo((0, cmap([(uint(1), uint(1))])), (1, array([NATIVE])), (2, array([SCRIPT])),
                                  (3, array([SCRIPT])), (4, array([SCRIPT])), (5, array([SCRIPT]))))
    a("tag 259 empty", auxiliary_alonzo())
    a("tag 259 with empty script lists", auxiliary_alonzo((2, array([])), (5, array([]))))
    a("tag 259 with the key 6", auxiliary_alonzo((6, array([]))))
    a("tag 259 with a key twice", auxiliary_alonzo((0, cmap([])), (0, cmap([]))))
    a("tag 259 indefinite", tag(259, cmap([(uint(0), cmap([]))], indef=True)))
    a("tag 259 with plutus scripts tagged", auxiliary_alonzo((4, tag(258, array([SCRIPT])))))
    a("tag 260", auxiliary_alonzo((0, cmap([])), number=260))
    a("tag 259 over a list", tag(259, array([])))
    a("an integer", uint(0))

    # Whole transactions with everything at once, and the issue's example inside a block.
    t("transaction with every top-level field",
      tx(body({3: uint(9), 4: array([array([uint(7), cred(), uint(2)])]), 5: cmap([(ACCOUNT, uint(0))]),
               7: bstr(h(0x47, 32)), 8: uint(1), 9: mint(uint(1)), 11: bstr(h(0x48, 32)), 13: array([TXIN_2]),
               14: array([cred(1)]), 15: uint(0), 16: OUTPUT, 17: uint(5), 18: array([TXIN_2]),
               19: cmap([(voter, cmap([(action, vote)]))]), 20: array([proposal(info)]), 21: uint(1), 22: uint(1),
               23: array([subtx(wits=wits((4, array([DATA]))))]), 24: cmap([(cred(1), DATA)]),
               25: cmap([(ACCOUNT, uint(5))]), 26: cmap([(ACCOUNT, uint(5))]), 27: cmap([(ACCOUNT, uint(5))])}),
         wits((0, array([VKEY_WIT])), (1, array([NATIVE])), (3, array([SCRIPT])), (4, array([DATA, DATA_2])),
              (5, cmap([(array([uint(6), uint(0)]), rd)])), (8, array([SCRIPT]))),
         cmap([(uint(1), uint(1))])))
