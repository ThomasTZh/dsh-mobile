package dev.dsh.mobile.web

/**
 * Small, reversible adaptations to the DSH web UI for a phone. They are applied
 * only inside this app's WebView, never on the PC, and degrade to the stock DSH
 * layout if a future DSH build renames its classes.
 *
 * - Enter inserts a newline; the send button still submits. DSH's own keymap
 *   handles Shift+Enter as a newline, so plain Enter is remapped to that path.
 * - Session switches no longer autofocus the composer and pop the keyboard;
 *   a tap on the composer (or its send button) still focuses it.
 * - The session header becomes two rows on narrow screens: title + utility
 *   icons, then preset/background-job/actions, with the tabs below. Its empty
 *   64px leading grid column is collapsed so the title starts at the left.
 * - The composer toolbar becomes two rows: plus/permission, then
 *   enhance/model/send. DSH's compact mode hides the permission and model
 *   labels; the labels are restored because the extra row has room.
 * - Portaled menus are raised above the sidebar's z-index (1200).
 */
object UiTweaks {
    private val css: String = """
        @media (max-width: 600px) {
          .wSkVaW_header {
            height: auto !important;
            grid-template-columns: 0 minmax(0, 1fr) !important;
          }
          .wSkVaW_headerLeading { display: none !important; }
          .wSkVaW_titleRow {
            flex-wrap: wrap !important;
            height: auto !important;
            position: relative;
            row-gap: 4px;
            grid-column: 1 / -1 !important;
            width: 100% !important;
          }
          .wSkVaW_titleCluster {
            flex: 1 1 100% !important;
            flex-wrap: wrap !important;
            row-gap: 4px;
          }
          .wSkVaW_crumbs {
            flex: 1 1 100% !important;
            padding-right: 184px;
            box-sizing: border-box;
            min-width: 0;
          }
          .wSkVaW_headerActions {
            flex: 1 1 100% !important;
            order: 2;
          }
          .wSkVaW_headerUtilities {
            position: absolute !important;
            top: 0;
            right: 32px;
            height: 28px;
          }
          .wSkVaW_headerCorner {
            position: absolute !important;
            top: 0;
            right: 0;
          }

          .uV2eYG_row { height: auto !important; }
          .uV2eYG_tools { flex: 1 1 100% !important; }
          .uV2eYG_trailing {
            flex: 1 1 100% !important;
            justify-content: flex-start !important;
          }
          .uV2eYG_trailing .uV2eYG_primary { margin-left: auto !important; }
          .uV2eYG_tools .iWlSmW_triggerLabel { display: inline-flex !important; }
          .uV2eYG_standardControls ._7KE1Ra_triggerLabel,
          .uV2eYG_standardControls ._7KE1Ra_triggerEffort {
            display: inline-flex !important;
          }
          .uV2eYG_standardControls [aria-label^="Select model"] {
            max-width: 230px !important;
          }
        }
        [role="menu"] {
          z-index: 2000 !important;
        }
    """.trimIndent()

    val script: String = """
        (function () {
          try {
            function ensureStyle() {
              try {
                if (!document.head || document.getElementById('dsh-mobile-ui')) return;
                var style = document.createElement('style');
                style.id = 'dsh-mobile-ui';
                style.textContent = ${cssJsLiteral(css)};
                document.head.appendChild(style);
              } catch (e) {}
            }
            ensureStyle();
            document.addEventListener('DOMContentLoaded', ensureStyle);
            window.addEventListener('load', ensureStyle);

            var allowFocusUntil = 0;
            var COMPOSER = '[data-composer-input]';
            var COMPOSER_AREA = '.wSkVaW_composerStack';

            function insideComposer(target) {
              if (!target || target.nodeType !== 1) return false;
              try {
                return !!target.closest(COMPOSER + ',' + COMPOSER_AREA + ',[aria-label="Send message"]');
              } catch (e) { return false; }
            }

            document.addEventListener('pointerdown', function (event) {
              if (insideComposer(event.target)) allowFocusUntil = Date.now() + 1500;
            }, true);

            document.addEventListener('focusin', function (event) {
              var target = event.target;
              if (!target || target.nodeType !== 1 || !target.matches(COMPOSER)) return;
              if (Date.now() <= allowFocusUntil) return;
              try { target.blur(); } catch (e) {}
              try {
                var selection = window.getSelection();
                if (selection) selection.removeAllRanges();
              } catch (e) {}
            }, true);

            document.addEventListener('keydown', function (event) {
              if (event.key !== 'Enter' || event.isComposing) return;
              if (event.shiftKey || event.ctrlKey || event.metaKey || event.altKey) return;
              var target = event.target;
              if (!target || target.nodeType !== 1 || !target.closest(COMPOSER)) return;
              try {
                Object.defineProperty(event, 'shiftKey', {
                  configurable: true,
                  get: function () { return true; }
                });
              } catch (e) {
                // If the native event is locked, replace it with a Shift+Enter.
                try {
                  event.preventDefault();
                  event.stopImmediatePropagation();
                  target.dispatchEvent(new KeyboardEvent('keydown', {
                    key: 'Enter', code: 'Enter', shiftKey: true,
                    bubbles: true, cancelable: true
                  }));
                } catch (e2) {}
              }
            }, true);
            window.__dshMobileUi = true;
          } catch (e) {}
        })();
    """.trimIndent()

    private fun cssJsLiteral(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("")
                else -> append(ch)
            }
        }
        append('"')
    }
}
