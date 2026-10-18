/**
 * Clipboard Link Bucket -> Google Sheets receiver.
 *
 * 1. Replace SPREADSHEET_ID with the ID from your Google Sheet URL.
 * 2. Replace CHANGE_THIS_TO_A_LONG_RANDOM_SECRET with your own long random secret.
 * 3. Deploy as a Web App, execute as yourself, access: Anyone.
 * 4. Paste the Web App URL and the same secret into the Android app.
 *
 * The URL is public when deployed with "Anyone" access, so the secret is required.
 * Do not reuse your Google password or share the secret publicly.
 */

const SPREADSHEET_ID = "PASTE_YOUR_SPREADSHEET_ID_HERE";
const TAB_NAME = "Links";
const SECRET_TOKEN = "CHANGE_THIS_TO_A_LONG_RANDOM_SECRET";

function doPost(e) {
  try {
    const body = JSON.parse((e && e.postData && e.postData.contents) || "{}");
    if (!body.token || body.token !== SECRET_TOKEN) {
      return jsonResponse({ ok: false, error: "Unauthorized: token mismatch" });
    }
    if (!Array.isArray(body.links) || body.links.length === 0) {
      return jsonResponse({ ok: false, error: "No links supplied" });
    }

    const ss = SpreadsheetApp.openById(SPREADSHEET_ID);
    let sheet = ss.getSheetByName(TAB_NAME);
    if (!sheet) sheet = ss.insertSheet(TAB_NAME);

    if (sheet.getLastRow() === 0) {
      sheet.appendRow(["Captured At", "X/Twitter URL", "Source", "Collector"]);
    }

    const rows = [];
    for (const item of body.links) {
      const url = String(item.url || "").trim();
      const capturedAt = String(item.capturedAt || "");
      if (!/^https:\/\/x\.com\/(?:[^/]+\/status\/\d+|i\/web\/status\/\d+)$/.test(url)) {
        continue;
      }
      rows.push([capturedAt, url, "X/Twitter", "Android Link Bucket"]);
    }
    if (rows.length === 0) {
      return jsonResponse({ ok: false, error: "No valid X status URLs found" });
    }

    const startRow = sheet.getLastRow() + 1;
    sheet.getRange(startRow, 1, rows.length, 4).setValues(rows);
    return jsonResponse({ ok: true, added: rows.length });
  } catch (err) {
    return jsonResponse({ ok: false, error: String(err && err.message ? err.message : err) });
  }
}

function doGet() {
  return jsonResponse({ ok: true, app: "Clipboard Link Bucket receiver", note: "POST links with the configured token." });
}

function jsonResponse(data) {
  return ContentService
    .createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}
