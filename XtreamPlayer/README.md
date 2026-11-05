# YM Player for Fire TV

A simple, Apple TV-style Xtream Codes IPTV player for Fire TV / Fire Stick (and Android TV). It's built for the remote control.

- **Live TV**: categories, channel list with logos, Now/Next guide, ▲▼ to change channel while watching, OK for channel info
- **Movies**: poster grid by category, resumes where you stopped
- **Series**: seasons and episodes, resume, plays the next episode automatically
- **Favourites** (☰ Menu button, or hold OK) and **search** for every section
- Plays .ts or .m3u8 live streams (if one fails it switches to the other on its own), with a user-agent setting for fussy providers
- Uses ExoPlayer (Media3), the same engine as most good IPTV apps

## 1. Get GitHub to build the APK (one-off, about 10 minutes)

1. Sign up at https://github.com (free), then click **New repository**.
   Name it `XtreamPlayer` and make it **Public**. Public is required: Downloader can't fetch files from a private repo.
2. On the new repo page, click **uploading an existing file**. Drag in **everything inside** this folder
   (`app`, `.github`, `build.gradle`, `settings.gradle`, `gradle.properties`, `README.md`), then click **Commit changes**.
   - Mac: the `.github` folder is hidden. Press **Cmd + Shift + .** in Finder to show it.
   - If `.github` didn't upload, click **Add file → Create new file**, type the name
     `.github/workflows/build.yml`, paste in the contents of that file, and commit.
3. Open the **Actions** tab. "Build APK" will be running (enable Actions if GitHub asks). It takes about 4–6 minutes.
4. When it goes green, a new entry appears under **Releases** with `XtreamPlayer.apk`.

Your permanent download link (put your GitHub username in):

```
https://github.com/YOUR-USERNAME/XtreamPlayer/releases/latest/download/XtreamPlayer.apk
```

This link always points to the newest build.

## 2. Make a Downloader code

1. Go to https://go.aftvnews.com and sign in or register.
2. Paste the link above and create a short URL. The number you get is your Downloader code.

## 3. Install on the Fire Stick

1. On the Fire TV: **Settings → My Fire TV → About**, then click the device name 7 times to unlock Developer Options.
2. **Settings → My Fire TV → Developer Options → Install unknown apps → Downloader → ON**.
3. Install **Downloader** from the Amazon Appstore, open it, type your code, then install.
4. Open **Xtream Player** from Your Apps. You can hold OK on it and pick *Move to front*.

## Updating

Change anything in the repo (even an edit to this README) and GitHub builds a new version.
Run your Downloader code again and it installs over the old version, keeping your login and favourites.

## Remote controls

| Where | Button | Does |
|---|---|---|
| Lists | OK | Play / open |
| Lists | ☰ Menu or hold OK | Add or remove favourite |
| Lists | Back | Jump to categories, then exit |
| Live TV | ▲ ▼ / CH+ CH- | Change channel |
| Live TV | OK / ◀ ▶ | Show channel info and Now/Next |
| Movies/Series | ◀ ▶ | Back 10 s / forward 30 s |
| Movies/Series | OK | Show controls |

## Troubleshooting

- **Login fails**: use the server address exactly as your provider gave it, including the port, e.g. `http://line.example.com:8080`. You can also paste a whole `get.php?username=…&password=…` M3U link into the server box.
- **Channels won't play**: Settings → switch *Live stream format* between TS and HLS. If your provider only allows certain apps, set the *User agent* (e.g. `IPTVSmartersPlayer`).
- **Build failed on GitHub**: open the failed run in the Actions tab and copy the red error text.

Note: `app/release.keystore` signs the app so that updates install over older versions. Keep it in the repo.
