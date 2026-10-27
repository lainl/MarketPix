# MarketPix

MarketPix turns an auctioneer's quick photos of a lot into clean listing photos. For each angle,
an image model removes the surroundings and places the item on a clean white studio background, or
into a background scene you upload, with studio lighting and a natural shadow. A vision model then
compares every result with the original photo and flags any picture where the item's details changed.

The repository contains a Spring Boot API in `Java/` and the web interface in `UserInterface/`.

## Requirements

- Java 17 or later
- Maven 3.6.3 or later
- An OpenAI API key with access to the image and vision models below
- [ImageMagick](https://imagemagick.org/script/download.php) 7 to accept HEIC and AVIF photos (the
  Docker image installs it). JPEG, PNG, GIF, TIFF, WebP, BMP and PSD work without it.

## Configure

Settings live in
[`Java/src/main/resources/application.properties`](./Java/src/main/resources/application.properties).
When `OPENAI_API_KEY` is set, that value is used. Otherwise the OpenAI key is read
from `openai.api.key`, or from the file at `openai.api.key-file`.

| Setting | Default | Purpose |
| --- | --- | --- |
| `OPENAI_API_KEY` | | OpenAI key. Overrides the file settings below. |
| `openai.api.key-file` | `C:/Dev/.secrets/openai-api-key` | File holding the OpenAI key, kept outside the repo. `openai.api.key` overrides it if set. |
| `openai.image.model` (`OPENAI_IMAGE_MODEL`) | `gpt-image-2.5-flare` | Edits each photo onto the background, at `high` quality. `gpt-image-2.5-sunburst` costs the same and gives the same output but is slower. |
| `openai.image.size` (`OPENAI_IMAGE_SIZE`) | `2048x2048` | Output size of each finished photo. |
| `r2.account-id`, `r2.bucket` (`R2_ACCOUNT_ID`, `R2_BUCKET`) | this account, `marketpix-images` | Cloudflare R2 account and bucket that finished images are saved to. |
| `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | | R2 S3 credentials. Overrides `r2.access-key-id` and `r2.secret-access-key`. Storage is off without them. |
| `marketpix.id.timezone` (`MARKETPIX_ID_TIMEZONE`) | server zone | Time zone of the day and time inside each id. |
| `openai.vision.model` (`OPENAI_VISION_MODEL`) | `gpt-5.5` | Checks the uploaded angles and compares every finished photo with its original. |
| `openai.chat.model` (`OPENAI_CHAT_MODEL`) | `gpt-5.4-mini` | Model for the chat assistant. |
| `marketpix.auth.username` / `.password` (`MARKETPIX_USERNAME` / `MARKETPIX_PASSWORD`) | `0` / `0` | The login for the web interface (demo values). |
| `dropbox.app-key`, `dropbox.app-secret` (`DROPBOX_APP_KEY`, `DROPBOX_APP_SECRET`) | | The Dropbox app's key and secret for bulk edit. |
| `dropbox.refresh-token` (`DROPBOX_REFRESH_TOKEN`) | | Long-lived Dropbox token; with it MarketPix renews access tokens itself. See [Bulk edit](#bulk-edit). |
| `dropbox.access-token` (`DROPBOX_ACCESS_TOKEN`) | | A Dropbox access token, used only while no refresh token is set. It expires after about four hours. |
| `dropbox.account-email` (`DROPBOX_ACCOUNT_EMAIL`) | `marketpixs@gmail.com` | The Dropbox account that folders are shared with; named in error messages. |
| `marketpix.bulk.cost-per-photo` (`MARKETPIX_BULK_COST_PER_PHOTO`) | `0.25` | Estimated USD per photo, shown before a bulk job starts. Adjust it to match your OpenAI bill. |
| `marketpix.bulk.cost-per-item` (`MARKETPIX_BULK_COST_PER_ITEM`) | `0.02` | Estimated USD per item for the object analysis, added to that estimate. |
| `MARKETPIX_IMAGEMAGICK` | | Path to `magick` when it is not on the `PATH` or in `C:/Program Files/ImageMagick*`. |
| `marketpix.bulk.max-photos` (`MARKETPIX_BULK_MAX_PHOTOS`) | `500` | The largest folder one bulk job accepts. |

Keep real keys out of source control. Set Dropbox credentials with the environment variables above.

## Host on Railway

`railway.toml` builds the root `Dockerfile`. The image serves `UserInterface/` and the API on
Railway's `PORT`. Open `/` for the app and `/api/health` for the health check.

Set `OPENAI_API_KEY` on the service before the first deploy. See `.env.example` for the other
optional variables.

## Run

From the `Java/` directory:

```powershell
mvn spring-boot:run
```

The API starts at `http://localhost:8000` (`PORT` or `SERVER_PORT` overrides it) and also serves
the interface at that address. Check it with `Invoke-RestMethod http://localhost:8000/api/health`,
then open `http://localhost:8000`. Opening `UserInterface/Home.html` from disk, or through a Live
Server on port 5500, still calls the API at `http://localhost:8000`.

## Logging in

Every page needs a login (the demo login is `0` / `0`). Logging in returns a session token that the
pages keep in the browser and send with every API call. A session ends after 12 hours without use, on
**Log out**, or when the server restarts. The **Single item** and **Bulk edit** tabs switch pages.

## Using it

1. **Background (optional):** a scene to place the item into, such as a wooden table, a shelf or a
   branded backdrop. Leave it empty for a clean white studio background.
2. **Angles (at least one):** Left Quarter View, Right Quarter View, Front and Side Flat, plus any
   number of extra angles. Any image format: the server converts whatever it receives. iPhone HEIC
   photos are also converted to JPEG in the browser so they can be previewed (the converter,
   `heic-to`, loads from jsDelivr the first time a HEIC is picked); a file the browser cannot show
   gets a placeholder preview and still uploads. Every finished photo has a clean white studio
   background.
3. Click **Process**. There is no item code to type: every set gets a generated id.

To switch background scenes off again, set `marketpix.background.enabled=false`
(`MARKETPIX_BACKGROUND_ENABLED`) on the server and `backgroundUploadEnabled = false` in
`UserInterface/Home.html` and `UserInterface/BulkEdit.html`.

The images are checked first. If a picture shows a different object or an unclear object, the chat
explains the problem. The slot a photo sits in (Front, Side Flat, ...) is only passed to the image
model as a hint; it is never checked or flagged. Add context in chat (for example "the item is the
cassette player, not the phone stand behind it") or replace the picture, then say "I'm ready" to
continue.

When processing finishes, the chat shows thumbnails (click one to zoom) and a ZIP download named
after the generated id, containing `<id>_01.jpg`, `<id>_02.jpg` and so on.

### Ids, cloud storage and tags

Each set gets an id built from the day, time, month and year it was made, then a random letter and four
random digits, with no dashes: 26 October 2026 at 13:40 could be `261340102026J2378` (day `26`, time
`1340`, month `10`, year `2026`, then `J2378`). The time zone is the server's unless
`marketpix.id.timezone` (`MARKETPIX_ID_TIMEZONE`) names one.

When R2 is configured, the finished JPEGs are saved to Cloudflare R2 as `<id>/<id>_01.jpg` and so on,
next to `<id>/record.json`: `{"id", "createdAt", "tags": [], "images": [...]}`. Then the chat asks
"What is the object?". Whatever the user answers (for example "a red American chair, French style")
is turned into an array of tags (`["Chair", "Red", "American", "French"]`) and saved in `record.json`.
Bulk edit saves each item under an id of its own, without tags.

Setup: create an R2 bucket named `marketpix-images` (or set `r2.bucket`), then create an R2 API token
and give the server its **S3 Access Key ID** and **Secret Access Key** as `R2_ACCESS_KEY_ID` and
`R2_SECRET_ACCESS_KEY`. Without them, nothing is uploaded and the app works as before.

## Bulk edit

The **Bulk edit** page runs the single-item mechanics over every photo in a Dropbox folder. Photos
are named `<itemCode>_<number>`: `8900_03.jpg` is the third photo of item 8900. Photos are grouped
by item, each item is identified once across all its photos (as a single item is), and every photo
is then edited and checked with that description.

1. Share the folder with `marketpixs@gmail.com` in Dropbox, copy the folder's link and paste it in.
   Optionally add a **background** scene; every photo in the folder is placed into it. Leave it empty
   for a clean white studio background.
2. Click **Start**. MarketPix lists the folder and shows how many photos and items it found and the
   approximate cost. Click **Continue** to begin, or **Cancel**.
3. A progress bar shows how many photos are finished (for example "3/103 photos finished"). The job
   runs on the server, so you can switch to Single item or close the page and come back; the page
   picks the job up again. **Stop** finishes the photos in progress and keeps them.
4. When it finishes, download the ZIP. It has one JPEG per photo under its original name, so
   `8900_03.heic` becomes `8900_03.jpg`. Photos that failed the detail check, and items whose photos
   may show different objects, are listed under "Check these before listing"; photos that could not
   be edited are listed with the reason.

Every image file directly inside the folder is used, in any format, including iPhone HEIC; subfolders
and other files are ignored. A file that does not follow the naming format becomes an item of its
own. Instead of stopping to ask, as a single
item does, a bulk job lists items that need a second look when it finishes.

### Dropbox setup

1. In the [Dropbox App Console](https://www.dropbox.com/developers/apps), open the app and, on the
   **Permissions** tab, tick `files.metadata.read`, `files.content.read` and `sharing.read`, then
   click **Submit**. Tokens only carry the permissions the app had when they were created.
2. For a token that never expires, sign the app in to the `marketpixs@gmail.com` account once at
   `https://www.dropbox.com/oauth2/authorize?client_id=<app key>&response_type=code&token_access_type=offline`,
   then exchange the code it shows:

   ```powershell
   curl.exe -X POST https://api.dropboxapi.com/oauth2/token -d code=<code> -d grant_type=authorization_code -u <app key>:<app secret>
   ```

   Put the `refresh_token` from the reply in `dropbox.refresh-token`.

## How each picture is edited

1. **Edit:** the photo (and the background scene, if you uploaded one) is sent to `gpt-image-2.5`.
   The prompt asks it to remove everything that is not the item and place the item on the
   background with studio lighting, while keeping the item exactly as photographed: its shape,
   angle, colors, wear, stickers, tape and every marking. Text too blurry to read should stay
   blurry rather than be invented.
2. **Detail check:** the vision model compares the item in the result with the original and looks
   for differences that would mislead a bidder: readable text or numbers changed, parts added or
   removed, shape or color changed, damage hidden or added, or clutter left behind. Lighting,
   sharpness and tiny scuff variations are ignored.
3. **Retry:** a result that fails the check is regenerated once. If the second result also fails,
   it is kept, outlined in red and listed with the reason, so you can reshoot or retouch it.

Image models redraw the whole picture, so a passing check is a strong signal, not a pixel-level
guarantee. Compare the results with the originals before listing, especially small labels and serial
numbers.

Each job makes one analysis call, plus one edit call and one check call per picture (two of each
when a retry is needed). Up to three pictures are processed at a time; a picture usually takes 40 to
90 seconds.

## API

Every route except `/api/health` and `/api/auth/login` needs `Authorization: Bearer <token>`.
GET requests may pass `?token=<token>` instead, so image previews and download links work.

- `POST /api/auth/login` (JSON `{"username": "...", "password": "..."}`): returns `{"token", "expiresAt"}`.
- `GET /api/auth/session`: 200 while the token is valid. `POST /api/auth/logout` ends the session.
- `POST /api/images/jobs` (multipart): repeated `angle` and `images` fields in slot
  order, an optional `background` image and optional `context`. The background is ignored when
  `marketpix.background.enabled` is off.
  Progress streams back as server-sent events.
- `POST /api/images/jobs/{jobId}/confirm` (JSON `{"ready": true, "context": "..."}`): continues a
  job that is waiting for the user.
- `GET /api/images/jobs/{jobId}/images/{n}`: the nth finished JPEG.
- `GET /api/images/jobs/{jobId}/download`: the ZIP.
- `POST /api/images/records/{id}/tags` (JSON `{"description": "..."}`): turns the description into a tag array, saves it on the stored set and returns `{"id", "tags"}`.
- `POST /api/chat/stream`: the chat assistant, which only helps explain context for an image job.
- `POST /api/bulk/jobs` (JSON `{"dropboxUrl": "..."}`, or multipart with `dropboxUrl` and an optional
  `background` image): lists the folder and returns a job awaiting confirmation, with `total`,
  `items`, `costPerPhoto`, `costPerItem` and `estimatedCost`. The background is ignored when
  `marketpix.background.enabled` is off.
- `POST /api/bulk/jobs/{jobId}/start`, `POST /api/bulk/jobs/{jobId}/cancel`,
  `GET /api/bulk/jobs/{jobId}` (progress: `finished` of `total`), `GET /api/bulk/jobs/{jobId}/download`.

Job files are kept in `marketpix.images.temp-dir` for up to one hour, then deleted.

## Test

```powershell
mvn test
```
