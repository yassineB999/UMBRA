'use strict';

const path = require('path');
const fs = require('fs');
const { encrypt } = require('./crypto');

const FCM_KEY_FILE = path.join(__dirname, 'firebase-admin-key.json');

let admin = null;
let configured = false;

function log(...args) {
  console.log(`[${new Date().toISOString()}] [FCM]`, ...args);
}

/**
 * Initialize Firebase Admin SDK.
 * Returns true if FCM is configured and ready, false otherwise.
 */
function init() {
  if (!fs.existsSync(FCM_KEY_FILE)) {
    log('WARNING: firebase-admin-key.json not found — FCM push disabled.');
    log('         Place your Firebase service account key at:', FCM_KEY_FILE);
    return false;
  }

  try {
    admin = require('firebase-admin');
    admin.initializeApp({
      credential: admin.credential.cert(FCM_KEY_FILE),
    });
    configured = true;
    log('Firebase Admin initialized successfully.');
    return true;
  } catch (err) {
    log('ERROR initializing Firebase Admin:', err.message);
    return false;
  }
}

/**
 * Send an encrypted command to a device via FCM.
 *
 * @param {string} deviceId - Target device ID
 * @param {object} command  - Command object {id, module, action, params, timestamp}
 * @returns {Promise<string|null>} FCM message ID on success, null on failure
 */
async function sendCommand(deviceId, command) {
  if (!configured || !admin) {
    log(`FCM not configured — cannot send command to ${deviceId}`);
    return null;
  }

  if (!deviceId) {
    log('sendCommand: missing deviceId');
    return null;
  }

  try {
    // Build command payload as JSON
    const payloadJson = JSON.stringify(command);
    // Encrypt with AES-256-GCM
    const encryptedPayload = encrypt(payloadJson);

    // CRITICAL: FCM data key must be "p" to match Android side: message.data["p"]
    const message = {
      data: {
        p: encryptedPayload,
      },
      android: {
        priority: 'high',
        ttl: 5 * 60 * 1000, // 5 minutes in milliseconds
      },
      token: deviceId, // deviceId IS the FCM registration token
    };

    const response = await admin.messaging().send(message);
    log(`FCM sent to ${deviceId.slice(0, 20)}... — messageId: ${response}`);
    return response;
  } catch (err) {
    log(`FCM send failed for ${deviceId.slice(0, 20)}...:`, err.message);
    return null;
  }
}

/**
 * Send a command to a device using FCM.
 * Accepts device FCM token directly (not the device registry ID).
 *
 * @param {string} fcmToken - FCM registration token
 * @param {object} command  - Command object to send
 */
async function sendToToken(fcmToken, command) {
  if (!configured || !admin) {
    log('FCM not configured — cannot send command');
    return null;
  }

  if (!fcmToken) {
    log('sendToToken: missing fcmToken');
    return null;
  }

  try {
    const payloadJson = JSON.stringify(command);
    const encryptedPayload = encrypt(payloadJson);

    const message = {
      data: {
        p: encryptedPayload,
      },
      android: {
        priority: 'high',
        ttl: 5 * 60 * 1000,
      },
      token: fcmToken,
    };

    const response = await admin.messaging().send(message);
    log(`FCM sent to token ${fcmToken.slice(0, 20)}... — messageId: ${response}`);
    return response;
  } catch (err) {
    log(`FCM send failed for token ${fcmToken.slice(0, 20)}...:`, err.message);
    return null;
  }
}

function isConfigured() {
  return configured;
}

module.exports = { init, sendCommand, sendToToken, isConfigured };
