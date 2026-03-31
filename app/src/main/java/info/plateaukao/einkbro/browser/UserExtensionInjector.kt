package info.plateaukao.einkbro.browser

import android.net.Uri
import android.util.Log
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import info.plateaukao.einkbro.preference.PassiveMatchType
import info.plateaukao.einkbro.preference.PassiveRunAt
import info.plateaukao.einkbro.preference.UserExtensionMeta
import info.plateaukao.einkbro.preference.UserExtensionRepository
import info.plateaukao.einkbro.view.EBWebView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.WeakHashMap

class UserExtensionInjector(
    private val repository: UserExtensionRepository,
) {
    private val scriptHandlers = WeakHashMap<EBWebView, MutableList<ScriptHandler>>()

    fun registerDocumentStartScripts(webView: EBWebView) {
        clearHandlers(webView)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return

        val handlers = mutableListOf<ScriptHandler>()
        repository.getPassiveExtensions()
            .filter { it.enabled }
            .forEach { extension ->
                val scriptContent = repository.readScript(extension) ?: return@forEach
                handlers += WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    buildDocumentStartScript(extension, scriptContent),
                    setOf("*")
                )
            }
        scriptHandlers[webView] = handlers
    }

    fun runFallbackScripts(webView: EBWebView, url: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return

        repository.getPassiveExtensions()
            .filter { it.enabled && matches(url, it) }
            .forEach { extension ->
                val scriptContent = repository.readScript(extension) ?: return@forEach
                webView.evaluateJavascript(buildRuntimeWrappedScript(extension.name, scriptContent)) {
                    Log.d(TAG, "Fallback extension ${extension.name} executed: $it")
                }
            }
    }

    fun buildRuntimeWrappedScript(
        extensionName: String,
        scriptContent: String,
    ): String {
        val sourceUrl = buildSourceUrl(extensionName)
        return """
            (function() {
              const extensionName = ${extensionName.asJsString()};
              const sourceUrl = ${sourceUrl.asJsString()};
              const errorPrefix = ${ExtensionErrorLogStore.ERROR_PREFIX.asJsString()};
              const userScript = ${scriptContent.asJsString()} + '\n//# sourceURL=' + sourceUrl;

              function formatError(error) {
                if (!error) return 'Unknown error';
                if (typeof error === 'string') return error;
                const message = error.message ? String(error.message) : String(error);
                const stack = error.stack ? String(error.stack) : '';
                return stack ? message + '\n' + stack : message;
              }

              function extractExtensionName(text) {
                const match = String(text || '').match(/einkbro-extension:\/\/([^\s)\]]+)/);
                if (!match) return 'Unknown extension';
                try {
                  return decodeURIComponent(match[1]);
                } catch (_) {
                  return match[1];
                }
              }

              function installGlobalErrorHooks() {
                if (window.__einkbroExtensionErrorHooksInstalled) {
                  return;
                }
                window.__einkbroExtensionErrorHooksInstalled = true;

                window.addEventListener('error', function(event) {
                  const filename = event && event.filename ? String(event.filename) : '';
                  const stack = event && event.error && event.error.stack ? String(event.error.stack) : '';
                  if (filename.indexOf('einkbro-extension://') === -1 &&
                      stack.indexOf('einkbro-extension://') === -1) {
                    return;
                  }

                  const derivedExtensionName = extractExtensionName(filename || stack);
                  const message = event && event.message
                    ? String(event.message)
                    : formatError(event && event.error);
                  const location = filename
                    ? ' @ ' + filename + ':' + (event.lineno || 0) + ':' + (event.colno || 0)
                    : '';
                  console.error(errorPrefix + derivedExtensionName + ': ' + message + location + (stack ? '\n' + stack : ''));
                }, true);

                window.addEventListener('unhandledrejection', function(event) {
                  const reason = event ? event.reason : null;
                  const formattedReason = formatError(reason);
                  if (formattedReason.indexOf('einkbro-extension://') === -1) {
                    return;
                  }

                  const derivedExtensionName = extractExtensionName(formattedReason);
                  console.error(errorPrefix + derivedExtensionName + ': Unhandled promise rejection\n' + formattedReason);
                });
              }

              installGlobalErrorHooks();

              try {
                (0, eval)(userScript);
              } catch (error) {
                console.error(errorPrefix + extensionName + ': ' + formatError(error));
              }
            })();
        """.trimIndent()
    }

    private fun clearHandlers(webView: EBWebView) {
        scriptHandlers.remove(webView)?.forEach { it.remove() }
    }

    private fun buildDocumentStartScript(
        extension: UserExtensionMeta,
        scriptContent: String,
    ): String {
        val matchValuesJson = Json.encodeToString(repository.splitMatchValues(extension.matchValue))
        val matchType = (extension.matchType ?: PassiveMatchType.LINK).name
        val runAt = (extension.runAt ?: PassiveRunAt.DOM_CONTENT_LOADED).name
        return """
            (function() {
              const extensionName = ${extension.name.asJsString()};
              const matchType = ${matchType.asJsString()};
              const runAt = ${runAt.asJsString()};
              const matchValues = $matchValuesJson;
              const currentUrl = window.location.href;
              const currentHost = window.location.hostname;

              function escapeRegex(value) {
                return value.replace(/[.*+?^${'$'}()|[\]\\]/g, '\\$&');
              }

              function globToRegex(value) {
                return new RegExp('^' + escapeRegex(value).replace(/\\\*/g, '.*') + '$');
              }

              function matches() {
                const target = matchType === 'DOMAIN' ? currentHost : currentUrl;
                return matchValues.some(function(value) {
                  return globToRegex(value).test(target);
                });
              }

              if (!matches()) {
                return;
              }

              const run = function() {
                try {
                  ${buildRuntimeWrappedScript(extension.name, scriptContent)}
                } catch (error) {
                  console.error('User extension failed: ' + extensionName, error);
                }
              };

              if (runAt === 'DOM_CONTENT_LOADED') {
                if (document.readyState === 'loading') {
                  document.addEventListener('DOMContentLoaded', run, { once: true });
                } else {
                  run();
                }
              } else {
                run();
              }
            })();
            //# sourceURL=${buildSourceUrl(extension.name)}
        """.trimIndent()
    }

    private fun matches(url: String, extension: UserExtensionMeta): Boolean {
        val target = when (extension.matchType ?: PassiveMatchType.LINK) {
            PassiveMatchType.DOMAIN -> Uri.parse(url).host.orEmpty()
            PassiveMatchType.LINK -> url
        }
        return repository.splitMatchValues(extension.matchValue).any { globToRegex(it).matches(target) }
    }

    private fun globToRegex(value: String): Regex {
        val escaped = Regex.escape(value).replace("\\*", ".*")
        return Regex("^$escaped$")
    }

    private fun String.asJsString(): String =
        this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("`", "\\`")
            .replace("$" + "{", "\\$" + "{")
            .let { "\"$it\"" }

    private fun buildSourceUrl(extensionName: String): String =
        "einkbro-extension://${Uri.encode(extensionName)}"

    companion object {
        private const val TAG = "UserExtensionInjector"
    }
}
