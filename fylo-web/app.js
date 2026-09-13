/**
 * FYLO — Application Bootstrap (app.js)
 */

import { bus, EVENTS }    from './core/eventBus.js';
import { State, Actions } from './core/state.js';
import { initStorage, FileStorage, SettingsStorage } from './core/storage.js';
import { Libs }           from './core/libs.js';
import { Router }         from './core/router.js';
import { PAGES }          from './core/constants.js';
import { toast, openModal, closeModal, ripple } from './core/ui.js';
import { createLogger }   from './core/logger.js';

import { ReaderModule, wireReaderToolbar, initGestureEngine }
  from './features/reader/index.js';
import { EditorModule, wireEditorToolbar }
  from './features/editor/index.js';
import { CameraModule }  from './features/camera/index.js';
import { ToolsModule }   from './features/tools/index.js';
import { FilesModule }   from './features/files/index.js';
import { SettingsModule } from './features/settings/index.js';

const log = createLogger('App');

// ── HTML escape (XSS fix for search results) ──────────────────────────────────
function _esc(str) {
  return String(str)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
    .replace(/"/g,'&quot;').replace(/\'\'/g,'&#39;');
}

// ── Extended app-level events ─────────────────────────────────────────────────
const APP_EVENTS = {
  READER_OPEN_REQUEST: 'reader:openRequest',
  EDITOR_OPEN_REQUEST: 'editor:openRequest',
  FILES_DELETED:       'files:deleted',
};

// ── Bootstrap ─────────────────────────────────────────────────────────────────
async function bootstrap() {
  log.info('FYLO starting');

  try {
    await initStorage();
    log.info('Storage ready');
  } catch (e) {
    log.error('Storage init failed:', e);
    toast('Storage unavailable — files will not persist');
  }

  Libs.init(); // fire-and-forget

  await SettingsModule.load();
  await _loadPersistedFiles();

  _registerRoutes();
  wireReaderToolbar();
  wireEditorToolbar();
  SettingsModule.init();
  _wireGlobalEvents();
  initGestureEngine(ReaderModule);
  Router.init();
  FilesModule.wireFileTabs();
  FilesModule.renderFiles();
  FilesModule.updateStorage();
  FilesModule.renderRecent();
  ToolsModule.renderCategories();
  ToolsModule.wireInputs();

  _hideSplash();

  if (typeof window.AndroidBridge !== 'undefined') {
    window.fyloEventBus = bus;
    import('./core/android-bridge.js').then(({ installBackHandler }) => {
      installBackHandler();
    }).catch(e => log.warn('Android bridge setup failed:', e));
    bus.on('camera:closeRequest', () => {
      import('./features/camera/index.js').then(({ CameraModule }) => CameraModule.close());
    });
    log.info('Android bridge active');
  }

  bus.emit(EVENTS.APP_READY, {});
  log.info('Bootstrap complete');
}

// ── Persisted files (parallel loads) ─────────────────────────────────────────
async function _loadPersistedFiles() {
  const [files, favIds] = await Promise.all([
    FileStorage.loadAll().catch(e => { log.warn('Could not load persisted files:', e); return null; }),
    SettingsStorage.load('favorites').catch(e => { log.warn('Could not load favorites:', e); return null; }),
  ]);
  if (files && files.length) {
    Actions.setFiles(files.reverse());
    log.info('Loaded ' + files.length + ' persisted files');
  }
  if (Array.isArray(favIds)) {
    favIds.forEach(id => {
      if (State.files.find(f => f.id === id)) Actions.toggleFavorite(id);
    });
    log.info('Loaded ' + favIds.length + ' favorites');
  }
}

// ── Routes ────────────────────────────────────────────────────────────────────
function _registerRoutes() {
  Router.register(PAGES.HOME,     { el: document.getElementById('page-home') });
  Router.register(PAGES.FILES,    {
    el:      document.getElementById('page-files'),
    onEnter: () => { FilesModule.renderFiles(); FilesModule.updateStorage(); },
  });
  Router.register(PAGES.TOOLS,    {
    el:      document.getElementById('page-tools'),
    onEnter: () => ToolsModule.renderCategories(),
  });
  Router.register(PAGES.SETTINGS, {
    el:      document.getElementById('page-settings'),
    onEnter: () => SettingsModule.render(),
  });
  Router.register(PAGES.READER,   {
    el:      document.getElementById('page-reader'),
    onLeave: () => ReaderModule.close(),
  });
  Router.register(PAGES.EDITOR,   { el: document.getElementById('page-editor') });
}

// ── Global events ─────────────────────────────────────────────────────────────
function _wireGlobalEvents() {
  document.querySelectorAll('.nav-item[data-page]').forEach(item => {
    item.addEventListener('click', e => { ripple(e, item); Router.go(item.dataset.page); });
  });
  document.getElementById('back-btn')?.addEventListener('click',
    () => Router.go(State.prevPage || PAGES.HOME));

  bus.on(APP_EVENTS.READER_OPEN_REQUEST, ({ fileId })       => ReaderModule.open(fileId));
  bus.on(APP_EVENTS.EDITOR_OPEN_REQUEST, ({ fileId, tool }) => EditorModule.open(fileId, tool));
  bus.on(APP_EVENTS.FILES_DELETED, () => {
    FilesModule.renderFiles();
    FilesModule.renderRecent();
    FilesModule.updateStorage();
  });

  const fileInput = document.getElementById('file-input');
  fileInput?.addEventListener('change', e => {
    const files = [...e.target.files];
    if (files.length) FilesModule.handleFiles(files);
    e.target.value = '';
  });
  document.getElementById('fab')?.addEventListener('click', () => fileInput?.click());

  const dropTargets = [
    document.getElementById('page-files'),
    document.getElementById('page-home'),
  ].filter(Boolean);
  dropTargets.forEach(zone => {
    zone.addEventListener('dragover',  e => { e.preventDefault(); zone.classList.add('drag-over'); });
    zone.addEventListener('dragleave', e => { if (!zone.contains(e.relatedTarget)) zone.classList.remove('drag-over'); });
    zone.addEventListener('drop', e => {
      e.preventDefault(); zone.classList.remove('drag-over');
      const files = [...e.dataTransfer.files].filter(f =>
        f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf'));
      if (files.length) FilesModule.handleFiles(files);
    });
  });

  const searchBtn   = document.getElementById('search-btn');
  const searchBar   = document.getElementById('search-overlay');
  const searchInput = document.getElementById('search-input');
  const searchClose = document.getElementById('search-close');

  searchBtn?.addEventListener('click', () => {
    searchBar?.classList.remove('hidden');
    searchBar?.classList.add('show');
    searchInput?.focus();
  });
  searchClose?.addEventListener('click', () => {
    searchBar?.classList.remove('show');
    searchBar?.classList.add('hidden');
    if (searchInput) searchInput.value = '';
    const resultsEl = document.getElementById('search-results');
    if (resultsEl) resultsEl.innerHTML = '';
    if (State.page === PAGES.FILES) FilesModule.renderFiles();
  });
  searchInput?.addEventListener('input', () => {
    const q = searchInput.value.trim().toLowerCase();
    const resultsEl = document.getElementById('search-results');
    if (!resultsEl) return;
    if (!q) { resultsEl.innerHTML = ''; return; }
    const filtered = State.files.filter(f => f.name.toLowerCase().includes(q));
    if (!filtered.length) {
      resultsEl.innerHTML = '<p style="padding:12px;color:var(--text-tertiary);font-size:13px">No PDFs found</p>';
    } else {
      resultsEl.innerHTML = filtered.map(f =>
        '<div class="search-result-item" data-file-id="' + _esc(f.id) + '" style="padding:10px 16px;display:flex;align-items:center;gap:10px;cursor:pointer;border-bottom:1px solid var(--border)">' +
        '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>' +
        '<span style="font-size:13px;color:var(--text-primary);flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + _esc(f.name) + '</span>' +
        '</div>').join('');
      resultsEl.querySelectorAll('[data-file-id]').forEach(el => {
        el.addEventListener('click', () => {
          searchBar?.classList.remove('show');
          searchBar?.classList.add('hidden');
          if (searchInput) searchInput.value = '';
          resultsEl.innerHTML = '';
          bus.emit('reader:openRequest', { fileId: el.dataset.fileId });
        });
      });
    }
    if (State.page === PAGES.FILES) bus.emit('files:renderFiltered', { files: filtered });
  });

  document.getElementById('modal-close')?.addEventListener('click', closeModal);
  document.getElementById('modal-overlay')?.addEventListener('click', e => {
    if (e.target.id === 'modal-overlay') closeModal();
  });

  document.getElementById('menu-btn')?.addEventListener('click', () => {
    if (State.page === PAGES.READER) {
      document.getElementById('reader-sidebar')?.classList.toggle('open');
    }
  });

  document.querySelectorAll('[data-page]').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('[data-page]').forEach(b => b.removeAttribute('aria-current'));
      btn.setAttribute('aria-current', 'page');
    });
  });

  bus.on('camera:openRequest', () => CameraModule.open());

  document.addEventListener('keydown', e => {
    const typing = ['INPUT','TEXTAREA','SELECT'].includes(document.activeElement?.tagName);
    const mod    = e.ctrlKey || e.metaKey;
    if (State.page === PAGES.READER && !typing) {
      if (e.key === '+' || e.key === '=') { e.preventDefault(); ReaderModule.zoomIn(); }
      if (e.key === '-')                  { e.preventDefault(); ReaderModule.zoomOut(); }
      if (e.key === 'Escape')             { e.preventDefault(); Router.go(PAGES.FILES); }
      if (e.key === 'ArrowLeft')          { e.preventDefault(); ReaderModule.goToPage(ReaderModule.currentPage - 1); }
      if (e.key === 'ArrowRight')         { e.preventDefault(); ReaderModule.goToPage(ReaderModule.currentPage + 1); }
    }
    if (State.page === PAGES.EDITOR) {
      if (mod && e.key === 'z')                                      { e.preventDefault(); EditorModule.undo(); }
      if (mod && (e.key === 'y' || (e.shiftKey && e.key === 'z')))  { e.preventDefault(); EditorModule.redo(); }
      if (!typing && (e.key === 'Delete' || e.key === 'Backspace')) { e.preventDefault(); EditorModule.deleteSelected(); }
    }
    if (e.key === 'Escape') {
      const overlay = document.getElementById('modal-overlay');
      if (overlay?.classList.contains('show')) closeModal();
    }
  });

  let _resizeTimer, _lastW = 0, _lastH = 0;
  window.addEventListener('resize', () => {
    if (State.page !== PAGES.READER) return;
    clearTimeout(_resizeTimer);
    _resizeTimer = setTimeout(() => {
      const ww = window.innerWidth, wh = window.innerHeight;
      const major = Math.abs(ww - _lastW) > 80 || Math.abs(wh - _lastH) > 80;
      _lastW = ww; _lastH = wh;
      const rs = ReaderModule._rs;
      if (!rs?.canvasWidth) return;
      if (major) {
        const wrap = document.getElementById('reader-canvas-wrap');
        const fit  = Math.max(0.4, Math.min(3, Math.min(
          (wrap.clientWidth  - 16) / rs.canvasWidth,
          (wrap.clientHeight - 16) / rs.canvasHeight,
        )));
        rs.scale = fit; rs._scale = 1;
        ReaderModule.renderPage(rs.currentPage, true);
      } else {
        ReaderModule._clampTransform?.();
        ReaderModule._applyTransform?.();
      }
    }, 200);
  });

  bus.on(EVENTS.APP_NAV, ({ page, push }) => Router.go(page, push ?? true));
  log.info('Global events wired');
}

// ── Splash hide (forceful — no CSS dependency) ────────────────────────────────
function _hideSplash() {
  const splash = document.getElementById('splash');
  const app    = document.getElementById('app');
  if (!splash || splash.style.display === 'none') return;

  splash.style.opacity       = '0';
  splash.style.pointerEvents = 'none';
  splash.style.transition    = 'opacity 350ms';
  splash.classList.add('fade-out');

  if (app) {
    app.classList.remove('hidden');
    app.style.display = '';
    app.style.opacity = '1';
    app.classList.add('show');
  }

  setTimeout(() => {
    splash.style.display = 'none';
    splash.classList.add('hidden');
  }, 400);
}

// ── Error safety nets ─────────────────────────────────────────────────────────
window.onerror = () => _hideSplash();
window.addEventListener('unhandledrejection', () => _hideSplash());

// ── Service Worker (web/PWA only — disabled on Android) ───────────────────────
if ('serviceWorker' in navigator && typeof window.AndroidBridge === 'undefined') {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('./sw.js', { scope: './' })
      .then(reg => {
        log.info('Service Worker registered:', reg.scope);
        navigator.serviceWorker.addEventListener('message', e => {
          if (e.data?.type === 'SW_UPDATED') bus.emit('sw:updateAvailable', {});
        });
      })
      .catch(e => log.warn('Service Worker registration failed:', e));
  });
}

bootstrap();
