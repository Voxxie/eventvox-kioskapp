// Het beheerscherm (app/assets/admin.*) en het netwerkscherm (app/assets/wifi.*) in Chromium, met een nagebootste
// koppeling met de app (window.Kiosk / window.WifiQr), op het formaat van een TC51 (360×640).
//   node --test tests/assets.test.mjs        (Playwright met Chromium nodig)
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright');
const ASSETS = new URL('../app/assets/', import.meta.url);
const page = (name) => `file://${fileURLToPath(new URL(name, ASSETS))}`;

let browser;
before(async () => { browser = await chromium.launch(); });
after(async () => { await browser?.close(); });

/** De staat zoals MainActivity.adminState() hem geeft; overrides per test */
const baseState = (o = {}) => ({
    version: '0.1.2', serial: '18123522501234', model: 'Zebra Technologies TC51', android: '8.1.0', owner: true, pinDefault: false,
    url: 'https://eventvox.nl/scanterminal?serial={serial}', resolvedUrl: 'https://eventvox.nl/scanterminal?serial=18123522501234',
    settings: { nfc_mode: 'keyboard', nfc_uid_format: 'hex', nfc_keyboard_value: 'uid', nfc_send_enter: true, nfc_refresh_seconds: 0, scan_mode: 'keyboard',
        datawedge: 'app', auto_update: true, update_check_hours: 0, keep_screen_on: false, reload_minutes: 0, ignore_ssl_errors: false, menu_taps: 10,
        vibrate: true, update_channel: 'stable', volume_lock: 0, dim_minutes: 2, dim_level: 15, nightly_reload: true, nightly_hour: 4, low_battery: 15,
        orientation: 'portrait' },
    nfc: { available: true, enabled: true, status: '' }, scanner: { installed: true, status: '' },
    update: { lastCheck: '', lastResult: 'actueel', url: '', defaultUrl: 'https://example/update.json' },
    wifi: '', wifiNow: { connected: true, level: 3, rssi: -61, ssid: 'EventVOX-Ingang', link_speed: 72, frequency: 5180 },
    battery: { level: 78, charging: false }, remoteKeys: [],
    health: { crashes: 0, clock: 'loopt gelijk met de server', identity: true, adminQr: true, log: '' },
    ...o,
});

/** Het beheerscherm met een nagebootste window.Kiosk; calls houdt bij wat de pagina vraagt */
async function admin(state, { unlocked = false, pin = '2468' } = {}) {
    const ctx = await browser.newContext({ viewport: { width: 360, height: 640 } });
    await ctx.addInitScript(([s, open, code]) => {
        window.__calls = [];
        let unlockedNow = open;
        window.Kiosk = {
            pin: (c) => { window.__calls.push(['pin', c]); if (c === code) { unlockedNow = true; return '{"ok":true}'; } return c.length >= 4 ? '{"wrong":true}' : '{}'; },
            state: () => (unlockedNow ? JSON.stringify(s) : '{}'),
            set: (k, v) => { window.__calls.push(['set', k, v]); if (k === 'pin') s.pinDefault = false; else s.settings[k] = /^\d+$/.test(v) ? +v : v === 'true' ? true : v === 'false' ? false : v; return '{"ok":true}'; },
            action: (n, a) => window.__calls.push(['action', n, a]),
        };
    }, [state, unlocked, pin]);
    const p = await ctx.newPage();
    const errors = [];
    p.on('pageerror', (e) => errors.push(String(e)));
    await p.goto(page('admin.html'));
    return { p, ctx, errors, calls: () => p.evaluate(() => window.__calls) };
}

test('PIN: wrong shakes and clears, right opens the overview; the default PIN must be changed first', async () => {
    const { p, ctx, errors, calls } = await admin(baseState());
    for (const k of '1111') await p.click(`[data-k="${k}"]`);
    await p.waitForFunction(() => document.querySelector('#pin-hint').textContent === 'Onjuiste PIN');
    for (const k of '2468') await p.click(`[data-k="${k}"]`);
    await p.waitForSelector('#admin:not([hidden])');
    assert.equal(await p.locator('[data-view="home"]').isVisible(), true);
    assert.deepEqual((await calls()).filter((c) => c[0] === 'pin').map((c) => c[1]), ['1111', '2468']);
    await ctx.close();

    const def = await admin(baseState({ pinDefault: true }), { unlocked: true });
    await def.p.waitForSelector('#mustpin:not([hidden])');
    await def.p.fill('#mp1', '7391');
    await def.p.fill('#mp2', '7390');
    await def.p.click('#mp-save');
    assert.match(await def.p.locator('#mp-error').innerText(), /niet gelijk/);
    await def.p.fill('#mp2', '7391');
    await def.p.click('#mp-save');
    await def.p.waitForSelector('#admin:not([hidden])');
    assert.deepEqual((await def.calls()).find((c) => c[0] === 'set'), ['set', 'pin', '7391']);
    assert.deepEqual([...errors, ...def.errors], []);
    await def.ctx.close();
});

test('overview: tiles grey when fine, red or orange on a problem; summaries in the menu', async () => {
    const fine = await admin(baseState(), { unlocked: true });
    await fine.p.waitForSelector('#admin:not([hidden])');
    assert.equal(await fine.p.locator('.tile.bad, .tile.warn').count(), 0);
    assert.equal(await fine.p.locator('#t-network .t-val').innerText(), 'goed');
    assert.match(await fine.p.locator('#m-network').innerText(), /EventVOX-Ingang · goed/);
    await fine.ctx.close();

    const bad = await admin(baseState({ owner: false, wifiNow: { connected: false }, battery: { level: 9, charging: false },
        update: { lastResult: 'nieuw: 0.1.3', url: '', defaultUrl: '' }, health: { clock: 'loopt 7 min voor', crashes: 1 } }), { unlocked: true });
    await bad.p.waitForSelector('#admin:not([hidden])');
    for (const id of ['t-network', 't-battery', 't-kiosk']) assert.equal(await bad.p.locator(`#${id}.bad`).count(), 1, id);
    assert.equal(await bad.p.locator('#t-updates.warn').count(), 1);
    assert.match(await bad.p.locator('#m-device').innerText(), /Klok loopt 7 min voor/);
    assert.deepEqual([...fine.errors, ...bad.errors], []);
    await bad.ctx.close();
});

test('mobile internet (a Zebra with a modem): 4G/5G in the network tile, the menu and the network screen; nothing on a TC51', async () => {
    const { p, ctx, errors } = await admin(baseState({ wifiNow: { connected: false }, uplink: 'mobile',
        mobile: { connected: true, sim: true, type: '5G', level: 3, operator: 'KPN', roaming: false } }), { unlocked: true });
    await p.waitForSelector('#admin:not([hidden])');
    assert.equal(await p.locator('#t-network .t-val').innerText(), '5G · goed');
    assert.equal(await p.locator('#t-network.bad, #t-network.warn').count(), 0, 'no wifi is fine on mobile internet');
    assert.match(await p.locator('#m-network').innerText(), /Mobiel 5G · KPN/);
    await p.click('[data-go="network"]');
    const net = await p.locator('#net-now').innerText();
    assert.match(net, /Wifi\s*niet verbonden/);
    assert.match(net, /Mobiel\s*5G · KPN/);
    assert.match(net, /Internet via\s*mobiel/);
    await ctx.close();
    // Without a SIM card it says so
    const nosim = await admin(baseState({ mobile: { connected: false, sim: false }, uplink: 'wifi' }), { unlocked: true });
    await nosim.p.waitForSelector('#admin:not([hidden])');
    await nosim.p.click('[data-go="network"]');
    assert.match(await nosim.p.locator('#net-now').innerText(), /Mobiel\s*geen simkaart/);
    await nosim.ctx.close();
    // A TC51 (no modem: mobile null): no mobile row
    const tc51 = await admin(baseState({ mobile: null }), { unlocked: true });
    await tc51.p.waitForSelector('#admin:not([hidden])');
    await tc51.p.click('[data-go="network"]');
    assert.doesNotMatch(await tc51.p.locator('#net-now').innerText(), /Mobiel/);
    assert.deepEqual([...errors, ...nosim.errors, ...tc51.errors], []);
    await tc51.ctx.close();
});

test('details: menu to a screen and back (arrow and Android back), settings go to the app, "via EventVOX" shown', async () => {
    const { p, ctx, errors, calls } = await admin(baseState({ remoteKeys: ['orientation'] }), { unlocked: true });
    await p.waitForSelector('#admin:not([hidden])');
    await p.click('.menu [data-go="screen"]');
    assert.equal(await p.locator('#top-title').innerText(), 'Scherm en geluid');
    assert.equal(await p.locator('[data-setting="orientation"]').evaluate((el) => el.closest('.field').classList.contains('remote')), true);
    assert.equal(await p.locator('#dim-row').isHidden(), true, 'dimming without "scherm altijd aan"');
    await p.click('[data-setting="orientation"] [data-v="landscape"]');
    await p.click('[data-setting="volume_lock"] [data-d="1"]');
    await p.locator('[data-setting="keep_screen_on"]').evaluate((el) => el.click());
    assert.equal(await p.locator('#dim-row').isVisible(), true);
    const sets = (await calls()).filter((c) => c[0] === 'set');
    assert.deepEqual(sets, [['set', 'orientation', 'landscape'], ['set', 'volume_lock', '10'], ['set', 'keep_screen_on', 'true']]);
    await p.click('#back');
    assert.equal(await p.locator('[data-view="home"]').isVisible(), true);
    await p.click('.menu [data-go="updates"]');
    await p.evaluate(() => window.kioskEvent('back', {}));
    assert.equal(await p.locator('[data-view="home"]').isVisible(), true);
    await p.evaluate(() => window.kioskEvent('back', {}));
    assert.deepEqual((await calls()).at(-1), ['action', 'close', '']);
    assert.deepEqual(errors, []);
    await ctx.close();
});

test('scan testen: starts the NFC test, shows a barcode and a tag, stops on leaving; elsewhere a scan is a toast', async () => {
    const { p, ctx, errors, calls } = await admin(baseState(), { unlocked: true });
    await p.waitForSelector('#admin:not([hidden])');
    await p.evaluate(() => window.kioskEvent('scan', { data: 'ANNA234567' }));
    await p.waitForSelector('#toast:not([hidden])');
    await p.click('.qbtn[data-go="test"]');
    await p.evaluate(() => window.kioskEvent('scan', { data: 'ANNA234567', symbology: 'QRCODE' }));
    assert.match(await p.locator('#test-result').innerText(), /ANNA234567[\s\S]*QRCODE/);
    await p.evaluate(() => window.kioskEvent('nfc', { uid: '04A1B2C3', techs: ['NfcA'], typed: '04A1B2C3' }));
    assert.match(await p.locator('#test-result').innerText(), /NFC-tag\s*04A1B2C3/);
    await p.click('#back');
    const actions = (await calls()).filter((c) => c[0] === 'action' && c[1] === 'nfcTest').map((c) => c[2]);
    assert.deepEqual(actions, ['start', 'stop']);
    assert.deepEqual(errors, []);
    await ctx.close();
});

test('updates: a newer version shows its notes and "Nu installeren"', async () => {
    const { p, ctx, errors, calls } = await admin(baseState(), { unlocked: true });
    await p.waitForSelector('#admin:not([hidden])');
    await p.click('.menu [data-go="updates"]');
    await p.click('#upd-check');
    await p.evaluate(() => window.kioskEvent('update', { newer: true, versionName: '0.1.3', notes: 'Beter geluid' }));
    assert.match(await p.locator('#upd-result').innerText(), /0\.1\.3[\s\S]*Beter geluid/);
    await p.click('[data-action="installUpdate"]');
    assert.deepEqual((await calls()).filter((c) => c[0] === 'action').map((c) => c[1]), ['checkUpdate', 'installUpdate']);
    assert.deepEqual(errors, []);
    await ctx.close();
});

test('network screen: a scanned wifi QR code to confirm, networks nearby, a password, and a failure to retry', async () => {
    const ctx = await browser.newContext({ viewport: { width: 360, height: 640 } });
    await ctx.addInitScript(() => {
        window.__calls = [];
        const list = [
            { ssid: 'EventVOX-Ingang', security: 'WPA', level: 4, band: '5 GHz', saved: true, current: false },
            { ssid: 'Festival-Crew', security: 'WPA', level: 3, band: '5 GHz', saved: false, current: false },
            { ssid: 'Zakelijk', security: 'EAP', level: 1, band: '2,4 GHz', saved: false, current: false },
        ];
        window.WifiQr = {
            info: () => JSON.stringify({ qr: null, offline: true }),
            scan: () => { window.__calls.push(['scan']); setTimeout(() => window.wifiEvent({ type: 'networks', list, location: true }), 10); },
            add: () => window.__calls.push(['add']),
            connect: (s, sec, pw) => window.__calls.push(['connect', s, sec, pw]),
            close: () => window.__calls.push(['close']),
        };
    });
    const p = await ctx.newPage();
    const errors = [];
    p.on('pageerror', (e) => errors.push(String(e)));
    await p.goto(page('wifi.html'));
    assert.equal(await p.locator('#title').innerText(), 'Je bent offline');
    await p.waitForSelector('.net');
    assert.match(await p.locator('#nets').innerText(), /Zakelijk[\s\S]*niet ondersteund/);
    await p.click('text=Festival-Crew');
    await p.fill('#pw', 'crew-wachtwoord');
    await p.click('[data-connect]');
    await p.evaluate(() => window.wifiEvent({ type: 'status', state: 'failed', text: 'Geen verbinding' }));
    assert.equal(await p.locator('#state.err').count(), 1);
    await p.evaluate(() => window.wifiEvent({ type: 'qr', qr: { ssid: 'Gast', security: 'Open (geen wachtwoord)', hidden: false } }));
    assert.equal(await p.locator('#confirm').isVisible(), true);
    await p.click('#qr-add');
    const calls = await p.evaluate(() => window.__calls);
    assert.deepEqual(calls.filter((c) => c[0] !== 'scan'), [['connect', 'Festival-Crew', 'WPA', 'crew-wachtwoord'], ['add']]);
    assert.deepEqual(errors, []);
    await ctx.close();
});
