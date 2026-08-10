package service

import (
	"sync"
	"time"

	"synapse-c2/app/synapse-c2/internal/model"
)

// DeviceRegistry is the in-memory device store.
type DeviceRegistry struct {
	mu      sync.RWMutex
	devices map[string]*model.Device
	started time.Time
}

var (
	// Registry is the singleton device registry.
	Registry = &DeviceRegistry{
		devices: make(map[string]*model.Device),
		started: time.Now(),
	}
)

// Register adds or updates a device in the registry.
func (r *DeviceRegistry) Register(deviceID string, info model.DeviceInfo, ws interface{}) *model.Device {
	r.mu.Lock()
	defer r.mu.Unlock()

	dev, exists := r.devices[deviceID]
	if !exists {
		dev = &model.Device{
			ID:       deviceID,
			Queue:    make([]model.Command, 0),
			Info:     info,
			LastSeen: time.Now(),
			Online:   true,
		}
		r.devices[deviceID] = dev
	} else {
		dev.Info = info
		dev.LastSeen = time.Now()
		dev.Online = true
	}

	return dev
}

// Unregister marks a device as offline.
func (r *DeviceRegistry) Unregister(deviceID string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if dev, ok := r.devices[deviceID]; ok {
		dev.Online = false
		dev.LastSeen = time.Now()
	}
}

// Get retrieves a device by ID.
func (r *DeviceRegistry) Get(deviceID string) *model.Device {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.devices[deviceID]
}

// List returns all devices as dashboard-safe structs.
func (r *DeviceRegistry) List() []model.DashboardDevice {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]model.DashboardDevice, 0, len(r.devices))
	for _, dev := range r.devices {
		result = append(result, model.DashboardDevice{
			ID:       dev.ID,
			Info:     dev.Info,
			LastSeen: dev.LastSeen,
			Online:   dev.Online,
			HasFCM:   dev.FCMToken != "",
		})
	}
	return result
}

// SetFCMToken sets the FCM token for a device.
func (r *DeviceRegistry) SetFCMToken(deviceID, token string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if dev, ok := r.devices[deviceID]; ok {
		dev.FCMToken = token
		dev.LastSeen = time.Now()
	}
}

// EnqueueCommand adds a command to a device's queue.
func (r *DeviceRegistry) EnqueueCommand(cmd model.Command) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if dev, ok := r.devices[cmd.DeviceID]; ok {
		dev.Queue = append(dev.Queue, cmd)
	}
}

// DequeueCommands drains and returns all queued commands for a device.
func (r *DeviceRegistry) DequeueCommands(deviceID string) []model.Command {
	r.mu.Lock()
	defer r.mu.Unlock()

	if dev, ok := r.devices[deviceID]; ok {
		cmds := dev.Queue
		dev.Queue = make([]model.Command, 0)
		return cmds
	}
	return nil
}

// Count returns total and online device counts.
func (r *DeviceRegistry) Count() (total, online int) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	total = len(r.devices)
	for _, dev := range r.devices {
		if dev.Online {
			online++
		}
	}
	return
}

// Uptime returns the server uptime duration.
func (r *DeviceRegistry) Uptime() time.Duration {
	return time.Since(r.started)
}

// MarkSeen updates the last-seen time for a device.
func (r *DeviceRegistry) MarkSeen(deviceID string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if dev, ok := r.devices[deviceID]; ok {
		dev.LastSeen = time.Now()
	}
}

// CheckOffline marks devices offline if they haven't been seen in 30 seconds.
// This is called periodically.
func (r *DeviceRegistry) CheckOffline(timeout time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()

	now := time.Now()
	for _, dev := range r.devices {
		if dev.Online && now.Sub(dev.LastSeen) > timeout {
			dev.Online = false
		}
	}
}
