// Package crypt1test builds crypt1 blobs the way the coordinator does, for tests.
package crypt1test

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
)

// Keys derives the encryption and MAC keys from a master key as crypt_link.rs does.
func Keys(master [32]byte) (enc, mac [32]byte) {
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

// Encrypt returns base64url(IV | AES-256-CBC/PKCS7(plaintext) | HMAC-SHA256(IV|ct)).
func Encrypt(master [32]byte, plaintext []byte) string {
	encKey, macKey := Keys(master)
	block, err := aes.NewCipher(encKey[:])
	if err != nil {
		panic(err)
	}
	iv := make([]byte, 16)
	if _, err := rand.Read(iv); err != nil {
		panic(err)
	}
	pad := 16 - len(plaintext)%16
	padded := make([]byte, 0, len(plaintext)+pad)
	padded = append(padded, plaintext...)
	for i := 0; i < pad; i++ {
		padded = append(padded, byte(pad))
	}
	ct := make([]byte, len(padded))
	cipher.NewCBCEncrypter(block, iv).CryptBlocks(ct, padded)
	h := hmac.New(sha256.New, macKey[:])
	h.Write(iv)
	h.Write(ct)
	out := append(append(iv, ct...), h.Sum(nil)...)
	return base64.RawURLEncoding.EncodeToString(out)
}

// Master is a fixed test key.
func Master() [32]byte {
	var k [32]byte
	for i := range k {
		k[i] = byte(i * 7)
	}
	return k
}
