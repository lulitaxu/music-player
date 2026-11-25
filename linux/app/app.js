"use strict";

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
async function apiPost(path) {
  await tokenReady;
  const r = await fetch(path, { method: "POST", headers: authHeaders() });
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
  theme: ["midnight", "graphite", "espresso", "forest"].includes(storedSettings.theme)
    ? storedSettings.theme : "midnight",
  crossfade: Number.isFinite(storedSettings.crossfade)
    ? Math.max(0, Math.min(12, Math.round(storedSettings.crossfade))) : 6,
  name: typeof storedSettings.name === "string" ? storedSettings.name.slice(0, 40) : "",
};
if (/AppleWebKit/.test(navigator.userAgent) && !/Chrome|Chromium|Edg|OPR/.test(navigator.userAgent)) {
  document.body.classList.add("lowfx");
}
let vizMainColor = settings.accentMode === "custom" ? settings.customAccent : DEFAULT_ACCENT;
let vizNpColor = vizMainColor;
const motionReduced = () => settings.motion === "reduced";
function setNpAccent(value) {
  const np = $("#nowPlaying");
  if (value) {
    np.style.setProperty("--np-accent", value);
    document.body.style.setProperty("--np-accent", value);
  } else {
    np.style.removeProperty("--np-accent");
    document.body.style.removeProperty("--np-accent");
  }
}
function saveSettings() {
  try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings)); } catch {}
}
function applySettings(save = true) {
  if (save) saveSettings();
  const root = document.documentElement;
  const np = $("#nowPlaying");
  document.body.dataset.theme = settings.theme;
  document.body.classList.toggle("motion-reduced", motionReduced());
  document.body.classList.toggle("viz-off", settings.vizStyle === "off");
  if (settings.accentMode === "custom") {
    root.style.setProperty("--accent", settings.customAccent);
    root.style.setProperty("--on-accent", "#ffffff");
    vizMainColor = settings.customAccent;
  } else if (settings.accentMode === "red") {
    root.style.setProperty("--accent", DEFAULT_ACCENT);
    root.style.setProperty("--on-accent", "#ffffff");
    vizMainColor = DEFAULT_ACCENT;
  } else {
    root.style.removeProperty("--accent");
    root.style.removeProperty("--on-accent");
    vizMainColor = DEFAULT_ACCENT;
  }
  if (settings.accentMode === "red") {
    setNpAccent(DEFAULT_ACCENT);
    vizNpColor = DEFAULT_ACCENT;
  } else if (settings.accentMode === "custom") {
    setNpAccent(settings.customAccent);
    vizNpColor = settings.customAccent;
  } else if (typeof player !== "undefined" && player.current) {
    if (accentCache.has(player.current.id)) {
      const rgb = accentCache.get(player.current.id);
      setNpAccent(rgb);
      vizNpColor = rgb;
    } else {
      setNpAccent(null);
      vizNpColor = DEFAULT_ACCENT;
      applyArtworkAccent(player.current);
    }
  } else {
    setNpAccent(null);
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

let modalResolve = null;
function askName(title, initial = "") {
  return new Promise((resolve) => {
    modalResolve = resolve;
    $("#modalTitle").textContent = title;
    const input = $("#modalInput");
    input.value = initial;
    $("#modalOverlay").classList.remove("hidden");
    setTimeout(() => { input.focus(); input.select(); }, 30);
  });
}
function closeModal(value) {
  $("#modalOverlay").classList.add("hidden");
  if (modalResolve) { modalResolve(value); modalResolve = null; }
}
$("#modalOk").onclick = () => closeModal($("#modalInput").value.trim() || null);
$("#modalCancel").onclick = () => closeModal(null);
$("#modalOverlay").addEventListener("mousedown", (e) => { if (e.target === e.currentTarget) closeModal(null); });
$("#modalInput").addEventListener("keydown", (e) => {
  e.stopPropagation();
  if (e.key === "Enter") closeModal($("#modalInput").value.trim() || null);
  if (e.key === "Escape") closeModal(null);
});

let playlists = store.get("arzxsh.playlists", []);
function savePlaylists() { store.set("arzxsh.playlists", playlists); renderSidePlaylists(); }
function getPlaylist(id) { return playlists.find((p) => p.id === id); }
async function createPlaylist(name) {
  const n = name || await askName("New Playlist", `My Playlist ${playlists.length + 1}`);
  if (!n) return null;
  const p = { id: "pl_" + Date.now().toString(36) + Math.floor(Math.random() * 1e4).toString(36), name: n, tracks: [] };
  playlists = [p, ...playlists];
  savePlaylists();
  return p;
}
function addToPlaylist(p, track) {
  if (p.tracks.some((t) => t.id === track.id)) { toast(`Already in ${p.name}`); return; }
  p.tracks = [...p.tracks, track];
  savePlaylists();
  toast(`Added to ${p.name}`);
}
function removeFromPlaylist(p, trackId) {
  p.tracks = p.tracks.filter((t) => t.id !== trackId);
  savePlaylists();
}
function renderSidePlaylists() {
  const box = $("#sidePlaylists");
  if (!box) return;
  box.innerHTML = playlists.map((p) =>
    `<button class="side-link" data-pl="${esc(p.id)}">♪&nbsp; ${esc(p.name)} <span class="side-count">${p.tracks.length}</span></button>`).join("");
  box.querySelectorAll("[data-pl]").forEach((b) => { b.onclick = () => navigate({ type: "playlist", id: b.dataset.pl }); });
}
$("#sideNewPlaylist").onclick = async () => {
  const p = await createPlaylist();
  if (p) navigate({ type: "playlist", id: p.id });
};

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
  else if (r.type === "playlist") renderPlaylist(r.id);
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
$("#railRadio").onclick = async () => {
  if (!player.current) { toast("Play a song first, then Radio builds a station from it"); return; }
  if (player.radioBusy) return;
  player.radioBusy = true;
  try {
    const r = await api(`/radio?video_id=${encodeURIComponent(player.current.id)}`);
    const fresh = (r.results || []).filter((t) => t.id !== player.current.id);
    if (!fresh.length) { toast("No radio mix found for this song. Try another one."); return; }
    register(fresh, null);
    player.queue = [player.current, ...fresh];
    player.index = 0;
    player.contextName = "Radio";
    renderQueue();
    toast("Radio station started");
  } catch { toast("Radio is unavailable right now. Try again in a bit."); }
  player.radioBusy = false;
};

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
      <span class="card-art"><img src="${esc(item.artwork || "")}" alt="" loading="lazy"><span class="card-play" data-quickplay-artist="${esc(item.id)}">▶</span></span>
      <div class="card-title">${esc(item.name)}</div>
      <div class="card-sub">Artist</div></button>`;
  }
  return `<button class="card" data-album="${esc(item.id)}">
    <span class="card-art"><img src="${esc(item.artwork || "")}" alt="" loading="lazy"><span class="card-play" data-quickplay-album="${esc(item.id)}">▶</span></span>
    <div class="card-title">${esc(item.title)}</div>
    <div class="card-sub">${esc(item.artist || "")}${item.year ? " · " + esc(item.year) : ""}</div></button>`;
}
function songCardHtml(track, ctx) {
  return `<button class="card" data-play="${esc(track.id)}" data-ctx="${ctx}">
    <span class="card-art"><img src="${esc(track.artwork || "")}" alt="" loading="lazy"><span class="card-play">▶</span></span>
    <div class="card-title">${esc(track.title)}</div>
    <div class="card-sub">Song · ${esc(track.artist)}</div></button>`;
}
async function quickPlayAlbum(id) {
  try {
    const a = await api(`/album/${encodeURIComponent(id)}`);
    const tracks = (a.tracks || []).map((t) => ({ ...t, album_id: a.id }));
    if (tracks.length) { register(tracks, null); playFromContext(tracks, 0, "Album"); }
  } catch { toast("Couldn't play this album. Try again."); }
}
async function quickPlayArtist(id) {
  try {
    const a = await api(`/artist/${encodeURIComponent(id)}`);
    if (a.songs && a.songs.length) { register(a.songs, null); playFromContext(a.songs, 0, "Artist"); }
  } catch { toast("Couldn't play this artist. Try again."); }
}
const CTX_LABELS = {
  charts: "Trending now", searchSongs: "Search", artistSongs: "Artist",
  albumTracks: "Album", recent: "Jump back in", recentLib: "Recently played",
  recentSide: "Recently played", likedList: "Liked Songs", localList: "On this device",
  playlistTracks: "Playlist",
};
function skeletons(n, h = 62) {
  return Array.from({ length: n }, () =>
    `<div class="skel" style="height:${h}px;margin-bottom:8px"></div>`).join("");
}
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
  const qpAlbum = e.target.closest("[data-quickplay-album]");
  if (qpAlbum) { e.stopPropagation(); quickPlayAlbum(qpAlbum.dataset.quickplayAlbum); return; }
  const qpArtist = e.target.closest("[data-quickplay-artist]");
  if (qpArtist) { e.stopPropagation(); quickPlayArtist(qpArtist.dataset.quickplayArtist); return; }
  const play = e.target.closest("[data-play]");
  if (play) {
    const list = contexts.get(play.dataset.ctx) || [findTrack(play.dataset.play)];
    const idx = list.findIndex((t) => t.id === play.dataset.play);
    if (idx >= 0) playFromContext(list, idx, CTX_LABELS[play.dataset.ctx] || "Songs",
      play.dataset.ctx === "playlistTracks" && routes[routeIdx].type === "playlist" ? routes[routeIdx].id : null);
    return;
  }
  const artist = e.target.closest("[data-artist]");
  if (artist) { navigate({ type: "artist", id: artist.dataset.artist }); return; }
  const album = e.target.closest("[data-album]");
  if (album) { navigate({ type: "album", id: album.dataset.album }); return; }
  const retry = e.target.closest("[data-retry]");
  if (retry) { renderRoute(); }
});

const trackReg = new Map();
const contexts = new Map();
function register(list, ctxName) {
  (list || []).forEach((t) => { if (t && t.id) trackReg.set(t.id, t); });
  if (ctxName) contexts.set(ctxName, list || []);
}
function findTrack(id) { return trackReg.get(id); }

const trackKey = (t) => `${t.source || "ytmusic"}_${t.id}`;
let mixStore = store.get("arzxsh.mix", {});
function getTransition(plId, outTrack, inTrack) {
  if (!plId || !outTrack || !inTrack) return null;
  const byPair = mixStore[plId];
  if (!byPair) return null;
  return byPair[`${trackKey(outTrack)}>${trackKey(inTrack)}`] || null;
}
function setTransition(plId, outTrack, inTrack, tr) {
  if (!mixStore[plId]) mixStore[plId] = {};
  const key = `${trackKey(outTrack)}>${trackKey(inTrack)}`;
  if (tr) mixStore[plId][key] = tr;
  else delete mixStore[plId][key];
  store.set("arzxsh.mix", mixStore);
}
function currentTransition() {
  const auto = { style: "fade", length: settings.crossfade, outSkip: 0, inSkip: 0 };
  if (!player.playlistId) return auto;
  const n = nextIndex(true);
  if (n < 0) return auto;
  const out = player.queue[player.index], inn = player.queue[n];
  return getTransition(player.playlistId, out, inn)
    || (autoMixStore[player.playlistId] ? generateTransition(out, inn) : null)
    || auto;
}

let autoMixStore = store.get("arzxsh.automix", {});
let analysisStore = store.get("arzxsh.analysis", {});
const analysisBusy = new Set();
const analysisFailed = new Set();
function getAnalysis(t) { return t ? analysisStore[trackKey(t)] || null : null; }
async function ensureAnalysis(t, onDone) {
  const key = trackKey(t);
  if (analysisStore[key] || analysisFailed.has(key) || analysisBusy.has(key)) return;
  analysisBusy.add(key);
  try {
    const r = await apiPost(`/analyze?source=${encodeURIComponent(t.source || "ytmusic")}&id=${encodeURIComponent(t.id)}`);
    if (r && r.bpm) { analysisStore[key] = r; store.set("arzxsh.analysis", analysisStore); }
    else analysisFailed.add(key);
  } catch { analysisFailed.add(key); }
  analysisBusy.delete(key);
  if (onDone) onDone();
}
async function primeAnalyses(list, onDone) {
  const missing = list.filter((t) => !getAnalysis(t) && !analysisFailed.has(trackKey(t)));
  if (!missing.length) return;
  try {
    const r = await api(`/analysis?keys=${encodeURIComponent(list.map(trackKey).join(","))}`);
    let got = false;
    for (const t of list) {
      const a = r[trackKey(t)];
      if (a) { analysisStore[trackKey(t)] = a; got = true; }
    }
    if (got) { store.set("arzxsh.analysis", analysisStore); if (onDone) onDone(); }
  } catch {}
  for (const t of list) {
    if (!getAnalysis(t) && !analysisFailed.has(trackKey(t))) await ensureAnalysis(t, onDone);
  }
}
function camelotCost(a, b) {
  if (!a || !b) return 3;
  if (a === b) return 0;
  const pa = /^(\d+)([AB])$/.exec(a), pb = /^(\d+)([AB])$/.exec(b);
  if (!pa || !pb) return 3;
  const na = Number(pa[1]), nb = Number(pb[1]);
  if (na === nb) return 1;
  const step = Math.min((na - nb + 12) % 12, (nb - na + 12) % 12);
  return step === 1 && pa[2] === pb[2] ? 1 : 3;
}
function generateTransition(outT, inT) {
  const A = getAnalysis(outT), B = getAnalysis(inT);
  if (!A || !B) return null;
  const gap = Math.min(Math.abs(A.bpm - B.bpm), Math.abs(A.bpm - 2 * B.bpm), Math.abs(2 * A.bpm - B.bpm));
  const cost = camelotCost(A.camelot, B.camelot);
  let style = "fade", bars = 2;
  if (cost <= 1 && gap <= 6) { style = "blend"; bars = 8; }
  else if (cost <= 1 && gap <= 14) { style = "rise"; bars = 4; }
  let length = Math.round(bars * 4 * 60 / A.bpm);
  length = Math.max(4, Math.min(16, length));
  const durs = [outT.duration_s, inT.duration_s].filter((d) => d > 0);
  if (durs.length) length = Math.max(2, Math.min(length, Math.floor(Math.min(...durs) / 4)));
  let inSkip = 0;
  const beatB = 60 / B.bpm;
  if (typeof B.phase === "number") {
    const durA = outT.duration_s;
    if (durA) {
      const beatA = 60 / A.bpm;
      const T = Math.max(0, durA - length);
      const frac = ((((T - (A.phase || 0)) % beatA) + beatA) % beatA) / beatA;
      inSkip = (((B.phase + frac * beatB) % beatB) + beatB) % beatB;
    } else {
      inSkip = (((B.phase % beatB) + beatB) % beatB);
    }
    inSkip = Math.round(inSkip * 100) / 100;
  }
  let inGain = 1;
  if (typeof A.tailDb === "number" && typeof B.headDb === "number") {
    inGain = Math.pow(10, (A.tailDb - B.headDb) / 20);
    inGain = Math.round(Math.max(0.5, Math.min(1.8, inGain)) * 100) / 100;
  }
  return { style, length, outSkip: 0, inSkip, inGain, auto: true };
}
function smartReorder(list) {
  const analyzed = list.filter((t) => getAnalysis(t));
  const unknown = list.filter((t) => !getAnalysis(t));
  if (analyzed.length < 2) return list;
  const remaining = [...analyzed];
  const out = [remaining.shift()];
  while (remaining.length) {
    const last = getAnalysis(out[out.length - 1]);
    let best = null, bestCost = Infinity;
    for (const cand of remaining) {
      const a = getAnalysis(cand);
      const cost = Math.min(Math.abs(a.bpm - last.bpm) / 10, 4) + camelotCost(last.camelot, a.camelot);
      if (cost < bestCost) { bestCost = cost; best = cand; }
    }
    out.push(best);
    remaining.splice(remaining.indexOf(best), 1);
  }
  return [...out, ...unknown];
}

function greeting() {
  const h = new Date().getHours();
  const name = settings.name ? `, ${settings.name}` : "";
  const day = Math.floor(Date.now() / 86400000);
  const pick = (arr) => arr[day % arr.length];
  if (h >= 22 || h < 4) return [`Good night${name}`, pick([
    "The late queue is yours.", "Low lights, loud songs.", "Night radio, no skips.",
    "For the ones still awake.", "Slow hours, deep cuts."])];
  if (h < 12) return [`Good morning${name}`, pick([
    "Start the day loud.", "First song sets the tone.", "Coffee can wait, press play.",
    "Your morning rotation.", "Wake up the speakers."])];
  if (h < 17) return [`Good afternoon${name}`, pick([
    "The midday queue.", "Halfway there, keep it playing.", "Sun's up, volume up.",
    "The afternoon rotation.", "A break between everything."])];
  return [`Good evening${name}`, pick([
    "Tonight's rotation.", "Golden hour, good songs.", "The day is done, the queue isn't.",
    "Evening mode: on.", "Songs for the slow hours."])];
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
      <div class="error-note">Couldn't load the catalog. Check your connection and try again.<button data-retry>Retry</button></div>`;
  }
}

const searchInput = $("#searchInput");
const suggestBox = $("#suggestBox");
let suggestTimer = null;
let suggestSeq = 0;
let suggestItems = []; // [{ text, kind: "history" | "suggest" }]
let suggestActive = -1;
function hideSuggest() {
  suggestBox.classList.add("hidden");
  suggestActive = -1;
}
function runSearch(text) {
  const q = (text || "").trim();
  if (!q) return;
  searchInput.value = q;
  saveSearch(q);
  hideSuggest();
  searchInput.blur();
  navigate({ type: "search", q },
    routeIdx >= 0 && routes[routeIdx].type === "search");
}
function setSuggestActive(i) {
  suggestActive = i;
  suggestBox.querySelectorAll("[data-suggest]").forEach((b) =>
    b.classList.toggle("sel", Number(b.dataset.suggest) === i));
  const el = suggestBox.querySelector(`[data-suggest="${i}"]`);
  if (el) el.scrollIntoView({ block: "nearest" });
}
function paintSuggest() {
  if (!suggestItems.length || document.activeElement !== searchInput) {
    hideSuggest();
    return;
  }
  const q = searchInput.value.trim().toLowerCase();
  suggestBox.innerHTML = suggestItems.map((item, i) => {
    let label = esc(item.text);
    if (item.kind === "suggest" && q && item.text.toLowerCase().startsWith(q)) {
      label = `<b>${esc(item.text.slice(0, q.length))}</b>${esc(item.text.slice(q.length))}`;
    }
    return `<button class="suggest-row ${i === suggestActive ? "sel" : ""}" data-suggest="${i}">
      <span class="s-ico">${item.kind === "history" ? "↺" : "⌕"}</span><span class="s-text">${label}</span>${item.kind === "history" ? '<span class="s-tag">History</span>' : ""}
    </button>`;
  }).join("");
  suggestBox.classList.remove("hidden");
  suggestBox.querySelectorAll("[data-suggest]").forEach((b) => {
    b.addEventListener("mousedown", (e) => {
      e.preventDefault();
      const item = suggestItems[Number(b.dataset.suggest)];
      if (item) runSearch(item.text);
    });
    b.addEventListener("mousemove", () => {
      const i = Number(b.dataset.suggest);
      if (suggestActive !== i) setSuggestActive(i);
    });
  });
}
async function refreshSuggest() {
  const q = searchInput.value.trim();
  const seq = ++suggestSeq;
  if (!q) {
    suggestItems = searchHistory.slice(0, 7).map((text) => ({ text, kind: "history" }));
    suggestActive = -1;
    paintSuggest();
    return;
  }
  const hist = searchHistory
    .filter((h) => h.toLowerCase().includes(q.toLowerCase()) && h.toLowerCase() !== q.toLowerCase())
    .slice(0, 3);
  suggestItems = hist.map((text) => ({ text, kind: "history" }));
  suggestActive = -1;
  paintSuggest();
  try {
    const r = await api(`/suggest?q=${encodeURIComponent(q)}`);
    if (seq !== suggestSeq) return; // a newer keystroke superseded this
    const seen = new Set(suggestItems.map((i) => i.text.toLowerCase()));
    const live = (r.results || [])
      .map((s) => String(s || ""))
      .filter((s) => s && !seen.has(s.toLowerCase()));
    suggestItems = [...suggestItems,
      ...live.map((text) => ({ text, kind: "suggest" }))].slice(0, 10);
    paintSuggest();
  } catch {}
}
searchInput.addEventListener("input", () => {
  $("#searchClear").classList.toggle("hidden", !searchInput.value);
  clearTimeout(suggestTimer);
  if (!searchInput.value.trim()) { refreshSuggest(); return; }
  suggestTimer = setTimeout(refreshSuggest, 180);
});
searchInput.addEventListener("focus", () => refreshSuggest());
document.addEventListener("click", (e) => {
  if (!e.target.closest("#searchWrap")) hideSuggest();
});
searchInput.addEventListener("keydown", (e) => {
  if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    if (suggestBox.classList.contains("hidden") || !suggestItems.length) return;
    e.preventDefault();
    const dir = e.key === "ArrowDown" ? 1 : -1;
    let next = suggestActive + dir;
    if (next < 0) next = suggestItems.length - 1;
    if (next >= suggestItems.length) next = 0;
    setSuggestActive(next);
  } else if (e.key === "Enter") {
    const item = suggestActive >= 0 ? suggestItems[suggestActive] : null;
    runSearch(item ? item.text : searchInput.value);
  } else if (e.key === "Escape") {
    e.stopPropagation();
    hideSuggest();
  }
});
$("#searchClear").onclick = () => {
  searchInput.value = "";
  $("#searchClear").classList.add("hidden");
  refreshSuggest();
  searchInput.focus();
};
async function renderSearch(q) {
  view.innerHTML = `<div class="page-title">${esc(q)}</div>${skeletons(6)}`;
  try {
    const [songs, artists, albums] = await Promise.all([
      api(`/search?q=${encodeURIComponent(q)}&filter=songs&limit=25`),
      api(`/search?q=${encodeURIComponent(q)}&filter=artists&limit=10`),
      api(`/search?q=${encodeURIComponent(q)}&filter=albums&limit=10`),
    ]);
    if (routes[routeIdx].type !== "search" || routes[routeIdx].q !== q) return;
    register(songs.results, "searchSongs");
    const topCards = [];
    if (artists.results.length) topCards.push(cardHtml(artists.results[0], "artist"));
    if (albums.results.length) topCards.push(cardHtml(albums.results[0], "album"));
    songs.results.slice(0, 2).forEach((t) => topCards.push(songCardHtml(t, "searchSongs")));
    view.innerHTML = `
      <div class="page-title">${esc(q)}</div>
      ${topCards.length ? `<div class="section-title">Top Results</div>
        <div class="rail">${topCards.join("")}</div>` : ""}
      <div class="section-title">Songs</div>
      ${songs.results.length ? songs.results.map((t, i) => rowHtml(t, i, { ctx: "searchSongs" })).join("") : `<div class="empty-note">No songs found for “${esc(q)}”.</div>`}
      ${artists.results.length ? `<div class="section-title">Artists</div>
        <div class="rail">${artists.results.map((a) => cardHtml(a, "artist")).join("")}</div>` : ""}
      ${albums.results.length ? `<div class="section-title">Albums</div>
        <div class="rail">${albums.results.map((a) => cardHtml(a, "album")).join("")}</div>` : ""}`;
    artists.results.forEach((a) => trackReg.set(a.id, a));
  } catch {
    if (routes[routeIdx].type !== "search") return;
    view.innerHTML = `<div class="page-title">${esc(q)}</div>
      <div class="error-note">Search didn't go through. Check your connection and try again.<button data-retry>Retry</button></div>`;
  }
}

async function renderArtist(id) {
  view.innerHTML = skeletons(1, 230) + skeletons(6);
  try {
    const a = await api(`/artist/${encodeURIComponent(id)}`);
    if (routes[routeIdx].type !== "artist" || routes[routeIdx].id !== id) return;
    register(a.songs, "artistSongs");
    view.innerHTML = `
      <div class="page-hero">
        ${a.artwork ? `<div class="page-wash" style="background-image:${cssUrl(a.artwork)}"></div>` : ""}
        <div class="page-hero-content">
          <div class="page-head round">
            <img src="${esc(a.artwork || "")}" alt="">
            <div>
              <div class="page-kind">Artist</div>
              <div class="page-name">${esc(a.name || "")}</div>
              <div class="page-meta">${a.subscribers ? esc(a.subscribers) + " subscribers" : ""}</div>
              <div class="page-actions">
                <button class="play-pill" id="pagePlay">▶ Play</button>
                <button class="shuffle-pill" id="pageShuffle">⤨ Shuffle</button>
              </div>
            </div>
          </div>
        </div>
      </div>
      ${a.description ? `<div class="section-title">About</div><p class="about-text">${esc(a.description)}</p>` : ""}
      <div class="section-title">Top songs</div>
      ${a.songs.length ? a.songs.map((t, i) => rowHtml(t, i, { ctx: "artistSongs" })).join("") : `<div class="empty-note">No songs listed for this artist yet.</div>`}
      ${a.albums.length ? `<div class="section-title">Albums</div><div class="rail">${a.albums.map((x) => cardHtml({ ...x, artist: a.name }, "album")).join("")}</div>` : ""}
      ${a.singles.length ? `<div class="section-title">Singles</div><div class="rail">${a.singles.map((x) => cardHtml({ ...x, artist: a.name }, "album")).join("")}</div>` : ""}
      ${a.related.length ? `<div class="section-title">Fans also like</div><div class="rail">${a.related.map((x) => cardHtml(x, "artist")).join("")}</div>` : ""}`;
    a.related.forEach((x) => trackReg.set(x.id, x));
    $("#pagePlay").onclick = () => { if (a.songs.length) playFromContext(a.songs, 0, "Artist"); };
    $("#pageShuffle").onclick = () => {
      if (!a.songs.length) return;
      if (!player.shuffle) toggleShuffle();
      playFromContext(a.songs, Math.floor(Math.random() * a.songs.length), "Artist");
    };
  } catch {
    if (routes[routeIdx].type !== "artist") return;
    view.innerHTML = `<div class="error-note">Couldn't load this artist. Try again.<button data-retry>Retry</button></div>`;
  }
}
async function renderAlbum(id) {
  view.innerHTML = skeletons(1, 230) + skeletons(6);
  try {
    const a = await api(`/album/${encodeURIComponent(id)}`);
    if (routes[routeIdx].type !== "album" || routes[routeIdx].id !== id) return;
    const tracks = (a.tracks || []).map((t) => ({ ...t, album_id: a.id, album: t.album || a.title }));
    register(tracks, "albumTracks");
    view.innerHTML = `
      <div class="page-hero">
        ${a.artwork ? `<div class="page-wash" style="background-image:${cssUrl(a.artwork)}"></div>` : ""}
        <div class="page-hero-content">
          <div class="page-head">
            <img src="${esc(a.artwork || "")}" alt="">
            <div>
              <div class="page-kind">Album</div>
              <div class="page-name">${esc(a.title || "")}</div>
              <div class="page-meta">${esc(a.artist || "")}${a.year ? " · " + esc(a.year) : ""} · ${tracks.length} songs</div>
              <div class="page-actions">
                <button class="play-pill" id="pagePlay">▶ Play</button>
                <button class="shuffle-pill" id="pageShuffle">⤨ Shuffle</button>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="section-title">Songs</div>
      ${tracks.map((t, i) => rowHtml(t, i, { rank: true, ctx: "albumTracks" })).join("")}`;
    $("#pagePlay").onclick = () => { if (tracks.length) playFromContext(tracks, 0, "Album"); };
    $("#pageShuffle").onclick = () => {
      if (!tracks.length) return;
      if (!player.shuffle) toggleShuffle();
      playFromContext(tracks, Math.floor(Math.random() * tracks.length), "Album");
    };
  } catch {
    if (routes[routeIdx].type !== "album") return;
    view.innerHTML = `<div class="error-note">Couldn't load this album. Try again.<button data-retry>Retry</button></div>`;
  }
}

function renderLibrary() {
  view.innerHTML = `
    <div class="section-title">Library</div>
    <div class="quick-grid">
      <button class="quick-tile" id="libLiked">
        ${coverHtml(likes[0], "♥", "cover-liked")}
        <span><div class="qt-title">Liked Songs</div><div class="qt-sub">${likes.length} songs</div></span>
      </button>
    </div>
    <div class="section-title">Playlists</div>
    <div class="quick-grid">
      ${playlists.map((p) => `
      <button class="quick-tile" data-libpl="${esc(p.id)}">
        ${playlistCoverHtml(p)}
        <span><div class="qt-title">${esc(p.name)}</div><div class="qt-sub">${p.tracks.length} songs</div></span>
      </button>`).join("")}
      <button class="quick-tile" id="libNewPlaylist">
        <span class="cover cover-fallback">＋</span>
        <span><div class="qt-title">New Playlist</div><div class="qt-sub">Make your own</div></span>
      </button>
    </div>
    <div class="section-title">On this device</div>
    <div class="quick-grid">
      <button class="quick-tile" id="libLocal">
        <span id="libLocalCover">${coverHtml(null, "♪")}</span>
        <span><div class="qt-title">Local files & downloads</div><div class="qt-sub" id="libLocalCount">From your music folder</div></span>
      </button>
    </div>
    <div class="section-title">Recently played</div>
    ${recentPlays.length ? recentPlays.map((t, i) => rowHtml(t, i, { ctx: "recentLib" })).join("") : `<div class="empty-note">Tracks you play will appear here.</div>`}`;
  register(recentPlays, "recentLib");
  $("#libLiked").onclick = () => navigate({ type: "liked" });
  $("#libLocal").onclick = () => navigate({ type: "local" });
  view.querySelectorAll("[data-libpl]").forEach((b) => { b.onclick = () => navigate({ type: "playlist", id: b.dataset.libpl }); });
  $("#libNewPlaylist").onclick = async () => {
    const p = await createPlaylist();
    if (p) navigate({ type: "playlist", id: p.id });
  };
  api("/local").then((d) => {
    const el = $("#libLocalCount");
    if (el) el.textContent = `${d.tracks.length} tracks · ${d.dir}`;
    const cov = $("#libLocalCover");
    if (cov && d.tracks.length) cov.innerHTML = coverHtml(d.tracks[0], "♪");
  }).catch(() => {});
}
async function renderLocal(fresh = false) {
  view.innerHTML = `<div class="section-title">On this device</div>${skeletons(5)}`;
  try {
    const data = await api(`/local${fresh ? "?fresh=1" : ""}`);
    if (routes[routeIdx].type !== "local") return;
    register(data.tracks, "localList");
    view.innerHTML = `
      <div class="page-head plain">
        ${coverHtml(data.tracks[0], "♪")}
        <div>
          <div class="page-kind">Local library</div>
          <div class="page-name">On this device</div>
          <div class="page-meta">${data.tracks.length} tracks · ${esc(data.dir)}</div>
        </div>
      </div>
      <button class="chip-btn" id="rescanBtn" style="margin:6px 0 4px">↻ Rescan folder</button>
      ${data.tracks.some((t) => !t.artwork) ? `<button class="chip-btn" id="healBtn" style="margin:6px 0 4px">Fetch missing covers & lyrics</button>` : ""}
      <div class="section-title">Songs</div>
      ${data.tracks.length ? data.tracks.map((t, i) => rowHtml(t, i, { ctx: "localList" })).join("") : `<div class="empty-note">No audio files found in that folder yet. Downloads you save land in its arzxsh subfolder.</div>`}`;
    $("#rescanBtn").onclick = () => renderLocal(true);
    const healBtn = $("#healBtn");
    if (healBtn) healBtn.onclick = async () => {
      const missing = data.tracks.filter((t) => !t.artwork);
      healBtn.disabled = true;
      let done = 0, fixed = 0;
      for (const t of missing) {
        healBtn.textContent = `Fetching ${done + 1} / ${missing.length}…`;
        try {
          const r = await apiPost(`/local/heal/${encodeURIComponent(t.id)}`);
          if (r.changed) fixed++;
        } catch {}
        done++;
      }
      toast(fixed ? `Added covers or lyrics to ${fixed} track${fixed === 1 ? "" : "s"}` : "No matches found for the missing ones");
      renderLocal(true);
    };
  } catch {
    if (routes[routeIdx].type !== "local") return;
    view.innerHTML = `<div class="section-title">On this device</div>
      <div class="error-note">Couldn't read your music folder. Try again.<button data-retry>Retry</button></div>`;
  }
}
function coverHtml(track, glyph = "♪", cls = "") {
  if (track && track.artwork) return `<span class="cover ${cls}"><img src="${esc(track.artwork)}" alt=""></span>`;
  return `<span class="cover cover-fallback ${cls}">${glyph}</span>`;
}
function playlistCoverHtml(p, cls = "") {
  const arts = [...new Set(p.tracks.map((t) => t.artwork).filter(Boolean))].slice(0, 4);
  if (arts.length >= 2) return `<span class="cover cover-collage ${cls}">${arts.map((a) => `<img src="${esc(a)}" alt="">`).join("")}</span>`;
  if (arts.length === 1) return `<span class="cover ${cls}"><img src="${esc(arts[0])}" alt=""></span>`;
  return `<span class="cover cover-fallback ${cls}">♪</span>`;
}
function mixSummary(tr) {
  if (!tr) return `Auto · ${settings.crossfade}s fade`;
  const skips = [];
  if (tr.outSkip) skips.push(`ends ${tr.outSkip}s early`);
  if (tr.inSkip >= 1) skips.push(`next starts ${Math.round(tr.inSkip)}s in`);
  const label = { fade: "Fade", rise: "Rise", blend: "Blend" }[tr.style] || "Fade";
  return `${label} · ${tr.length}s${skips.length ? " · " + skips.join(" · ") : ""}`;
}
function mixDisplay(p, out, inn) {
  const manual = getTransition(p.id, out, inn);
  if (manual) return mixSummary(manual);
  if (!autoMixStore[p.id]) return mixSummary(null);
  const A = getAnalysis(out), B = getAnalysis(inn);
  if (A && B) {
    const gen = generateTransition(out, inn);
    return `Auto · ${mixSummary(gen)} · ${Math.round(A.bpm)} → ${Math.round(B.bpm)} BPM · ${A.camelot} → ${B.camelot}`;
  }
  if (analysisBusy.has(trackKey(out)) || analysisBusy.has(trackKey(inn))) return "Auto · analyzing…";
  if (analysisFailed.has(trackKey(out)) || analysisFailed.has(trackKey(inn))) return "Auto · couldn't analyze";
  return "Auto · analyzing…";
}
function mixBarHtml(p, out, inn, i) {
  return `<button class="mix-bar" data-mixbar="${i}">⇄ <span>${esc(mixDisplay(p, out, inn))}</span></button>
  <div class="mix-editor hidden" data-mixed="${i}">
    <div class="mix-styles">
      ${["fade", "rise", "blend"].map((s) => `<button class="chip-btn" data-mixstyle="${s}">${s[0].toUpperCase() + s.slice(1)}</button>`).join("")}
    </div>
    <label class="mix-slider">Mix length <input type="range" min="1" max="12" step="1" data-mixlen> <span data-mixlenval></span></label>
    <label class="mix-slider">Skip end of this song <input type="range" min="0" max="10" step="1" data-mixout> <span data-mixoutval></span></label>
    <label class="mix-slider">Skip start of next song <input type="range" min="0" max="10" step="1" data-mixin> <span data-mixinval></span></label>
    <button class="chip-btn" data-mixauto>Reset to Auto</button>
  </div>`;
}
function renderPlaylist(id) {
  const p = getPlaylist(id);
  if (!p) { navigate({ type: "library" }, true); return; }
  const tracks = p.tracks;
  register(tracks, "playlistTracks");
  view.innerHTML = `
    <div class="page-head plain">
      ${playlistCoverHtml(p)}
      <div>
        <div class="page-kind">Playlist</div>
        <div class="page-name">${esc(p.name)}</div>
        <div class="page-meta">${tracks.length} songs · stored on this machine only${autoMixStore[p.id] ? ` · Auto Mix on · ${tracks.filter((t) => getAnalysis(t)).length}/${tracks.length} analyzed` : ""}</div>
        <div class="page-actions">
          <button class="play-pill" id="pagePlay">▶ Play</button>
          <button class="shuffle-pill" id="pageShuffle">⤨ Shuffle</button>
          <button class="chip-btn ${autoMixStore[p.id] ? "on" : ""}" id="plAutoMix">Auto Mix</button>
          ${autoMixStore[p.id] ? `<button class="chip-btn" id="plSmart">Smart reorder</button>` : ""}
          <button class="chip-btn" id="plRename">Rename</button>
          <button class="chip-btn" id="plDelete">Delete</button>
        </div>
      </div>
    </div>
    <div class="section-title">Songs</div>
    ${tracks.length ? tracks.map((t, i) => rowHtml(t, i, { rank: true, ctx: "playlistTracks" }) + (i < tracks.length - 1 ? mixBarHtml(p, tracks[i], tracks[i + 1], i) : "")).join("") : `<div class="empty-note">Nothing here yet. Right-click any song and choose Add to Playlist.</div>`}`;
  $("#pagePlay").onclick = () => { if (tracks.length) playFromContext(tracks, 0, "Playlist", p.id); };
  $("#pageShuffle").onclick = () => {
    if (!tracks.length) return;
    if (!player.shuffle) toggleShuffle();
    playFromContext(tracks, Math.floor(Math.random() * tracks.length), "Playlist", p.id);
  };
  view.querySelectorAll("[data-mixbar]").forEach((bar) => {
    const i = Number(bar.dataset.mixbar);
    const ed = view.querySelector(`[data-mixed="${i}"]`);
    if (!ed) return;
    const out = tracks[i], inn = tracks[i + 1];
    let draft = getTransition(p.id, out, inn) || { style: "fade", length: settings.crossfade || 6, outSkip: 0, inSkip: 0 };
    const len = ed.querySelector("[data-mixlen]");
    const outS = ed.querySelector("[data-mixout]");
    const inS = ed.querySelector("[data-mixin]");
    const paint = () => {
      ed.querySelectorAll("[data-mixstyle]").forEach((b) => b.classList.toggle("on", b.dataset.mixstyle === draft.style));
      len.value = draft.length; outS.value = draft.outSkip; inS.value = draft.inSkip;
      ed.querySelector("[data-mixlenval]").textContent = `${draft.length}s`;
      ed.querySelector("[data-mixoutval]").textContent = `${draft.outSkip}s`;
      ed.querySelector("[data-mixinval]").textContent = `${draft.inSkip}s`;
      bar.querySelector("span").textContent = mixDisplay(p, out, inn);
    };
    bar.onclick = () => { ed.classList.toggle("hidden"); paint(); };
    ed.querySelectorAll("[data-mixstyle]").forEach((b) => { b.onclick = () => {
      draft = { ...draft, style: b.dataset.mixstyle };
      setTransition(p.id, out, inn, draft);
      paint();
    }; });
    const onSlide = () => {
      draft = { ...draft, length: Number(len.value), outSkip: Number(outS.value), inSkip: Number(inS.value) };
      setTransition(p.id, out, inn, draft);
      paint();
    };
    [len, outS, inS].forEach((s) => s.addEventListener("input", onSlide));
    ed.querySelector("[data-mixauto]").onclick = () => {
      setTransition(p.id, out, inn, null);
      draft = { style: "fade", length: settings.crossfade || 6, outSkip: 0, inSkip: 0 };
      paint();
    };
    paint();
  });
  const autoBtn = $("#plAutoMix");
  if (autoBtn) autoBtn.onclick = () => {
    autoMixStore[p.id] = !autoMixStore[p.id];
    if (!autoMixStore[p.id]) delete autoMixStore[p.id];
    store.set("arzxsh.automix", autoMixStore);
    renderPlaylist(id);
  };
  const smartBtn = $("#plSmart");
  if (smartBtn) smartBtn.onclick = () => {
    const reordered = smartReorder(tracks);
    const changed = reordered.some((t, i) => t !== tracks[i]);
    if (!changed) { toast("Already in smooth order"); return; }
    const unknown = tracks.filter((t) => !getAnalysis(t)).length;
    p.tracks = reordered;
    savePlaylists();
    renderPlaylist(id);
    toast(unknown ? `Reordered for smooth mixing. ${unknown} unanalyzed song${unknown > 1 ? "s" : ""} moved to the end.` : "Reordered for smooth mixing");
  };
  if (autoMixStore[p.id] && tracks.length) {
    let repaintTimer = null;
    primeAnalyses(tracks, () => {
      clearTimeout(repaintTimer);
      repaintTimer = setTimeout(() => { const r = routes[routeIdx]; if (r && r.type === "playlist" && r.id === id) renderPlaylist(id); }, 350);
    });
  }
  $("#plRename").onclick = async () => {
    const n = await askName("Rename Playlist", p.name);
    if (n) { p.name = n; savePlaylists(); renderPlaylist(id); }
  };
  $("#plDelete").onclick = () => {
    playlists = playlists.filter((x) => x.id !== id);
    savePlaylists();
    toast("Playlist deleted");
    navigate({ type: "library" }, true);
  };
}
function renderLiked() {
  register(likes, "likedList");
  view.innerHTML = `
    <div class="page-head plain">
      ${coverHtml(likes[0], "♥", "cover-liked")}
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
          <div class="setting-name">Crossfade</div>
          <div class="setting-desc">Auto mix between songs. Playlists can override it per pair with their own Mix.</div>
        </div>
        <span class="setting-range"><input id="settingCrossfade" type="range" min="0" max="12" step="1"> <span id="crossfadeVal"></span></span>
      </div>
      <div class="setting-row">
        <div>
          <div class="setting-name">Theme</div>
          <div class="setting-desc">The base look of the whole app. Accent colors apply on top.</div>
        </div>
        <select id="settingTheme" aria-label="Theme">
          <option value="midnight">Midnight</option>
          <option value="graphite">Graphite</option>
          <option value="espresso">Espresso</option>
          <option value="forest">Forest</option>
        </select>
      </div>
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
      <div class="setting-row">
        <div>
          <div class="setting-name">Your name</div>
          <div class="setting-desc">Used in the Home greeting. Stays on this machine.</div>
        </div>
        <input id="settingName" type="text" maxlength="40" placeholder="e.g. Arzu" autocomplete="off" spellcheck="false">
      </div>
    </div>
    <div class="section-title">Keyboard shortcuts</div>
    <div>
      <div class="shortcut-row"><span>Play or pause</span><kbd>Space</kbd></div>
      <div class="shortcut-row"><span>Next / previous track</span><span><kbd>Ctrl</kbd> + <kbd>→</kbd> / <kbd>←</kbd></span></div>
      <div class="shortcut-row"><span>Seek forward / back 10 seconds</span><span><kbd>→</kbd> / <kbd>←</kbd></span></div>
      <div class="shortcut-row"><span>Volume up / down</span><span><kbd>↑</kbd> / <kbd>↓</kbd></span></div>
      <div class="shortcut-row"><span>Lyrics panel</span><kbd>L</kbd></div>
      <div class="shortcut-row"><span>Up Next panel</span><kbd>Q</kbd></div>
      <div class="shortcut-row"><span>Now Playing</span><kbd>F</kbd></div>
      <div class="shortcut-row"><span>Search</span><kbd>/</kbd></div>
      <div class="shortcut-row"><span>Close menu, panel or Now Playing</span><kbd>Esc</kbd></div>
    </div>
    <div class="settings-note">Settings are stored only in this browser on this machine. ${typeof window.__arzxshUserCss === "string" ? "A user stylesheet was supplied by the desktop shell and is active." : "No user stylesheet was supplied by the desktop shell."}</div>`;
  $("#settingAccentMode").value = settings.accentMode;
  $("#settingVizStyle").value = settings.vizStyle;
  $("#settingMotion").value = settings.motion;
  $("#settingTheme").value = settings.theme;
  $("#settingTheme").addEventListener("change", (e) => {
    settings.theme = e.target.value;
    applySettings();
  });
  const cfInput = $("#settingCrossfade");
  cfInput.value = settings.crossfade;
  paintRangeFill(cfInput);
  $("#crossfadeVal").textContent = settings.crossfade === 0 ? "Off" : `${settings.crossfade}s`;
  cfInput.addEventListener("input", (e) => {
    settings.crossfade = Number(e.target.value);
    $("#crossfadeVal").textContent = settings.crossfade === 0 ? "Off" : `${settings.crossfade}s`;
    paintRangeFill(cfInput);
    applySettings();
  });
  const nameInput = $("#settingName");
  nameInput.value = settings.name;
  nameInput.addEventListener("change", (e) => {
    settings.name = e.target.value.trim().slice(0, 40);
    applySettings();
  });
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

const player = {
  queue: [], index: -1, current: null, contextName: "",
  playing: false, shuffle: false, repeat: 0, playlistId: null,
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
      d.filter = actx.createBiquadFilter();
      d.filter.type = "lowpass";
      d.filter.frequency.value = 18000;
      d.filter.Q.value = 0.4;
      d.gain = actx.createGain();
      d.source.connect(d.filter);
      d.filter.connect(d.gain);
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
function setDeckFilter(deck, freq) {
  if (webAudioOk && deck.filter && actx) {
    deck.filter.frequency.cancelScheduledValues(actx.currentTime);
    deck.filter.frequency.setTargetAtTime(freq, actx.currentTime, 0.05);
  }
}
function rampFilter(deck, freq, seconds) {
  if (webAudioOk && deck.filter && actx) {
    deck.filter.frequency.cancelScheduledValues(actx.currentTime);
    deck.filter.frequency.setValueAtTime(deck.filter.frequency.value, actx.currentTime);
    deck.filter.frequency.exponentialRampToValueAtTime(Math.max(40, freq), actx.currentTime + seconds);
  }
}

function urlFor(track) {
  return track.source === "local"
    ? `/local/file/${encodeURIComponent(track.id)}`
    : `/audio/${encodeURIComponent(track.id)}`;
}
function playFromContext(list, idx, ctxLabel, playlistId) {
  if (!list || !list.length || idx < 0) return;
  player.queue = list.slice();
  player.contextName = ctxLabel || "Songs";
  player.playlistId = playlistId || null;
  startAt(idx, false);
}
function startAt(idx, fade, tr) {
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
  to.el.currentTime = fade && tr ? (tr.inSkip || 0) : 0;
  if (webAudioOk) setDeckLevel(to, 1);
  to.el.play().catch(() => onTrackError(to));
  if (fade) {
    const F = Math.max(0.4, (tr && tr.length) || settings.crossfade || 3);
    const style = (tr && tr.style) || "fade";
    if (webAudioOk && to.gain && actx) {
      to.gain.gain.cancelScheduledValues(actx.currentTime);
      to.gain.gain.setValueAtTime(0, actx.currentTime);
    }
    if (style === "rise") {
      setDeckFilter(to, 500);
      rampFilter(to, 18000, F);
      setDeckFilter(from, 18000);
    } else if (style === "blend") {
      setDeckFilter(to, 500);
      rampFilter(to, 18000, F);
      rampFilter(from, 500, F);
    } else {
      setDeckFilter(to, 18000);
      setDeckFilter(from, 18000);
    }
    rampDeck(to, (tr && tr.inGain) || 1, F);
    rampDeck(from, 0, F);
    setTimeout(() => {
      from.el.pause();
      from.trackId = null;
      setDeckFilter(from, 18000);
    }, F * 1000 + 120);
  } else if (webAudioOk) {
    setDeckFilter(to, 18000);
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
async function advance(auto, fade, tr) {
  const n = nextIndex(auto);
  if (n >= 0) { startAt(n, fade, tr); return; }
  if (auto) {
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
    } catch {}
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
    toast("Couldn't load this track, skipping to the next one.");
    advance(true, false);
  } else {
    toast("Playback stopped, the stream couldn't be loaded.");
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
    if (remaining <= 45 && remaining > 2) {
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
    if (remaining <= 20 && player.repeat !== 2 && hasNext(true) && !deck.fading) {
      const tr = currentTransition();
      const F = tr.length || 0;
      if (F > 0 && remaining <= F + (tr.outSkip || 0) && remaining > 0.15) {
        deck.fading = true;
        advance(true, true, tr);
        setTimeout(() => { deck.fading = false; }, (F + (tr.outSkip || 0)) * 1000 + 200);
      }
    }
  });
});

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
  crossfadeAmbient(src);
}
const ambientLayers = [$("#ambientA"), $("#ambientB")];
let ambientActive = 0;
function crossfadeAmbient(src) {
  const outgoing = ambientLayers[ambientActive];
  const incoming = ambientLayers[1 - ambientActive];
  if (!incoming || !outgoing) return;
  const next = src ? cssUrl(src) : "";
  if (incoming.style.backgroundImage === next && incoming.classList.contains("active")) return;
  if (outgoing.style.backgroundImage === next && outgoing.classList.contains("active")) return;
  incoming.style.backgroundImage = next;
  incoming.classList.add("active");
  outgoing.classList.remove("active");
  ambientActive = 1 - ambientActive;
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
$("#pHeart").onclick = (e) => { e.stopPropagation(); if (player.current) toggleLike(player.current); };
$("#npHeart").onclick = () => { if (player.current) toggleLike(player.current); };
let preMuteVolume = 80;
const VOL_SVGS = [
  '<svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4z" fill="currentColor" stroke="none"/><line x1="16" y1="9" x2="22" y2="15"/><line x1="22" y1="9" x2="16" y2="15"/></svg>',
  '<svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4z" fill="currentColor" stroke="none"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/></svg>',
  '<svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5 6 9H3v6h3l5 4z" fill="currentColor" stroke="none"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M18.5 5.5a9.4 9.4 0 0 1 0 13"/></svg>',
];
function volumeIcon(v) { return VOL_SVGS[v <= 0 ? 0 : v < 50 ? 1 : 2]; }
function setVolume(v) {
  v = Math.max(0, Math.min(100, Math.round(v)));
  if (v > 0) preMuteVolume = v;
  $("#volume").value = v;
  paintRangeFill($("#volume"));
  const npv = $("#npVolume");
  if (npv) { npv.value = v; paintRangeFill(npv); }
  $("#muteBtn").innerHTML = volumeIcon(v);
  $("#npMute").innerHTML = volumeIcon(v);
  store.set("arzxsh.volume", v);
  if (webAudioOk && masterGain) masterGain.gain.value = v / 100;
  else decks.forEach((d) => { d.el.volume = v / 100; });
}
function toggleMute() {
  const cur = Number($("#volume").value);
  if (cur > 0) setVolume(0);
  else setVolume(preMuteVolume || 80);
}
$("#volume").addEventListener("input", () => setVolume(Number($("#volume").value)));
$("#npVolume").addEventListener("input", () => setVolume(Number($("#npVolume").value)));
$("#muteBtn").onclick = toggleMute;
$("#npMute").onclick = toggleMute;
setVolume(store.get("arzxsh.volume", 80));
if (navigator.mediaSession) {
  navigator.mediaSession.setActionHandler("play", togglePlay);
  navigator.mediaSession.setActionHandler("pause", togglePlay);
  navigator.mediaSession.setActionHandler("nexttrack", () => { const n = nextIndex(false); if (n >= 0) startAt(n, false); });
  navigator.mediaSession.setActionHandler("previoustrack", prevTrack);
}

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
  showNpLyrics();
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
$("#queueBtn").onclick = () => togglePanel("queue");
$("#npClose").onclick = closeNp;
$("#npQueueBtn").onclick = () => {
  const card = $("#queueCard");
  card.classList.toggle("hidden");
  $("#npQueueBtn").classList.toggle("on", !card.classList.contains("hidden"));
  renderQueue();
};
function renderQueue() {
  const html = player.queue.map((t, i) => `
    <button class="queue-row ${player.current && t.id === player.current.id ? "current" : ""}" data-qid="${esc(t.id)}" data-qidx="${i}">
      <img src="${esc(t.artwork || "")}" alt="">
      <span class="q-main"><div class="q-title">${esc(t.title)}</div><div class="q-sub">${esc(t.artist)}</div></span>
      <span class="row-dur">${fmt(t.duration_s)}</span>
    </button>`).join("");
  const note = player.queue.length
    ? `${player.queue.length} tracks · radio continues when the queue ends`
    : "";
  [["#queueList", "#queueNote"], ["#panelQueueList", "#panelQueueNote"]]
    .forEach(([listSel, noteSel]) => {
      const box = $(listSel);
      if (!box) return;
      box.innerHTML = html;
      $(noteSel).textContent = note;
      box.querySelectorAll("[data-qidx]").forEach((b) =>
        b.addEventListener("click", () => startAt(Number(b.dataset.qidx), false)));
    });
}

const panel = { open: false, tab: "lyrics" };
function syncPanelUi() {
  document.body.classList.toggle("panel-open", panel.open);
  $("#panelTabLyrics").classList.toggle("active", panel.tab === "lyrics");
  $("#panelTabQueue").classList.toggle("active", panel.tab === "queue");
  $("#panelLyricsView").classList.toggle("hidden", panel.tab !== "lyrics");
  $("#panelQueueView").classList.toggle("hidden", panel.tab !== "queue");
  $("#panelLyricsBtn").classList.toggle("on", panel.open && panel.tab === "lyrics");
  $("#queueBtn").classList.toggle("on", panel.open && panel.tab === "queue");
}
function setPanelTab(tab) {
  panel.tab = tab;
  syncPanelUi();
  if (tab === "lyrics") {
    loadLyricsForCurrent();
    updateLyricsSync(activeDeck.el.currentTime || 0, true);
  } else {
    renderQueue();
  }
}
function openPanel(tab) {
  panel.open = true;
  setPanelTab(tab || panel.tab);
  setTimeout(() => updateLyricsSync(activeDeck.el.currentTime || 0, true), 420);
  wakeMotionLoop(450);
}
function closePanel() {
  panel.open = false;
  syncPanelUi();
}
function togglePanel(tab) {
  if (panel.open && panel.tab === tab) closePanel();
  else openPanel(tab);
}
$("#panelTabLyrics").onclick = () => setPanelTab("lyrics");
$("#panelTabQueue").onclick = () => setPanelTab("queue");
$("#panelClose").onclick = closePanel;
$("#panelLyricsBtn").onclick = () => togglePanel("lyrics");

function playNext(track) {
  if (!track) return;
  register([track], null);
  if (!player.current) { playFromContext([track], 0, "Songs"); return; }
  player.queue.splice(player.index + 1, 0, track);
  renderQueue();
  toast("Plays next");
}
function addToQueue(track) {
  if (!track) return;
  register([track], null);
  if (!player.current) { playFromContext([track], 0, "Songs"); return; }
  player.queue.push(track);
  renderQueue();
  toast("Added to queue");
}
const ctxMenu = $("#ctxMenu");
let ctxOpen = false;
function closeCtxMenu() {
  ctxMenu.classList.add("hidden");
  ctxOpen = false;
}
let ctxLastX = 0, ctxLastY = 0;
function openCtxMenu(x, y, items) {
  ctxLastX = x; ctxLastY = y;
  ctxMenu.innerHTML = items.map((it, i) => it.sep
    ? `<div class="ctx-sep"></div>`
    : `<button class="ctx-item" data-ctx-item="${i}"><span class="ctx-ico">${it.icon || ""}</span>${esc(it.label)}</button>`).join("");
  ctxMenu.classList.remove("hidden");
  ctxOpen = true;
  const w = ctxMenu.offsetWidth;
  const h = ctxMenu.offsetHeight;
  ctxMenu.style.left = `${Math.max(6, Math.min(x, window.innerWidth - w - 8))}px`;
  ctxMenu.style.top = `${Math.max(6, Math.min(y, window.innerHeight - h - 8))}px`;
  ctxMenu.querySelectorAll("[data-ctx-item]").forEach((b) =>
    b.addEventListener("click", () => {
      const it = items[Number(b.dataset.ctxItem)];
      closeCtxMenu();
      if (it && it.action) it.action();
    }));
}
function trackCtxItems(track, playAction) {
  const items = [];
  if (playAction) items.push({ label: "Play", icon: "▶", action: playAction });
  items.push({ label: "Play Next", icon: "⤵", action: () => playNext(track) });
  items.push({ label: "Add to Queue", icon: "+", action: () => addToQueue(track) });
  items.push({ sep: true });
  items.push({
    label: isLiked(track.id) ? "Unlike" : "Like",
    icon: isLiked(track.id) ? "♥" : "♡",
    action: () => toggleLike(track),
  });
  items.push({ label: "Add to Playlist", icon: "＋", action: () => openPlaylistPicker(track) });
  if (track.source !== "local" && !downloads[track.id]) {
    items.push({ label: "Download", icon: "⬇", action: () => doDownload(track) });
  }
  if (track.artist_id || track.album_id) items.push({ sep: true });
  if (track.artist_id) {
    items.push({ label: "Go to Artist", icon: "→", action: () => navigate({ type: "artist", id: track.artist_id }) });
  }
  if (track.album_id) {
    items.push({ label: "Go to Album", icon: "→", action: () => navigate({ type: "album", id: track.album_id }) });
  }
  return items;
}
function openPlaylistPicker(track) {
  const items = playlists.map((p) => ({
    label: p.name, icon: "♪",
    action: () => addToPlaylist(p, track),
  }));
  if (items.length) items.push({ sep: true });
  items.push({
    label: "New Playlist…", icon: "＋",
    action: async () => { const p = await createPlaylist(); if (p) addToPlaylist(p, track); },
  });
  openCtxMenu(ctxLastX, ctxLastY, items);
}
document.addEventListener("contextmenu", (e) => {
  const row = e.target.closest("[data-play]");
  const albumCard = e.target.closest("[data-album]");
  const artistCard = e.target.closest("[data-artist]");
  if (!row && !albumCard && !artistCard) return;
  e.preventDefault();
  if (row) {
    const track = findTrack(row.dataset.play);
    if (!track) return;
    const list = contexts.get(row.dataset.ctx) || [track];
    const idx = list.findIndex((t) => t.id === track.id);
    const items = trackCtxItems(track,
      idx >= 0 ? () => playFromContext(list, idx, CTX_LABELS[row.dataset.ctx] || "Songs") : null);
    if (row.dataset.ctx === "playlistTracks" && routes[routeIdx].type === "playlist") {
      items.push({ sep: true });
      items.push({
        label: "Remove from Playlist", icon: "✕",
        action: () => {
          const p = getPlaylist(routes[routeIdx].id);
          if (p) { removeFromPlaylist(p, track.id); renderPlaylist(p.id); }
        },
      });
    }
    openCtxMenu(e.clientX, e.clientY, items);
  } else if (albumCard) {
    const id = albumCard.dataset.album;
    openCtxMenu(e.clientX, e.clientY, [
      { label: "Play", icon: "▶", action: () => quickPlayAlbum(id) },
      { label: "Open Album", icon: "→", action: () => navigate({ type: "album", id }) },
    ]);
  } else if (artistCard) {
    const id = artistCard.dataset.artist;
    openCtxMenu(e.clientX, e.clientY, [
      { label: "Play Top Songs", icon: "▶", action: () => quickPlayArtist(id) },
      { label: "Open Artist", icon: "→", action: () => navigate({ type: "artist", id }) },
    ]);
  }
});
document.addEventListener("mousedown", (e) => {
  if (ctxOpen && !e.target.closest("#ctxMenu")) closeCtxMenu();
});
view.addEventListener("scroll", () => { if (ctxOpen) closeCtxMenu(); }, { passive: true });
window.addEventListener("resize", () => { if (ctxOpen) closeCtxMenu(); });

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

const accentCache = new Map();
let accentTrackId = null;
function applyArtworkAccent(track) {
  accentTrackId = track.id;
  if (settings.accentMode === "red") {
    setNpAccent(DEFAULT_ACCENT);
    vizNpColor = DEFAULT_ACCENT;
    return;
  }
  if (settings.accentMode === "custom") {
    setNpAccent(settings.customAccent);
    vizNpColor = settings.customAccent;
    return;
  }
  if (!track.artwork) {
    setNpAccent(null);
    vizNpColor = DEFAULT_ACCENT;
    return;
  }
  if (accentCache.has(track.id)) {
    const rgb = accentCache.get(track.id);
    setNpAccent(rgb);
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
      setNpAccent(rgb);
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

const lyricsCache = new Map();
let lyricsTrackId = null;
const syncState = { lines: [], active: -1, trackId: null, karaoke: -1, wordCounts: [] };
const syncViews = [
  { box: $("#lyricsSync"), inner: $("#lyricsSyncInner"), els: [], words: [] },
  { box: $("#panelLyricsSync"), inner: $("#panelLyricsSyncInner"), els: [], words: [] },
];
const plainLyricsEls = [$("#lyricsText"), $("#panelLyricsText")];
function showNpLyrics() {
  $("#lyricsCard").classList.remove("hidden");
  $("#lyricsBtn").classList.add("on");
  loadLyricsForCurrent();
}
function resetLyrics(track) {
  lyricsTrackId = track.id;
  clearSyncedLyrics();
  if (!$("#nowPlaying").classList.contains("hidden")) showNpLyrics();
  else {
    $("#lyricsCard").classList.add("hidden");
    $("#lyricsBtn").classList.remove("on");
  }
  if (panel.open && panel.tab === "lyrics") loadLyricsForCurrent();
}
function clearSyncedLyrics() {
  syncState.lines = [];
  syncState.active = -1;
  syncState.trackId = null;
  syncState.karaoke = -1;
  syncState.wordCounts = [];
  syncViews.forEach((v) => {
    v.els = [];
    v.words = [];
    v.box.classList.add("hidden");
    v.inner.innerHTML = "";
    v.inner.style.transform = "";
  });
  plainLyricsEls.forEach((el) => el.classList.remove("hidden"));
}
function showPlainLyrics(text) {
  clearSyncedLyrics();
  plainLyricsEls.forEach((el) => { el.textContent = text; });
}
function buildSyncedLyrics(lines) {
  clearSyncedLyrics();
  syncState.lines = lines;
  syncState.trackId = lyricsTrackId;
  syncState.wordCounts = lines.map((l) => l.text.split(/\s+/).filter(Boolean).length);
  syncViews.forEach((v) => {
    v.inner.innerHTML = lines.map((l, i) => {
      const words = l.text.split(/\s+/).filter(Boolean);
      if (!words.length) return `<div class="sync-line" data-li="${i}">${esc(l.text)}</div>`;
      return `<div class="sync-line has-words" data-li="${i}">${words.map((w) => `<span class="w">${esc(w)}</span>`).join(" ")}</div>`;
    }).join("");
    v.els = [...v.inner.children];
    v.words = v.els.map((el) => [...el.querySelectorAll(".w")]);
    v.els.forEach((el, i) => el.addEventListener("click", () => {
      activeDeck.el.currentTime = lines[i].t;
      updateLyricsSync(lines[i].t, true);
    }));
    v.box.classList.remove("hidden");
  });
  plainLyricsEls.forEach((el) => el.classList.add("hidden"));
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
    syncViews.forEach((v) => {
      v.els.forEach((el, i) => {
        const distance = idx >= 0 ? Math.abs(i - idx) : 99;
        el.classList.toggle("active", i === idx);
        el.classList.toggle("passed", idx >= 0 && i < idx);
        el.classList.toggle("depth-1", distance === 1);
        el.classList.toggle("depth-2", distance === 2);
        el.classList.toggle("depth-3", distance === 3);
        el.classList.toggle("depth-far", idx >= 0 && distance >= 4);
        if (i !== idx) el.style.removeProperty("--karaoke");
      });
      const el = v.els[Math.max(0, idx)];
      if (el && v.box.clientHeight) {
        const y = v.box.clientHeight / 2 - (el.offsetTop + el.offsetHeight / 2);
        v.inner.style.transform = `translateY(${y}px)`;
      }
    });
  }
  if (idx < 0) return;
  if (!syncViews.some((v) => v.els[idx])) return;
  if (motionReduced()) {
    if (syncState.karaoke !== 1) {
      syncViews.forEach((v) => {
        if (v.els[idx]) v.els[idx].style.setProperty("--karaoke", "100%");
      });
      syncState.karaoke = 1;
    }
    return;
  }
  const start = lines[idx].t;
  const next = idx + 1 < lines.length ? lines[idx + 1].t : start + 5;
  const duration = Math.max(0.8, next - start);
  const pct = clamp01((now - start) / duration);
  if (force || Math.abs(pct - syncState.karaoke) >= 0.004) {
    const wordCount = syncState.wordCounts[idx] || 0;
    syncViews.forEach((v) => {
      if (!v.els[idx] || !v.box.clientHeight) return;
      v.els[idx].style.setProperty("--karaoke", `${(pct * 100).toFixed(2)}%`);
      const spans = v.words[idx];
      if (spans && wordCount) {
        spans.forEach((s, j) => {
          s.style.setProperty("--w", `${(clamp01(pct * wordCount - j) * 100).toFixed(1)}%`);
        });
      }
    });
    syncState.karaoke = pct;
  }
}
$("#npDownload").onclick = () => {
  if (player.current && player.current.source !== "local" && !downloads[player.current.id]) doDownload(player.current);
  else if (player.current) toast("Already saved for offline");
};
async function loadLyricsForCurrent() {
  if (!player.current) return;
  const id = player.current.id;
  if (lyricsCache.has(id)) { fillLyrics(lyricsCache.get(id)); return; }
  showPlainLyrics("Loading…");
  $("#lyricsSource").textContent = "";
  try {
    const r = await api(player.current.source === "local"
      ? `/local/lyrics/${encodeURIComponent(id)}`
      : `/lyrics/${encodeURIComponent(id)}`);
    lyricsCache.set(id, r);
    if (lyricsTrackId === id) fillLyrics(r);
  } catch {
    if (lyricsTrackId === id) showPlainLyrics("Lyrics could not be loaded.");
  }
}
function fillLyrics(r) {
  if (r.synced && r.synced.length) buildSyncedLyrics(r.synced);
  else showPlainLyrics(r.lyrics || "No lyrics found for this track.");
  $("#lyricsSource").textContent = r.source ? `Source: ${r.source}` : "";
}
$("#lyricsBtn").onclick = () => {
  const card = $("#lyricsCard");
  const show = card.classList.contains("hidden");
  card.classList.toggle("hidden", !show);
  $("#lyricsBtn").classList.toggle("on", show);
  if (show) loadLyricsForCurrent();
};

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
  if (syncState.lines.length) updateLyricsSync(now);
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

document.addEventListener("keydown", (e) => {
  const tag = e.target.tagName;
  const typing = tag === "TEXTAREA" || tag === "SELECT" ||
    (tag === "INPUT" && ["text", "search", "color"].includes(e.target.type));
  if (e.key === "Escape") {
    if (ctxOpen) { closeCtxMenu(); return; }
    if (panel.open) { closePanel(); return; }
    if (!$("#nowPlaying").classList.contains("hidden")) { closeNp(); return; }
    return;
  }
  if (typing) return;
  if (e.key === "/") {
    e.preventDefault();
    searchInput.focus();
    searchInput.select();
    return;
  }
  if ((e.ctrlKey || e.metaKey) && e.key === "ArrowRight") {
    e.preventDefault();
    const n = nextIndex(false);
    if (n >= 0) startAt(n, false);
    return;
  }
  if ((e.ctrlKey || e.metaKey) && e.key === "ArrowLeft") {
    e.preventDefault();
    prevTrack();
    return;
  }
  if (e.key === "l" || e.key === "L") { togglePanel("lyrics"); return; }
  if (e.key === "q" || e.key === "Q") { togglePanel("queue"); return; }
  if (e.key === "f" || e.key === "F") {
    if ($("#nowPlaying").classList.contains("hidden")) openNp(false);
    return;
  }
  if (!player.current) return;
  if (e.code === "Space" && tag !== "BUTTON") { e.preventDefault(); togglePlay(); }
  else if (e.key === "ArrowRight" && tag !== "INPUT") activeDeck.el.currentTime += 10;
  else if (e.key === "ArrowLeft" && tag !== "INPUT") activeDeck.el.currentTime -= 10;
  else if (e.key === "ArrowUp" && tag !== "INPUT") {
    e.preventDefault();
    setVolume(Number($("#volume").value) + 5);
  } else if (e.key === "ArrowDown" && tag !== "INPUT") {
    e.preventDefault();
    setVolume(Number($("#volume").value) - 5);
  }
});

document.body.classList.add("no-player");
applySettings(false);
paintSeekRatio(0, 0);
renderSideRecent();
renderSidePlaylists();
const ART_SPACER = "data:image/gif;base64,R0lGODlhAQABAAAAACw=";
document.addEventListener("error", (e) => {
  const t = e.target;
  if (t && t.tagName === "IMG" && t.src !== ART_SPACER) t.src = ART_SPACER;
}, true);
[likes, recentPlays, ...playlists.map((p) => p.tracks)].forEach((list) => {
  list.forEach((t) => {
    if (!t || !t.artwork) return;
    if (t.artwork.includes("maxresdefault")) {
      t.artwork = t.artwork.replace("maxresdefault", "hqdefault");
    }
    if (t.artwork.includes("googleusercontent.com")) {
      if (/=w\d+-h\d+/.test(t.artwork)) {
        t.artwork = t.artwork.replace(/=w\d+-h\d+/, "=w1080-h1080");
      } else if (!t.artwork.split("/").pop().includes("=")) {
        t.artwork += "=w1080-h1080";
      }
    }
  });
});
store.set("arzxsh.likes", likes);
store.set("arzxsh.recent", recentPlays);
store.set("arzxsh.playlists", playlists);
navigate({ type: "home" }, true);
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
