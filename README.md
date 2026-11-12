# Anime Tracker

Program na Windows do śledzenia oglądanych anime (sezony, odcinki, oceny, link do Shinden).

## Pobieranie
Najnowszy `AnimeTracker.exe` jest w zakładce **Releases** (budowany automatycznie przy każdej zmianie w `main`).

## Android
Najnowszy `AnimeTracker.apk` jest w **Releases** (tag `apk-vN`). Ten sam interfejs co na Windows.
- Domyślnie zapis w pamięci aplikacji; przycisk „Miejsce zapisu” → „Zmień folder…” pozwala wybrać folder na telefonie (np. Dokumenty) — wtedy dane przetrwają odinstalowanie.
- Kopie dzienne (ostatnie 60) w podfolderze `kopie`.

## Dane
- Zapis automatyczny: domyślnie `Dokumenty\AnimeTracker\biblioteka.json` (zmiana: przycisk „Miejsce zapisu”)
- Kopie dzienne (ostatnie 60): `Dokumenty\AnimeTracker\kopie\`
- Wymaga Microsoft Edge WebView2 (wbudowany w Windows 10/11).

## Struktura
- `main.go` – okno (WebView2), lokalny serwer, zapis plików
- `ui/index.html` – interfejs
- `android/` – aplikacja na Androida (WebView + zapis plików)
- `seed.json` – startowa biblioteka przy pierwszym uruchomieniu
