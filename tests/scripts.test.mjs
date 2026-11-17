// De JavaScript die de app in de pagina zet (Scripts.java), in Node met een nagebootste window.WebKiosk:
// navigator.getBattery() met en zonder accu (present false, zoals een Zebra CC600).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const java = readFileSync(new URL('../app/src/nl/webkiosk/Scripts.java', import.meta.url), 'utf8');
/** Een Java-string zoals static final String NAAM = "..." + "..."; als JavaScript-tekst */
function javaString(name) {
    const m = new RegExp(`static final String ${name} =([\\s\\S]*?);\\n`).exec(java);
    assert.ok(m, `${name} niet gevonden`);
    const parts = [...m[1].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((p) => JSON.parse(`"${p[1]}"`));
    return parts.join('');
}

async function battery(report) {
    const listeners = {};
    const navigator = {};
    const window = {
        WebKiosk: { getBattery: () => JSON.stringify(report) },
        addEventListener: (t, f) => { listeners[t] = f; },
    };
    window.window = window;
    const ctx = vm.createContext({ window, navigator, Navigator: function Navigator() {}, Promise, JSON, Object, Array, Number, isFinite, Math });
    ctx.WebKiosk = window.WebKiosk;
    vm.runInContext(javaString('BATTERY_POLYFILL'), ctx);
    return navigator.getBattery();
}

test('a device with a battery: level and charging from Android, chargingTime unknown (Infinity)', async () => {
    const b = await battery({ present: true, level: 80, charging: true });
    assert.equal(b.level, 0.8);
    assert.equal(b.charging, true);
    assert.equal(b.chargingTime, Infinity);
});

test('no battery (present false, a Zebra CC600): like a device without a battery, so the scanterminal shows none', async () => {
    const b = await battery({ present: false, level: 100, charging: true });
    assert.equal(b.level, 1);
    assert.equal(b.charging, true);
    assert.equal(b.chargingTime, 0);
});

test('an older report without present: as before', async () => {
    const b = await battery({ level: 100, charging: true });
    assert.equal(b.chargingTime, Infinity);
});
