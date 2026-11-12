/**
 * core/fetch-rewrite.js - file:// WebView -> core origin fetch rewrite.
 *
 * The WebView loads from file:///android_asset/web/, so main.js builds its
 * transport from window.location.origin ("file://") and the Connect/grpc-web
 * transport then fetches e.g.
 *   file://exa.language_server_pb.languageserverservice/Method
 * which the WebView rejects: URL scheme "file" is not supported.
 *
 * Rewrite file:// or relative ConnectRPC URLs onto the core origin, preserving
 * method/headers/body and attaching the CSRF token. Idempotent and a no-op for
 * non-RPC / already-absolute http(s) URLs.
 *
 * The original periodic HEAD ping is not ported (it referenced an undefined
 * helper; see docs/DEADCODE.md).
 */
var RPC_MARKERS = ['exa.language_server_pb.', 'exa.host_bridge_pb.', '/exa.'];

// RPC path (no leading slash) when `url` is a file:// or relative ConnectRPC
// URL; null for anything else (http(s), blob, data, static assets).
function rpcPathFrom(url) {
  if (!url) return null;
  var s = String(url);
  if (/^(https?|blob|data|wss?|chrome-extension):/i.test(s)) return null;
  var best = -1;
  for (var i = 0; i < RPC_MARKERS.length; i++) {
    var at = s.indexOf(RPC_MARKERS[i]);
    if (at >= 0 && (best === -1 || at < best)) best = at;
  }
  if (best === -1) return null;
  return s.slice(best).replace(/^\/+/, '') || null;
}

function installFetchRewrite() {
  if (global.__AG_FETCH_REWRITE__ || typeof global.fetch !== 'function') return;
  var origFetch = global.fetch.bind(global);

  function fetchWithStartupRetry(u, opts, retries) {
    return origFetch(u, opts).catch(function (err) {
      if (retries > 0) {
        return new Promise(function (resolve) {
          setTimeout(function () {
            resolve(fetchWithStartupRetry(u, opts, retries - 1));
          }, 120);
        });
      }
      throw err;
    });
  }

  var activeStreams = typeof Set === 'function' ? new Set() : null;
  var lastHiddenAt = 0;

  function isStreamUrl(u) {
    return typeof u === 'string' && (u.indexOf('/Stream') !== -1 || u.indexOf('StreamAgentState') !== -1 || u.indexOf('StreamCascade') !== -1);
  }

  function fetchWithStreamTracking(u, opts, origResource, useStartupRetry) {
    var urlStr = typeof u === 'string' ? u : (origResource && origResource.url) || '';
    if (!activeStreams || !isStreamUrl(urlStr) || typeof AbortController !== 'function' || typeof ReadableStream !== 'function') {
      return useStartupRetry ? fetchWithStartupRetry(u, opts, 30) : origFetch(u, opts);
    }

    var streamCtrl = new AbortController();
    var callerSignal = (opts && opts.signal) || (origResource && typeof origResource === 'object' ? origResource.signal : null);
    var onCallerAbort = null;
    if (callerSignal) {
      if (callerSignal.aborted) {
        try { streamCtrl.abort(callerSignal.reason); } catch (e) { streamCtrl.abort(); }
      } else {
        onCallerAbort = function () {
          try { streamCtrl.abort(callerSignal.reason); } catch (e) { streamCtrl.abort(); }
        };
        callerSignal.addEventListener('abort', onCallerAbort, { once: true });
      }
    }

    var entry = {
      ctrl: streamCtrl,
      url: urlStr,
      lastDataAt: Date.now(),
      forceClose: null,
      cleaned: false
    };
    activeStreams.add(entry);

    function cleanup() {
      if (entry.cleaned) return;
      entry.cleaned = true;
      activeStreams.delete(entry);
      if (callerSignal && onCallerAbort) {
        try { callerSignal.removeEventListener('abort', onCallerAbort); } catch (e) {}
      }
    }

    var nextOpts = {};
    if (opts) {
      for (var k in opts) {
        if (Object.prototype.hasOwnProperty.call(opts, k)) nextOpts[k] = opts[k];
      }
    }
    nextOpts.signal = streamCtrl.signal;

    var pending = useStartupRetry ? fetchWithStartupRetry(u, nextOpts, 30) : origFetch(u, nextOpts);
    return pending.then(function (resp) {
      if (!resp || !resp.body || typeof resp.body.getReader !== 'function') {
        cleanup();
        return resp;
      }
      var contentType = '';
      try { contentType = (resp.headers && resp.headers.get('content-type')) || ''; } catch (e) {}
      var isConnectStream = contentType.indexOf('connect+') !== -1;
      var origReader = resp.body.getReader();
      var resolveForceClose = null;
      var forceClosePromise = new Promise(function (resolve) {
        resolveForceClose = resolve;
      });

      entry.forceClose = function () {
        if (entry.cleaned) return;
        cleanup();
        if (resolveForceClose) {
          resolveForceClose({ __agyForceClose: true });
        }
        try { origReader.cancel('agy:resume-refresh').catch(function () {}); } catch (e) {}
        try { streamCtrl.abort(); } catch (e) {}
      };

      var wrappedBody = new ReadableStream({
        pull: function (controller) {
          return Promise.race([origReader.read(), forceClosePromise]).then(function (res) {
            if (res && res.__agyForceClose) {
              if (isConnectStream && typeof Uint8Array === 'function') {
                // Valid empty Connect EndStreamResponse frame (flags=2, len=2, "{}")
                // allows consumeAgentStateStream to exit cleanly and rehydrate.
                controller.enqueue(new Uint8Array([2, 0, 0, 0, 2, 123, 125]));
              }
              controller.close();
              return;
            }
            entry.lastDataAt = Date.now();
            if (res.done) {
              cleanup();
              controller.close();
            } else {
              controller.enqueue(res.value);
            }
          }, function (err) {
            cleanup();
            controller.error(err);
          });
        },
        cancel: function (reason) {
          cleanup();
          try { streamCtrl.abort(); } catch (e) {}
          return origReader.cancel(reason);
        }
      });

      var wrappedResp = new Response(wrappedBody, {
        status: resp.status,
        statusText: resp.statusText,
        headers: resp.headers
      });
      try {
        if (resp.url) Object.defineProperty(wrappedResp, 'url', { value: resp.url });
      } catch (e) {}
      return wrappedResp;
    }, function (err) {
      cleanup();
      throw err;
    });
  }

  function recoverStalledStreams(awayMs) {
    if (!activeStreams || activeStreams.size === 0) return;
    var resumeAt = Date.now();
    setTimeout(function () {
      activeStreams.forEach(function (entry) {
        if (!entry.cleaned && entry.lastDataAt < resumeAt && (awayMs >= 2000 || (Date.now() - entry.lastDataAt) >= 3000)) {
          try {
            console.log('[AGY bridge.core] Refreshing stale stream after background (' + awayMs + 'ms):', entry.url);
            if (entry.forceClose) entry.forceClose();
          } catch (e) {}
        }
      });
    }, 800);
  }

  try {
    global.addEventListener('agy:host-pause', function () {
      lastHiddenAt = Date.now();
    });
    global.addEventListener('agy:host-resume', function (ev) {
      var away = (ev && ev.detail && typeof ev.detail.awayMs === 'number')
        ? ev.detail.awayMs
        : (lastHiddenAt ? Date.now() - lastHiddenAt : 0);
      lastHiddenAt = 0;
      recoverStalledStreams(away);
    });
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', function () {
        if (document.hidden) {
          if (!lastHiddenAt) lastHiddenAt = Date.now();
        } else if (lastHiddenAt) {
          var away = Date.now() - lastHiddenAt;
          lastHiddenAt = 0;
          recoverStalledStreams(away);
        }
      });
    }
  } catch (e) {}

  global.fetch = function (resource, init) {
    try {
      // Request object: rebuild against the core origin, keep method/body.
      if (resource && typeof resource === 'object' && typeof resource.url === 'string') {
        var rpath = rpcPathFrom(resource.url);
        if (rpath) {
          try {
            var targetUrl = appendCsrfQuery(coreBase() + '/' + rpath);
            var reqMethod = resource.method || (init && init.method) || 'POST';
            var reqHeaders = new Headers(resource.headers || (init && init.headers) || {});
            ensureCsrf(reqHeaders);

            var bodyPromise;
            if (init && init.body !== undefined) {
              bodyPromise = Promise.resolve(init.body);
            } else {
              try {
                bodyPromise = resource.clone().arrayBuffer().then(function (buf) {
                  return buf.byteLength > 0 ? buf : undefined;
                }).catch(function () { return undefined; });
              } catch (e) {
                bodyPromise = Promise.resolve(undefined);
              }
            }

            return bodyPromise.then(function (bodyData) {
              var nextOpts = {
                method: reqMethod,
                headers: reqHeaders,
                body: bodyData,
                credentials: 'omit'
              };
              return fetchWithStreamTracking(targetUrl, nextOpts, resource, true);
            });
          } catch (e) { warn('fetch rewrite (Request)', e); }
        }
        return fetchWithStreamTracking(resource, init, resource, false);
      }

      // String URL: the Connect/grpc-web transport's common path.
      if (typeof resource === 'string') {
        var path = rpcPathFrom(resource);
        if (path) {
          var url = appendCsrfQuery(coreBase() + '/' + path);
          var headers = new Headers((init && init.headers) || {});
          ensureCsrf(headers);
          var next = {};
          if (init) {
            for (var k in init) {
              if (Object.prototype.hasOwnProperty.call(init, k)) next[k] = init[k];
            }
          }
          next.headers = headers;

          return fetchWithStreamTracking(url, next, null, true);
        }
        return fetchWithStreamTracking(resource, init, null, false);
      }
    } catch (e) { warn('fetch rewrite', e); }
    return origFetch(resource, init);
  };
  global.__AG_FETCH_REWRITE__ = true;
}

installFetchRewrite();
