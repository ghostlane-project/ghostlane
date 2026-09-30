package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/pem"
)

// releasePubKeyPEM verifies SHA256SUMS.sig of every release; install.sh embeds
// the same key. Rotation ships a new key here and in install.sh first.
const releasePubKeyPEM = `-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEAz252wYKYdbLzn/fyN1ZvGt7oTDNZTNJrWosR8/L+S/0=
-----END PUBLIC KEY-----
`

func releasePubKeyFingerprint() string {
	block, _ := pem.Decode([]byte(releasePubKeyPEM))
	if block == nil {
		return "invalid"
	}
	sum := sha256.Sum256(block.Bytes)
	return hex.EncodeToString(sum[:])
}
