package pl.panrodla.animetracker;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Anime Tracker na Androida — ten sam interfejs (ui/index.html) co wersja na Windows.
 * Zapytania strony do /api/... obsługuje tutaj ten sam zestaw funkcji co serwer w main.go.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String INDEX_URL = "https://" + HOST + "/index.html";
    private static final String DATA = "biblioteka.json";
    private static final String BACKUPS = "kopie";
    private static final int KEEP_BACKUPS = 60;
    private static final int REQ_TREE = 1, REQ_FILE = 2;

    private WebView web;
    private WebViewAssetLoader loader;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private Store store;
    private int pendingPickId = -1;
    private Uri pendingUri;
    private ValueCallback<Uri[]> fileCallback;

    // ---------------------------------------------------------------- storage

    interface Store {
        String label();
        boolean isDefault();
        Uri treeUri();
        byte[] read(String dir, String name) throws IOException; // null = brak pliku
        void write(String dir, String name, byte[] b) throws IOException;
        List<String> list(String dir);
        void delete(String dir, String name);
    }

    /** Domyślnie: prywatna pamięć aplikacji. */
    static class FileStore implements Store {
        final File root;
        FileStore(File root) { this.root = root; }
        public String label() { return "Pamięć aplikacji (telefon)"; }
        public boolean isDefault() { return true; }
        public Uri treeUri() { return null; }
        File f(String dir, String name) { return dir == null ? new File(root, name) : new File(new File(root, dir), name); }
        public byte[] read(String dir, String name) throws IOException {
            File f = f(dir, name);
            if (!f.exists()) return null;
            try (InputStream in = new FileInputStream(f)) { return readAll(in); }
        }
        public void write(String dir, String name, byte[] b) throws IOException {
            File f = f(dir, name);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Nie mogę utworzyć folderu " + parent);
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(b); out.flush(); }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) throw new IOException("Nie mogę zapisać " + f);
            }
        }
        public List<String> list(String dir) {
            List<String> out = new ArrayList<>();
            String[] names = new File(root, dir).list();
            if (names != null) Collections.addAll(out, names);
            return out;
        }
        public void delete(String dir, String name) { f(dir, name).delete(); }
    }

    /** Folder wybrany przez użytkownika (np. Dokumenty/AnimeTracker, Dysk Google). */
    class TreeStore implements Store {
        final Uri uri;
        final DocumentFile root;
        TreeStore(Uri uri) { this.uri = uri; this.root = DocumentFile.fromTreeUri(MainActivity.this, uri); }
        public String label() { return treeLabel(uri); }
        public boolean isDefault() { return false; }
        public Uri treeUri() { return uri; }
        DocumentFile dir(String dir, boolean create) {
            if (dir == null) return root;
            DocumentFile d = root.findFile(dir);
            if (d == null && create) d = root.createDirectory(dir);
            return d;
        }
        public byte[] read(String dir, String name) throws IOException {
            DocumentFile d = dir(dir, false);
            DocumentFile f = d == null ? null : d.findFile(name);
            if (f == null) return null;
            try (InputStream in = getContentResolver().openInputStream(f.getUri())) {
                if (in == null) throw new IOException("Nie mogę odczytać " + name);
                return readAll(in);
            }
        }
        public void write(String dir, String name, byte[] b) throws IOException {
            DocumentFile d = dir(dir, true);
            if (d == null) throw new IOException("Nie mogę utworzyć folderu " + dir);
            DocumentFile f = d.findFile(name);
            if (f == null) f = d.createFile("application/json", name);
            if (f == null) throw new IOException("Brak prawa zapisu w wybranym folderze");
            OutputStream out;
            try { out = getContentResolver().openOutputStream(f.getUri(), "wt"); }
            catch (Exception e) { out = getContentResolver().openOutputStream(f.getUri(), "w"); }
            if (out == null) throw new IOException("Nie mogę zapisać " + name);
            try (OutputStream o = out) { o.write(b); o.flush(); }
        }
        public List<String> list(String dir) {
            List<String> out = new ArrayList<>();
            DocumentFile d = dir(dir, false);
            if (d != null) for (DocumentFile f : d.listFiles()) if (f.getName() != null) out.add(f.getName());
            return out;
        }
        public void delete(String dir, String name) {
            DocumentFile d = dir(dir, false);
            DocumentFile f = d == null ? null : d.findFile(name);
            if (f != null) f.delete();
        }
    }

    static String treeLabel(Uri uri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(uri);
            if (id.startsWith("primary:")) return "Pamięć wewnętrzna/" + id.substring(8);
            return id.replace(':', '/');
        } catch (Exception e) {
            return uri.toString();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private Store defaultStore() { return new FileStore(new File(getFilesDir(), "AnimeTracker")); }

    private Store initialStore() {
        String saved = prefs.getString("treeUri", null);
        if (saved != null) {
            Uri u = Uri.parse(saved);
            for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(u) && p.isWritePermission()) {
                    DocumentFile d = DocumentFile.fromTreeUri(this, u);
                    if (d != null && d.canWrite()) return new TreeStore(u);
                }
            }
            prefs.edit().remove("treeUri").apply();
            ui.post(() -> Toast.makeText(this, "Brak dostępu do wybranego folderu — zapis w pamięci aplikacji", Toast.LENGTH_LONG).show());
        }
        return defaultStore();
    }

    // ---------------------------------------------------------------- activity

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        io.execute(() -> store = initialStore());

        loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF0d0e16);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.addJavascriptInterface(new Bridge(), "AndroidApi");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost()) && ("/".equals(u.getPath()) || "/index.html".equals(u.getPath()))) {
                    return indexResponse();
                }
                return loader.shouldInterceptRequest(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Wybierz plik backupu"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(web);
        web.loadUrl(INDEX_URL);
    }

    private WebResourceResponse indexResponse() {
        try (InputStream in = getAssets().open("index.html")) {
            String html = new String(readAll(in), StandardCharsets.UTF_8);
            String shim = "<script>" + SHIM + "</script>";
            int i = html.indexOf("<head>");
            html = i >= 0 ? html.substring(0, i + 6) + shim + html.substring(i + 6) : shim + html;
            WebResourceResponse r = new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            return r;
        } catch (IOException e) {
            return null;
        }
    }

    // Podmienia fetch('/api/...') na wywołania do aplikacji.
    private static final String SHIM =
            "(function(){var of=window.fetch.bind(window),pend={},seq=0;" +
            "window.__atResolve=function(id,st,body){var p=pend[id];if(p){delete pend[id];" +
            "p(new Response(body,{status:st,headers:{'Content-Type':'application/json'}}))}};" +
            "window.fetch=function(input,init){var url=typeof input==='string'?input:(input&&input.url)||'';" +
            "if(url.indexOf('/api/')===0){init=init||{};var id=++seq;return new Promise(function(res){pend[id]=res;" +
            "AndroidApi.call(id,url,String(init.method||'GET').toUpperCase(),typeof init.body==='string'?init.body:'')})}" +
            "return of(input,init)};})();";

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){if(document.querySelector('.modal:not(.hidden)')){" +
                "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));return true}return false})()",
                v -> { if (!"true".equals(v)) super.onBackPressed(); });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                Uri u = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
                fileCallback.onReceiveValue(u == null ? null : new Uri[]{u});
                fileCallback = null;
            }
        } else if (requestCode == REQ_TREE) {
            final int id = pendingPickId;
            pendingPickId = -1;
            if (id < 0) return;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                resolve(id, 200, "{\"cancelled\":true}");
                return;
            }
            final Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
            io.execute(() -> {
                try {
                    pendingUri = uri;
                    DocumentFile d = DocumentFile.fromTreeUri(this, uri);
                    boolean has = d != null && d.findFile(DATA) != null;
                    boolean same = uri.equals(store.treeUri());
                    JSONObject o = new JSONObject();
                    o.put("path", treeLabel(uri));
                    o.put("hasLibrary", has);
                    o.put("same", same);
                    resolve(id, 200, o.toString());
                } catch (Exception e) {
                    resolve(id, 500, err(e));
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- API (jak w main.go)

    class Bridge {
        @JavascriptInterface
        public void call(int id, String url, String method, String body) {
            io.execute(() -> {
                try {
                    handle(id, Uri.parse("https://" + HOST + url), method, body);
                } catch (Exception e) {
                    resolve(id, 500, err(e));
                }
            });
        }
    }

    private void handle(int id, Uri u, String method, String body) throws Exception {
        switch (String.valueOf(u.getPath())) {
            case "/api/data":
                if ("POST".equals(method)) {
                    JSONArray arr;
                    try { arr = new JSONArray(body); } catch (Exception e) { resolve(id, 400, "{\"error\":\"nieprawidłowe dane\"}"); return; }
                    byte[] b = wrap(arr);
                    store.write(null, DATA, b);
                    dailyBackup(b);
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("savedAt", new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()));
                    o.put("count", arr.length());
                    resolve(id, 200, o.toString());
                } else {
                    JSONArray animes = loadData();
                    Store def = defaultStore();
                    JSONObject o = new JSONObject();
                    o.put("animes", animes);
                    o.put("file", store.label() + "/" + DATA);
                    o.put("dir", store.label());
                    o.put("defaultDir", def.label());
                    o.put("isDefault", store.isDefault());
                    resolve(id, 200, o.toString());
                }
                return;
            case "/api/backup": {
                byte[] b = store.read(null, DATA);
                if (b == null) throw new IOException("Brak pliku biblioteki");
                String name = "reczna-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date()) + ".json";
                store.write(BACKUPS, name, b);
                resolve(id, 200, new JSONObject().put("file", store.label() + "/" + BACKUPS + "/" + name).toString());
                return;
            }
            case "/api/pick-folder":
                ui.post(() -> {
                    if (pendingPickId >= 0) resolve(pendingPickId, 200, "{\"cancelled\":true}");
                    pendingPickId = id;
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        startActivityForResult(i, REQ_TREE);
                    } catch (Exception e) {
                        pendingPickId = -1;
                        resolve(id, 500, err(e));
                    }
                });
                return;
            case "/api/set-folder": {
                JSONObject req = new JSONObject(body.isEmpty() ? "{}" : body);
                String path = req.optString("path", "");
                String mode = req.optString("mode", "copy");
                Store target;
                if ("default".equals(path)) target = defaultStore();
                else if (pendingUri != null) target = new TreeStore(pendingUri);
                else { resolve(id, 500, "{\"error\":\"nie wybrano folderu\"}"); return; }
                if ("use".equals(mode)) {
                    byte[] b = target.read(null, DATA);
                    if (b == null) throw new IOException("W wybranym folderze nie ma biblioteki");
                } else {
                    copyData(store, target);
                }
                store = target;
                if (target.treeUri() != null) prefs.edit().putString("treeUri", target.treeUri().toString()).apply();
                else prefs.edit().remove("treeUri").apply();
                pendingUri = null;
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("dir", store.label());
                resolve(id, 200, o.toString());
                return;
            }
            case "/api/open-folder": {
                String label = store.label();
                ui.post(() -> Toast.makeText(this, "Biblioteka zapisuje się w:\n" + label, Toast.LENGTH_LONG).show());
                resolve(id, 200, "{\"ok\":true}");
                return;
            }
            case "/api/open-url": {
                String t = u.getQueryParameter("u");
                if (t != null && (t.startsWith("http://") || t.startsWith("https://"))) ui.post(() -> openExternal(t));
                resolve(id, 200, "{\"ok\":true}");
                return;
            }
            default:
                resolve(id, 404, "{\"error\":\"nieznane polecenie\"}");
        }
    }

    private JSONArray loadData() throws Exception {
        byte[] b = store.read(null, DATA);
        if (b == null) {
            try (InputStream in = getAssets().open("seed.json")) { b = readAll(in); }
            store.write(null, DATA, b);
        }
        String s = new String(b, StandardCharsets.UTF_8).trim();
        try {
            if (s.startsWith("[")) return new JSONArray(s);
            JSONArray a = new JSONObject(s).optJSONArray("animes");
            if (a != null) return a;
        } catch (Exception ignored) { }
        throw new IOException("plik danych jest uszkodzony: " + store.label() + "/" + DATA);
    }

    private static byte[] wrap(JSONArray animes) throws Exception {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject o = new JSONObject();
        o.put("app", "Anime Tracker 2.0");
        o.put("version", 2);
        o.put("exportedAt", iso.format(new Date()));
        o.put("animes", animes);
        return o.toString(2).getBytes(StandardCharsets.UTF_8);
    }

    // Jedna automatyczna kopia dziennie (nadpisywana w ciągu dnia), trzymamy najnowsze 60.
    private void dailyBackup(byte[] b) {
        try {
            String name = "biblioteka-" + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".json";
            store.write(BACKUPS, name, b);
            List<String> files = new ArrayList<>();
            for (String n : store.list(BACKUPS)) if (n.startsWith("biblioteka-") && n.endsWith(".json")) files.add(n);
            Collections.sort(files);
            while (files.size() > KEEP_BACKUPS) store.delete(BACKUPS, files.remove(0));
        } catch (Exception ignored) { }
    }

    // Kopiuje bibliotekę i kopie zapasowe do nowego miejsca (stare pliki zostają).
    private static void copyData(Store from, Store to) throws IOException {
        byte[] b = from.read(null, DATA);
        if (b != null) to.write(null, DATA, b);
        else to.write(null, DATA, "{\"animes\":[]}".getBytes(StandardCharsets.UTF_8));
        List<String> existing = to.list(BACKUPS);
        for (String n : from.list(BACKUPS)) {
            if (!n.endsWith(".json") || existing.contains(n)) continue;
            byte[] c = from.read(BACKUPS, n);
            if (c != null) to.write(BACKUPS, n, c);
        }
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Nie mogę otworzyć linku", Toast.LENGTH_SHORT).show();
        }
    }

    private static String err(Exception e) {
        try { return new JSONObject().put("error", String.valueOf(e.getMessage())).toString(); }
        catch (Exception x) { return "{\"error\":\"błąd\"}"; }
    }

    private void resolve(int id, int status, String body) {
        ui.post(() -> web.evaluateJavascript("__atResolve(" + id + "," + status + "," + JSONObject.quote(body) + ")", null));
    }
}
