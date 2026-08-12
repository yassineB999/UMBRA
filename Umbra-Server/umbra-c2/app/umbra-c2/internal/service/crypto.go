// Package service implements AES-256-GCM encryption/decryption matching the Android agent.
package service

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
)

// CryptoKey is the 32-byte AES-256 key shared with the Android agent.
// Derived from hex: d41d8cd98f00b204e9800998ecf8427ed41d8cd98f00b204e9800998ecf8427e
var CryptoKey []byte

func init() {
	keyHex := "d41d8cd98f00b204e9800998ecf8427ed41d8cd98f00b204e9800998ecf8427e"
	var err error
	CryptoKey, err = hex.DecodeString(keyHex)
	if err != nil {
		panic(fmt.Sprintf("crypto: invalid key hex: %v", err))
	}
	if len(CryptoKey) != 32 {
		panic(fmt.Sprintf("crypto: key must be 32 bytes, got %d", len(CryptoKey)))
	}
}

// Encrypt encrypts a plaintext string using AES-256-GCM.
// Returns base64(iv(12 bytes) + ciphertext + tag(16 bytes)).
func Encrypt(plaintext string) (string, error) {
	block, err := aes.NewCipher(CryptoKey)
	if err != nil {
		return "", fmt.Errorf("encrypt: new cipher: %w", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", fmt.Errorf("encrypt: new gcm: %w", err)
	}

	// Generate 12-byte random IV (standard for GCM)
	iv := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(iv); err != nil {
		return "", fmt.Errorf("encrypt: rand iv: %w", err)
	}

	// Seal appends encrypted data to iv, then appends the tag.
	// Output layout: iv(12) + ciphertext + tag(16)
	ciphertext := gcm.Seal(iv, iv, []byte(plaintext), nil)

	return base64.StdEncoding.EncodeToString(ciphertext), nil
}

// Decrypt decrypts a base64-encoded AES-256-GCM ciphertext.
// Format: base64(iv(12 bytes) + ciphertext + tag(16 bytes)).
func Decrypt(encoded string) (string, error) {
	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return "", fmt.Errorf("decrypt: base64 decode: %w", err)
	}

	block, err := aes.NewCipher(CryptoKey)
	if err != nil {
		return "", fmt.Errorf("decrypt: new cipher: %w", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", fmt.Errorf("decrypt: new gcm: %w", err)
	}

	nonceSize := gcm.NonceSize()
	if len(data) < nonceSize {
		return "", fmt.Errorf("decrypt: ciphertext too short (need at least %d bytes, got %d)", nonceSize, len(data))
	}

	iv := data[:nonceSize]
	ciphertext := data[nonceSize:]

	plaintext, err := gcm.Open(nil, iv, ciphertext, nil)
	if err != nil {
		return "", fmt.Errorf("decrypt: gcm open: %w", err)
	}

	return string(plaintext), nil
}
