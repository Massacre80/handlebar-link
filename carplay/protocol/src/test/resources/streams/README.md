# Media stream vectors

`generate_vectors.py` computes the vectors that `StreamKeysTest`, `ScreenStreamReaderTest`,
`AudioPacketsTest` and `TimingClockTest` compare against. It is written from DiPlay's source (file:line in
its comments), not from our Kotlin, and uses Python's `cryptography` package (OpenSSL) for HKDF-SHA512
and ChaCha20-Poly1305. Run 2026-11-19 with Python 3.15.2 and cryptography 50.0.2:

    python3 -I generate_vectors.py

The DataStream-Salt12345 keys match those in docs/research/carplay-spec/C-airplay-pairing.md §9.2, which
were computed separately with Python's standard library.
