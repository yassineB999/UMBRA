'use strict';

const crypto = require('crypto');

// AES-256-GCM encryption key (64 hex chars = 32 bytes)
// Must match Android's CryptoEngine.kt
const KEY_HEX = 'd41d8cd98f00b204e9800998ecf8427ed41d8cd98f00b204e9800998ecf8427e';
const KEY = Buffer.from(KEY_HEX, 'hex');

const IV_LENGTH = 12;  // 96 bits, recommended for GCM
const TAG_LENGTH = 16; // 128 bits

/**
 * Encrypt plaintext using AES-256-GCM.
 * Returns base64 string of: IV (12 bytes) + ciphertext + auth tag (16 bytes).
 */
function encrypt(plaintext) {
  const iv = crypto.randomBytes(IV_LENGTH);
  const cipher = crypto.createCipheriv('aes-256-gcm', KEY, iv, { authTagLength: TAG_LENGTH });

  const encrypted = Buffer.concat([
    cipher.update(String(plaintext), 'utf8'),
    cipher.final()
  ]);

  const tag = cipher.getAuthTag();

  // Combine IV + ciphertext + tag, then base64 encode
  const combined = Buffer.concat([iv, encrypted, tag]);
  return combined.toString('base64');
}

/**
 * Decrypt a base64-encoded payload produced by encrypt().
 * Input format: base64(IV[12] + ciphertext + tag[16])
 */
function decrypt(encoded) {
  const combined = Buffer.from(encoded, 'base64');

  if (combined.length < IV_LENGTH + TAG_LENGTH) {
    throw new Error(`Payload too short: ${combined.length} bytes (need at least ${IV_LENGTH + TAG_LENGTH})`);
  }

  const iv = combined.subarray(0, IV_LENGTH);
  const tag = combined.subarray(combined.length - TAG_LENGTH);
  const encrypted = combined.subarray(IV_LENGTH, combined.length - TAG_LENGTH);

  const decipher = crypto.createDecipheriv('aes-256-gcm', KEY, iv, { authTagLength: TAG_LENGTH });
  decipher.setAuthTag(tag);

  const decrypted = Buffer.concat([
    decipher.update(encrypted),
    decipher.final()
  ]);

  return decrypted.toString('utf8');
}

module.exports = { encrypt, decrypt, KEY_HEX };
