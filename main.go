//go:build windows

package main

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	webview2 "github.com/jchv/go-webview2"
	"golang.org/x/sys/windows"
)

//go:embed ui/index.html
var indexHTML []byte

//go:embed seed.json
var seedJSON []byte

const appName = "Anime Tracker"
const keepBackups = 60

var (
	dataDir    string
	dataFile   string
	backupDir  string
	mu         sync.Mutex
	lastBackup string
)

func msgBox(text string) {
	u := syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString(appName)
	u.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x10)
}

func documentsDir() string {
	if p, err := windows.KnownFolderPath(windows.FOLDERID_Documents, 0); err == nil && p != "" {
		return p
	}
	h, _ := os.UserHomeDir()
	return filepath.Join(h, "Documents")
}

func atomicWrite(path string, b []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// One automatic copy per day (overwritten during the day), keep the newest N.
func dailyBackup(b []byte) {
	name := "biblioteka-" + time.Now().Format("2006-01-02") + ".json"
	_ = atomicWrite(filepath.Join(backupDir, name), b)
	lastBackup = name
	files, _ := filepath.Glob(filepath.Join(backupDir, "biblioteka-*.json"))
	sort.Strings(files)
	for len(files) > keepBackups {
		os.Remove(files[0])
		files = files[1:]
	}
}

func wrap(animes json.RawMessage) []byte {
	payload := map[string]any{
		"app":        "Anime Tracker 2.0",
		"version":    2,
		"exportedAt": time.Now().UTC().Format(time.RFC3339),
		"animes":     animes,
	}
	b, _ := json.MarshalIndent(payload, "", "  ")
	return b
}

func loadData() (json.RawMessage, error) {
	b, err := os.ReadFile(dataFile)
	if os.IsNotExist(err) {
		// first run: start from the seed library
		if err := atomicWrite(dataFile, seedJSON); err != nil {
			return nil, err
		}
		b = seedJSON
	} else if err != nil {
		return nil, err
	}
	var p struct {
		Animes json.RawMessage `json:"animes"`
	}
	if json.Unmarshal(b, &p) != nil || len(p.Animes) == 0 {
		var arr json.RawMessage
		if json.Unmarshal(b, &arr) == nil && len(arr) > 0 && arr[0] == '[' {
			return arr, nil
		}
		return nil, fmt.Errorf("plik danych jest uszkodzony: %s", dataFile)
	}
	return p.Animes, nil
}

func openExternal(target string) {
	exec.Command("rundll32", "url.dll,FileProtocolHandler", target).Start()
}

func jsonOut(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(v)
}

func serve(ln net.Listener) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(indexHTML)
	})
	mux.HandleFunc("/api/data", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		switch r.Method {
		case http.MethodGet:
			a, err := loadData()
			if err != nil {
				w.WriteHeader(500)
				jsonOut(w, map[string]string{"error": err.Error()})
				return
			}
			jsonOut(w, map[string]any{"animes": a, "file": dataFile, "dir": dataDir, "defaultDir": defaultDataDir(), "isDefault": filepath.Clean(dataDir) == filepath.Clean(defaultDataDir())})
		case http.MethodPost:
			body, err := io.ReadAll(io.LimitReader(r.Body, 50<<20))
			var arr []json.RawMessage
			if err == nil {
				err = json.Unmarshal(body, &arr)
			}
			if err != nil {
				w.WriteHeader(400)
				jsonOut(w, map[string]string{"error": "nieprawidłowe dane"})
				return
			}
			b := wrap(body)
			if err := atomicWrite(dataFile, b); err != nil {
				w.WriteHeader(500)
				jsonOut(w, map[string]string{"error": err.Error()})
				return
			}
			dailyBackup(b)
			jsonOut(w, map[string]any{"ok": true, "savedAt": time.Now().Format("15:04:05"), "count": len(arr)})
		}
	})
	mux.HandleFunc("/api/backup", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		b, err := os.ReadFile(dataFile)
		if err == nil {
			name := "reczna-" + time.Now().Format("2006-01-02_15-04-05") + ".json"
			err = atomicWrite(filepath.Join(backupDir, name), b)
			if err == nil {
				jsonOut(w, map[string]string{"file": filepath.Join(backupDir, name)})
				return
			}
		}
		w.WriteHeader(500)
		jsonOut(w, map[string]string{"error": err.Error()})
	})
	mux.HandleFunc("/api/pick-folder", func(w http.ResponseWriter, r *http.Request) {
		p := pickFolder("Wybierz folder, w którym Anime Tracker ma zapisywać bibliotekę i kopie")
		if p == "" {
			jsonOut(w, map[string]any{"cancelled": true})
			return
		}
		_, err := os.Stat(filepath.Join(p, "biblioteka.json"))
		jsonOut(w, map[string]any{"path": p, "hasLibrary": err == nil, "same": filepath.Clean(p) == filepath.Clean(dataDir)})
	})
	// mode: "copy" = przenieś obecną bibliotekę do nowego folderu, "use" = wczytaj bibliotekę, która już tam jest
	mux.HandleFunc("/api/set-folder", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		var req struct{ Path, Mode string }
		json.NewDecoder(r.Body).Decode(&req)
		if req.Path == "default" {
			req.Path = defaultDataDir()
		}
		fail := func(err error) {
			w.WriteHeader(500)
			jsonOut(w, map[string]string{"error": err.Error()})
		}
		if req.Path == "" {
			fail(fmt.Errorf("nie podano folderu"))
			return
		}
		if req.Mode != "use" {
			if err := copyDataTo(req.Path); err != nil {
				fail(err)
				return
			}
		}
		if err := setDataDir(req.Path); err != nil {
			fail(err)
			return
		}
		cfg := config{DataDir: req.Path}
		if filepath.Clean(req.Path) == filepath.Clean(defaultDataDir()) {
			cfg.DataDir = ""
		}
		if err := saveConfig(cfg); err != nil {
			fail(err)
			return
		}
		jsonOut(w, map[string]any{"ok": true, "dir": dataDir})
	})
	mux.HandleFunc("/api/open-folder", func(w http.ResponseWriter, r *http.Request) {
		exec.Command("explorer", dataDir).Start()
		jsonOut(w, map[string]bool{"ok": true})
	})
	mux.HandleFunc("/api/open-url", func(w http.ResponseWriter, r *http.Request) {
		u := r.URL.Query().Get("u")
		if strings.HasPrefix(u, "http://") || strings.HasPrefix(u, "https://") {
			openExternal(u)
		}
		jsonOut(w, map[string]bool{"ok": true})
	})
	http.Serve(ln, mux)
}

func main() {
	dir := loadConfig().DataDir
	if dir == "" {
		dir = defaultDataDir()
	}
	if err := setDataDir(dir); err != nil {
		msgBox("Nie mogę użyć folderu danych:\n" + dir + "\n\n" + err.Error() + "\n\nWracam do domyślnego folderu w Dokumentach.")
		if err := setDataDir(defaultDataDir()); err != nil {
			msgBox("Nie mogę utworzyć folderu danych:\n" + defaultDataDir() + "\n\n" + err.Error())
			return
		}
		saveConfig(config{})
	}
	// sanity check of seed
	if !bytes.Contains(seedJSON, []byte("animes")) {
		seedJSON = []byte(`{"animes":[]}`)
	}

	ln, err := net.Listen("tcp", "127.0.0.1:47613")
	if err != nil {
		ln, err = net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			msgBox("Nie mogę uruchomić lokalnego serwera: " + err.Error())
			return
		}
	}
	go serve(ln)

	local := os.Getenv("LOCALAPPDATA")
	if local == "" {
		local = dataDir
	}
	w := webview2.NewWithOptions(webview2.WebViewOptions{
		Debug:     false,
		AutoFocus: true,
		DataPath:  filepath.Join(local, "AnimeTracker", "webview"),
		WindowOptions: webview2.WindowOptions{
			Title:  "Anime Tracker",
			Width:  1280,
			Height: 900,
			IconId: 1,
			Center: true,
		},
	})
	if w == nil {
		msgBox("Brakuje komponentu Microsoft Edge WebView2.\nZainstaluj go ze strony Microsoft (WebView2 Runtime) i uruchom program ponownie.")
		openExternal("https://developer.microsoft.com/microsoft-edge/webview2/")
		return
	}
	defer w.Destroy()
	mainHwnd = uintptr(w.Window())
	w.Navigate(fmt.Sprintf("http://%s/", ln.Addr().String()))
	w.Run()
}
