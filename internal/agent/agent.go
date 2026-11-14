// Package agent is the headless command interface the Android app drives. The
// app runs the carryover-agent binary with one command per process and reads
// its answer from stdout: one JSON object for every command, except watch,
// which keeps running and prints one JSON object per line.
//
// It sits above core (like services/, which is the desktop GUI's API).
package agent

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"strings"
	"time"

	"github.com/akfaiz/carryover/internal/app/version"
	"github.com/akfaiz/carryover/internal/cloudsync/cloud"
	"github.com/akfaiz/carryover/internal/core"
)

// Usage lists the commands.
const Usage = `carryover-agent — headless save sync for the Carryover Android app

Every command prints one JSON object on stdout ({"ok":false,"error":...} on
failure); watch prints one object per line until it is stopped.

  info                                      device, cloud settings and remotes
  configure [--device-name N] [--remote R] [--folder F]
  import-rclone <rclone.conf>               import remotes of supported providers
  test                                      check access to the cloud folder
  games [--offline]                         games and their profiles on this device
  set-path --game G [--emulator E] --slot K --path P
                                            set (or with an empty path, clear) a save folder
  sync <key>|--all [--resolve local|cloud]  sync one profile (sync key) or every ready one
  watch [--interval 30s] [--full-every 15m] sync local changes as they happen, and
                                            everything every --full-every
  version
`

// Agent runs commands against a Core.
type Agent struct {
	C   *core.Core
	out *json.Encoder
	now func() time.Time
	// done collects what each sync transferred (set by engine).
	done *doneMessages
}

// New creates an agent writing its answers to out.
func New(c *core.Core, out io.Writer) *Agent {
	enc := json.NewEncoder(out)
	enc.SetEscapeHTML(false)
	return &Agent{C: c, out: enc, now: time.Now}
}

// usageError is a malformed command line (exit code 2).
type usageError struct{ msg string }

func (e usageError) Error() string { return e.msg }

// Run executes one command and returns the process exit code: 0 on success,
// 1 on failure, 2 for a malformed command line, 3 when a sync left a conflict.
func (a *Agent) Run(ctx context.Context, args []string) int {
	if len(args) == 0 {
		return a.fail(usageError{"no command; run with --help"})
	}
	cmd, rest := args[0], args[1:]
	var (
		res any
		err error
	)
	switch cmd {
	case "info":
		res, err = a.info()
	case "configure":
		res, err = a.configure(rest)
	case "import-rclone":
		res, err = a.importRclone(rest)
	case "test":
		res, err = a.test(ctx)
	case "games":
		res, err = a.games(ctx, rest)
	case "set-path":
		res, err = a.setPath(ctx, rest)
	case "sync":
		var sr *SyncResponse
		sr, err = a.sync(ctx, rest)
		if err == nil {
			if werr := a.write(sr); werr != nil {
				return 1
			}
			return sr.exitCode()
		}
	case "watch":
		err = a.watch(ctx, rest)
		if err == nil || errors.Is(err, context.Canceled) {
			return 0
		}
	case "version":
		res = map[string]any{"ok": true, "version": version.Get().String()}
	default:
		err = usageError{fmt.Sprintf("unknown command %q", cmd)}
	}
	if err != nil {
		return a.fail(err)
	}
	if err := a.write(res); err != nil {
		return 1
	}
	return 0
}

func (a *Agent) write(v any) error {
	if err := a.out.Encode(v); err != nil {
		log.Printf("agent: write answer: %v", err)
		return err
	}
	return nil
}

// fail writes {"ok":false,"error":...} and returns the exit code.
func (a *Agent) fail(err error) int {
	log.Printf("agent: %v", err)
	_ = a.write(map[string]any{"ok": false, "error": err.Error()})
	if _, ok := errors.AsType[usageError](err); ok {
		return 2
	}
	return 1
}

// newFlags returns a flag set that reports errors instead of printing usage.
func newFlags(name string) *flag.FlagSet {
	f := flag.NewFlagSet(name, flag.ContinueOnError)
	f.SetOutput(io.Discard)
	return f
}

func parse(f *flag.FlagSet, args []string) error {
	if err := f.Parse(args); err != nil {
		return usageError{f.Name() + ": " + err.Error()}
	}
	return nil
}

// Info describes this device and its cloud settings.
type Info struct {
	OK              bool           `json:"ok"`
	Version         string         `json:"version"`
	DeviceID        string         `json:"deviceId"`
	DeviceName      string         `json:"deviceName"`
	Remote          string         `json:"remote"`
	Folder          string         `json:"folder"`
	CloudConfigured bool           `json:"cloudConfigured"`
	Remotes         []cloud.Remote `json:"remotes"`
	DataDir         string         `json:"dataDir"`
}

func (a *Agent) info() (Info, error) {
	s := a.C.Settings.Get()
	remotes := cloud.ListRemotes()
	if remotes == nil {
		remotes = []cloud.Remote{}
	}
	return Info{
		OK: true, Version: version.Get().String(),
		DeviceID: s.DeviceID, DeviceName: s.DeviceName,
		Remote: s.Remote, Folder: s.RemoteRoot, CloudConfigured: s.CloudBase() != "",
		Remotes: remotes, DataDir: a.C.Dirs.Root,
	}, nil
}

func (a *Agent) configure(args []string) (Info, error) {
	f := newFlags("configure")
	name := f.String("device-name", "", "name shown on other devices")
	remote := f.String("remote", "", "rclone remote to sync with")
	folder := f.String("folder", "", "folder inside the remote")
	if err := parse(f, args); err != nil {
		return Info{}, err
	}
	set := map[string]bool{}
	f.Visit(func(fl *flag.Flag) { set[fl.Name] = true })
	s := a.C.Settings.Get()
	if set["device-name"] {
		if strings.TrimSpace(*name) == "" {
			return Info{}, errors.New("the device name can't be empty")
		}
		s.DeviceName = strings.TrimSpace(*name)
	}
	if set["remote"] {
		r := strings.TrimSuffix(strings.TrimSpace(*remote), ":")
		if r != "" && !hasRemote(r) {
			return Info{}, fmt.Errorf("there is no remote named %q; import an rclone.conf first", r)
		}
		s.Remote = r
	}
	if set["folder"] {
		s.RemoteRoot = strings.Trim(strings.TrimSpace(*folder), "/")
		if s.RemoteRoot == "" {
			s.RemoteRoot = "Carryover"
		}
	}
	if err := a.C.Settings.Update(s); err != nil {
		return Info{}, err
	}
	return a.info()
}

func hasRemote(name string) bool {
	for _, r := range cloud.ListRemotes() {
		if r.Name == name {
			return true
		}
	}
	return false
}

func (a *Agent) importRclone(args []string) (any, error) {
	if len(args) != 1 {
		return nil, usageError{"import-rclone: give the path of an rclone.conf"}
	}
	res, err := cloud.ImportConfig(args[0])
	if err != nil {
		return nil, err
	}
	if res.Imported == nil {
		res.Imported = []string{}
	}
	if res.Skipped == nil {
		res.Skipped = []string{}
	}
	// The first remote imported becomes the sync location, as on the desktop.
	if s := a.C.Settings.Get(); s.Remote == "" && len(res.Imported) > 0 {
		s.Remote = res.Imported[0]
		if err := a.C.Settings.Update(s); err != nil {
			return nil, err
		}
	}
	return map[string]any{"ok": true, "imported": res.Imported, "skipped": res.Skipped}, nil
}

func (a *Agent) test(ctx context.Context) (any, error) {
	base := a.C.Settings.Get().CloudBase()
	if base == "" {
		return nil, errors.New("no cloud remote chosen")
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	if err := cloud.Test(ctx, base); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}
