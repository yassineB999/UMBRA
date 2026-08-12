//go:build ignore

package main

import (
	"fmt"
	"umbra-c2/app/umbra-c2/internal/service"
)

func main() {
	plain := "Hello from Android agent!"
	enc, err := service.Encrypt(plain)
	if err != nil {
		fmt.Printf("Encrypt error: %v\n", err)
		return
	}
	fmt.Printf("Encrypted: %s\n", enc)

	dec, err := service.Decrypt(enc)
	if err != nil {
		fmt.Printf("Decrypt error: %v\n", err)
		return
	}
	fmt.Printf("Decrypted: %s\n", dec)
	fmt.Printf("Match: %v\n", dec == plain)
	fmt.Printf("Key length: %d bytes\n", len(service.CryptoKey))
}
