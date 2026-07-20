package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

func main() {
	address := flag.String("listen", envOrDefault("MURONG_RELAY_LISTEN", ":8787"), "HTTP listen address")
	maxConnections := flag.Int("max-connections", 1024, "maximum concurrent WebSocket connections")
	flag.Parse()

	relay := desktopbridge.NewCloudRelayServer(*maxConnections)
	server := &http.Server{
		Addr:              *address,
		Handler:           relay.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       75 * time.Second,
		MaxHeaderBytes:    16 * 1024,
	}
	shutdownContext, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-shutdownContext.Done()
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(ctx)
	}()
	log.Printf("Murong cloud relay listening on %s", *address)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
