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
        $('#admin').hidden = false;
        refresh();
    }

    function refresh() {
        state = call('state');
        render();
    }

    function stepText(el, v) {
        var zero = el.getAttribute('data-zero');
        if (v === 0 && zero) return zero;
        return v + ' ' + el.getAttribute('data-unit');
    }

    function render() {
        var s = state;
        $('#top-sub').textContent = 'versie ' + s.version + ' · SN ' + s.serial + (s.owner ? '' : ' · geen device owner');
        if (document.activeElement !== $('#url')) $('#url').value = s.url;
        $('#url-preview').textContent = '→ ' + s.resolvedUrl;
        if (document.activeElement !== $('#update_url')) $('#update_url').value = s.update.url;
        $('#update_url').placeholder = s.update.defaultUrl;

        // Instellingen met data-setting
        $$('.seg[data-setting]').forEach(function (seg) {
            var v = s.settings[seg.getAttribute('data-setting')];
            $$('button', seg).forEach(function (b) { b.classList.toggle('on', b.getAttribute('data-v') === v); });
        });
        $$('select[data-setting]').forEach(function (sel) { sel.value = s.settings[sel.getAttribute('data-setting')]; });
        $$('input[type=checkbox][data-setting]').forEach(function (cb) { cb.checked = !!s.settings[cb.getAttribute('data-setting')]; });
        $$('.stepper[data-setting]').forEach(function (st) {
            if (!st.firstChild) st.innerHTML = '<button data-d="-1" aria-label="Minder">−</button><b></b><button data-d="1" aria-label="Meer">+</button>';
            $('b', st).textContent = stepText(st, s.settings[st.getAttribute('data-setting')]);
        });

        // NFC
        var pill = $('#nfc-pill');
        pill.textContent = !s.nfc.available ? 'niet aanwezig' : s.nfc.enabled ? 'aan' : 'uit in Android';
        pill.classList.toggle('off', !s.nfc.available || !s.nfc.enabled);
        $('#nfc-status').textContent = s.nfc.status;

        // Updates
        $('#upd-version').textContent = 'Versie ' + s.version;
        $('#upd-last').textContent = s.update.lastCheck ? 'Laatst gecontroleerd ' + s.update.lastCheck + (s.update.lastResult ? ' · ' + s.update.lastResult : '') : 'Nog niet gecontroleerd';

        $('#wifi-status').textContent = s.wifi;

        // Scanner (DataWedge)
        var sc = s.scanner || {};
        var spill = $('#scan-pill');
        spill.textContent = !sc.installed ? 'geen DataWedge' : s.settings.datawedge === 'app' ? 'via de app' : 'eigen profielen';
        spill.classList.toggle('off', !sc.installed);
        $('#scan-status').textContent = sc.status || '';

        var b = s.battery || {};
        $('#device').innerHTML = [
            ['Serienummer', '<span class="mono">' + esc(s.serial) + '</span>'],
            ['Model', esc(s.model)],
            ['Android', esc(s.android)],
            ['Batterij', b.level == null ? '—' : b.level + '%' + (b.charging ? ' · laadt op' : '') + (b.temperature ? ' · ' + b.temperature + ' °C' : '')],
            ['Kiosk-modus', s.owner ? 'aan (device owner)' : '<span style="color:var(--red)">uit: geen device owner</span>'],
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
            if (k === 'pin') { if (set('pin', $('#newpin').value)) { $('#newpin').value = ''; $('#newpin').closest('details').open = false; } }
            return;
        }
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

    // NFC testen
    var testing = false;
    $('#nfc-test-btn').addEventListener('click', function () {
        testing = !testing;
        $('#nfc-test').hidden = !testing;
        $('#nfc-test').classList.remove('got');
        $('#nfc-wait').hidden = false;
        $('#nfc-result').hidden = true;
        this.textContent = testing ? 'Test stoppen' : 'NFC testen';
        K.action('nfcTest', testing ? 'start' : 'stop');
    });

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
        if (type === 'refresh') refresh();
        if (type === 'back') {
            if (!$('#sheet').hidden) $('#sheet').hidden = true;
            else K.action('close', '');
        }
        if (type === 'nfc') {
            $('#nfc-test').classList.add('got');
            $('#nfc-wait').hidden = true;
            var rows = [['Tag-ID', d.uid], ['Hex', d.uidHex], ['Type', (d.techs || []).join(', ')]];
            (d.ndef || []).forEach(function (r) { rows.push(['Inhoud (' + r.type + ')', r.value]); });
            rows.push(['Wordt getypt als', d.typed]);
            var dl = $('#nfc-result');
            dl.innerHTML = rows.map(function (r) { return '<dt>' + esc(r[0]) + '</dt><dd class="mono">' + esc(r[1]) + '</dd>'; }).join('');
            dl.hidden = false;
        }
        if (type === 'scan') {
            $('#scan-test').hidden = false;
            $('#scan-result').innerHTML = '<dt>Gescand</dt><dd class="mono">' + esc(d.data) + '</dd>' + (d.symbology ? '<dt>Type</dt><dd>' + esc(d.symbology) + '</dd>' : '');
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
})();
