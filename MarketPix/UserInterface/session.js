// Shared by every page: where the API lives and the login session token it requires.
const apiBaseUrl = window.location.protocol === "file:" || window.location.port === "5500"
  ? "http://localhost:3008"
  : window.location.origin;
const sessionTokenKey = "marketpix.sessionToken";
const sessionPages = ["Home.html", "BulkEdit.html", "Gallery.html"];

// Storage can be unavailable (private windows, blocked site data), so every access is guarded.
function getSessionToken() {
  try {
    return localStorage.getItem(sessionTokenKey);
  } catch {
    return null;
  }
}

function setSessionToken(token) {
  try {
    localStorage.setItem(sessionTokenKey, token);
  } catch {
    // The session then lasts only until the page is left.
  }
}

function clearSessionToken() {
  try {
    localStorage.removeItem(sessionTokenKey);
  } catch {
    // Nothing stored.
  }
}

function currentPageName() {
  return window.location.pathname.split("/").pop() || "Home.html";
}

// Sends the user to log in, then back to the page they were on.
function redirectToLogin() {
  clearSessionToken();
  const page = currentPageName();
  const next = sessionPages.includes(page) ? `?next=${encodeURIComponent(page)}` : "";
  window.location.replace(`Login.html${next}`);
}

// Call at the top of every page that needs a login.
function requireSession() {
  if (!getSessionToken()) {
    redirectToLogin();
    return;
  }
  apiFetch(`${apiBaseUrl}/api/auth/session`).catch(() => {
    // apiFetch already redirects on an expired session; a network error leaves the page usable.
  });
}

// fetch() with the session token; an expired session sends the user back to the login page.
async function apiFetch(url, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set("Authorization", `Bearer ${getSessionToken() || ""}`);
  const response = await fetch(url, { ...options, headers });
  if (response.status === 401) {
    redirectToLogin();
    throw new Error("Your session has expired. Please log in again.");
  }
  return response;
}

// Image previews and download links cannot send headers, so they carry the token in the URL.
function withSessionToken(url) {
  const signed = new URL(url, apiBaseUrl);
  signed.searchParams.set("token", getSessionToken() || "");
  return signed.toString();
}

async function logout() {
  try {
    await apiFetch(`${apiBaseUrl}/api/auth/logout`, { method: "POST" });
  } catch {
    // The token is dropped locally either way.
  }
  clearSessionToken();
  window.location.replace("Login.html");
}
