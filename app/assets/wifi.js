// Bevestiging voor een gescande wifi-QR-code (MainActivity.WifiBridge): WifiQr.info(), WifiQr.add(), WifiQr.close();
// de app meldt de voortgang via window.wifiEvent({ state: connecting|connected|failed, text }).
(function () {
    'use strict';
    var W = window.WifiQr;
    var $ = function (s) { return document.querySelector(s); };
    var info = {};
    try { info = JSON.parse(W.info() || '{}'); } catch (e) { info = {}; }
    $('#ssid').textContent = info.ssid || '?';
    $('#security').textContent = info.security || '';
    $('#hidden').textContent = info.hidden ? 'ja' : 'nee';

    $('#add').addEventListener('click', function () {
        this.disabled = true;
        W.add();
    });
    $('#close').addEventListener('click', function () { W.close(); });

    window.wifiEvent = function (d) {
        var box = $('#state');
        box.hidden = false;
        box.className = 'wifi-state' + (d.state === 'connected' ? ' ok' : d.state === 'failed' ? ' err' : '');
        $('#state-text').textContent = d.text;
        if (d.state === 'failed') {
            $('#add').disabled = false;
            $('#add').textContent = 'Opnieuw proberen';
            $('#close').textContent = 'Sluiten';
        }
        if (d.state === 'connected') { $('#add').hidden = true; $('#close').textContent = 'Sluiten'; }
    };
})();
