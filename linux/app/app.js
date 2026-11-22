"use strict";
/* arzxsh for Linux — vanilla SPA over the ytmusic-backend API.
   Mirrors the phone app: Home / Search / Library, dual-deck
   playback with crossfade, radio continuation, liked songs and
   history kept in localStorage (on this machine only). */

const $ = (sel) => document.querySelector(sel);
const view = $("#view");
if (typeof window.__arzxshUserCss === "string") {
  const userStyle = document.createElement("style");
  userStyle.id = "arzxsh-user-css";
  userStyle.textContent = window.__arzxshUserCss;
  document.head.appendChild(userStyle);
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}
/* Build a CSS url() value without letting the URL break out of its
   quoted string (audit 2026-11-11): quotes, backslashes, parens and
   control/space characters are percent-encoded before interpolation. */
function cssUrl(u) {
  const extra = { "(": "%28", ")": "%29", "'": "%27" };
  const safe = String(u).replace(/["'()\\\s\u0000-\u001f\u007f]/g,
    (c) => extra[c] || encodeURIComponent(c));
  return `url("${safe}")`;
}
function fmt(sec) {
  if (!isFinite(sec) || sec == null) return "0:00";
  sec = Math.max(0, Math.round(sec));
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, "0")}`;
}
/* Per-run request token (audit 2026-11-11): the launcher generates
   it and the backend hands it to this same-origin page via
   /ui-config. Mutating calls and /local reads must present it, so a
   cross-origin page (which cannot read /ui-config and cannot set
   custom headers on a simple request) cannot drive the backend. */
let apiToken = "";
const tokenReady = fetch("/ui-config")
  .then((r) => r.json())
  .then((d) => { apiToken = d.token || ""; })
  .catch(() => {});
function authHeaders() {
  return apiToken ? { "X-Arzxsh-Token": apiToken } : {};
}
async function api(path) {
  await tokenReady;
  const r = await fetch(path, { headers: authHeaders() });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}
let toastTimer = null;
let toastHideTimer = null;
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  clearTimeout(toastTimer);
  clearTimeout(toastHideTimer);
  t.classList.remove("hidden", "show");
  void t.offsetWidth;
  t.classList.add("show");
  toastTimer = setTimeout(() => {
    t.classList.remove("show");
    toastHideTimer = setTimeout(() => t.classList.add("hidden"), 240);
  }, 3200);
}

/* ---------------- persistent local state ---------------- */
const store = {
  get(key, fallback) {
    try { return JSON.parse(localStorage.getItem(key)) ?? fallback; }
    catch { return fallback; }
  },
  set(key, value) { localStorage.setItem(key, JSON.stringify(value)); },
};
const SETTINGS_KEY = "arzxsh-settings";
const DEFAULT_ACCENT = "#fc3c44";
const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const storedSettings = (() => {
  try { return JSON.parse(localStorage.getItem(SETTINGS_KEY)) || {}; }
  catch { return {}; }
})();
const settings = {
  accentMode: ["artwork", "red", "custom"].includes(storedSettings.accentMode)
    ? storedSettings.accentMode : "artwork",
  customAccent: /^#[0-9a-f]{6}$/i.test(storedSettings.customAccent || "")
    ? storedSettings.customAccent : "#3ddc84",
  vizStyle: ["bars", "wave", "off"].includes(storedSettings.vizStyle)
    ? storedSettings.vizStyle : "bars",
  motion: ["full", "reduced"].includes(storedSettings.motion)
    ? storedSettings.motion : (prefersReducedMotion ? "reduced" : "full"),
};
let vizMainColor = settings.accentMode === "custom" ? settings.customAccent : DEFAULT_ACCENT;
let vizNpColor = vizMainColor;
const motionReduced = () => settings.motion === "reduced";
function saveSettings() {
  try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings)); } catch {}
}
function applySettings(save = true) {
  if (save) saveSettings();
  const root = document.documentElement;
  const np = $("#nowPlaying");
  document.body.classList.toggle("motion-reduced", motionReduced());
  document.body.classList.toggle("viz-off", settings.vizStyle === "off");
  if (settings.accentMode === "custom") {
    root.style.setProperty("--accent", settings.customAccent);
    vizMainColor = settings.customAccent;
  } else {
    root.style.removeProperty("--accent");
    vizMainColor = DEFAULT_ACCENT;
  }
  if (settings.accentMode === "red") {
    np.style.setProperty("--np-accent", DEFAULT_ACCENT);
    vizNpColor = DEFAULT_ACCENT;
  } else if (settings.accentMode === "custom") {
    np.style.setProperty("--np-accent", settings.customAccent);
    vizNpColor = settings.customAccent;
  } else if (typeof player !== "undefined" && player.current) {
    if (accentCache.has(player.current.id)) {
      const rgb = accentCache.get(player.current.id);
      np.style.setProperty("--np-accent", rgb);
      vizNpColor = rgb;
    } else {
      np.style.removeProperty("--np-accent");
      vizNpColor = DEFAULT_ACCENT;
      applyArtworkAccent(player.current);
    }
  } else {
    np.style.removeProperty("--np-accent");
    vizNpColor = DEFAULT_ACCENT;
  }
  const accentSelect = $("#settingAccentMode");
  if (accentSelect) {
    accentSelect.value = settings.accentMode;
    $("#settingCustomAccent").value = settings.customAccent;
    $("#customAccentRow").classList.toggle("hidden", settings.accentMode !== "custom");
    $("#settingVizStyle").value = settings.vizStyle;
    $("#settingMotion").value = settings.motion;
  }
  if (typeof wakeMotionLoop === "function") wakeMotionLoop(350);
}
let likes = store.get("arzxsh.likes", []);           // full track objects
let recentPlays = store.get("arzxsh.recent", []);    // full track objects
let searchHistory = store.get("arzxsh.searchHistory", []);
let downloads = store.get("arzxsh.downloads", {}); // videoId -> local track id
const isLiked = (id) => likes.some((t) => t.id === id);
function popLikeElements(trackId) {
  const els = [
    ...document.querySelectorAll(`[data-like="${CSS.escape(trackId)}"]`),
    $("#pHeart"), $("#npHeart"),
  ].filter(Boolean);
  els.forEach((el) => {
    el.classList.remove("like-pop");
    void el.offsetWidth;
    el.classList.add("like-pop");
    setTimeout(() => el.classList.remove("like-pop"), 360);
  });
}
function toggleLike(track) {
  const nowLiked = !isLiked(track.id);
  if (!nowLiked) likes = likes.filter((t) => t.id !== track.id);
  else likes = [track, ...likes.filter((t) => t.id !== track.id)];
  store.set("arzxsh.likes", likes);
  refreshLikeUi();
  if (nowLiked) popLikeElements(track.id);
}
function saveSearch(q) {
  q = q.trim();
  if (!q) return;
  searchHistory = [q, ...searchHistory.filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(0, 10);
  store.set("arzxsh.searchHistory", searchHistory);
}
function notePlayed(track) {
  recentPlays = [track, ...recentPlays.filter((t) => t.id !== track.id)].slice(0, 12);
  store.set("arzxsh.recent", recentPlays);
  renderSideRecent();
}

/* ---------------- routing ---------------- */
const routes = [];
let routeIdx = -1;
function navigate(route, replace = false) {
  if (!replace && routeIdx >= 0 && JSON.stringify(routes[routeIdx]) === JSON.stringify(route)) return;
  routes.splice(routeIdx + 1);
  routes.push(route);
  routeIdx = routes.length - 1;
  renderRoute();
}
function renderRoute() {
  const r = routes[routeIdx];
  $("#backBtn").disabled = routeIdx <= 0;
  $("#fwdBtn").disabled = routeIdx >= routes.length - 1;
  document.querySelectorAll(".nav-item").forEach((b) =>
    b.classList.toggle("active", b.dataset.nav === r.type));
  view.scrollTop = 0;
  if (r.type === "home") renderHome();
  else if (r.type === "search") renderSearch(r.q);
  else if (r.type === "artist") renderArtist(r.id);
  else if (r.type === "album") renderAlbum(r.id);
  else if (r.type === "library") renderLibrary();
  else if (r.type === "liked") renderLiked();
  else if (r.type === "local") renderLocal();
  else if (r.type === "settings") renderSettings();
}
let routeAnimGen = 0;
let routeAnimScheduled = false;
function animateRouteBlocks() {
  const gen = ++routeAnimGen;
  if (motionReduced()) return;
  const blocks = [...view.children].filter((el) => el.nodeType === 1);
  blocks.forEach((el, i) => {
    el.classList.remove("route-in");
    el.style.transitionDelay = `${Math.min(i * 45, 405)}ms`;
    el.classList.add("route-enter");
  });
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (gen !== routeAnimGen) return;
    blocks.forEach((el) => el.classList.add("route-in"));
    setTimeout(() => {
      if (gen !== routeAnimGen) return;
      blocks.forEach((el) => {
        el.classList.remove("route-enter", "route-in");
        el.style.transitionDelay = "";
      });
    }, 760);
  }));
}
new MutationObserver(() => {
  if (routeAnimScheduled) return;
  routeAnimScheduled = true;
  requestAnimationFrame(() => {
    routeAnimScheduled = false;
    animateRouteBlocks();
  });
}).observe(view, { childList: true });
$("#backBtn").onclick = () => { if (routeIdx > 0) { routeIdx--; renderRoute(); } };
$("#fwdBtn").onclick = () => { if (routeIdx < routes.length - 1) { routeIdx++; renderRoute(); } };
document.querySelectorAll(".nav-item").forEach((b) =>
  b.addEventListener("click", () => {
    if (b.dataset.nav === "search") { $("#searchInput").focus(); }
    else navigate({ type: b.dataset.nav });
  }));
$("#likedLink").onclick = () => navigate({ type: "liked" });

/* ---------------- shared row builders ---------------- */
function dlBtn(track) {
  if (track.source === "local") return "";
  const saved = downloads[track.id];
  return `<button class="heart-btn ${saved ? "liked" : ""}" data-download="${esc(track.id)}" title="${saved ? "Saved for offline" : "Download for offline"}">${saved ? "✓" : "⬇"}</button>`;
}
async function doDownload(track) {
  toast(`Downloading “${track.title}”…`);
  try {
    await tokenReady;
    const r = await fetch(`/download/${encodeURIComponent(track.id)}?title=${encodeURIComponent(track.title)}&artist=${encodeURIComponent(track.artist)}`, { method: "POST", headers: authHeaders() });
    if (!r.ok) throw new Error();
    const data = await r.json();
    downloads[track.id] = data.track.id;
    store.set("arzxsh.downloads", downloads);
    register([data.track], null);
    document.querySelectorAll(`[data-download="${CSS.escape(track.id)}"]`).forEach((b) => {
      b.classList.add("liked"); b.textContent = "✓"; b.title = "Saved for offline";
    });
    syncNpDownload();
    toast(data.already ? "Already saved for offline" : "Saved for offline");
  } catch {
    toast("Download failed. Check your connection and try again.");
  }
}
function heartBtn(track) {
  return `<button class="heart-btn ${isLiked(track.id) ? "liked" : ""}" data-like="${esc(track.id)}" title="Like">${isLiked(track.id) ? "♥" : "♡"}</button>`;
}
function rowHtml(track, i, opts = {}) {
  const cur = player.current && player.current.id === track.id;
  return `<div class="row ${cur ? "current" : ""} ${cur && player.playing ? "playing" : ""}" data-play="${esc(track.id)}" data-ctx="${opts.ctx || ""}" role="button" tabindex="0">
    ${opts.rank ? `<span class="rank">${i + 1}</span>` : ""}
    <img src="${esc(track.artwork || "")}" alt="" loading="lazy">
    <span class="row-main">
      <span class="row-title">${esc(track.title)} ${track.is_explicit ? '<span class="badge-x">E</span>' : ""}<span class="eq"><i></i><i></i><i></i></span></span>
      <span class="row-sub">${esc(track.artist)}${track.album ? " · " + esc(track.album) : ""}</span>
    </span>
    <span class="row-dur">${fmt(track.duration_s)}</span>
    ${dlBtn(track)}
    ${heartBtn(track)}
  </div>`;
}
function cardHtml(item, kind) {
  if (kind === "artist") {
    return `<button class="card round" data-artist="${esc(item.id)}">
      <img src="${esc(item.artwork || "")}" alt="" loading="lazy">
      <div class="card-title">${esc(item.name)}</div>
      <div class="card-sub">Artist</div></button>`;
  }
  return `<button class="card" data-album="${esc(item.id)}">
    <img src="${esc(item.artwork || "")}" alt="" loading="lazy">
    <div class="card-title">${esc(item.title)}</div>
    <div class="card-sub">${esc(item.artist || "")}${item.year ? " · " + esc(item.year) : ""}</div></button>`;
}
function skeletons(n, h = 62) {
  return Array.from({ length: n }, () =>
    `<div class="skel" style="height:${h}px;margin-bottom:8px"></div>`).join("");
}
/* One delegated click handler for everything the views render. */
view.addEventListener("click", (e) => {
  const dl = e.target.closest("[data-download]");
  if (dl) {
    e.stopPropagation();
    const t = findTrack(dl.dataset.download);
    if (t && !downloads[t.id]) doDownload(t);
    else if (t) toast("Already saved for offline");
    return;
  }
  const like = e.target.closest("[data-like]");
  if (like) {
    e.stopPropagation();
    const t = findTrack(like.dataset.like);
    if (t) toggleLike(t);
    return;
  }
  const play = e.target.closest("[data-play]");
  if (play) {
    const list = contexts.get(play.dataset.ctx) || [findTrack(play.dataset.play)];
    const idx = list.findIndex((t) => t.id === play.dataset.play);
    const labels = {
      charts: "Trending now", searchSongs: "Search", artistSongs: "Artist",
      albumTracks: "Album", recent: "Jump back in", recentLib: "Recently played",
      recentSide: "Recently played", likedList: "Liked Songs", localList: "On this device",
    };
    if (idx >= 0) playFromContext(list, idx, labels[play.dataset.ctx] || "Songs");
    return;
  }
  const artist = e.target.closest("[data-artist]");
  if (artist) { navigate({ type: "artist", id: artist.dataset.artist }); return; }
  const album = e.target.closest("[data-album]");
  if (album) { navigate({ type: "album", id: album.dataset.album }); return; }
  const retry = e.target.closest("[data-retry]");
  if (retry) { renderRoute(); }
});

/* Track registry: every rendered track is remembered so like /
   play lookups never need a refetch. Contexts map a name to the
   exact list a set of rows was rendered from. */
const trackReg = new Map();
const contexts = new Map();
function register(list, ctxName) {
  (list || []).forEach((t) => { if (t && t.id) trackReg.set(t.id, t); });
  if (ctxName) contexts.set(ctxName, list || []);
}
function findTrack(id) { return trackReg.get(id); }

/* ---------------- Home ---------------- */
function greeting() {
  const h = new Date().getHours();
  if (h >= 22 || h < 4) return ["Good night", "The late queue is yours."];
  if (h < 12) return ["Good morning", "Start the day loud."];
  if (h < 17) return ["Good afternoon", "The midday queue."];
  return ["Good evening", "Tonight's rotation."];
}
async function renderHome() {
  const [g, sub] = greeting();
  view.innerHTML = `<div class="greeting">${g}</div><div class="greeting-sub">${sub}</div>
    ${recentPlays.length ? `<div class="section-title">Jump back in</div>
      <div class="quick-grid">${recentPlays.slice(0, 6).map((t) => `
        <button class="quick-tile" data-play="${esc(t.id)}" data-ctx="recent">
          <img src="${esc(t.artwork || "")}" alt="">
          <span><div class="qt-title">${esc(t.title)}</div><div class="qt-sub">${esc(t.artist)}</div></span>
        </button>`).join("")}</div>` : ""}
    <div class="section-title">Trending now</div>${skeletons(6)}
    <div class="section-title">Top artists</div>${skeletons(1, 190)}`;
  register(recentPlays, "recent");
  try {
    const charts = await api("/charts");
    if (routes[routeIdx].type !== "home") return;
    register(charts.songs, "charts");
    register(charts.artists.map((a) => ({ id: a.id })), null);
    view.innerHTML = `<div class="greeting">${g}</div><div class="greeting-sub">${sub}</div>
      ${recentPlays.length ? `<div class="section-title">Jump back in</div>
        <div class="quick-grid">${recentPlays.slice(0, 6).map((t) => `
          <button class="quick-tile" data-play="${esc(t.id)}" data-ctx="recent">
            <img src="${esc(t.artwork || "")}" alt="">
            <span><div class="qt-title">${esc(t.title)}</div><div class="qt-sub">${esc(t.artist)}</div></span>
          </button>`).join("")}</div>` : ""}
      <div class="section-title">Trending now</div>
      ${charts.songs.slice(0, 15).map((t, i) => rowHtml(t, i, { rank: true, ctx: "charts" })).join("")}
      <div class="section-title">Top artists</div>
      <div class="rail">${charts.artists.map((a) => cardHtml(a, "artist")).join("")}</div>`;
    charts.artists.forEach((a) => trackReg.set(a.id, a));
  } catch {
    if (routes[routeIdx].type !== "home") return;
    view.innerHTML = `<div class="greeting">${g}</div>
      <div class="error-note">Couldn't load the catalog. Check your connection.<button data-retry>Retry</button></div>`;
  }
}

/* ---------------- Search ---------------- */
const searchInput = $("#searchInput");
const suggestBox = $("#suggestBox");
let suggestTimer = null;
searchInput.addEventListener("input", () => {
  $("#searchClear").classList.toggle("hidden", !searchInput.value);
  clearTimeout(suggestTimer);
  const q = searchInput.value.trim();
  if (!q) { renderSuggest(); return; }
  renderSuggest(q);
});
searchInput.addEventListener("focus", () => renderSuggest(searchInput.value.trim()));
document.addEventListener("click", (e) => {
  if (!e.target.closest("#searchWrap")) suggestBox.classList.add("hidden");
});
searchInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    const q = searchInput.value.trim();
    if (q) { saveSearch(q); suggestBox.classList.add("hidden"); navigate({ type: "search", q }, routes[routeIdx].type === "search"); }
  }
});
$("#searchClear").onclick = () => {
  searchInput.value = "";
  $("#searchClear").classList.add("hidden");
  renderSuggest();
  searchInput.focus();
};
function renderSuggest(q = "") {
  const matches = (q
    ? searchHistory.filter((h) => h.toLowerCase().includes(q.toLowerCase()))
    : searchHistory).slice(0, 7);
  if (!matches.length || document.activeElement !== searchInput) {
    suggestBox.classList.add("hidden");
    return;
  }
  suggestBox.innerHTML = matches.map((h) => `
    <button class="suggest-row" data-q="${esc(h)}">
      <span class="s-ico">↺</span><span>${esc(h)}</span><span class="s-tag">History</span>
    </button>`).join("");
  suggestBox.classList.remove("hidden");
  suggestBox.querySelectorAll("[data-q]").forEach((b) => b.addEventListener("click", () => {
    searchInput.value = b.dataset.q;
    saveSearch(b.dataset.q);
    suggestBox.classList.add("hidden");
    navigate({ type: "search", q: b.dataset.q }, routes[routeIdx].type === "search");
  }));
}
async function renderSearch(q) {
  view.innerHTML = `<div class="section-title">Results for “${esc(q)}”</div>${skeletons(6)}`;
  try {
    const [songs, artists, albums] = await Promise.all([
      api(`/search?q=${encodeURIComponent(q)}&filter=songs&limit=25`),
      api(`/search?q=${encodeURIComponent(q)}&filter=artists&limit=10`),
      api(`/search?q=${encodeURIComponent(q)}&filter=albums&limit=10`),
    ]);
    if (routes[routeIdx].type !== "search" || routes[routeIdx].q !== q) return;
    register(songs.results, "searchSongs");
    view.innerHTML = `
      ${artists.results.length ? `<div class="section-title">Artists</div>
        <div class="rail">${artists.results.map((a) => cardHtml(a, "artist")).join("")}</div>` : ""}
      <div class="section-title">Songs</div>
      ${songs.results.length ? songs.results.map((t, i) => rowHtml(t, i, { ctx: "searchSongs" })).join("") : `<div class="empty-note">No songs found for “${esc(q)}”.</div>`}
      ${albums.results.length ? `<div class="section-title">Albums</div>
        <div class="rail">${albums.results.map((a) => cardHtml(a, "album")).join("")}</div>` : ""}`;
    artists.results.forEach((a) => trackReg.set(a.id, a));
  } catch {
    if (routes[routeIdx].type !== "search") return;
    view.innerHTML = `<div class="section-title">Results for “${esc(q)}”</div>
      <div class="error-note">Search failed. Check your connection.<button data-retry>Retry</button></div>`;
  }
}

/* ---------------- Artist / Album ---------------- */
async function renderArtist(id) {
  view.innerHTML = skeletons(1, 230) + skeletons(6);
  try {
    const a = await api(`/artist/${encodeURIComponent(id)}`);
    if (routes[routeIdx].type !== "artist" || routes[routeIdx].id !== id) return;
    register(a.songs, "artistSongs");
    view.innerHTML = `
      <div class="page-head round">
        <img src="${esc(a.artwork || "")}" alt="">
        <div>
          <div class="page-kind">Artist</div>
          <div class="page-name">${esc(a.name || "")}</div>
          <div class="page-meta">${a.subscribers ? esc(a.subscribers) + " subscribers" : ""}</div>
        </div>
      </div>
      ${a.description ? `<div class="section-title">About</div><p class="about-text">${esc(a.description)}</p>` : ""}
      <div class="section-title">Top songs</div>
      ${a.songs.length ? a.songs.map((t, i) => rowHtml(t, i, { ctx: "artistSongs" })).join("") : `<div class="empty-note">No songs listed.</div>`}
      ${a.albums.length ? `<div class="section-title">Albums</div><div class="rail">${a.albums.map((x) => cardHtml({ ...x, artist: a.name }, "album")).join("")}</div>` : ""}
      ${a.singles.length ? `<div class="section-title">Singles</div><div class="rail">${a.singles.map((x) => cardHtml({ ...x, artist: a.name }, "album")).join("")}</div>` : ""}
      ${a.related.length ? `<div class="section-title">Fans also like</div><div class="rail">${a.related.map((x) => cardHtml(x, "artist")).join("")}</div>` : ""}`;
    a.related.forEach((x) => trackReg.set(x.id, x));
  } catch {
    if (routes[routeIdx].type !== "artist") return;
    view.innerHTML = `<div class="error-note">Couldn't load this artist.<button data-retry>Retry</button></div>`;
  }
}
async function renderAlbum(id) {
  view.innerHTML = skeletons(1, 230) + skeletons(6);
  try {
    const a = await api(`/album/${encodeURIComponent(id)}`);
    if (routes[routeIdx].type !== "album" || routes[routeIdx].id !== id) return;
    register(a.tracks, "albumTracks");
    view.innerHTML = `
      <div class="page-head">
        <img src="${esc(a.artwork || "")}" alt="">
        <div>
          <div class="page-kind">Album</div>
          <div class="page-name">${esc(a.title || "")}</div>
          <div class="page-meta">${esc(a.artist || "")}${a.year ? " · " + esc(a.year) : ""} · ${a.tracks.length} songs</div>
        </div>
      </div>
      <div class="section-title">Songs</div>
      ${a.tracks.map((t, i) => rowHtml(t, i, { rank: true, ctx: "albumTracks" })).join("")}`;
  } catch {
    if (routes[routeIdx].type !== "album") return;
    view.innerHTML = `<div class="error-note">Couldn't load this album.<button data-retry>Retry</button></div>`;
  }
}

/* ---------------- Library ---------------- */
function renderLibrary() {
  view.innerHTML = `
    <div class="section-title">Library</div>
    <div class="quick-grid">
      <button class="quick-tile" id="libLiked">
        <img src="" alt="" style="background:var(--accent)">
        <span><div class="qt-title">Liked Songs</div><div class="qt-sub">${likes.length} songs</div></span>
      </button>
    </div>
    <div class="section-title">On this device</div>
    <div class="quick-grid">
      <button class="quick-tile" id="libLocal">
        <img src="" alt="" style="background:var(--surface2)">
        <span><div class="qt-title">Local files & downloads</div><div class="qt-sub" id="libLocalCount">From your music folder</div></span>
      </button>
    </div>
    <div class="section-title">Recently played</div>
    ${recentPlays.length ? recentPlays.map((t, i) => rowHtml(t, i, { ctx: "recentLib" })).join("") : `<div class="empty-note">Tracks you play will appear here.</div>`}`;
  register(recentPlays, "recentLib");
  $("#libLiked").onclick = () => navigate({ type: "liked" });
  $("#libLocal").onclick = () => navigate({ type: "local" });
  api("/local").then((d) => {
    const el = $("#libLocalCount");
    if (el) el.textContent = `${d.tracks.length} tracks · ${d.dir}`;
  }).catch(() => {});
}
async function renderLocal(fresh = false) {
  view.innerHTML = `<div class="section-title">On this device</div>${skeletons(5)}`;
  try {
    const data = await api(`/local${fresh ? "?fresh=1" : ""}`);
    if (routes[routeIdx].type !== "local") return;
    register(data.tracks, "localList");
    view.innerHTML = `
      <div class="page-head">
        <img src="" alt="" style="background:var(--surface2);box-shadow:none">
        <div>
          <div class="page-kind">Local library</div>
          <div class="page-name">On this device</div>
          <div class="page-meta">${data.tracks.length} tracks · ${esc(data.dir)}</div>
        </div>
      </div>
      <button class="chip-btn" id="rescanBtn" style="margin:6px 0 4px">↻ Rescan folder</button>
      <div class="section-title">Songs</div>
      ${data.tracks.length ? data.tracks.map((t, i) => rowHtml(t, i, { ctx: "localList" })).join("") : `<div class="empty-note">No audio files found in that folder yet. Downloads you save land in its arzxsh subfolder.</div>`}`;
    $("#rescanBtn").onclick = () => renderLocal(true);
  } catch {
    if (routes[routeIdx].type !== "local") return;
    view.innerHTML = `<div class="section-title">On this device</div>
      <div class="error-note">Couldn't read the local library.<button data-retry>Retry</button></div>`;
  }
}
function renderLiked() {
  register(likes, "likedList");
  view.innerHTML = `
    <div class="page-head">
      <img src="" alt="" style="background:var(--accent);box-shadow:none">
      <div>
        <div class="page-kind">Playlist</div>
        <div class="page-name">Liked Songs</div>
        <div class="page-meta">${likes.length} songs · stored on this machine only</div>
      </div>
    </div>
    <div class="section-title">Songs</div>
    ${likes.length ? likes.map((t, i) => rowHtml(t, i, { ctx: "likedList" })).join("") : `<div class="empty-note">Tap the heart on any song and it lands here.</div>`}`;
}
function renderSettings() {
  view.innerHTML = `
    <div class="section-title">Settings</div>
    <div class="settings-panel">
      <div class="setting-row">
        <div>
          <div class="setting-name">Accent</div>
          <div class="setting-desc">Artwork colors Now Playing. Red or a custom color applies everywhere.</div>
        </div>
        <select id="settingAccentMode" aria-label="Accent mode">
          <option value="artwork">Artwork</option>
          <option value="red">arzxsh Red</option>
          <option value="custom">Custom</option>
        </select>
      </div>
      <div class="setting-row ${settings.accentMode === "custom" ? "" : "hidden"}" id="customAccentRow">
        <div>
          <div class="setting-name">Custom accent</div>
          <div class="setting-desc">Used for the interface and Now Playing.</div>
        </div>
        <input id="settingCustomAccent" type="color" value="${esc(settings.customAccent)}" aria-label="Custom accent color">
      </div>
      <div class="setting-row">
        <div>
          <div class="setting-name">Visualizer</div>
          <div class="setting-desc">Choose the live audio display in the player and Now Playing.</div>
        </div>
        <select id="settingVizStyle" aria-label="Visualizer style">
          <option value="bars">Bars</option>
          <option value="wave">Wave</option>
          <option value="off">Off</option>
        </select>
      </div>
      <div class="setting-row">
        <div>
          <div class="setting-name">Motion</div>
          <div class="setting-desc">Reduced motion removes travel, stagger, karaoke fill and lyric glide.</div>
        </div>
        <select id="settingMotion" aria-label="Motion">
          <option value="full">Full</option>
          <option value="reduced">Reduced</option>
        </select>
      </div>
    </div>
    <div class="settings-note">Settings are stored only in this browser on this machine. ${typeof window.__arzxshUserCss === "string" ? "A user stylesheet was supplied by the desktop shell and is active." : "No user stylesheet was supplied by the desktop shell."}</div>`;
  $("#settingAccentMode").value = settings.accentMode;
  $("#settingVizStyle").value = settings.vizStyle;
  $("#settingMotion").value = settings.motion;
  $("#settingAccentMode").addEventListener("change", (e) => {
    settings.accentMode = e.target.value;
    applySettings();
  });
  $("#settingCustomAccent").addEventListener("input", (e) => {
    settings.customAccent = e.target.value;
    settings.accentMode = "custom";
    applySettings();
  });
  $("#settingVizStyle").addEventListener("change", (e) => {
    settings.vizStyle = e.target.value;
    applySettings();
  });
  $("#settingMotion").addEventListener("change", (e) => {
    settings.motion = e.target.value;
    applySettings();
    if (motionReduced()) {
      document.querySelectorAll(".route-enter").forEach((el) => {
        el.classList.remove("route-enter", "route-in");
        el.style.transitionDelay = "";
      });
    }
  });
}
function renderSideRecent() {
  $("#likedCount").textContent = likes.length ? String(likes.length) : "";
  $("#sideRecent").innerHTML = recentPlays.slice(0, 5).map((t) => `
    <button class="side-recent" data-play="${esc(t.id)}" data-ctx="recentSide">
      <img src="${esc(t.artwork || "")}" alt=""><span>${esc(t.title)}</span>
    </button>`).join("");
  register(recentPlays, "recentSide");
  $("#sideRecent").querySelectorAll("[data-play]").forEach((b) =>
    b.addEventListener("click", () => {
      const list = contexts.get("recentSide") || recentPlays;
      const idx = list.findIndex((t) => t.id === b.dataset.play);
      if (idx >= 0) playFromContext(list, idx, "Recently played");
    }));
}
function refreshLikeUi() {
  $("#likedCount").textContent = likes.length ? String(likes.length) : "";
  document.querySelectorAll("[data-like]").forEach((b) => {
    const on = isLiked(b.dataset.like);
    b.classList.toggle("liked", on);
    b.textContent = on ? "♥" : "♡";
  });
  syncHeartStates();
}

/* ================= playback engine =================
   Two decks alternate. Where Web Audio is available the decks
   route through per-deck gains into one analyser (real
   visualizer data, same-origin media via the /audio proxy);
   otherwise the elements play directly and the visualizer
   idles. Crossfade is 3s; the next track preloads 45s out. */
const CROSSFADE_S = 3;
const player = {
  queue: [], index: -1, current: null, contextName: "",
  playing: false, shuffle: false, repeat: 0, // 0 off, 1 all, 2 one
  radioBusy: false, failCount: 0,
};
function makeDeck() {
  const el = new Audio();
  el.preload = "auto";
  return { el, gain: null, source: null, trackId: null, fading: false };
}
const decks = [makeDeck(), makeDeck()];
let activeDeck = decks[0];
let idleDeck = decks[1];
let actx = null, analyser = null, masterGain = null, webAudioOk = false;

function ensureWebAudio() {
  if (actx) { if (actx.state === "suspended") actx.resume(); return; }
  try {
    actx = new (window.AudioContext || window.webkitAudioContext)();
    analyser = actx.createAnalyser();
    analyser.fftSize = 512;
    masterGain = actx.createGain();
    masterGain.gain.value = Number($("#volume").value) / 100;
    analyser.connect(masterGain);
    masterGain.connect(actx.destination);
    decks.forEach((d) => {
      d.source = actx.createMediaElementSource(d.el);
      d.gain = actx.createGain();
      d.source.connect(d.gain);
      d.gain.connect(analyser);
    });
    webAudioOk = true;
  } catch { webAudioOk = false; }
}
function setDeckLevel(deck, v) {
  if (webAudioOk && deck.gain && actx) {
    deck.gain.gain.setTargetAtTime(v, actx.currentTime, 0.1);
  }
}
function rampDeck(deck, v, seconds) {
  if (webAudioOk && deck.gain && actx) {
    deck.gain.gain.cancelScheduledValues(actx.currentTime);
    deck.gain.gain.setValueAtTime(deck.gain.gain.value, actx.currentTime);
    deck.gain.gain.linearRampToValueAtTime(v, actx.currentTime + seconds);
  }
}

function urlFor(track) {
  return track.source === "local"
    ? `/local/file/${encodeURIComponent(track.id)}`
    : `/audio/${encodeURIComponent(track.id)}`;
}
function playFromContext(list, idx, ctxLabel) {
  if (!list || !list.length || idx < 0) return;
  player.queue = list.slice();
  player.contextName = ctxLabel || "Songs";
  startAt(idx, false);
}
function startAt(idx, fade) {
  const track = player.queue[idx];
  if (!track) return;
  player.index = idx;
  player.current = track;
  player.failCount = 0;
  ensureWebAudio();
  const from = activeDeck;
  const to = fade ? idleDeck : activeDeck;
  if (fade) { activeDeck = to; idleDeck = from; }
  const wasPreloaded = to.trackId === track.id && to.el.src;
  to.trackId = track.id;
  if (!wasPreloaded) to.el.src = urlFor(track);
  to.el.currentTime = 0;
  if (webAudioOk) setDeckLevel(to, 1);
  to.el.play().catch(() => onTrackError(to));
  if (fade) {
    rampDeck(to, 1, CROSSFADE_S);
    rampDeck(from, 0, CROSSFADE_S);
    setTimeout(() => { from.el.pause(); from.trackId = null; }, CROSSFADE_S * 1000 + 120);
  }
  player.playing = true;
  notePlayed(track);
  onTrackChanged(track);
  wakeMotionLoop();
}
function hasNext(auto) {
  if (player.shuffle) return player.queue.length > 1;
  if (player.index < player.queue.length - 1) return true;
  return player.repeat === 1 && player.queue.length > 0;
}
function nextIndex(auto) {
  if (player.repeat === 2 && auto) return player.index;
  if (player.shuffle && player.queue.length > 1) {
    let n = player.index;
    while (n === player.index) n = Math.floor(Math.random() * player.queue.length);
    return n;
  }
  if (player.index < player.queue.length - 1) return player.index + 1;
  if (player.repeat === 1) return 0;
  return -1;
}
async function advance(auto, fade) {
  const n = nextIndex(auto);
  if (n >= 0) { startAt(n, fade); return; }
  if (auto) {
    // Queue exhausted: continue with radio built on the last
    // track (the phone app's radio endpoint), once.
    if (player.radioBusy || !player.current) return stopPlayback();
    player.radioBusy = true;
    try {
      const r = await api(`/radio?video_id=${encodeURIComponent(player.current.id)}`);
      const fresh = (r.results || []).filter((t) => !player.queue.some((q) => q.id === t.id));
      if (fresh.length) {
        register(fresh, null);
        player.queue.push(...fresh);
        player.radioBusy = false;
        startAt(player.index + 1, fade);
        toast("Radio continues the queue");
        return;
      }
    } catch { /* fall through to stop */ }
    player.radioBusy = false;
  }
  stopPlayback();
}
function stopPlayback() {
  decks.forEach((d) => { d.el.pause(); });
  player.playing = false;
  syncPlayButtons();
  markCurrentRows();
}
function togglePlay() {
  if (!player.current) return;
  ensureWebAudio();
  if (player.playing) {
    activeDeck.el.pause();
    player.playing = false;
  } else {
    activeDeck.el.play().catch(() => onTrackError(activeDeck));
    player.playing = true;
  }
  syncPlayButtons();
  markCurrentRows();
  wakeMotionLoop(500);
}
function onTrackError(deck) {
  if (deck !== activeDeck) return;
  player.failCount++;
  if (player.failCount <= 2) {
    toast("Couldn't load this track. Skipping.");
    advance(true, false);
  } else {
    toast("Playback stopped. The stream could not be loaded.");
    stopPlayback();
  }
}
decks.forEach((deck) => {
  deck.el.addEventListener("ended", () => {
    if (deck !== activeDeck) return;
    if (deck.fading) return;
    advance(true, false);
  });
  deck.el.addEventListener("error", () => onTrackError(deck));
  deck.el.addEventListener("playing", () => {
    if (deck !== activeDeck) return;
    player.failCount = 0;
    player.playing = true;
    syncPlayButtons();
    markCurrentRows();
    wakeMotionLoop();
  });
  deck.el.addEventListener("pause", () => {
    if (deck === activeDeck && !deck.fading) {
      player.playing = false;
      syncPlayButtons();
      markCurrentRows();
      wakeMotionLoop(500);
    }
  });
  deck.el.addEventListener("seeking", () => { if (deck === activeDeck) wakeMotionLoop(250); });
  deck.el.addEventListener("seeked", () => { if (deck === activeDeck) wakeMotionLoop(250); });
  deck.el.addEventListener("timeupdate", () => {
    if (deck !== activeDeck) return;
    const el = deck.el;
    if (!el.duration) return;
    wakeMotionLoop();
    const remaining = el.duration - el.currentTime;
    // Preload the next track into the idle deck.
    if (remaining <= 45 && remaining > CROSSFADE_S + 1) {
      const n = nextIndex(true);
      if (n >= 0 && player.repeat !== 2) {
        const nt = player.queue[n];
        if (nt && idleDeck.trackId !== nt.id) {
          idleDeck.trackId = nt.id;
          idleDeck.el.src = urlFor(nt);
          idleDeck.el.preload = "auto";
        }
      }
    }
    // Crossfade window.
    if (CROSSFADE_S > 0 && remaining <= CROSSFADE_S && remaining > 0.15 &&
        player.repeat !== 2 && hasNext(true) && !deck.fading) {
      deck.fading = true;
      advance(true, true);
      setTimeout(() => { deck.fading = false; }, CROSSFADE_S * 1000 + 200);
    }
  });
});

/* ---------------- player UI sync ---------------- */
const seekInputs = [$("#seek"), $("#npSeek")];
const seekElapsedEls = [$("#tElapsed"), $("#npElapsed")];
const seekTotalEls = [$("#tTotal"), $("#npTotal")];
let scrubbing = false;
let scrubInput = null;
let lastSeekSecond = -1;
let lastSeekDuration = -1;
const clamp01 = (v) => Math.max(0, Math.min(1, Number.isFinite(v) ? v : 0));
function bufferedRatio(el, at = el.currentTime || 0) {
  if (!el.duration || !el.buffered) return 0;
  try {
    let end = 0;
    for (let i = 0; i < el.buffered.length; i++) {
      const start = el.buffered.start(i);
      const stop = el.buffered.end(i);
      if (at >= start - 0.25 && at <= stop + 0.25) { end = stop; break; }
      if (stop > end && stop <= at) end = stop;
    }
    if (!end && el.buffered.length) end = el.buffered.end(el.buffered.length - 1);
    return clamp01(end / el.duration);
  } catch { return 0; }
}
function paintSeekRatio(ratio, buffered = 0) {
  const fill = clamp01(ratio);
  seekInputs.forEach((s) => {
    s.style.setProperty("--fill", `${(fill * 100).toFixed(2)}%`);
    s.style.setProperty("--buffered", `${(clamp01(Math.max(buffered, fill)) * 100).toFixed(2)}%`);
    if (!scrubbing || s !== scrubInput) {
      const v = Math.round(fill * 1000);
      if (Math.abs(Number(s.value) - v) >= 0.25) s.value = v;
    }
  });
}
function paintRangeFill(input) {
  const min = Number(input.min || 0);
  const max = Number(input.max || 100);
  const ratio = clamp01((Number(input.value) - min) / (max - min || 1));
  input.style.setProperty("--fill", `${(ratio * 100).toFixed(2)}%`);
  input.style.setProperty("--buffered", `${(ratio * 100).toFixed(2)}%`);
}
function setSeekLabels(cur, dur) {
  const sec = Math.floor(Math.max(0, cur));
  if (sec !== lastSeekSecond) {
    lastSeekSecond = sec;
    const text = fmt(cur);
    seekElapsedEls.forEach((el) => { el.textContent = text; });
  }
  const durSec = Math.floor(Math.max(0, dur || 0));
  if (durSec !== lastSeekDuration) {
    lastSeekDuration = durSec;
    const text = fmt(dur);
    seekTotalEls.forEach((el) => { el.textContent = text; });
  }
}
function finishScrub(input, commit = true) {
  if (!scrubbing) return;
  const el = activeDeck.el;
  if (commit && el.duration) el.currentTime = (Number(input.value) / 1000) * el.duration;
  scrubbing = false;
  scrubInput = null;
  input.classList.remove("scrubbing");
  wakeMotionLoop(250);
}
seekInputs.forEach((s) => {
  s.addEventListener("pointerdown", () => {
    scrubbing = true;
    scrubInput = s;
    s.classList.add("scrubbing");
    wakeMotionLoop();
  });
  s.addEventListener("pointerup", () => finishScrub(s));
  s.addEventListener("pointercancel", () => finishScrub(s, false));
  s.addEventListener("input", () => {
    scrubbing = true;
    scrubInput = s;
    s.classList.add("scrubbing");
    const el = activeDeck.el;
    if (el.duration) {
      const ratio = Number(s.value) / 1000;
      paintSeekRatio(ratio, bufferedRatio(el, ratio * el.duration));
      setSeekLabels(ratio * el.duration, el.duration);
    }
    wakeMotionLoop();
  });
  s.addEventListener("change", () => finishScrub(s));
});
function syncSeek(cur, dur) {
  if (!dur) return;
  if (scrubbing && scrubInput) {
    const ratio = Number(scrubInput.value) / 1000;
    paintSeekRatio(ratio, bufferedRatio(activeDeck.el, ratio * dur));
    setSeekLabels(ratio * dur, dur);
    return;
  }
  paintSeekRatio(cur / dur, bufferedRatio(activeDeck.el, cur));
  setSeekLabels(cur, dur);
}
function syncPlayButtons() {
  const glyph = player.playing ? "⏸" : "▶";
  $("#playBtn").textContent = glyph;
  $("#npPlay").textContent = glyph;
  document.title = player.current
    ? `${player.current.title} · ${player.current.artist} — arzxsh`
    : "arzxsh for Linux";
}
function syncHeartStates() {
  const t = player.current;
  const on = t ? isLiked(t.id) : false;
  $("#pHeart").textContent = on ? "♥" : "♡";
  $("#pHeart").style.color = on ? "var(--accent)" : "";
  $("#npHeart").textContent = on ? "♥ Liked" : "♡ Like";
  $("#npHeart").classList.toggle("liked", on);
}
function markCurrentRows() {
  const id = player.current ? player.current.id : null;
  document.querySelectorAll(".row[data-play]").forEach((r) => {
    const cur = r.dataset.play === id;
    r.classList.toggle("current", cur);
    r.classList.toggle("playing", cur && player.playing);
  });
  document.querySelectorAll(".queue-row").forEach((r) => {
    r.classList.toggle("current", r.dataset.qid === id);
  });
}
function syncNpDownload() {
  const btn = $("#npDownload");
  if (!btn || !player.current) return;
  if (player.current.source === "local") { btn.classList.add("hidden"); return; }
  btn.classList.remove("hidden");
  const saved = downloads[player.current.id];
  btn.textContent = saved ? "✓ Saved" : "⬇ Download";
  btn.classList.toggle("liked", !!saved);
}
const npArtLayers = [$("#npArtBack"), $("#npArt")];
let npArtActive = 1;
const npBackdropLayers = [$("#npBackdropBack"), $("#npBackdrop")];
let npBackdropActive = 1;
function crossfadeNpArt(src) {
  const outgoing = npArtLayers[npArtActive];
  const incoming = npArtLayers[1 - npArtActive];
  if (!incoming || !outgoing) return;
  if (incoming.getAttribute("src") === src && incoming.classList.contains("active")) return;
  if (outgoing.getAttribute("src") === src && outgoing.classList.contains("active")) return;
  incoming.src = src;
  incoming.classList.add("active");
  outgoing.classList.remove("active");
  npArtActive = 1 - npArtActive;
}
function crossfadeNpBackdrop(src) {
  const outgoing = npBackdropLayers[npBackdropActive];
  const incoming = npBackdropLayers[1 - npBackdropActive];
  if (!incoming || !outgoing) return;
  const next = src ? cssUrl(src) : "";
  if (incoming.style.backgroundImage === next && incoming.classList.contains("active")) return;
  if (outgoing.style.backgroundImage === next && outgoing.classList.contains("active")) return;
  incoming.style.backgroundImage = next;
  incoming.classList.add("active");
  outgoing.classList.remove("active");
  npBackdropActive = 1 - npBackdropActive;
}
function onTrackChanged(track) {
  $("#player").classList.remove("hidden");
  document.body.classList.remove("no-player");
  $("#pArt").src = track.artwork || "";
  $("#pTitle").textContent = track.title;
  $("#pArtist").textContent = track.artist;
  crossfadeNpArt(track.artwork || "");
  $("#npTitle").textContent = track.title;
  $("#npArtist").textContent = track.artist;
  crossfadeNpBackdrop(track.artwork && /^https:\/\//.test(track.artwork) ? track.artwork : "");
  $("#npContext").textContent = player.contextName || "Now Playing";
  applyArtworkAccent(track);
  const npLeft = document.querySelector(".np-left");
  npLeft.classList.remove("np-swap");
  void npLeft.offsetWidth;
  npLeft.classList.add("np-swap");
  setTimeout(() => npLeft.classList.remove("np-swap"), 220);
  syncPlayButtons();
  syncHeartStates();
  syncNpDownload();
  markCurrentRows();
  fitVizEntries();
  renderQueue();
  loadAbout(track);
  resetLyrics(track);
  if (navigator.mediaSession) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: track.title, artist: track.artist, album: track.album || "",
      artwork: track.artwork ? [{ src: track.artwork, sizes: "512x512" }] : [],
    });
  }
}

/* transport wiring (bar + NP share the engine) */
$("#playBtn").onclick = togglePlay;
$("#npPlay").onclick = togglePlay;
$("#nextBtn").onclick = () => { const n = nextIndex(false); if (n >= 0) startAt(n, false); };
$("#npNext").onclick = () => { const n = nextIndex(false); if (n >= 0) startAt(n, false); };
$("#prevBtn").onclick = prevTrack;
$("#npPrev").onclick = prevTrack;
function prevTrack() {
  if (activeDeck.el.currentTime > 3) { activeDeck.el.currentTime = 0; return; }
  if (player.index > 0) startAt(player.index - 1, false);
  else activeDeck.el.currentTime = 0;
}
function cycleRepeat() {
  player.repeat = (player.repeat + 1) % 3;
  const label = ["↻", "↻¹", "↻²"][player.repeat];
  [$("#repeatBtn"), $("#npRepeat")].forEach((b) => {
    b.textContent = label;
    b.classList.toggle("on", player.repeat > 0);
  });
  toast(["Repeat off", "Repeat all", "Repeat one"][player.repeat]);
}
$("#repeatBtn").onclick = cycleRepeat;
$("#npRepeat").onclick = cycleRepeat;
function toggleShuffle() {
  player.shuffle = !player.shuffle;
  [$("#shuffleBtn"), $("#npShuffle")].forEach((b) => b.classList.toggle("on", player.shuffle));
  toast(player.shuffle ? "Shuffle on" : "Shuffle off");
}
$("#shuffleBtn").onclick = toggleShuffle;
$("#npShuffle").onclick = toggleShuffle;
$("#pHeart").onclick = () => { if (player.current) toggleLike(player.current); };
$("#npHeart").onclick = () => { if (player.current) toggleLike(player.current); };
$("#volume").value = store.get("arzxsh.volume", 80);
paintRangeFill($("#volume"));
$("#volume").addEventListener("input", () => {
  const v = Number($("#volume").value);
  paintRangeFill($("#volume"));
  store.set("arzxsh.volume", v);
  if (webAudioOk && masterGain) masterGain.gain.value = v / 100;
  else decks.forEach((d) => { d.el.volume = v / 100; });
});
if (navigator.mediaSession) {
  navigator.mediaSession.setActionHandler("play", togglePlay);
  navigator.mediaSession.setActionHandler("pause", togglePlay);
  navigator.mediaSession.setActionHandler("nexttrack", () => { const n = nextIndex(false); if (n >= 0) startAt(n, false); });
  navigator.mediaSession.setActionHandler("previoustrack", prevTrack);
}

/* ---------------- Now Playing panels ---------------- */
let npCloseTimer = null;
let npMorphClone = null;
let npMorphTimer = null;
function finishArtworkMorph() {
  clearTimeout(npMorphTimer);
  npMorphTimer = null;
  if (npMorphClone) npMorphClone.remove();
  npMorphClone = null;
  npArtLayers.forEach((el) => el && el.classList.remove("morph-hidden"));
}
function beginArtworkMorph(opening) {
  if (motionReduced() || !player.current || !player.current.artwork) return false;
  const npArt = npArtLayers[npArtActive];
  const barArt = $("#pArt");
  const fromEl = opening ? barArt : npArt;
  const toEl = opening ? npArt : barArt;
  if (!fromEl || !toEl) return false;
  const from = fromEl.getBoundingClientRect();
  const rawTo = toEl.getBoundingClientRect();
  if (!from.width || !from.height || !rawTo.width || !rawTo.height) return false;
  const to = {
    left: rawTo.left,
    top: rawTo.top - (opening ? 16 : 0),
    width: rawTo.width,
    height: rawTo.height,
  };
  finishArtworkMorph();
  const clone = document.createElement("img");
  clone.src = player.current.artwork;
  clone.alt = "";
  clone.className = "artwork-morph-clone";
  clone.style.left = `${from.left}px`;
  clone.style.top = `${from.top}px`;
  clone.style.width = `${from.width}px`;
  clone.style.height = `${from.height}px`;
  clone.style.borderRadius = getComputedStyle(toEl).borderRadius;
  document.body.appendChild(clone);
  npMorphClone = clone;
  npArt.classList.add("morph-hidden");
  void clone.offsetWidth;
  clone.style.transform = `translate(${to.left - from.left}px, ${to.top - from.top}px) scale(${to.width / from.width}, ${to.height / from.height})`;
  npMorphTimer = setTimeout(finishArtworkMorph, 390);
  return true;
}
function openNp(showQueue = false) {
  if (!player.current) return;
  clearTimeout(npCloseTimer);
  const np = $("#nowPlaying");
  const wasHidden = np.classList.contains("hidden");
  np.classList.remove("hidden");
  void np.offsetWidth; // start the enter transition from the pre-visible state
  if (wasHidden) beginArtworkMorph(true);
  np.classList.add("np-visible");
  if (showQueue) { $("#queueCard").classList.remove("hidden"); $("#npQueueBtn").classList.add("on"); }
  renderQueue();
  fitVizEntries();
  updateLyricsSync(activeDeck.el.currentTime || 0, true);
  wakeMotionLoop(450);
}
function closeNp() {
  const np = $("#nowPlaying");
  if (!np.classList.contains("hidden")) beginArtworkMorph(false);
  np.classList.remove("np-visible");
  clearTimeout(npCloseTimer);
  npCloseTimer = setTimeout(() => {
    np.classList.add("hidden");
    if (!npMorphClone) npArtLayers.forEach((el) => el && el.classList.remove("morph-hidden"));
  }, 300);
}
$("#expandBtn").onclick = () => openNp(false);
$("#playerTrack").onclick = () => openNp(false);
$("#queueBtn").onclick = () => openNp(true);
$("#npClose").onclick = closeNp;
$("#npQueueBtn").onclick = () => {
  const card = $("#queueCard");
  card.classList.toggle("hidden");
  $("#npQueueBtn").classList.toggle("on", !card.classList.contains("hidden"));
  renderQueue();
};
function renderQueue() {
  const box = $("#queueList");
  if (!box) return;
  box.innerHTML = player.queue.map((t, i) => `
    <button class="queue-row ${player.current && t.id === player.current.id ? "current" : ""}" data-qid="${esc(t.id)}" data-qidx="${i}">
      <img src="${esc(t.artwork || "")}" alt="">
      <span class="q-main"><div class="q-title">${esc(t.title)}</div><div class="q-sub">${esc(t.artist)}</div></span>
      <span class="row-dur">${fmt(t.duration_s)}</span>
    </button>`).join("");
  $("#queueNote").textContent = player.queue.length
    ? `${player.queue.length} tracks · radio continues when the queue ends`
    : "";
  box.querySelectorAll("[data-qidx]").forEach((b) =>
    b.addEventListener("click", () => startAt(Number(b.dataset.qidx), false)));
}

/* About the artist — same content as the phone's 0.6.17 card. */
const aboutCache = new Map();
let aboutKey = null;
async function loadAbout(track) {
  const card = $("#aboutCard");
  const id = track.artist_id;
  if (!id) { aboutKey = null; card.classList.add("hidden"); return; }
  if (id === aboutKey) return;
  aboutKey = id;
  card.classList.add("hidden");
  try {
    if (!aboutCache.has(id)) aboutCache.set(id, await api(`/artist/${encodeURIComponent(id)}`));
    if (aboutKey !== id) return;
    const a = aboutCache.get(id);
    $("#aboutArt").src = a.artwork || "";
    $("#aboutName").textContent = a.name || track.artist;
    $("#aboutSubs").textContent = a.subscribers ? `${a.subscribers} subscribers` : "";
    const bio = a.description || "";
    $("#aboutBio").textContent = bio;
    $("#aboutBio").style.display = bio ? "" : "none";
    card.classList.remove("hidden");
    card.querySelector(".about-row").onclick = () => { closeNp(); navigate({ type: "artist", id }); };
  } catch { if (aboutKey === id) card.classList.add("hidden"); }
}

/* Artwork accent — Now Playing takes its tint from the current
   cover, the way the phone app does: the value lands in the
   --np-accent custom property (registered in CSS so it crossfades
   between tracks). Sampling runs on a small canvas; artwork is
   same-origin (local art endpoint) or CORS-enabled
   (googleusercontent). Any failure keeps the previous accent. */
const accentCache = new Map();
let accentTrackId = null;
function applyArtworkAccent(track) {
  accentTrackId = track.id;
  const np = $("#nowPlaying");
  if (settings.accentMode === "red") {
    np.style.setProperty("--np-accent", DEFAULT_ACCENT);
    vizNpColor = DEFAULT_ACCENT;
    return;
  }
  if (settings.accentMode === "custom") {
    np.style.setProperty("--np-accent", settings.customAccent);
    vizNpColor = settings.customAccent;
    return;
  }
  if (!track.artwork) {
    np.style.removeProperty("--np-accent");
    vizNpColor = DEFAULT_ACCENT;
    return;
  }
  if (accentCache.has(track.id)) {
    const rgb = accentCache.get(track.id);
    np.style.setProperty("--np-accent", rgb);
    vizNpColor = rgb;
    return;
  }
  const img = new Image();
  img.crossOrigin = "anonymous";
  img.onload = () => {
    let rgb = null;
    try { rgb = sampleAccent(img); } catch { rgb = null; }
    if (!rgb) return;
    accentCache.set(track.id, rgb);
    if (accentTrackId === track.id && settings.accentMode === "artwork") {
      np.style.setProperty("--np-accent", rgb);
      vizNpColor = rgb;
    }
  };
  img.onerror = () => {};
  img.src = track.artwork;
}
function sampleAccent(img) {
  const S = 28;
  const cv = document.createElement("canvas");
  cv.width = S; cv.height = S;
  const ctx = cv.getContext("2d", { willReadFrequently: true });
  ctx.drawImage(img, 0, 0, S, S);
  const data = ctx.getImageData(0, 0, S, S).data;
  const buckets = new Map();
  for (let i = 0; i < data.length; i += 4) {
    const r = data[i], g = data[i + 1], b = data[i + 2];
    if (data[i + 3] < 200) continue;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
    if (mx < 40 || mx > 245 || mx - mn < 28) continue; // near-black, near-white, grey
    const key = `${Math.round(r / 32)}-${Math.round(g / 32)}-${Math.round(b / 32)}`;
    const w = (mx - mn) * (1 - Math.abs(mx - 150) / 300);
    const cur = buckets.get(key) || { r: 0, g: 0, b: 0, w: 0 };
    cur.r += r * w; cur.g += g * w; cur.b += b * w; cur.w += w;
    buckets.set(key, cur);
  }
  let best = null;
  buckets.forEach((v) => { if (!best || v.w > best.w) best = v; });
  if (!best || !best.w) return null;
  let r = best.r / best.w, g = best.g / best.w, b = best.b / best.w;
  const mean = (r + g + b) / 3; // saturation push, then lift the peak
  r = mean + (r - mean) * 1.35; g = mean + (g - mean) * 1.35; b = mean + (b - mean) * 1.35;
  const peak = Math.max(r, g, b);
  if (peak > 0) { const lift = 212 / peak; r *= lift; g *= lift; b *= lift; }
  const clamp = (v) => Math.max(0, Math.min(255, Math.round(v)));
  return `rgb(${clamp(r)}, ${clamp(g)}, ${clamp(b)})`;
}

/* Lyrics — synced lines when a source has timestamps (LRCLIB or
   the backend's timed ytmusicapi page), plain text otherwise.
   The sync view follows the active deck's currentTime from the
   same timeupdate hook that drives the seek bar; clicking a line
   seeks to it. */
const lyricsCache = new Map();
let lyricsTrackId = null;
const syncState = { lines: [], els: [], active: -1, trackId: null, karaoke: -1 };
function resetLyrics(track) {
  lyricsTrackId = track.id;
  clearSyncedLyrics();
  $("#lyricsCard").classList.add("hidden");
  $("#lyricsBtn").classList.remove("on");
}
function clearSyncedLyrics() {
  syncState.lines = [];
  syncState.els = [];
  syncState.active = -1;
  syncState.trackId = null;
  syncState.karaoke = -1;
  const box = $("#lyricsSync");
  box.classList.add("hidden");
  const inner = $("#lyricsSyncInner");
  inner.innerHTML = "";
  inner.style.transform = "";
  $("#lyricsText").classList.remove("hidden");
}
function showPlainLyrics(text) {
  clearSyncedLyrics();
  $("#lyricsText").textContent = text;
}
function buildSyncedLyrics(lines) {
  clearSyncedLyrics();
  syncState.lines = lines;
  syncState.trackId = lyricsTrackId;
  const inner = $("#lyricsSyncInner");
  inner.innerHTML = lines.map((l, i) =>
    `<div class="sync-line" data-li="${i}">${esc(l.text)}</div>`).join("");
  syncState.els = [...inner.children];
  syncState.els.forEach((el, i) => el.addEventListener("click", () => {
    activeDeck.el.currentTime = lines[i].t;
    updateLyricsSync(lines[i].t, true);
  }));
  $("#lyricsText").classList.add("hidden");
  $("#lyricsSync").classList.remove("hidden");
  updateLyricsSync(activeDeck.el.currentTime || 0, true);
}
function updateLyricsSync(now, force = false) {
  const lines = syncState.lines;
  if (!lines.length || syncState.trackId !== lyricsTrackId) return;
  let idx = -1;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].t <= now + 0.05) idx = i; else break;
  }
  const changed = idx !== syncState.active || force;
  if (changed) {
    syncState.active = idx;
    syncState.karaoke = -1;
    syncState.els.forEach((el, i) => {
      const distance = idx >= 0 ? Math.abs(i - idx) : 99;
      el.classList.toggle("active", i === idx);
      el.classList.toggle("passed", idx >= 0 && i < idx);
      el.classList.toggle("depth-1", distance === 1);
      el.classList.toggle("depth-2", distance === 2);
      el.classList.toggle("depth-3", distance === 3);
      el.classList.toggle("depth-far", idx >= 0 && distance >= 4);
      if (i !== idx) el.style.removeProperty("--karaoke");
    });
    const box = $("#lyricsSync");
    const el = syncState.els[Math.max(0, idx)];
    if (el && box.clientHeight) {
      const y = box.clientHeight / 2 - (el.offsetTop + el.offsetHeight / 2);
      $("#lyricsSyncInner").style.transform = `translateY(${y}px)`;
    }
  }
  if (idx < 0) return;
  const activeEl = syncState.els[idx];
  if (!activeEl) return;
  if (motionReduced()) {
    if (syncState.karaoke !== 1) {
      activeEl.style.setProperty("--karaoke", "100%");
      syncState.karaoke = 1;
    }
    return;
  }
  const start = lines[idx].t;
  const next = idx + 1 < lines.length ? lines[idx + 1].t : start + 5;
  const duration = Math.max(0.8, next - start);
  const pct = clamp01((now - start) / duration);
  if (force || Math.abs(pct - syncState.karaoke) >= 0.004) {
    activeEl.style.setProperty("--karaoke", `${(pct * 100).toFixed(2)}%`);
    syncState.karaoke = pct;
  }
}
$("#npDownload").onclick = () => {
  if (player.current && player.current.source !== "local" && !downloads[player.current.id]) doDownload(player.current);
  else if (player.current) toast("Already saved for offline");
};
$("#lyricsBtn").onclick = async () => {
  if (player.current && player.current.source === "local") {
    const card = $("#lyricsCard");
    card.classList.remove("hidden");
    $("#lyricsBtn").classList.add("on");
    showPlainLyrics("Lyrics are not available for local files.");
    $("#lyricsSource").textContent = "";
    return;
  }
  const card = $("#lyricsCard");
  const show = card.classList.contains("hidden");
  card.classList.toggle("hidden", !show);
  $("#lyricsBtn").classList.toggle("on", show);
  if (!show || !player.current) return;
  const id = player.current.id;
  if (lyricsCache.has(id)) { fillLyrics(lyricsCache.get(id)); return; }
  showPlainLyrics("Loading…");
  $("#lyricsSource").textContent = "";
  try {
    const r = await api(`/lyrics/${encodeURIComponent(id)}`);
    lyricsCache.set(id, r);
    if (lyricsTrackId === id) fillLyrics(r);
  } catch {
    if (lyricsTrackId === id) showPlainLyrics("Lyrics could not be loaded.");
  }
};
function fillLyrics(r) {
  if (r.synced && r.synced.length) buildSyncedLyrics(r.synced);
  else showPlainLyrics(r.lyrics || "No lyrics found for this track.");
  $("#lyricsSource").textContent = r.source ? `Source: ${r.source}` : "";
}

/* ---------------- visualizer + smoothness engine ---------------- */
const vizEntries = [
  { canvas: $("#viz"), ctx: $("#viz").getContext("2d"), bars: 56, np: true, w: 0, h: 0, levels: null, peaks: null },
  { canvas: $("#vizMini"), ctx: $("#vizMini").getContext("2d"), bars: 14, np: false, w: 0, h: 0, levels: null, peaks: null },
];
vizEntries.forEach((entry) => {
  entry.levels = new Float32Array(entry.bars);
  entry.peaks = new Float32Array(entry.bars);
});
function fitVizEntry(entry) {
  const w = entry.canvas.clientWidth || 0;
  const h = entry.canvas.clientHeight || 0;
  if (!w || !h) { entry.w = 0; entry.h = 0; return; }
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const pw = Math.round(w * dpr);
  const ph = Math.round(h * dpr);
  if (entry.canvas.width !== pw || entry.canvas.height !== ph) {
    entry.canvas.width = pw;
    entry.canvas.height = ph;
  }
  entry.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  entry.w = w;
  entry.h = h;
}
function fitVizEntries() { vizEntries.forEach(fitVizEntry); }
if ("ResizeObserver" in window) {
  const vizResizeObserver = new ResizeObserver((entries) => {
    entries.forEach((resizeEntry) => {
      const entry = vizEntries.find((v) => v.canvas === resizeEntry.target);
      if (entry) fitVizEntry(entry);
    });
  });
  vizEntries.forEach((entry) => vizResizeObserver.observe(entry.canvas));
}
window.addEventListener("resize", fitVizEntries);
function visualizerHasEnergy() {
  return vizEntries.some((entry) =>
    entry.levels.some((v) => v > 0.012) || entry.peaks.some((v) => v > 0.012));
}
function drawVisualizers(dt, ts) {
  if (settings.vizStyle === "off") return;
  const npOpen = !$("#nowPlaying").classList.contains("hidden");
  const playerVisible = !$("#player").classList.contains("hidden");
  if (!npOpen && !playerVisible) return;
  const live = webAudioOk && analyser && player.playing;
  let freq = null;
  let wave = null;
  if (live && settings.vizStyle === "bars") {
    freq = new Uint8Array(analyser.frequencyBinCount);
    analyser.getByteFrequencyData(freq);
  } else if (live && settings.vizStyle === "wave") {
    wave = new Uint8Array(analyser.fftSize);
    analyser.getByteTimeDomainData(wave);
  }
  vizEntries.forEach((entry) => {
    if (entry.np && !npOpen) return;
    if (!entry.np && !playerVisible) return;
    if (!entry.w || !entry.h) return;
    const { ctx, w, h } = entry;
    const color = entry.np ? vizNpColor : vizMainColor;
    ctx.clearRect(0, 0, w, h);
    if (settings.vizStyle === "wave") {
      ctx.beginPath();
      ctx.strokeStyle = color;
      ctx.lineWidth = 2;
      ctx.globalAlpha = live || !webAudioOk ? 0.9 : 0.38;
      const points = 96;
      for (let i = 0; i <= points; i++) {
        const x = (i / points) * w;
        let v = 0;
        if (wave) v = (wave[Math.floor((i / points) * (wave.length - 1))] - 128) / 128;
        else if (!webAudioOk) v = Math.sin(i * 0.32 + ts / 280) * 0.08;
        const y = h / 2 + v * (h * 0.46);
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      }
      ctx.stroke();
      ctx.globalAlpha = 1;
      return;
    }
    const bw = w / entry.bars;
    ctx.fillStyle = color;
    for (let i = 0; i < entry.bars; i++) {
      let target = 0;
      if (freq) target = freq[Math.floor((i / entry.bars) * freq.length * 0.72)] / 255;
      else if (!webAudioOk) target = 0.06;
      const level = entry.levels[i];
      entry.levels[i] = target > level
        ? level + (target - level) * 0.58
        : level + (target - level) * 0.12;
      const smoothed = entry.levels[i];
      entry.peaks[i] = smoothed > entry.peaks[i]
        ? smoothed
        : Math.max(smoothed, entry.peaks[i] - dt * 0.22);
      const bh = Math.max(2, smoothed * h);
      ctx.globalAlpha = 0.5 + smoothed * 0.5;
      ctx.fillRect(i * bw + 1, h - bh, Math.max(1, bw - 2), bh);
      const peakY = Math.max(0, h - entry.peaks[i] * h - 2);
      ctx.globalAlpha = 0.88;
      ctx.fillRect(i * bw + 1, peakY, Math.max(1, bw - 2), 2);
    }
    ctx.globalAlpha = 1;
  });
}
const frameState = { id: 0, running: false, lastTs: 0, until: 0 };
function wakeMotionLoop(ms = 0) {
  if (ms) frameState.until = Math.max(frameState.until, performance.now() + ms);
  if (frameState.running || document.hidden || !player.current) return;
  frameState.running = true;
  frameState.id = requestAnimationFrame(motionFrame);
}
function stopMotionLoop() {
  if (frameState.id) cancelAnimationFrame(frameState.id);
  frameState.id = 0;
  frameState.running = false;
  frameState.lastTs = 0;
}
function motionFrame(ts) {
  frameState.id = 0;
  frameState.running = false;
  if (document.hidden || !player.current) { frameState.lastTs = 0; return; }
  const dt = frameState.lastTs ? Math.min(0.05, (ts - frameState.lastTs) / 1000) : 1 / 60;
  frameState.lastTs = ts;
  const el = activeDeck.el;
  const now = el.currentTime || 0;
  const dur = el.duration || 0;
  if (dur) syncSeek(now, dur);
  if (!$("#lyricsSync").classList.contains("hidden")) updateLyricsSync(now);
  drawVisualizers(dt, ts);
  if (player.playing || scrubbing || ts < frameState.until || visualizerHasEnergy()) {
    wakeMotionLoop();
  } else {
    frameState.lastTs = 0;
  }
}
document.addEventListener("visibilitychange", () => {
  if (document.hidden) stopMotionLoop();
  else wakeMotionLoop(300);
});

/* ---------------- keyboard ---------------- */
document.addEventListener("keydown", (e) => {
  const tag = e.target.tagName;
  if (tag === "INPUT" && e.target.type === "text") return;
  if (!player.current) return;
  if (e.code === "Space" && tag !== "BUTTON") { e.preventDefault(); togglePlay(); }
  else if (e.key === "ArrowRight" && tag !== "INPUT") activeDeck.el.currentTime += 10;
  else if (e.key === "ArrowLeft" && tag !== "INPUT") activeDeck.el.currentTime -= 10;
  else if (e.key === "Escape") closeNp();
});

/* ---------------- init ---------------- */
document.body.classList.add("no-player");
applySettings(false);
paintSeekRatio(0, 0);
renderSideRecent();
navigate({ type: "home" }, true);
/* Debug/testing handle, gated (audit 2026-11-11): only exposed when
   the page was opened with ?debug=1, so shipped sessions do not hand
   playback control to any script that reaches this origin. */
if (new URLSearchParams(location.search).has("debug")) {
  window.__arzxsh = { player, decks };
}
api("/local").then((d) => {
  const present = new Set(d.tracks.map((t) => t.id));
  let changed = false;
  Object.keys(downloads).forEach((k) => {
    if (!present.has(downloads[k])) { delete downloads[k]; changed = true; }
  });
  if (changed) store.set("arzxsh.downloads", downloads);
}).catch(() => {});
