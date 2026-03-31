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
        val candidateUrls = listOf(url, webView.url.orEmpty()).filter { it.isNotBlank() }.distinct()
        val matchedExtensions = repository.getPassiveExtensions()
            .filter { it.enabled && matchesAny(candidateUrls, it) }

        matchedExtensions.forEach { extension ->
                val scriptContent = repository.readScript(extension) ?: return@forEach
                val documentReady = webView.url.orEmpty().isNotBlank()
                webView.post {
                    webView.evaluateJavascript(
                        buildLegacyFallbackScript(
                            extension = extension,
                            scriptContent = scriptContent,
                            shouldPersistExecutionState = documentReady,
                        )
                    ) {
                        Log.d(TAG, "Fallback extension ${extension.name} executed: $it")
                    }
                }
            }
    }

    private fun matchesAny(urls: List<String>, extension: UserExtensionMeta): Boolean {
        if (urls.isEmpty()) return false
        return urls.any { matches(it, extension) }
    }

    private fun buildLegacyFallbackScript(
        extension: UserExtensionMeta,
        scriptContent: String,
        shouldPersistExecutionState: Boolean,
    ): String {
        val extensionName = extension.name
        val sourceUrl = buildSourceUrl(extensionName)
        val runAt = (extension.runAt ?: PassiveRunAt.DOM_CONTENT_LOADED).name
        val persistExecutionState = if (shouldPersistExecutionState) {
            "executionState[extensionName] = true;"
        } else {
            ""
        }
        return """
            (function() {
              const extensionName = ${extensionName.asJsString()};
              const runAt = ${runAt.asJsString()};
              const executionState = window.__einkbroExecutedPassiveExtensions || (window.__einkbroExecutedPassiveExtensions = {});
              if (executionState[extensionName]) {
                return;
              }

              const run = function() {
                if (executionState[extensionName]) {
                  return;
                }
                try {
                  $scriptContent
                  $persistExecutionState
                } catch (error) {
                  const message = error && error.message ? error.message : String(error);
                  const stack = error && error.stack ? error.stack : '';
                  console.error(${ExtensionErrorLogStore.ERROR_PREFIX.asJsString()} + extensionName + ': ' + message + (stack ? '\n' + stack : ''));
                }
              };

              if (runAt === 'DOM_CONTENT_LOADED' && document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', run, { once: true });
              } else {
                run();
              }
            })();
            //# sourceURL=$sourceUrl
        """.trimIndent()
    }

    fun buildRuntimeWrappedScript(
        extensionName: String,
        scriptContent: String,
        preventDuplicateExecution: Boolean = false,
    ): String {
        val sourceUrl = buildSourceUrl(extensionName)
        val duplicateGuard = if (preventDuplicateExecution) {
            """
              const executionState = window.__einkbroExecutedPassiveExtensions || (window.__einkbroExecutedPassiveExtensions = {});
              if (executionState[extensionName]) {
                return;
              }
            """.trimIndent()
        } else {
            ""
        }
        val markExecuted = if (preventDuplicateExecution) {
            "executionState[extensionName] = true;"
        } else {
            ""
        }
        return """
            (function() {
              const extensionName = ${extensionName.asJsString()};
              const sourceUrl = ${sourceUrl.asJsString()};
              const errorPrefix = ${ExtensionErrorLogStore.ERROR_PREFIX.asJsString()};
              const userScript = ${scriptContent.asJsString()} + '\n//# sourceURL=' + sourceUrl;
              $duplicateGuard

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
                $markExecuted
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

              function globMatches(pattern, target) {
                const normalizedPattern = String(pattern || '').toLowerCase();
                const normalizedTarget = String(target || '').toLowerCase();
                if (normalizedPattern === '*') {
                  return true;
                }

                const parts = normalizedPattern.split('*');
                const startsWithWildcard = normalizedPattern.startsWith('*');
                const endsWithWildcard = normalizedPattern.endsWith('*');
                let currentIndex = 0;

                for (let index = 0; index < parts.length; index++) {
                  const part = parts[index];
                  if (!part) continue;

                  const foundIndex = normalizedTarget.indexOf(part, currentIndex);
                  if (foundIndex === -1) {
                    return false;
                  }

                  if (index === 0 && !startsWithWildcard && foundIndex !== 0) {
                    return false;
                  }

                  currentIndex = foundIndex + part.length;
                }

                if (!endsWithWildcard) {
                  const lastPart = parts[parts.length - 1];
                  return !lastPart || normalizedTarget.endsWith(lastPart);
                }

                return true;
              }

              function matches() {
                const target = matchType === 'DOMAIN' ? currentHost : currentUrl;
                return matchValues.some(function(value) {
                  return globMatches(value, target);
                });
              }

              if (!matches()) {
                return;
              }

              const run = function() {
                try {
                  ${buildRuntimeWrappedScript(extension.name, scriptContent, preventDuplicateExecution = true)}
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
        val matchValues = repository.splitMatchValues(extension.matchValue)
        if (matchValues.contains("*")) return true

        val target = when (extension.matchType ?: PassiveMatchType.LINK) {
            PassiveMatchType.DOMAIN -> Uri.parse(url).host.orEmpty()
            PassiveMatchType.LINK -> url
        }
        return matchValues.any { globMatches(it, target) }
    }

    private fun globMatches(pattern: String, target: String): Boolean {
        if (pattern == "*") return true

        val normalizedPattern = pattern.lowercase()
        val normalizedTarget = target.lowercase()
        val parts = normalizedPattern.split('*')
        val startsWithWildcard = normalizedPattern.startsWith('*')
        val endsWithWildcard = normalizedPattern.endsWith('*')
        var currentIndex = 0

        parts.forEachIndexed { index, part ->
            if (part.isEmpty()) return@forEachIndexed

            val foundIndex = normalizedTarget.indexOf(part, currentIndex)
            if (foundIndex < 0) return false
            if (index == 0 && !startsWithWildcard && foundIndex != 0) return false
            currentIndex = foundIndex + part.length
        }

        val lastPart = parts.lastOrNull().orEmpty()
        return endsWithWildcard || lastPart.isEmpty() || normalizedTarget.endsWith(lastPart)
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
