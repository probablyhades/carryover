// Command carryover-agent is Carryover's headless sync engine for Android.
// The Android app ships it as a native library (libcarryover.so) and runs it
// for every sync; see internal/agent for the commands.
//
// It is built without cgo (GOOS=android CGO_ENABLED=0), so it needs no NDK
// and has no Fyne dependency.
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/akfaiz/carryover/internal/agent"
	"github.com/akfaiz/carryover/internal/app/applog"
	"github.com/akfaiz/carryover/internal/app/version"
	"github.com/akfaiz/carryover/internal/core"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	if len(args) == 1 {
		switch args[0] {
		case "-h", "--help", "help":
			fmt.Print(agent.Usage)
			return 0
		case "--version":
			fmt.Println("carryover-agent " + version.Get().String())
			return 0
		}
	}
	setupDNS()
	c, err := core.New()
	if err != nil {
		// Still answer in JSON: the app shows the error.
		fmt.Printf("{\"ok\":false,\"error\":%q}\n", "Carryover could not start: "+err.Error())
		return 1
	}
	if l, err := applog.Open(c.Dirs.LogsDir(), fmt.Sprintf("Carryover agent %s started, args %q", version.Get(), args)); err == nil {
		defer l.Close()
	} else {
		log.Printf("open log: %v", err)
	}
	// SIGTERM (the app stopping the watcher) and Ctrl+C cancel cleanly:
	// transfers stop and partial backups are removed.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return agent.New(c, os.Stdout).Run(ctx, args)
}
