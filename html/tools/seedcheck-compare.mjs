// WEB-PORT: compares the SeedCheck dump of the desktop build with the one from the browser.
//
//   ./gradlew html:seedCheckDesktop -Pseed=AAA-BBB-CCC,DDD-EEE-FFF -Phero=warrior,mage -Pdepths=5
//   open  <site>/?seedcheck=AAA-BBB-CCC,DDD-EEE-FFF&hero=warrior,mage&depths=5  and press "Download"
//   node html/tools/seedcheck-compare.mjs html/build/seedcheck/desktop.txt ~/Downloads/seedcheck-web.txt
//
// Compared strictly: floors (terrain, transitions, items, mobs, traps, plants, blobs), number formatting
// in every game language, float semantics. Save data ("save"/"hero" lines, Bundle JSON) is compared as
// JSON: key order and the order of HashSet-backed arrays are not meaningful, and missile weapon set ids
// come from SecureRandom on every platform. Lines starting with '~' and the numbers/samples probes are
// informative only (see SeedCheck.java and the README).
import { readFileSync } from 'node:fs';

const [, , desktopFile, webFile] = process.argv;
if (!desktopFile || !webFile) {
	console.error('usage: node seedcheck-compare.mjs <desktop.txt> <web.txt>');
	process.exit(2);
}

const canon = (v) => {
	if (Array.isArray(v)) {
		const items = v.map(canon);
		return '[' + (v.length && typeof v[0] === 'object' ? items.sort() : items).join(',') + ']';
	}
	if (v && typeof v === 'object') {
		return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canon(v[k])).join(',') + '}';
	}
	return JSON.stringify(v);
};
const maskRandomIds = (s) => s
	.replace(/"set_id":-?\d+/g, '"set_id":0')
	.replace(/"(set_ids_enchants|set_ids)":\[[-\d,]*\]/g, (m, k) => `"${k}":${m.endsWith('[]') ? '[]' : '[0]'}`);
const normalize = (line) => (line.startsWith('save ') || line.startsWith('hero '))
	? line.slice(0, 5) + canon(JSON.parse(maskRandomIds(line.slice(5))))
	: line;
const informative = (line) => line.startsWith('~') || line.startsWith('numbers ') || line.startsWith('samples');

const read = (file) => readFileSync(file, 'utf8').replace(/\r\n/g, '\n').split('\n').filter((l) => !informative(l)).map(normalize);
const desktop = read(desktopFile);
const web = read(webFile);

let differences = 0;
for (let i = 0; i < Math.max(desktop.length, web.length); i++) {
	if (desktop[i] !== web[i]) {
		if (differences < 10) {
			const a = desktop[i] ?? '', b = web[i] ?? '';
			let j = 0;
			while (j < a.length && a[j] === b[j]) j++;
			console.log(`line ${i + 1}, column ${j + 1}:\n  desktop: ${a.slice(Math.max(0, j - 60), j + 60)}\n  web:     ${b.slice(Math.max(0, j - 60), j + 60)}`);
		}
		differences++;
	}
}
console.log(differences === 0
	? `IDENTICAL: ${desktop.length} lines (${desktop.filter((l) => l.startsWith('== depth')).length} floors)`
	: `${differences} differing lines`);
process.exit(differences === 0 ? 0 : 1);
