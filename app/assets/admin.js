// Beheerscherm van EventVOX-Webkiosk. Praat met de app via window.Kiosk (MainActivity.AdminBridge):
//   Kiosk.state() -> JSON, Kiosk.pin(code) -> {ok, locked}, Kiosk.set(key, value) -> {ok, error},
//   Kiosk.action(name, arg). De app meldt terug via window.kioskEvent(type, data).
(function () {
    'use strict';
    var K = window.Kiosk;
    var $ = function (s, el) { return (el || document).querySelector(s); };
    var $$ = function (s, el) { return Array.prototype.slice.call((el || document).querySelectorAll(s)); };
    var state = {};
    var code = '';
    var PIN_MAX = 8;

    function call(fn) {
        try { return JSON.parse(K[fn].apply(K, Array.prototype.slice.call(arguments, 1)) || '{}'); } catch (e) { return {}; }
    }
    function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

    var toastTimer;
    function toast(text) {
        var t = $('#toast');
        t.textContent = text;
        t.hidden = false;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () { t.hidden = true; }, 2600);
    }

    // --- PIN ---------------------------------------------------------------------------------------
    function renderDots(n) {
        var len = Math.max(4, code.length);
        $('#dots').innerHTML = new Array(len + 1).join('<i></i>');
        $$('#dots i').forEach(function (d, i) { d.classList.toggle('on', i < code.length); });
    }
    var pinTimer;
    function tryPin() {
        var r = call('pin', code);
        if (r.ok) { openAdmin(); return; }
        if (r.locked) {
            $('#pin-hint').textContent = 'Te vaak fout: probeer het over ' + r.locked + ' s opnieuw';
        } else if (r.wrong) {
            $('#pin-hint').textContent = 'Onjuiste PIN';
            var d = $('#dots');
            d.classList.remove('shake'); void d.offsetWidth; d.classList.add('shake');
            code = '';
            renderDots();
        }
    }
    $('#keypad').addEventListener('click', function (e) {
        var b = e.target.closest('button');
        if (!b) return;
        var k = b.getAttribute('data-k');
        if (k === 'cancel') { K.action('close', ''); return; }
        if (k === 'back') code = code.slice(0, -1);
        else if (code.length < PIN_MAX) code += k;
        renderDots();
        clearTimeout(pinTimer);
        // Controleren zodra er een pauze is na minstens 4 cijfers (PIN-lengte is vrij, 4-8)
        if (code.length >= 4) pinTimer = setTimeout(tryPin, code.length === PIN_MAX ? 0 : 700);
    });

    // --- Beheer ------------------------------------------------------------------------------------
    function openAdmin() {
        $('#pin').hidden = true;
        state = call('state');
        // Nog de standaard-PIN: eerst een eigen PIN kiezen
        if (state.pinDefault) { $('#mustpin').hidden = false; $('#mp1').focus(); return; }
        $('#mustpin').hidden = true;
        $('#admin').hidden = false;
        render();
        go('home');
    }

    $('#mustpin [data-action="close"]').addEventListener('click', function () { K.action('close', ''); });
    $('#mp-save').addEventListener('click', function () {
        var a = $('#mp1').value, b = $('#mp2').value;
        if (!/^[0-9]{4,8}$/.test(a)) { $('#mp-error').textContent = 'De PIN moet 4 tot 8 cijfers zijn'; return; }
        if (a !== b) { $('#mp-error').textContent = 'De twee PIN-codes zijn niet gelijk'; return; }
        var r = call('set', 'pin', a);
        if (r.error) { $('#mp-error').textContent = r.error; return; }
        toast('Nieuwe PIN opgeslagen');
        openAdmin();
    });

    function refresh() {
        state = call('state');
        render();
    }

    function stepText(el, v) {
        if (el.getAttribute('data-format') === 'hour') return (v < 10 ? '0' : '') + v + ':00';
        var zero = el.getAttribute('data-zero');
        if (v === 0 && zero) return zero;
        return v + ' ' + el.getAttribute('data-unit');
    }

    // --- Navigatie: overzicht en detailschermen ---------------------------------------------------
    var view = 'home';
    function go(name) {
        if (view === 'test' && name !== 'test') K.action('nfcTest', 'stop');
        view = name;
        $$('.view').forEach(function (v) { v.hidden = v.getAttribute('data-view') !== name; });
        var el = $('.view[data-view="' + name + '"]');
        var home = name === 'home';
        $('#back').hidden = home;
        $('#top-logo').hidden = !home;
        $('#top-title').textContent = home ? 'EventVOX-Webkiosk' : el.getAttribute('data-title');
        $('#top-sub').hidden = !home;
        window.scrollTo(0, 0);
        if (name === 'test') {
            $('#test-box').classList.remove('got');
            $('#test-wait').hidden = false;
            $('#test-result').hidden = true;
            K.action('nfcTest', 'start');
        }
    }
    $('#back').addEventListener('click', function () { go('home'); });

    var LEVEL = ['heel zwak', 'zwak', 'redelijk', 'goed', 'uitstekend'];
    var PASS = { off: 'uit', keyboard: 'typen', event: 'event', both: 'typen + event' };
    var ORIENT = { portrait: 'staand', landscape: 'liggend', auto: 'draait mee' };

    function tile(id, value, level) {
        var t = $('#' + id);
        $('.t-val', t).textContent = value;
        t.classList.toggle('warn', level === 'warn');
        t.classList.toggle('bad', level === 'bad');
    }

    function render() {
        var s = state, st = s.settings || {};
        var h = s.health || {}, b = s.battery || {}, w = s.wifiNow || {}, sc = s.scanner || {}, n = s.nfc || {}, u = s.update || {};
        $('#top-sub').textContent = 'versie ' + s.version + ' · SN ' + s.serial;

        // Statustegels
        tile('t-network', w.connected ? (w.level != null ? LEVEL[w.level] : 'verbonden') : 'geen verbinding',
            !w.connected ? 'bad' : w.level <= 1 ? 'warn' : '');
        tile('t-battery', b.level == null ? '—' : b.level + '%' + (b.charging ? ' ⚡' : ''),
            b.level == null || b.charging ? '' : st.low_battery && b.level <= st.low_battery ? 'bad' : b.level <= 30 ? 'warn' : '');
        tile('t-nfc', !n.available ? 'niet aanwezig' : !n.enabled ? 'uit in Android' : st.nfc_mode === 'off' ? 'uit' : PASS[st.nfc_mode],
            n.available && !n.enabled ? 'warn' : '');
        tile('t-scanner', !sc.installed ? 'geen DataWedge' : st.datawedge === 'app' ? 'via de app' : 'eigen profielen', '');
        var newer = /^nieuw/.test(u.lastResult || ''), failed = /^fout/.test(u.lastResult || '');
        tile('t-updates', newer ? 'nieuwe versie' : failed ? 'niet gelukt' : s.version, newer || failed ? 'warn' : '');
        tile('t-kiosk', s.owner ? 'vergrendeld' : 'niet vergrendeld', s.owner ? '' : 'bad');

        // Samenvattingen in het menu
        $('#m-website').textContent = (s.resolvedUrl || '').replace(/^https?:\/\//, '');
        $('#m-scan').textContent = 'Scanner ' + (st.datawedge === 'app' ? 'via de app' : 'eigen profielen') + ' · NFC ' + (PASS[st.nfc_mode] || '');
        $('#m-screen').textContent = [ORIENT[st.orientation], st.keep_screen_on ? 'scherm altijd aan' : '', st.volume_lock ? 'volume ' + st.volume_lock + '%' : '']
            .filter(Boolean).join(' · ');
        $('#m-network').textContent = w.connected ? (w.ssid || 'verbonden') + ' · ' + LEVEL[w.level] : 'geen verbinding';
        $('#m-updates').textContent = 'versie ' + s.version + (u.lastResult ? ' · ' + u.lastResult : '') + (st.update_channel === 'beta' ? ' · testkanaal' : '');
        var clockOff = h.clock && !/gelijk/.test(h.clock);
        var dev = $('#m-device');
        dev.textContent = clockOff ? 'Klok ' + h.clock : 'SN ' + s.serial + (b.level != null ? ' · ' + b.level + '%' : '') + (h.crashes ? ' · ' + h.crashes + '× hersteld' : '');
        dev.classList.toggle('bad', !!clockOff);
        $('#m-security').textContent = 'Openen met ' + st.menu_taps + '× tikken' + (h.adminQr ? ' of een beheer-QR-code' : '');

        // Velden
        if (document.activeElement !== $('#url')) $('#url').value = s.url;
        $('#url-preview').textContent = '→ ' + s.resolvedUrl;
        if (document.activeElement !== $('#update_url')) $('#update_url').value = u.url;
        $('#update_url').placeholder = u.defaultUrl;
        $$('.seg[data-setting]').forEach(function (seg) {
            var v = st[seg.getAttribute('data-setting')];
            $$('button', seg).forEach(function (btn) { btn.classList.toggle('on', btn.getAttribute('data-v') === v); });
        });
        $$('select[data-setting]').forEach(function (sel) { sel.value = st[sel.getAttribute('data-setting')]; });
        $$('input[type=checkbox][data-setting]').forEach(function (cb) { cb.checked = !!st[cb.getAttribute('data-setting')]; });
        $$('.stepper[data-setting]').forEach(function (el) {
            if (!el.firstChild) el.innerHTML = '<button data-d="-1" aria-label="Minder">−</button><b></b><button data-d="1" aria-label="Meer">+</button>';
            $('b', el).textContent = stepText(el, st[el.getAttribute('data-setting')]);
        });
        // Dimmen kan alleen met scherm altijd aan
        $('#dim-row').hidden = !st.keep_screen_on;
        // Ingesteld vanuit EventVOX: een labeltje
        var remote = s.remoteKeys || [];
        $$('[data-setting]').forEach(function (el) {
            var row = el.closest('.field, .toggle-row');
            if (row) row.classList.toggle('remote', remote.indexOf(el.getAttribute('data-setting')) >= 0);
        });

        // Scannen
        var spill = $('#scan-pill');
        spill.textContent = !sc.installed ? 'geen DataWedge' : st.datawedge === 'app' ? 'via de app' : 'eigen profielen';
        spill.classList.toggle('off', !sc.installed);
        $('#scan-status').textContent = sc.status || '';
        var pill = $('#nfc-pill');
        pill.textContent = !n.available ? 'niet aanwezig' : n.enabled ? 'aan' : 'uit in Android';
        pill.classList.toggle('off', !n.available || !n.enabled);
        $('#nfc-status').textContent = n.status || '';

        // Netwerk
        $('#net-now').innerHTML = (w.connected ? [
            ['Verbonden met', esc(w.ssid || '(naam onbekend)')],
            ['Signaal', esc(LEVEL[w.level] + (w.rssi != null ? ' · ' + w.rssi + ' dBm' : ''))],
            ['Snelheid', w.link_speed ? esc(w.link_speed + ' Mbps · ' + (w.frequency >= 5000 ? '5' : '2,4') + ' GHz') : '—'],
        ] : [['Wifi', '<span style="color:var(--red)">geen verbinding</span>']])
            .map(function (r) { return '<dt>' + r[0] + '</dt><dd>' + r[1] + '</dd>'; }).join('');
        $('#wifi-status').textContent = s.wifi || '';

        // Updates
        $('#upd-version').textContent = 'Versie ' + s.version;
        $('#upd-last').textContent = u.lastCheck ? 'Gecontroleerd ' + u.lastCheck + (u.lastResult ? ' · ' + u.lastResult : '') : 'Nog niet gecontroleerd';

        // Apparaat
        $('#diag-status').textContent = h.diagnosticsPending ? 'Gaat mee met de volgende heartbeat…'
            : h.diagnosticsSent ? 'Laatst verstuurd ' + h.diagnosticsSent : '';
        $('#log').textContent = h.log || '(leeg)';
        $('#device').innerHTML = [
            ['Serienummer', '<span class="mono">' + esc(s.serial) + '</span>'],
            ['Model', esc(s.model)],
            ['Android', esc(s.android)],
            ['Accu', b.level == null ? '—' : b.level + '%' + (b.charging ? ' · laadt op' : '') + (b.temperature ? ' · ' + b.temperature + ' °C' : '')],
            ['Klok', h.clock ? (clockOff ? '<span style="color:var(--red)">' + esc(h.clock) + '</span>' : esc(h.clock)) : 'nog niet gecontroleerd'],
            ['Kiosk-modus', s.owner ? 'vergrendeld (device owner)' : '<span style="color:var(--red)">niet vergrendeld</span>'],
            ['Terminal', h.identity ? 'identiteit bewaard' : 'nog niet gekoppeld'],
            ['Crashes', h.crashes ? h.crashes + '× hersteld, laatste ' + esc(h.lastCrash) : 'geen'],
            ['Uit EventVOX', h.remote ? esc(h.remote) : 'geen instellingen'],
        ].map(function (r) { return '<dt>' + r[0] + '</dt><dd>' + r[1] + '</dd>'; }).join('');
    }

    function set(key, value) {
        var r = call('set', key, String(value));
        if (r.error) toast(r.error);
        else if (r.message) toast(r.message);
        refresh();
        return !r.error;
    }

    document.addEventListener('click', function (e) {
        var b = e.target.closest('button');
        if (!b || $('#admin').hidden) return;
        var seg = b.closest('.seg[data-setting]');
        if (seg) { set(seg.getAttribute('data-setting'), b.getAttribute('data-v')); return; }
        var st = b.closest('.stepper[data-setting]');
        if (st) {
            var key = st.getAttribute('data-setting');
            var step = +st.getAttribute('data-step');
            var v = Math.min(+st.getAttribute('data-max'), Math.max(+st.getAttribute('data-min'), state.settings[key] + step * +b.getAttribute('data-d')));
            set(key, v);
            return;
        }
        if (b.hasAttribute('data-save')) {
            var k = b.getAttribute('data-save');
            if (k === 'url') { if (set('url', $('#url').value)) $('#url').blur(); }
            if (k === 'update_url') set('update_url', $('#update_url').value);
            if (k === 'pin') { if (set('pin', $('#newpin').value)) { $('#newpin').value = ''; $('#newpin').blur(); } }
            return;
        }
        if (b.hasAttribute('data-go')) { go(b.getAttribute('data-go')); return; }
        if (b.hasAttribute('data-confirm')) { confirmAction(b.getAttribute('data-confirm')); return; }
        if (b.hasAttribute('data-action')) {
            var a = b.getAttribute('data-action');
            if (a === 'checkUpdate') { b.disabled = true; b.textContent = 'Bezig…'; }
            K.action(a, b.getAttribute('data-arg') || '');
        }
    });
    $$('select[data-setting]').forEach(function (sel) {
        sel.addEventListener('change', function () { set(sel.getAttribute('data-setting'), sel.value); });
    });
    $$('input[type=checkbox][data-setting]').forEach(function (cb) {
        cb.addEventListener('change', function () { set(cb.getAttribute('data-setting'), cb.checked); });
    });
    $('#url').addEventListener('keydown', function (e) { if (e.key === 'Enter') { set('url', this.value); this.blur(); } });

    // Bevestigen
    var CONFIRM = {
        leave: ['App sluiten?', 'De kiosk wordt tijdelijk verlaten en je komt in de Android-instellingen. Open EventVOX-Webkiosk opnieuw of herstart het toestel om terug te gaan.', 'Sluiten'],
        removeOwner: ['Kiosk-modus uitschakelen?', 'Het toestel wordt weer een normaal Android-toestel: geen vergrendeling meer, en de app kan worden verwijderd. Opnieuw inrichten kan alleen met het provision-script.', 'Uitschakelen'],
    };
    function confirmAction(name) {
        var c = CONFIRM[name];
        $('#sheet-title').textContent = c[0];
        $('#sheet-text').textContent = c[1];
        $('#sheet-yes').textContent = c[2];
        $('#sheet-yes').classList.toggle('red', name === 'removeOwner');
        $('#sheet-yes').classList.toggle('primary', name !== 'removeOwner');
        $('#sheet').hidden = false;
        $('#sheet-yes').onclick = function () { $('#sheet').hidden = true; K.action(name, ''); };
    }
    $('#sheet-no').addEventListener('click', function () { $('#sheet').hidden = true; });

    // Terugmeldingen van de app
    window.kioskEvent = function (type, d) {
        if (type === 'toast') toast(d.text);
        if (type === 'unlocked' && !$('#pin').hidden) openAdmin(); // beheer-QR-code gescand op het PIN-scherm
        if (type === 'refresh') refresh();
        if (type === 'back') {
            if (!$('#sheet').hidden) $('#sheet').hidden = true;
            else if (view !== 'home' && !$('#admin').hidden) go('home');
            else K.action('close', '');
        }
        if (type === 'nfc' || type === 'scan') {
            var rows = type === 'scan'
                ? [['Barcode', d.data]].concat(d.symbology ? [['Type', d.symbology]] : [])
                : [['NFC-tag', d.uid], ['Type', (d.techs || []).join(', ')]]
                    .concat((d.ndef || []).map(function (r) { return ['Inhoud', r.value]; })).concat([['Wordt getypt als', d.typed]]);
            if (view !== 'test') { toast((type === 'scan' ? 'Gescand: ' : 'NFC-tag: ') + rows[0][1]); return; }
            $('#test-box').classList.add('got');
            $('#test-wait').hidden = true;
            var dl = $('#test-result');
            dl.innerHTML = rows.map(function (r) { return '<dt>' + esc(r[0]) + '</dt><dd class="mono">' + esc(r[1]) + '</dd>'; }).join('');
            dl.hidden = false;
        }
        if (type === 'update') {
            var btn = $('#upd-check');
            btn.disabled = false;
            btn.textContent = 'Controleren';
            var box = $('#upd-result');
            box.hidden = false;
            box.className = 'result' + (d.error ? ' err' : d.newer ? ' new' : '');
            if (d.error) box.innerHTML = '<b>Controleren mislukt</b><br>' + esc(d.error);
            else if (d.newer) box.innerHTML = '<b>Nieuwe versie ' + esc(d.versionName) + ' beschikbaar</b>' + (d.notes ? '<div class="notes">' + esc(d.notes) + '</div>' : '')
                + '<div class="row"><button class="btn primary" data-action="installUpdate">Nu installeren</button></div>';
            else box.innerHTML = '✓ Je hebt de nieuwste versie.';
            refresh();
        }
    };

    renderDots();
    // Geopend met een beheer-QR-code: al ontgrendeld, meteen het beheer
    var pre = call('state');
    if (pre && pre.version) openAdmin();
})();
