const CACHE = 'despejo-teste-v1';
const SHELL = ['./', 'index.html', 'manifest.webmanifest', 'icon.svg'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));

function openDb() {
  return new Promise((res, rej) => {
    const r = indexedDB.open('despejo-teste', 1);
    r.onupgradeneeded = () => {
      const d = r.result;
      d.createObjectStore('recs', { keyPath: 'id' });
      const c = d.createObjectStore('chunks', { autoIncrement: true });
      c.createIndex('rec', 'rec');
    };
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}

// Recebe áudio vindo do menu "Compartilhar" do Android (Web Share Target)
async function receiveShare(request) {
  const form = await request.formData();
  const files = form.getAll('audio').filter(f => f && f.size);
  if (!files.length) return Response.redirect('./?shared=0', 303);
  const d = await openDb();
  await new Promise((res, rej) => {
    const t = d.transaction(['recs'], 'readwrite');
    for (const f of files) {
      const now = Date.now();
      t.objectStore('recs').put({
        id: crypto.randomUUID(), start: now, end: now, mime: f.type, source: 'compartilhado',
        name: f.name, chunks: 0, bytes: f.size, maxGap: 0, gaps: [], events: [], blob: f
      });
    }
    t.oncomplete = res; t.onerror = () => rej(t.error);
  });
  return Response.redirect('./?shared=1', 303);
}

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method === 'POST' && url.pathname.endsWith('/share')) {
    e.respondWith(receiveShare(e.request));
    return;
  }
  if (e.request.method !== 'GET') return;
  e.respondWith(
    fetch(e.request).then(r => {
      const copy = r.clone();
      caches.open(CACHE).then(c => c.put(e.request, copy));
      return r;
    }).catch(() => caches.match(e.request, { ignoreSearch: true }))
  );
});
