// Netwerk toevoegen (MainActivity.WifiBridge): een wifi-QR-code scannen, of een netwerk in de buurt kiezen.
//   WifiQr.info() -> { qr, offline }, WifiQr.scan(), WifiQr.add() (de QR-code), WifiQr.connect(ssid, security, password),
//   WifiQr.close(). De app meldt terug via window.wifiEvent({ type: 'status'|'networks'|'qr', ... }).
(function () {
    'use strict';
    var W = window.WifiQr;
    var $ = function (s, el) { return (el || document).querySelector(s); };
    var esc = function (s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); };
    var info = {};
    try { info = JSON.parse(W.info() || '{}'); } catch (e) { info = {}; }
    var open = null; // het netwerk waarvan het wachtwoordveld open staat
    var busy = false;

    $('#title').textContent = info.offline ? 'Je bent offline' : 'Netwerk toevoegen';
    $('#sub').textContent = info.offline ? 'Voeg een wifi-netwerk toe om weer verbinding te maken.' : 'Voeg een wifi-netwerk toe of kies een ander netwerk.';

    function showQr(qr) {
        $('#confirm').hidden = !qr;
        $('#choose').hidden = !!qr;
        if (!qr) return;
        $('#qr-ssid').textContent = qr.ssid;
        $('#qr-security').textContent = qr.security;
        $('#qr-hidden').textContent = qr.hidden ? 'ja' : 'nee';
        $('#qr-add').disabled = false;
        $('#qr-add').textContent = 'Toevoegen en verbinden';
    }
    showQr(info.qr);

    $('#qr-add').addEventListener('click', function () { this.disabled = true; busy = true; W.add(); });
    $('#qr-back').addEventListener('click', function () { showQr(null); });
    $('#close').addEventListener('click', function () { W.close(); });
    $('#refresh').addEventListener('click', function () { $('#list-hint').textContent = 'Zoeken…'; $('#list-hint').hidden = false; W.scan(); });

    function bars(level) {
        var r = '';
        for (var i = 0; i < 4; i++) r += '<rect x="' + i * 6 + '" y="' + (16 - (i + 1) * 4) + '" width="4" height="' + (i + 1) * 4 + '" rx="1"' + (i < level ? '' : ' class="off"') + '/>';
        return '<svg class="bars" viewBox="0 0 22 16" aria-hidden="true">' + r + '</svg>';
    }

    var networks = [];
    function renderList() {
        var ul = $('#nets');
        ul.innerHTML = networks.map(function (n, i) {
            var tags = [];
            if (n.current) tags.push('<span class="on">verbonden</span>');
            else if (n.saved) tags.push('opgeslagen');
            if (n.security === 'EAP') tags.push('niet ondersteund');
            tags.push(n.band);
            var form = '';
            if (open === n.ssid) {
                var needPw = n.security !== 'OPEN' && n.security !== 'EAP';
                form = '<div class="net-form">'
                    + (needPw ? '<input type="password" id="pw" autocomplete="off" placeholder="' + (n.saved ? 'Wachtwoord (leeg: opgeslagen wachtwoord)' : 'Wachtwoord') + '">' : '')
                    + (n.security === 'EAP' ? '<div class="hint">Bedrijfsnetwerken met een gebruikersnaam worden niet ondersteund.</div>'
                        : '<div class="row"><button class="btn primary" data-connect="' + i + '">Verbinden</button></div>')
                    + '</div>';
            }
            return '<li class="net"><button class="net-row" data-net="' + i + '">'
                + '<span class="net-name">' + esc(n.ssid) + '<div class="net-tags">' + tags.join(' · ') + '</div></span>'
                + (n.security !== 'OPEN' ? '<span class="net-lock" aria-label="beveiligd">🔒</span>' : '') + bars(n.level)
                + '</button>' + form + '</li>';
        }).join('');
        var pw = $('#pw');
        if (pw) pw.focus();
    }

    $('#nets').addEventListener('click', function (e) {
        var c = e.target.closest('[data-connect]');
        if (c) {
            if (busy) return;
            var n = networks[+c.getAttribute('data-connect')];
            var pw = $('#pw');
            busy = true;
            c.disabled = true;
            W.connect(n.ssid, n.security, pw ? pw.value : '');
            return;
        }
        var row = e.target.closest('[data-net]');
        if (!row) return;
        var net = networks[+row.getAttribute('data-net')];
        open = open === net.ssid ? null : net.ssid;
        renderList();
    });
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && e.target.id === 'pw') { var b = $('[data-connect]'); if (b) b.click(); }
    });

    window.wifiEvent = function (d) {
        if (d.type === 'qr') { showQr(d.qr); return; }
        if (d.type === 'networks') {
            networks = d.list || [];
            var hint = $('#list-hint');
            hint.hidden = networks.length > 0;
            hint.textContent = d.location === false ? 'Zet locatie aan op het toestel om netwerken in de buurt te zien.' : 'Geen netwerken gevonden.';
            if (!busy) renderList();
            return;
        }
        // status
        busy = d.state === 'connecting';
        var box = $('#state');
        box.hidden = false;
        box.className = 'wifi-state' + (d.state === 'connected' ? ' ok' : d.state === 'failed' ? ' err' : '');
        $('#state-text').textContent = d.text;
        if (d.state === 'failed') {
            $('#qr-add').disabled = false;
            $('#qr-add').textContent = 'Opnieuw proberen';
            renderList();
        }
    };

    W.scan();
    setInterval(function () { if (!busy && !open) W.scan(); }, 15000);
})();
