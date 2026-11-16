package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/rclone/rclone/fs/config"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/akfaiz/carryover/internal/app/appdir"
	"github.com/akfaiz/carryover/internal/cloudsync/syncer"
	"github.com/akfaiz/carryover/internal/core"
	"github.com/akfaiz/carryover/internal/library"
)

// env is an isolated data dir with a local "cloud" remote and one game with
// a RetroArch profile that another device set up, but this one hasn't.
type env struct {
	c            *core.Core
	gameID, emu  string
	saves, cloud string
}

func newEnv(t *testing.T) env {
	t.Helper()
	home := t.TempDir()
	t.Setenv(appdir.EnvHome, home)
	c, err := core.New()
	require.NoError(t, err)
	config.FileSetValue("cloud", "type", "local")
	cloudDir := filepath.Join(home, "cloud")
	st := c.Settings.Get()
	st.Remote, st.RemoteRoot, st.DeviceName = "cloud", filepath.ToSlash(cloudDir), "Phone"
	require.NoError(t, c.Settings.Update(st))
	saves := filepath.Join(home, "saves")
	require.NoError(t, os.MkdirAll(saves, 0o755))
	require.NoError(t, os.WriteFile(filepath.Join(saves, "game.srm"), []byte("save 1"), 0o644))
	emu, err := c.Library.SaveEmulator(library.Emulator{Name: "RetroArch"}, c.DeviceID())
	require.NoError(t, err)
	g, err := c.Library.Save(library.Game{
		Name:      "Pocket Quest",
		Emulators: []library.GameEmulator{{EmulatorID: emu.ID, Slots: []library.SaveSlot{{Key: "saves", Label: "Saves", Include: []string{"*.srm"}}}}},
		Devices: map[string]library.DeviceConfig{"deck": {
			DeviceName: "Steam Deck",
			Emulators:  map[string]library.EmulatorConfig{emu.ID: {Paths: map[string]string{"saves": "{home}/RetroArch/saves"}}},
		}},
	}, "deck")
	require.NoError(t, err)
	return env{c: c, gameID: g.ID, emu: emu.ID, saves: saves, cloud: cloudDir}
}

// run executes a command and decodes its JSON answer into v (if not nil).
func (e env) run(t *testing.T, v any, args ...string) int {
	t.Helper()
	var out bytes.Buffer
	code := New(e.c, &out).Run(context.Background(), args)
	if v != nil {
		require.NoError(t, json.Unmarshal(out.Bytes(), v), out.String())
	}
	return code
}

func (e env) setUp(t *testing.T) {
	t.Helper()
	var resp SetPathResponse
	require.Zero(t, e.run(t, &resp, "set-path", "--game", e.gameID, "--emulator", "RetroArch", "--slot", "saves", "--path", e.saves))
}

func TestInfoAndConfigure(t *testing.T) {
	e := newEnv(t)
	var info Info
	require.Zero(t, e.run(t, &info, "configure", "--device-name", " Pixel 9 ", "--folder", "/Saves/"))
	assert.Equal(t, "Pixel 9", info.DeviceName)
	assert.Equal(t, "Saves", info.Folder)
	assert.Equal(t, "cloud", info.Remote)
	assert.True(t, info.CloudConfigured)
	require.Len(t, info.Remotes, 1)
	assert.Equal(t, "cloud", info.Remotes[0].Name)

	var fail map[string]any
	assert.Equal(t, 1, e.run(t, &fail, "configure", "--remote", "missing"))
	assert.Equal(t, false, fail["ok"])
	assert.Contains(t, fail["error"], "no remote named")
	assert.Equal(t, 2, e.run(t, &fail, "nope"))
}

func TestGamesAndSetPath(t *testing.T) {
	e := newEnv(t)
	var games GamesResponse
	require.Zero(t, e.run(t, &games, "games", "--offline"))
	require.Len(t, games.Games, 1)
	g := games.Games[0]
	require.Len(t, g.Profiles, 1, "a game with only emulator saves has no native profile")
	p := g.Profiles[0]
	assert.Equal(t, core.SyncKey(e.gameID, e.emu), p.Key)
	assert.Equal(t, "RetroArch", p.EmulatorName)
	assert.False(t, p.Ready)
	require.Len(t, p.Slots, 1)
	assert.Equal(t, []PathHint{{Device: "Steam Deck", Path: "{home}/RetroArch/saves"}}, p.Slots[0].Elsewhere)
	assert.Equal(t, []string{"*.srm"}, p.Slots[0].Include)

	var resp SetPathResponse
	require.Zero(t, e.run(t, &resp, "set-path", "--game", "pocket quest", "--emulator", e.emu, "--slot", "saves", "--path", e.saves+"/"))
	p = resp.Game.Profiles[0]
	assert.True(t, p.Ready, p.SetupError)
	assert.Equal(t, e.saves, p.Slots[0].Resolved)
	assert.Empty(t, resp.PublishError)

	// The change is published to the cloud library, with this device's name.
	var cat library.Catalog
	b, err := os.ReadFile(filepath.Join(e.cloud, "library.json"))
	require.NoError(t, err)
	require.NoError(t, json.Unmarshal(b, &cat))
	require.Len(t, cat.Games, 1)
	d := cat.Games[0].Devices[e.c.DeviceID()]
	assert.Equal(t, "Phone", d.DeviceName)
	assert.Contains(t, d.Emulators, e.emu)

	// An empty path clears it, and the emulator is no longer set up here.
	require.Zero(t, e.run(t, &resp, "set-path", "--game", e.gameID, "--emulator", e.emu, "--slot", "saves", "--path", ""))
	assert.False(t, resp.Game.Profiles[0].Ready)
	got, err := e.c.Library.Get(e.gameID)
	require.NoError(t, err)
	assert.NotContains(t, got.Devices[e.c.DeviceID()].Emulators, e.emu)

	var fail map[string]any
	assert.Equal(t, 1, e.run(t, &fail, "set-path", "--game", e.gameID, "--emulator", e.emu, "--slot", "nope", "--path", "x"))
	assert.Equal(t, 2, e.run(t, &fail, "set-path", "--game", e.gameID, "--slot", "saves"))
}

func TestSyncAllPushesAndSkipsLockedGames(t *testing.T) {
	e := newEnv(t)
	var resp SyncResponse
	require.Zero(t, e.run(t, &resp, "sync", "--all"))
	assert.Empty(t, resp.Results, "nothing is set up on this device yet")

	e.setUp(t)
	require.Zero(t, e.run(t, &resp, "sync", "--all"))
	require.Len(t, resp.Results, 1)
	r := resp.Results[0]
	assert.Equal(t, "push", r.Action, r.Error)
	assert.Equal(t, "Pocket Quest (RetroArch)", r.Name)
	assert.NotEmpty(t, r.Summary)
	key := core.SyncKey(e.gameID, e.emu)
	assert.FileExists(t, filepath.Join(e.cloud, "games", key, "current", "saves", "game.srm"))

	require.Zero(t, e.run(t, &resp, "sync", key))
	assert.Equal(t, "none", resp.Results[0].Action)

	// Another device is playing: the game is left alone.
	lock := `{"device":"deck","deviceName":"Steam Deck","since":"` + time.Now().UTC().Format(time.RFC3339) + `"}`
	require.NoError(t, os.WriteFile(filepath.Join(e.cloud, "games", key, "lock.json"), []byte(lock), 0o644))
	require.NoError(t, os.WriteFile(filepath.Join(e.saves, "game.srm"), []byte("save 2"), 0o644))
	require.Zero(t, e.run(t, &resp, "sync", "--all"))
	assert.Equal(t, OutcomeSkipped, resp.Results[0].Action)
	assert.Equal(t, "being played on Steam Deck", resp.Results[0].Reason)
}

func TestSyncConflictAndResolve(t *testing.T) {
	e := newEnv(t)
	e.setUp(t)
	key := core.SyncKey(e.gameID, e.emu)
	var resp SyncResponse
	require.Zero(t, e.run(t, &resp, "sync", key))
	// Both sides change.
	require.NoError(t, os.WriteFile(filepath.Join(e.cloud, "games", key, "current", "saves", "game.srm"), []byte("cloud"), 0o644))
	m := filepath.Join(e.cloud, "games", key, "manifest.json")
	b, err := os.ReadFile(m)
	require.NoError(t, err)
	// Another device pushed: a new revision with other files.
	var man syncer.Manifest
	require.NoError(t, json.Unmarshal(b, &man))
	man.Revision = "other"
	fi := man.Files["saves/game.srm"]
	fi.Size, fi.Hash = 5, "0000"
	man.Files["saves/game.srm"] = fi
	b, err = json.Marshal(man)
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(m, b, 0o644))
	require.NoError(t, os.WriteFile(filepath.Join(e.saves, "game.srm"), []byte("local"), 0o644))

	assert.Equal(t, 3, e.run(t, &resp, "sync", key))
	assert.Equal(t, "conflict", resp.Results[0].Action)
	var fail map[string]any
	assert.Equal(t, 2, e.run(t, &fail, "sync", "--all", "--resolve", "local"))

	require.Zero(t, e.run(t, &resp, "sync", key, "--resolve", "local"))
	assert.Equal(t, "push", resp.Results[0].Action)
	got, err := os.ReadFile(filepath.Join(e.cloud, "games", key, "current", "saves", "game.srm"))
	require.NoError(t, err)
	assert.Equal(t, "local", string(got))
}

func TestWatchUploadsAChangeOnceItSettles(t *testing.T) {
	e := newEnv(t)
	e.setUp(t)
	var out bytes.Buffer
	a := New(e.c, &out)
	ctx := context.Background()
	a.watchFull(ctx) // the first push
	states := map[string]*watchState{}
	a.watchLocal(ctx, states)
	assert.NotContains(t, out.String(), `"action":"none"`)

	require.NoError(t, os.WriteFile(filepath.Join(e.saves, "game.srm"), []byte("save 2"), 0o644))
	out.Reset()
	a.watchLocal(ctx, states)
	assert.NotContains(t, out.String(), `"type":"sync"`, "a change is uploaded only once it stops changing")
	a.watchLocal(ctx, states)
	events := decodeEvents(t, out.String())
	require.Len(t, events, 3)
	assert.Equal(t, "cycle", events[0].Type)
	require.Equal(t, "sync", events[1].Type)
	assert.Equal(t, "push", events[1].Result.Action)
	got, err := os.ReadFile(filepath.Join(e.cloud, "games", core.SyncKey(e.gameID, e.emu), "current", "saves", "game.srm"))
	require.NoError(t, err)
	assert.Equal(t, "save 2", string(got))
}

func TestWatchStopsWhenCancelled(t *testing.T) {
	e := newEnv(t)
	ctx, cancel := context.WithCancel(context.Background())
	var out bytes.Buffer
	done := make(chan int)
	go func() { done <- New(e.c, &out).Run(ctx, []string{"watch", "--interval", "1s", "--full-every", "1h"}) }()
	time.Sleep(200 * time.Millisecond)
	cancel()
	select {
	case code := <-done:
		assert.Zero(t, code)
	case <-time.After(10 * time.Second):
		t.Fatal("watch did not stop")
	}
	assert.Contains(t, out.String(), `"type":"started"`)
}

func decodeEvents(t *testing.T, s string) []WatchEvent {
	t.Helper()
	var out []WatchEvent
	for line := range strings.SplitSeq(strings.TrimSpace(s), "\n") {
		var ev WatchEvent
		require.NoError(t, json.Unmarshal([]byte(line), &ev), line)
		out = append(out, ev)
	}
	return out
}
