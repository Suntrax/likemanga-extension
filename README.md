# Oni Extensions

Official template repository for creating background scraper extensions for the **Oni** manga client for Android.

## 📖 How it Works

Oni extensions are headless Android apps (no UI) that run in the background.
1. Oni scans your phone for apps containing the `com.blissless.mangaclient.EXTENSION_BEACON` receiver.
2. When you open a manga, Oni queries the extension's `ContentProvider` at the `chapters` path to fetch the full chapter list (metadata only — no image URLs yet).
3. When you open a specific chapter, Oni queries the `scrape` path, passing the `manga` (English and Romaji name), `anilistId`, and `chapter` (the chapter to fetch).
4. The extension scrapes its target website and returns a JSON string — either the chapter list (for `chapters`) or the image URLs for the requested chapter (for `scrape`).

## 🛠️ Creating a New Extension

1. Click **"Use this template"** at the top of this repository to create a new repo for your extension.
2. Clone your new repo and open it in Android Studio.
3. In Android Studio, press `Ctrl+Shift+R` (or `Cmd+Shift+R` on Mac) to open **Replace in Path**.
   - Search for `com.blissless.oni_extension_template` and replace it with your new package name (e.g., `com.blissless.seadex`).
   - Search for `TEMPLATE_NAME` and replace it with your extension's display name (e.g., `SeaDex`). This string is also used in the app label (`Oni: TEMPLATE_NAME`) declared in `AndroidManifest.xml`.
4. Move your Kotlin files into the new package folder structure (e.g., `com/blissless/seadex/`).
5. Configure release signing. The `release` build type reads keystore credentials from `local.properties` (already git-ignored). Add four keys there:
   ```properties
   storeFile=release.jks
   storePassword=********
   keyAlias=********
   keyPassword=********
   ```
   `storeFile` is resolved relative to the project root, so the keystore can live anywhere — `release.jks`, `app/release.jks`, etc. Never commit the keystore itself; `*.jks` and `*.keystore` are already in `.gitignore`.
6. Open `TemplateScraper.kt` (rename it if you like) and implement your scraping logic!

## 📦 Data Contract

Oni communicates with your extension via two `ContentProvider` URI paths. Both return a single-row `MatrixCursor` whose `"data"` column holds a JSON string.

### Common query parameters

Both paths receive these query string parameters:
- `manga`: The English and Romaji name of the manga (e.g., "Attack on Titan" and "Shingeki no Kyojin").
- `anilistId`: The AniList ID (e.g., "137822").
- `chapter`: (scrape path only) The chapter to fetch — a number (`"38"`), decimal (`"1.5"`), version suffix (`"12v2"`), or chapter title (`"Episode 38"`). **Required** for the image-URL format.

---

### Path 1: `chapters` — List all chapters (metadata only)

```
content://<your-package>.provider/chapters?manga=<title>&anilistId=<id>
```

Called when the user opens the chapter selection screen. Your extension should search its source site, fetch the manga's chapter list, and return it WITHOUT image URLs (images are fetched on-demand via the `scrape` path when the user opens a chapter).

**Success:**
```json
{
  "totalChapters": 353,
  "chapters": [
    {
      "number": "1",
      "title": "Episode 1",
      "id": "chapter-internal-id-123",
      "index": 0,
      "pageCount": 42
    },
    {
      "number": "2",
      "title": "Episode 2",
      "id": "chapter-internal-id-124",
      "index": 1,
      "pageCount": 40
    }
  ]
}
```

Fields:
- `totalChapters` (int) — total number of chapters available on the source site.
- `chapters` (array) — one object per chapter, in reading order (oldest first):
  - `number` (string) — chapter number as displayed on the source site (e.g. `"1"`, `"1.5"`, `"346.2"`). Sub-chapters (decimals) are supported and will be grouped under their parent chapter in the UI.
  - `title` (string) — chapter title. May be empty if the source doesn't provide one.
  - `id` (string) — the source site's internal chapter/translation ID. Used by your `scrape` implementation to fetch images. Pass any identifier your scrape function needs.
  - `index` (int) — 0-based position in the source's chapter list. Used for sorting.
  - `pageCount` (int) — number of pages (0 if unknown).

**Error:**
```json
{
  "error": "No manga found for 'Attack on Titan'."
}
```

---

### Path 2: `scrape` — Fetch a single chapter's image URLs

```
content://<your-package>.provider/scrape?manga=<title>&anilistId=<id>&chapter=<number-or-title>
```

Called when the user opens a specific chapter. The `chapter` parameter matches the `number` field you returned in the `chapters` response (e.g. `"38"`, `"1.5"`, `"346.2"`).

**Success — single chapter with image URLs**
```json
{
  "totalChapters": 353,
  "chapter": {
    "number": "38",
    "title": "Episode 38",
    "group": "Asura Scans",
    "images": [
      "https://example.com/chapters/.../001.webp",
      "https://example.com/chapters/.../002.webp"
    ]
  }
}
```

`totalChapters` is included so the UI can show the available range even when the requested chapter is missing.

**Error:**
```json
{
  "error": "Description of what went wrong."
}
```

When the error is "chapter not found", include `totalChapters` so the UI can tell the user the valid range:
```json
{
  "totalChapters": 353,
  "error": "Chapter '999' not found. Available range: 1–353."
}
```

---

## 🔗 How the two paths work together

1. User opens a manga → Oni calls `chapters` → your extension returns the full chapter list (metadata only).
2. Oni displays the chapter list in the chapter selection screen.
3. User taps a chapter → Oni calls `scrape` with that chapter's `number` → your extension fetches and returns the image URLs.
4. Oni displays the images in the reader.

This two-step design means the chapter list loads fast (no image fetching), and images are only fetched when the user actually opens a chapter.

## 🏗️ Building

Extensions are built to be as tiny as possible (target: under 50 KB).
- Do not add any external dependencies (no OkHttp, no Jsoup, no Gson). Use Android's built-in `HttpURLConnection`, `WebView`, and `org.json`.
- R8 shrinking rules are stored in `app/src/main/keepRules/rules.keep`.
- Always build the **Release APK** (`./gradlew assembleRelease`) to ensure R8 shrinks the APK size.

## 📚 Reference implementations

- [atsumaru-extension](https://github.com/Suntrax/atsumaru-extension) — atsu.moe scraper
- [mangadotnet-extension](https://github.com/Suntrax/mangadotnet-extension) — single-chapter scraper (pre-`chapters` path)
