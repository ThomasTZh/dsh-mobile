package dev.dsh.mobile.web

/**
 * Runs before any page script. DSH is served over plain http on the LAN, so a
 * browser (and a WebView) has no secure-context clipboard; the native bridge
 * restores copy/paste and share. The WebSocket constructor is wrapped only to
 * report liveness to the native status dot.
 */
object Injection {
    val documentStart: String = """
        (function () {
          // Android WebView on this device reports a 0-height layout viewport for
          // CSS vh/media queries even though window.innerHeight is correct, which
          // collapses the DSH grid (height: 100%). Pin the height in pixels.
          try {
            function viewportUnitsBroken() {
              try {
                var probe = document.createElement('div');
                probe.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:100vh;visibility:hidden;pointer-events:none';
                (document.body || document.documentElement).appendChild(probe);
                var broken = probe.getBoundingClientRect().height === 0;
                probe.remove();
                return broken;
              } catch (e) {
                return false;
              }
            }
            function fitViewport() {
              try {
                var existing = document.getElementById('dsh-mobile-fit');
                if (!viewportUnitsBroken()) {
                  if (existing) existing.remove();
                  return;
                }
                var height = Math.max(1, window.innerHeight || 0);
                var px = height + 'px';
                var style = existing;
                if (!style) {
                  if (!document.head) return;
                  style = document.createElement('style');
                  style.id = 'dsh-mobile-fit';
                  document.head.appendChild(style);
                }
                style.textContent = 'html,body,#root{height:' + px + ' !important;}';
              } catch (e) {}
            }
            window.__dshMobileFit = fitViewport;
            fitViewport();
            window.addEventListener('resize', fitViewport, { passive: true });
            window.addEventListener('orientationchange', fitViewport, { passive: true });
            if (window.visualViewport) window.visualViewport.addEventListener('resize', fitViewport, { passive: true });
            document.addEventListener('DOMContentLoaded', fitViewport);
            window.addEventListener('load', fitViewport);
          } catch (e) {}
          try {
            var bridge = window.DshMobile;
            if (!bridge) return;
            try {
              if (!window.isSecureContext || !navigator.clipboard) {
                Object.defineProperty(navigator, 'clipboard', {
                  configurable: true,
                  value: {
                    writeText: function (text) {
                      try { bridge.copyText(String(text == null ? '' : text)); return Promise.resolve(); }
                      catch (e) { return Promise.reject(e); }
                    },
                    readText: function () {
                      try { return Promise.resolve(String(bridge.readClipboard() || '')); }
                      catch (e) { return Promise.reject(e); }
                    }
                  }
                });
              }
            } catch (e) {}
            try {
              if (!navigator.share) {
                navigator.share = function (data) {
                  try {
                    bridge.shareText(String((data && data.text) || ''), String((data && data.url) || ''));
                    return Promise.resolve();
                  } catch (e) { return Promise.reject(e); }
                };
              }
            } catch (e) {}
            try {
              var Native = window.WebSocket;
              if (Native && !window.__dshMobileWs) {
                window.__dshMobileWs = true;
                function report(state) { try { bridge.socketState(state); } catch (e) {} }
                function Wrapped(url, protocols) {
                  var socket = arguments.length > 1 ? new Native(url, protocols) : new Native(url);
                  socket.addEventListener('open', function () { report('open'); });
                  socket.addEventListener('close', function () { report('closed'); });
                  socket.addEventListener('error', function () { report('error'); });
                  return socket;
                }
                Wrapped.prototype = Native.prototype;
                ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (key) {
                  try { Object.defineProperty(Wrapped, key, { value: Native[key], enumerable: true }); } catch (e) {}
                });
                window.WebSocket = Wrapped;
              }
            } catch (e) {}
            window.__DSH_MOBILE__ = true;
          } catch (e) {}
        })();
    """.trimIndent()

    const val LOGIN_PROBE: String =
        "(function(){try{return document.querySelector('form[action=\"/pocket-login\"]')?'login':" +
            "((document.title||'').indexOf('正在进入')>=0||(document.title||'').toLowerCase().indexOf('opening')>=0" +
            "?'transition':'app');}catch(e){return 'unknown';}})()"
}
