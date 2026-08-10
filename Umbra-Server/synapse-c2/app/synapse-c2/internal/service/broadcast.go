package service

import (
	"sync"

	"synapse-c2/app/synapse-c2/internal/model"
)

// ResultBroadcaster fans out decrypted results to multiple SSE listeners.
type ResultBroadcaster struct {
	mu         sync.RWMutex
	subscribers map[chan model.DashboardResult]struct{}
}

var (
	// Broadcaster is the singleton result broadcaster.
	Broadcaster = &ResultBroadcaster{
		subscribers: make(map[chan model.DashboardResult]struct{}),
	}
)

// Subscribe returns a channel that receives decrypted results.
func (b *ResultBroadcaster) Subscribe() chan model.DashboardResult {
	ch := make(chan model.DashboardResult, 64)
	b.mu.Lock()
	b.subscribers[ch] = struct{}{}
	b.mu.Unlock()
	return ch
}

// Unsubscribe removes a subscriber channel.
func (b *ResultBroadcaster) Unsubscribe(ch chan model.DashboardResult) {
	b.mu.Lock()
	delete(b.subscribers, ch)
	b.mu.Unlock()
	close(ch)
}

// Broadcast sends a result to all subscribers.
func (b *ResultBroadcaster) Broadcast(result model.DashboardResult) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	for ch := range b.subscribers {
		select {
		case ch <- result:
		default:
			// Drop if subscriber is slow
		}
	}
}
