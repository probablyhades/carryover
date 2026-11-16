package agent

import (
	"context"
	"errors"
	"fmt"
	"log"
	"slices"
	"sync"
	"time"

	"github.com/akfaiz/carryover/internal/app/appdir"
	"github.com/akfaiz/carryover/internal/cloudsync/syncer"
	"github.com/akfaiz/carryover/internal/core"
	"github.com/akfaiz/carryover/internal/library"
)

// Outcomes of a sync besides syncer's actions (push, pull, none, conflict).
const (
	OutcomeSkipped = "skipped" // not synced now (in use elsewhere); Reason says why
	OutcomeError   = "error"
)

// SyncResult is what syncing one profile did.
type SyncResult struct {
	Key        string `json:"key"`
	GameID     string `json:"gameId"`
	EmulatorID string `json:"emulatorId"`
	Name       string `json:"name"` // the game's name, plus the emulator's in brackets
	// Action is push, pull, none, conflict, skipped or error.
	Action  string `json:"action"`
	Summary string `json:"summary,omitempty"` // e.g. "Uploaded 2 files (1.2 MB)"
	Reason  string `json:"reason,omitempty"`
	Error   string `json:"error,omitempty"`
}

// SyncResponse is the answer of "sync".
type SyncResponse struct {
	OK      bool         `json:"ok"`
	Results []SyncResult `json:"results"`
	// LibraryError is set when the cloud library could not be merged first.
	LibraryError string `json:"libraryError,omitempty"`
}

func (r *SyncResponse) exitCode() int {
	code := 0
	for _, res := range r.Results {
		switch res.Action {
		case string(syncer.ActionConflict):
			return 3
		case OutcomeError:
			code = 1
		}
	}
	return code
}

func parseResolution(s string) (syncer.Resolution, error) {
	switch s {
	case "", "auto":
		return syncer.ResolveAuto, nil
	case "local":
		return syncer.ResolveLocal, nil
	case "cloud":
		return syncer.ResolveCloud, nil
	}
	return "", usageError{fmt.Sprintf("--resolve must be local or cloud, not %q", s)}
}

func (a *Agent) sync(ctx context.Context, args []string) (*SyncResponse, error) {
	f := newFlags("sync")
	all := f.Bool("all", false, "sync every profile set up on this device")
	resolve := f.String("resolve", "", "on conflict keep \"local\" or \"cloud\"")
	if err := parse(f, reorder(args, "resolve")); err != nil {
		return nil, err
	}
	res, err := parseResolution(*resolve)
	if err != nil {
		return nil, err
	}
	if *all == (f.NArg() == 1) || f.NArg() > 1 {
		return nil, usageError{"sync: give one sync key or --all"}
	}
	if *all && res != syncer.ResolveAuto {
		return nil, usageError{"sync: --resolve only works for one profile"}
	}
	if !a.C.CloudConfigured() {
		return nil, errors.New("no cloud remote chosen")
	}
	if *all {
		return a.syncAll(ctx), nil
	}
	resp := &SyncResponse{OK: true}
	if err := a.C.SyncCatalog(ctx); err != nil {
		resp.LibraryError = err.Error()
		log.Printf("agent: library sync: %v", err)
	}
	gameID, emuID := core.ParseKey(f.Arg(0))
	g, err := a.C.Library.Find(gameID)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", gameID, err)
	}
	eng, err := a.engine()
	if err != nil {
		return nil, err
	}
	resp.Results = []SyncResult{a.syncOne(ctx, eng, g, emuID, res)}
	return resp, nil
}

// syncAll merges the cloud library, then syncs every profile set up on this device.
func (a *Agent) syncAll(ctx context.Context) *SyncResponse {
	resp := &SyncResponse{OK: true, Results: []SyncResult{}}
	if err := a.C.SyncCatalog(ctx); err != nil {
		resp.LibraryError = err.Error()
		log.Printf("agent: library sync: %v", err)
	}
	eng, err := a.engine()
	if err != nil {
		resp.LibraryError = err.Error()
		return resp
	}
	for _, g := range a.C.Library.List() {
		for _, emuID := range a.C.Profiles(g) {
			if ctx.Err() != nil {
				return resp
			}
			if _, err := a.C.JobFor(g, emuID); err != nil {
				continue // not set up on this device
			}
			resp.Results = append(resp.Results, a.syncOne(ctx, eng, g, emuID, syncer.ResolveAuto))
		}
	}
	return resp
}

// doneMessages collects the engine's "done" message of each sync, which
// says what was transferred.
type doneMessages struct {
	mu  sync.Mutex
	msg map[string]string
}

func (d *doneMessages) handle(ev syncer.Event) {
	if ev.Phase != "done" {
		return
	}
	d.mu.Lock()
	d.msg[ev.GameID] = ev.Message
	d.mu.Unlock()
}

func (d *doneMessages) take(id string) string {
	d.mu.Lock()
	defer d.mu.Unlock()
	m := d.msg[id]
	delete(d.msg, id)
	return m
}

func (a *Agent) engine() (*syncer.Engine, error) {
	eng, err := a.C.Engine()
	if err != nil {
		return nil, err
	}
	done := &doneMessages{msg: map[string]string{}}
	eng.OnEvent = done.handle
	a.done = done
	return eng, nil
}

func (a *Agent) syncOne(ctx context.Context, eng *syncer.Engine, g library.Game, emuID string, res syncer.Resolution) SyncResult {
	r := SyncResult{Key: core.SyncKey(g.ID, emuID), GameID: g.ID, EmulatorID: emuID, Name: g.Name}
	if emuID != "" {
		r.Name += " (" + a.C.ProfileName(emuID) + ")"
	}
	job, err := a.C.JobFor(g, emuID)
	if err != nil {
		r.Action, r.Error = OutcomeError, err.Error()
		return r
	}
	// Leave a game alone while another device plays it: its session pulls
	// before and pushes after, and syncing in between only makes a conflict.
	// Forcing a side (a conflict answer) goes ahead anyway.
	if res == syncer.ResolveAuto {
		if l, err := eng.CheckLock(ctx, job.GameID); err != nil {
			log.Printf("agent: check lock %s: %v", r.Key, err)
		} else if l != nil {
			r.Action, r.Reason = OutcomeSkipped, "being played on "+l.DeviceName
			return r
		}
	}
	lock, err := syncer.AcquireLocalLock(a.C.Dirs.StateDir(), job.GameID)
	if errors.Is(err, syncer.ErrLocked) {
		r.Action, r.Reason = OutcomeSkipped, "already syncing"
		return r
	}
	if err != nil {
		r.Action, r.Error = OutcomeError, err.Error()
		return r
	}
	_, action, err := eng.Sync(ctx, job, res)
	lock.Release()
	switch {
	case errors.Is(err, syncer.ErrConflict):
		r.Action = string(syncer.ActionConflict)
	case err != nil:
		r.Action, r.Error = OutcomeError, err.Error()
		if ctx.Err() == nil {
			a.C.States.SetError(job.StateID(), err.Error())
		}
	default:
		r.Action = string(action)
		if a.done != nil {
			r.Summary = a.done.take(job.GameID)
		}
	}
	return r
}

// WatchEvent is one line printed by "watch".
type WatchEvent struct {
	// Type is "started", "sync" (Result is set), "cycle" (a check finished;
	// Full tells whether it synced everything) or "error".
	Type   string      `json:"type"`
	Result *SyncResult `json:"result,omitempty"`
	Full   bool        `json:"full,omitempty"`
	Error  string      `json:"error,omitempty"`
	At     time.Time   `json:"at"`
}

// watchState is what watch remembers about one profile's local saves.
type watchState struct {
	prev    *syncer.Manifest // the last scan, so unchanged files aren't hashed again
	pending *syncer.Manifest // a changed scan waiting to be seen again unchanged
}

func (a *Agent) watch(ctx context.Context, args []string) error {
	f := newFlags("watch")
	interval := f.Duration("interval", 30*time.Second, "how often local saves are checked")
	fullEvery := f.Duration("full-every", 15*time.Minute, "how often everything is synced (picks up cloud changes)")
	if err := parse(f, args); err != nil {
		return err
	}
	if *interval < time.Second || *fullEvery < *interval {
		return usageError{"watch: --interval must be at least 1s and --full-every at least --interval"}
	}
	a.emit(WatchEvent{Type: "started"})
	states := map[string]*watchState{}
	var lastFull time.Time
	t := time.NewTicker(*interval)
	defer t.Stop()
	for {
		a.reload()
		if a.C.CloudConfigured() {
			if a.now().Sub(lastFull) >= *fullEvery {
				a.watchFull(ctx)
				clear(states)
				lastFull = a.now()
			} else {
				a.watchLocal(ctx, states)
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.C:
		}
	}
}

func (a *Agent) emit(ev WatchEvent) {
	if ev.At.IsZero() {
		ev.At = a.now().UTC()
	}
	_ = a.write(ev)
}

// reload rereads the library and settings, which the app changes from other
// processes while watch runs.
func (a *Agent) reload() {
	if lib, err := library.Open(a.C.Dirs.LibraryFile()); err == nil {
		a.C.Library = lib
	} else {
		log.Printf("agent: reload library: %v", err)
	}
	if st, err := appdir.OpenSettings(a.C.Dirs.SettingsFile()); err == nil {
		a.C.Settings = st
	} else {
		log.Printf("agent: reload settings: %v", err)
	}
}

func (a *Agent) watchFull(ctx context.Context) {
	resp := a.syncAll(ctx)
	if resp.LibraryError != "" {
		a.emit(WatchEvent{Type: "error", Error: resp.LibraryError})
	}
	for i := range resp.Results {
		if resp.Results[i].Action != string(syncer.ActionNone) {
			a.emit(WatchEvent{Type: "sync", Result: &resp.Results[i]})
		}
	}
	a.emit(WatchEvent{Type: "cycle", Full: true})
}

// watchLocal scans the saves of every ready profile without touching the
// cloud, and syncs those that changed since their last sync once a second
// scan finds the same files (so a save being written is not uploaded half-done).
func (a *Agent) watchLocal(ctx context.Context, states map[string]*watchState) {
	var eng *syncer.Engine
	for _, g := range a.C.Library.List() {
		for _, emuID := range a.C.Profiles(g) {
			if ctx.Err() != nil {
				return
			}
			job, err := a.C.JobFor(g, emuID)
			if err != nil {
				continue
			}
			key := job.StateID()
			st, err := a.C.States.Load(key)
			if err != nil || st.Conflict {
				continue // a conflict waits for the user's answer
			}
			ws := states[key]
			if ws == nil {
				ws = &watchState{}
				states[key] = ws
			}
			changed, scan, err := syncer.LocalChanges(ctx, job.Slots, st.LastSynced, ws.prev)
			if err != nil || !changed {
				ws.pending = nil
				if scan != nil {
					ws.prev = scan
				}
				continue
			}
			ws.prev = scan
			if ws.pending == nil || !syncer.SameFiles(ws.pending, scan) {
				ws.pending = scan // still being written, or just changed: look again next time
				continue
			}
			ws.pending = nil
			if eng == nil {
				if eng, err = a.engine(); err != nil {
					a.emit(WatchEvent{Type: "error", Error: err.Error()})
					return
				}
			}
			r := a.syncOne(ctx, eng, g, emuID, syncer.ResolveAuto)
			if r.Action != string(syncer.ActionNone) {
				a.emit(WatchEvent{Type: "sync", Result: &r})
			}
		}
	}
	a.emit(WatchEvent{Type: "cycle"})
}

// reorder moves flags before positional args so "sync <key> --resolve x"
// works; valued flags keep their value.
func reorder(args []string, valued ...string) []string {
	var flags, pos []string
	for i := 0; i < len(args); i++ {
		a := args[i]
		switch {
		case len(a) == 0 || a[0] != '-':
			pos = append(pos, a)
		case isValued(a, valued) && i+1 < len(args):
			flags = append(flags, a, args[i+1])
			i++
		default:
			flags = append(flags, a)
		}
	}
	return append(flags, pos...)
}

func isValued(a string, valued []string) bool {
	for len(a) > 0 && a[0] == '-' {
		a = a[1:]
	}
	return slices.Contains(valued, a)
}
