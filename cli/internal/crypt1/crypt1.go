// Package crypt1 decrypts the app's encrypted subscription links and bodies.
//
// The format is the coordinator's crypt_link.rs: base64url (no padding) of
// IV(16) | AES-256-CBC/PKCS7 ciphertext | HMAC-SHA256(IV | ciphertext)(32),
// with the encryption key sha256("olcrtc-crypt-v1-enc" | master) and the MAC
// key sha256("olcrtc-crypt-v1-mac" | master). The CLI only decrypts.
package crypt1

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"strings"
)

// LinkPrefix starts an encrypted link; the rest is the blob.
const LinkPrefix = "olcrtc://crypt1/"

const (
	ivLen  = 16
	tagLen = 32
)

// IsLink reports whether s is an encrypted link.
func IsLink(s string) bool { return strings.HasPrefix(s, LinkPrefix) }

// DecryptLink decrypts an encrypted link's blob.
func DecryptLink(master [32]byte, link string) ([]byte, bool) {
	if !IsLink(link) {
		return nil, false
	}
	return Decrypt(master, strings.TrimPrefix(link, LinkPrefix))
}

// Decrypt verifies and decrypts one blob. It returns false for anything that
// does not verify: a short or unparsable blob, a bad MAC, a wrong key, bad
// padding.
func Decrypt(master [32]byte, blob string) ([]byte, bool) {
	raw, ok := decodeBase64(strings.TrimSpace(blob))
	if !ok || len(raw) < ivLen+aes.BlockSize+tagLen {
		return nil, false
	}
	iv := raw[:ivLen]
	ct := raw[ivLen : len(raw)-tagLen]
	tag := raw[len(raw)-tagLen:]
	if len(ct)%aes.BlockSize != 0 {
		return nil, false
	}
	encKey, macKey := keys(master)
	mac := hmac.New(sha256.New, macKey[:])
	mac.Write(iv)
	mac.Write(ct)
	if !hmac.Equal(mac.Sum(nil), tag) {
		return nil, false
	}
	block, err := aes.NewCipher(encKey[:])
	if err != nil {
		return nil, false
	}
	plain := make([]byte, len(ct))
	cipher.NewCBCDecrypter(block, iv).CryptBlocks(plain, ct)
	return unpad(plain)
}

// ParseKey reads the master key: 32 bytes in base64, standard or url-safe,
// padded or not.
func ParseKey(s string) ([32]byte, bool) {
	var k [32]byte
	raw, ok := decodeBase64(strings.TrimSpace(s))
	if !ok || len(raw) != len(k) {
		return k, false
	}
	copy(k[:], raw)
	return k, true
}

func keys(master [32]byte) (enc, mac [32]byte) {
	e := sha256.New()
	e.Write([]byte("olcrtc-crypt-v1-enc"))
	e.Write(master[:])
	copy(enc[:], e.Sum(nil))
	m := sha256.New()
	m.Write([]byte("olcrtc-crypt-v1-mac"))
	m.Write(master[:])
	copy(mac[:], m.Sum(nil))
	return enc, mac
}

func decodeBase64(s string) ([]byte, bool) {
	if s == "" {
		return nil, false
	}
	for _, enc := range []*base64.Encoding{base64.RawURLEncoding, base64.URLEncoding, base64.RawStdEncoding, base64.StdEncoding} {
		if b, err := enc.DecodeString(s); err == nil {
			return b, true
		}
	}
	return nil, false
}

func unpad(b []byte) ([]byte, bool) {
	if len(b) == 0 {
		return nil, false
	}
	n := int(b[len(b)-1])
	if n == 0 || n > aes.BlockSize || n > len(b) {
		return nil, false
	}
	for _, c := range b[len(b)-n:] {
		if int(c) != n {
			return nil, false
		}
	}
	return b[:len(b)-n], true
}
