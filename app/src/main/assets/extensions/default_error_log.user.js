// ==UserScript==
// @name        错误日志
// @namespace   einkbro
// @match       *://*/*
// @run-at      document-start
// @version     1.0.0
// @description Show JavaScript errors on page
// ==/UserScript==

(function() {
    var errorLog = null;
    var errorLogReady = false;

    function createErrorLog() {
        if (errorLog) return;
        errorLog = document.createElement('div');
        errorLog.style.cssText = 'position:fixed;bottom:0;left:0;right:0;background:#fff;border-top:2px solid red;font-size:12px;padding:5px;max-height:150px;overflow-y:auto;z-index:99999;';
        errorLogReady = true;
    }

    window.addEventListener('error', function(e) {
        var msg = 'Error: ' + e.message + ' at ' + (e.filename ? e.filename.split('/').pop() : '') + ':' + e.lineno;

        if (errorLogReady) {
            var div = document.createElement('div');
            div.textContent = new Date().toLocaleTimeString() + ' ' + msg;
            div.style.color = 'red';
            errorLog.appendChild(div);
        } else {
            console.error('[ErrorLog]', msg);
        }
    });

    if (document.body) {
        createErrorLog();
        document.body.appendChild(errorLog);
    } else {
        document.addEventListener('DOMContentLoaded', function() {
            createErrorLog();
            document.body.appendChild(errorLog);
        });
    }
})();