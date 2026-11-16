package agent

import (
	"context"
	"errors"
	"flag"
	"log"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/akfaiz/carryover/internal/core"
	"github.com/akfaiz/carryover/internal/library"
)

// GamesResponse is the answer of "games".
type GamesResponse struct {
	OK    bool       `json:"ok"`
	Games []GameView `json:"games"`
	// LibraryError is set when the cloud library could not be read; the list
	// is then this device's copy.
	LibraryError string `json:"libraryError,omitempty"`
}

// GameView is a game with every profile it can be synced with.
type GameView struct {
	ID       string        `json:"id"`
	Name     string        `json:"name"`
	Profiles []ProfileView `json:"profiles"`
}

// ProfileView is one cloud save of a game: its native saves or one emulator's.
type ProfileView struct {
	// Key is the sync key ("<game id>" or "<game id>.<emulator id>") that
	// "sync" takes.
	Key          string `json:"key"`
	EmulatorID   string `json:"emulatorId"` // "" for the game's own saves
	EmulatorName string `json:"emulatorName"`
	// ShareWith names the emulator whose cloud save this one uses ("" = its own).
	ShareWith string     `json:"shareWith,omitempty"`
	Slots     []SlotView `json:"slots"`
	// Ready is true when every save folder is set on this device.
	Ready      bool       `json:"ready"`
	SetupError string     `json:"setupError,omitempty"`
	Status     StatusView `json:"status"`
}

// SlotView is a save location and its folder on this device.
type SlotView struct {
	Key     string   `json:"key"`
	Label   string   `json:"label"`
	Include []string `json:"include"`
	Exclude []string `json:"exclude"`
	// Path is the folder as stored (it may contain tokens such as {storage});
	// Resolved is the real path, or Error says why it can't be resolved.
	Path     string `json:"path"`
	Resolved string `json:"resolved,omitempty"`
	Error    string `json:"error,omitempty"`
	// Elsewhere lists the folder the other devices use, as a hint.
	Elsewhere []PathHint `json:"elsewhere"`
}

// PathHint is a save folder on another device.
type PathHint struct {
	Device string `json:"device"`
	Path   string `json:"path"`
}

// StatusView is the last sync of a profile on this device.
type StatusView struct {
	LastSyncAt time.Time `json:"lastSyncAt,omitzero"`
	LastAction string    `json:"lastAction,omitempty"`
	Conflict   bool      `json:"conflict"`
	LastError  string    `json:"lastError,omitempty"`
}

func (a *Agent) games(ctx context.Context, args []string) (*GamesResponse, error) {
	f := newFlags("games")
	offline := f.Bool("offline", false, "don't merge the cloud library first")
	if err := parse(f, args); err != nil {
		return nil, err
	}
	resp := &GamesResponse{OK: true, Games: []GameView{}}
	if !*offline && a.C.CloudConfigured() {
		if err := a.C.SyncCatalog(ctx); err != nil {
			log.Printf("agent: library sync: %v", err)
			resp.LibraryError = err.Error()
		}
	}
	for _, g := range a.C.Library.List() {
		resp.Games = append(resp.Games, a.gameView(g))
	}
	return resp, nil
}

// emulatorIDs lists the profiles a game can be synced with on any device:
// its native saves (when it has save locations of its own) and each emulator.
func emulatorIDs(g library.Game) []string {
	var out []string
	if len(g.Slots) > 0 {
		out = append(out, "")
	}
	for _, ge := range g.Emulators {
		out = append(out, ge.EmulatorID)
	}
	return out
}

func (a *Agent) gameView(g library.Game) GameView {
	gv := GameView{ID: g.ID, Name: g.Name, Profiles: []ProfileView{}}
	for _, emuID := range emulatorIDs(g) {
		gv.Profiles = append(gv.Profiles, a.profileView(g, emuID))
	}
	return gv
}

// slotsOf returns a profile's save locations: the game's own, or the
// emulator's (the shared emulator's when it uses another one's cloud save).
func slotsOf(g library.Game, emuID string) ([]library.SaveSlot, string) {
	if emuID == "" {
		return g.Slots, ""
	}
	ge, _ := g.Emulator(emuID)
	if ge.ShareWith != "" {
		if target, ok := g.Emulator(ge.ShareWith); ok {
			return target.Slots, ge.ShareWith
		}
	}
	return ge.Slots, ge.ShareWith
}

// pathsOf returns a device's folders of a profile, by slot key.
func pathsOf(d library.DeviceConfig, emuID string) map[string]string {
	if emuID == "" {
		return d.Paths
	}
	return d.Emulators[emuID].Paths
}

func (a *Agent) profileView(g library.Game, emuID string) ProfileView {
	key := core.SyncKey(g.ID, emuID)
	slots, share := slotsOf(g, emuID)
	pv := ProfileView{Key: key, EmulatorID: emuID, EmulatorName: a.C.ProfileName(emuID), ShareWith: share, Slots: []SlotView{}}
	me := a.C.DeviceID()
	d, _ := g.Device(me)
	mine := pathsOf(d, emuID)
	pctx := a.C.PathContextFor(g, emuID)
	for _, sl := range slots {
		sv := SlotView{Key: sl.Key, Label: sl.Label, Include: orEmpty(sl.Include), Exclude: orEmpty(sl.Exclude), Path: mine[sl.Key], Elsewhere: []PathHint{}}
		if sv.Label == "" {
			sv.Label = sl.Key
		}
		if strings.TrimSpace(sv.Path) != "" {
			if p, err := pctx.Expand(sv.Path); err != nil {
				sv.Error = err.Error()
			} else {
				sv.Resolved = p
			}
		}
		for id, od := range g.Devices {
			if id == me {
				continue
			}
			if p := pathsOf(od, emuID)[sl.Key]; p != "" {
				name := od.DeviceName
				if name == "" {
					name = "another device"
				}
				sv.Elsewhere = append(sv.Elsewhere, PathHint{Device: name, Path: p})
			}
		}
		sort.Slice(sv.Elsewhere, func(i, j int) bool { return sv.Elsewhere[i].Device < sv.Elsewhere[j].Device })
		pv.Slots = append(pv.Slots, sv)
	}
	if _, err := a.C.JobFor(g, emuID); err != nil {
		pv.SetupError = err.Error()
	} else {
		pv.Ready = true
	}
	if st, err := a.C.States.Load(key); err == nil {
		pv.Status = StatusView{LastSyncAt: st.LastSyncAt, LastAction: string(st.LastAction), Conflict: st.Conflict, LastError: st.LastError}
	}
	return pv
}

func orEmpty(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// SetPathResponse is the answer of "set-path": the game as it is now.
type SetPathResponse struct {
	OK   bool     `json:"ok"`
	Game GameView `json:"game"`
	// PublishError is set when the change was saved on this device but could
	// not be uploaded to the cloud library yet (the next sync retries).
	PublishError string `json:"publishError,omitempty"`
}

func (a *Agent) setPath(ctx context.Context, args []string) (*SetPathResponse, error) {
	f := newFlags("set-path")
	gameArg := f.String("game", "", "game id or name")
	emuArg := f.String("emulator", "", "emulator id or name (empty or \"native\" for the game's own saves)")
	slot := f.String("slot", "", "save location key")
	path := f.String("path", "", "folder on this device; empty clears it")
	if err := parse(f, args); err != nil {
		return nil, err
	}
	var hasPath bool
	f.Visit(func(fl *flag.Flag) { hasPath = hasPath || fl.Name == "path" })
	if *gameArg == "" || *slot == "" || !hasPath {
		return nil, usageError{"set-path: --game, --slot and --path are required"}
	}
	g, err := a.C.Library.Find(*gameArg)
	if err != nil {
		return nil, err
	}
	emuID := ""
	if *emuArg != "" && *emuArg != core.NativeProfile {
		e, err := a.C.Library.FindEmulator(*emuArg)
		if err != nil {
			return nil, errors.New("unknown emulator " + *emuArg)
		}
		if _, ok := g.Emulator(e.ID); !ok {
			return nil, errors.New(g.Name + " is not set up with " + e.Name)
		}
		emuID = e.ID
	}
	slots, _ := slotsOf(g, emuID)
	found := false
	for _, sl := range slots {
		found = found || sl.Key == *slot
	}
	if !found {
		return nil, errors.New("unknown save location " + *slot)
	}
	value := strings.TrimSpace(*path)
	if value != "" && !strings.HasPrefix(value, "{") {
		// Store it with tokens where one fits ({storage}/RetroArch/saves),
		// which reads better on the other devices.
		value = a.C.PathContextFor(g, emuID).Contract(filepath.Clean(value))
	}
	deviceName := a.C.Settings.Get().DeviceName
	g, err = a.C.Library.UpdateDevice(g.ID, a.C.DeviceID(), func(d *library.DeviceConfig) {
		d.DeviceName = deviceName
		var paths map[string]string
		if emuID == "" {
			if d.Paths == nil {
				d.Paths = map[string]string{}
			}
			paths = d.Paths
		} else {
			if d.Emulators == nil {
				d.Emulators = map[string]library.EmulatorConfig{}
			}
			ec := d.Emulators[emuID]
			if ec.Paths == nil {
				ec.Paths = map[string]string{}
			}
			paths = ec.Paths
			d.Emulators[emuID] = ec
		}
		if value == "" {
			delete(paths, *slot)
		} else {
			paths[*slot] = value
		}
		// An emulator with no folder left is no longer set up here.
		if emuID != "" && len(paths) == 0 {
			delete(d.Emulators, emuID)
		}
	})
	if err != nil {
		return nil, err
	}
	resp := &SetPathResponse{OK: true, Game: a.gameView(g)}
	if a.C.CloudConfigured() {
		if err := a.C.SyncCatalog(ctx); err != nil {
			log.Printf("agent: publish library: %v", err)
			resp.PublishError = err.Error()
		}
	}
	return resp, nil
}
