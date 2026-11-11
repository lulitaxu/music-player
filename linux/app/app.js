"use strict";
/* arzxsh for Linux — vanilla SPA over the ytmusic-backend API.
   Mirrors the phone app: Home / Search / Library, dual-deck
   playback with crossfade, radio continuation, liked songs and
   history kept in localStorage (on this machine only). */

const $ = (sel) => document.querySelector(sel);
const view = $("#view");

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
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add("hidden"), 3200);
}

/* ---------------- persistent local state ---------------- */
const store = {
  get(key, fallback) {
    try { return JSON.parse(localStorage.getItem(key)) ?? fallback; }
    catch { return fallback; }
  },
  set(key, value) { localStorage.setItem(key, JSON.stringify(value)); },
};
let likes = store.get("arzxsh.likes", []);           // full track objects
let recentPlays = store.get("arzxsh.recent", []);    // full track objects
let searchHistory = store.get("arzxsh.searchHistory", []);
let downloads = store.get("arzxsh.downloads", {}); // videoId -> local track id
const isLiked = (id) => likes.some((t) => t.id === id);
function toggleLike(track) {
  if (isLiked(track.id)) likes = likes.filter((t) => t.id !== track.id);
  else likes = [track, ...likes.filter((t) => t.id !== track.id)];
  store.set("arzxsh.likes", likes);
  refreshLikeUi();
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
}
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
    analyser.fftSize = 128;
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
  deck.el.addEventListener("playing", () => { if (deck === activeDeck) player.failCount = 0; });
  deck.el.addEventListener("pause", () => {
    if (deck === activeDeck && !deck.fading) { player.playing = false; syncPlayButtons(); markCurrentRows(); }
  });
  deck.el.addEventListener("timeupdate", () => {
    if (deck !== activeDeck) return;
    const el = deck.el;
    if (!el.duration) return;
    syncSeek(el.currentTime, el.duration);
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
let scrubbing = false;
seekInputs.forEach((s) => {
  s.addEventListener("pointerdown", () => { scrubbing = true; });
  s.addEventListener("input", () => {
    const el = activeDeck.el;
    if (el.duration) {
      const t = (Number(s.value) / 1000) * el.duration;
      $("#tElapsed").textContent = fmt(t);
      $("#npElapsed").textContent = fmt(t);
    }
  });
  s.addEventListener("change", () => {
    const el = activeDeck.el;
    if (el.duration) el.currentTime = (Number(s.value) / 1000) * el.duration;
    scrubbing = false;
  });
});
function syncSeek(cur, dur) {
  if (!scrubbing) {
    const v = Math.round((cur / dur) * 1000);
    seekInputs.forEach((s) => { s.value = v; });
  }
  $("#tElapsed").textContent = fmt(cur);
  $("#npElapsed").textContent = fmt(cur);
  $("#tTotal").textContent = fmt(dur);
  $("#npTotal").textContent = fmt(dur);
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
function onTrackChanged(track) {
  $("#player").classList.remove("hidden");
  document.body.classList.remove("no-player");
  $("#pArt").src = track.artwork || "";
  $("#pTitle").textContent = track.title;
  $("#pArtist").textContent = track.artist;
  $("#npArt").src = track.artwork || "";
  $("#npTitle").textContent = track.title;
  $("#npArtist").textContent = track.artist;
  $("#npBackdrop").style.backgroundImage =
    track.artwork && /^https:\/\//.test(track.artwork) ? cssUrl(track.artwork) : "";
  $("#npContext").textContent = player.contextName || "Now Playing";
  syncPlayButtons();
  syncHeartStates();
  syncNpDownload();
  markCurrentRows();
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
$("#volume").addEventListener("input", () => {
  const v = Number($("#volume").value);
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
function openNp(showQueue = false) {
  if (!player.current) return;
  $("#nowPlaying").classList.remove("hidden");
  if (showQueue) { $("#queueCard").classList.remove("hidden"); $("#npQueueBtn").classList.add("on"); }
  renderQueue();
}
function closeNp() { $("#nowPlaying").classList.add("hidden"); }
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

/* Lyrics — plain text via the backend (ytmusicapi). */
const lyricsCache = new Map();
let lyricsTrackId = null;
function resetLyrics(track) {
  lyricsTrackId = track.id;
  $("#lyricsCard").classList.add("hidden");
  $("#lyricsBtn").classList.remove("on");
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
    $("#lyricsText").textContent = "Lyrics are not available for local files.";
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
  $("#lyricsText").textContent = "Loading…";
  $("#lyricsSource").textContent = "";
  try {
    const r = await api(`/lyrics/${encodeURIComponent(id)}`);
    lyricsCache.set(id, r);
    if (lyricsTrackId === id) fillLyrics(r);
  } catch {
    if (lyricsTrackId === id) { $("#lyricsText").textContent = "Lyrics could not be loaded."; }
  }
};
function fillLyrics(r) {
  $("#lyricsText").textContent = r.lyrics || "No lyrics found for this track.";
  $("#lyricsSource").textContent = r.source ? `Source: ${r.source}` : "";
}

/* ---------------- visualizer ---------------- */
const vizCtx = $("#viz").getContext("2d");
const vizMiniCtx = $("#vizMini").getContext("2d");
function drawViz() {
  requestAnimationFrame(drawViz);
  const npOpen = !$("#nowPlaying").classList.contains("hidden");
  if (!npOpen && $("#player").classList.contains("hidden")) return;
  const draw = (ctx, w, h, bars) => {
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = "#fc3c44";
    const bw = w / bars;
    let data = null;
    if (webAudioOk && analyser && player.playing) {
      data = new Uint8Array(analyser.frequencyBinCount);
      analyser.getByteFrequencyData(data);
    }
    for (let i = 0; i < bars; i++) {
      const v = data ? data[Math.floor((i / bars) * data.length * 0.72)] / 255 : 0.06;
      const bh = Math.max(2, v * h);
      ctx.globalAlpha = 0.55 + v * 0.45;
      ctx.fillRect(i * bw + 1, h - bh, bw - 2, bh);
    }
    ctx.globalAlpha = 1;
  };
  if (npOpen) draw(vizCtx, 520, 64, 56);
  draw(vizMiniCtx, 72, 28, 14);
}
requestAnimationFrame(drawViz);

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
