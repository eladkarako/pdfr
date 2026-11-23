#!/usr/bin/env node
'use strict';

/**
 * Drives the reader's tap and long-press menus and captures screenshots.
 *
 * Node.js port of the old reader-actions.ps1. Windows-first, built-ins only.
 * Single-finger actions go through `adb shell input`, so the script needs no
 * root and works on a normal device. It is the companion to zoom-probe.js:
 * that one covers two-finger gestures, this one covers the page menu,
 * selection and the overflow/info dialogs.
 *
 * Every run writes a PNG (and, for menu actions, the uiautomator XML used to
 * find the button) to build\reader-actions\. Use -Action list to print the
 * visible text/content descriptions when a button cannot be found.
 *
 * Coordinates are fractions of the screen by default, so the same command
 * works on the pdfr_full AVD and on a phone. Override them with -X/-Y and
 * -MenuX/-MenuY. Use -Prefix to keep several runs side by side.
 *
 *     node tools\reader-actions.js -Action list
 *     node tools\reader-actions.js -Action long-press
 *     node tools\reader-actions.js -Action select-all
 *     node tools\reader-actions.js -Action info -Prefix sample-info
 *     node tools\reader-actions.js -Action metadata -Prefix sample-metadata
 *     node tools\reader-actions.js -Action text-size -Prefix sample-text-size
 *     node tools\reader-actions.js -Action theme-amoled
 *     node tools\reader-actions.js -Action language-hebrew -Prefix hebrew
 *     node tools\reader-actions.js -Action language -Language ja
 *
 * Run tools\open-fixture.bat first so a document is open. The long-press point
 * should be over readable text; the default (centre of the screen) is fine for
 * the first page of sample.pdf. The text-size and toggle actions need a
 * reflowable document (for example notes.txt or book.epub). The theme actions
 * match both the English and Hebrew labels. The language actions work from any
 * UI language: they find the combo box by its current caption, open it and tap
 * the row by its native name, so the three-column combo does not have to be
 * translated for this script.
 */

const fs = require('node:fs');
const path = require('node:path');
const {
    ensureDir,
    outDir,
    parseCli,
    resolveAdb,
    run,
    runMain,
    say,
    sleep,
    step,
} = require('./lib/common');

const ACTIONS = [
    'long-press',
    'tap',
    'select-all',
    'copy',
    'menu',
    'menu-mirror',
    'info',
    'metadata',
    'metadata-apply',
    'text-size',
    'text-size-apply',
    'toggle-single-page',
    'toggle-line-wrap',
    'toggle-align-right',
    'toggle-line-numbers',
    'settings',
    'theme-system',
    'theme-light',
    'theme-dark',
    'theme-amoled',
    'language',
    'language-hebrew',
    'language-system',
    'scroll',
    'screenshot',
    'list',
];

const LANGUAGE_TAGS = [
    'system', 'en', 'en-GB', 'he', 'ar', 'fr', 'fr-CA', 'de', 'es', 'es-419',
    'pt', 'pt-BR', 'ru', 'ja', 'zh-CN', 'zh-TW', 'ko', 'hi',
];

const SPEC = {
    Action: { type: 'string', default: 'long-press' },
    // Tap/long-press point, as fractions of the screen.
    X: { type: 'double', default: 0.5 },
    Y: { type: 'double', default: 0.45 },
    // Overflow-menu button, as fractions of the screen. The default is the
    // centre of the three-dot icon on the pdfr_full toolbar.
    MenuX: { type: 'double', default: 0.95 },
    MenuY: { type: 'double', default: 0.085 },
    ScreenWidth: { type: 'int', default: 1080 },
    ScreenHeight: { type: 'int', default: 2340 },
    LongPressMs: { type: 'int', default: 750 },
    WaitMs: { type: 'int', default: 600 },
    ScrollDirection: { type: 'string', default: 'up' },
    ScrollDistance: { type: 'double', default: 0.45 },
    // Tag for -Action language. "system" clears the override.
    Language: { type: 'string', default: 'system' },
    OutDir: { type: 'string', default: '' },
    Prefix: { type: 'string', default: 'actions' },
};

// The overflow entry that opens Settings, in every UI language this build ships.
const SETTINGS_PATTERN = '^(Settings…|הגדרות…|Paramètres…|Einstellungen…|Ajustes…|Configuración…|Definições…|Configurações…|Настройки…|設定…|设置…|설정…|सेटिंग्स…|الإعدادات…)$';

// The combo trigger shows the current language caption. The dropdown is closed
// when this is used, so the only matching node is the trigger itself. The
// regional entries carry their region in parentheses in both columns.
const LANGUAGE_TRIGGER_PATTERN = '(English( \\([^)]*\\))?|Hebrew|Arabic|French( \\([^)]*\\))?|German|Spanish( \\([^)]*\\))?|Portuguese( \\([^)]*\\))?|Russian|Japanese|Chinese( \\([^)]*\\))?|Korean|Hindi|עברית|العربية|Français( \\([^)]*\\))?|Deutsch|Español( \\([^)]*\\))?|Português( \\([^)]*\\))?|Русский|日本語|简体中文|繁體中文|한국어|हिन्दी|System default|Langue du système|Systemstandard|Predeterminado del sistema|Predefinido do sistema|Padrão do sistema|Системный язык|システムの既定|系统默认|系統預設|시스템 기본값|सिस्टम डिफ़ॉल्ट|ברירת המחדל של המערכת|الافتراضي للنظام)';

// Full-row patterns for the two-column captions (English name, native name).
// The picker does not show a resource code, so the native name is the part
// that distinguishes plain languages from their regional overlays: the overlay
// endonyms always carry a parenthesised region. The (?s) prefix is translated
// to the JS `s` (dotAll) flag by compilePattern.
const LANGUAGE_ROW_PATTERNS = {
    system: '(?s)^\\s*(System default|Langue du système|Systemstandard|Predeterminado del sistema|Predefinido do sistema|Padrão do sistema|Системный язык|システムの既定|系统默认|系統預設|시스템 기본값|सिस्टम डिफ़ॉल्ट|ברירת המחדל של המערכת|الافتراضي للنظام)\\s*$',
    en: '(?s)^English.*English$',
    'en-gb': '(?s)^English.*English \\(.*\\)$',
    he: '(?s)^Hebrew.*עברית$',
    ar: '(?s)^Arabic.*العربية$',
    fr: '(?s)^French.*Français$',
    'fr-ca': '(?s)^French.*Français \\(.*\\)$',
    de: '(?s)^German.*Deutsch$',
    es: '(?s)^Spanish.*Español$',
    'es-419': '(?s)^Spanish.*Español \\(.*\\)$',
    pt: '(?s)^Portuguese.*Português$',
    'pt-br': '(?s)^Portuguese.*Português \\(.*\\)$',
    ru: '(?s)^Russian.*Русский$',
    ja: '(?s)^Japanese.*日本語$',
    'zh-cn': '(?s)^Chinese.*简体中文$',
    'zh-tw': '(?s)^Chinese.*繁體中文$',
    ko: '(?s)^Korean.*한국어$',
    hi: '(?s)^Hindi.*हिन्दी$',
};

/**
 * PowerShell casts doubles to int with banker's rounding; keep that so the
 * computed tap points match the old scripts exactly.
 */
function toInt(value) {
    const floor = Math.floor(value);
    const difference = value - floor;
    if (difference > 0.5) return floor + 1;
    if (difference < 0.5) return floor;
    return floor % 2 === 0 ? floor : floor + 1;
}

/** Compiles a .NET/PowerShell-style pattern, including a leading `(?s)`. */
function compilePattern(pattern) {
    let source = pattern;
    let flags = 'i'; // PowerShell's -match is case-insensitive.
    if (source.startsWith('(?s)')) {
        source = source.slice(4);
        flags += 's';
    }
    return new RegExp(source, flags);
}

function decodeXml(value) {
    return value
        .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => String.fromCodePoint(Number.parseInt(hex, 16)))
        .replace(/&#(\d+);/g, (_, decimal) => String.fromCodePoint(Number.parseInt(decimal, 10)))
        .replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'")
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&amp;/g, '&');
}

/** Reads the `<node>` elements out of a uiautomator dump. */
function parseNodes(xml) {
    const nodes = [];
    const element = /<node\b([^>]*?)\/?>/g;
    for (const match of xml.matchAll(element)) {
        const attributes = {};
        for (const attribute of match[1].matchAll(/([\w:.-]+)\s*=\s*"([^"]*)"/g)) {
            attributes[attribute[1]] = decodeXml(attribute[2]);
        }
        nodes.push({
            text: attributes.text || '',
            contentDesc: attributes['content-desc'] || '',
            bounds: attributes.bounds || '',
        });
    }
    return nodes;
}

function requireChoice(value, allowed, label) {
    const lower = String(value).toLowerCase();
    if (!allowed.some((candidate) => candidate.toLowerCase() === lower)) {
        throw new Error(`Unknown ${label} '${value}'. Choose one of: ${allowed.join(', ')}.`);
    }
    return lower;
}

function main() {
    const cli = parseCli(process.argv.slice(2), SPEC);

    // Validate before touching adb, so a typo fails even with no device.
    const action = requireChoice(cli.Action, ACTIONS, 'action');
    const scrollDirection = requireChoice(cli.ScrollDirection, ['up', 'down'], 'scroll direction');
    requireChoice(cli.Language, LANGUAGE_TAGS, 'language tag');

    const adb = resolveAdb();
    const outputDirectory = cli.OutDir || outDir('reader-actions');
    ensureDir(outputDirectory);

    function invokeAdb(args, options = {}) {
        const result = run(adb, args, { allowFailure: options.allowFailure === true });
        if (!options.quiet && result.stdout) process.stdout.write(result.stdout);
        if (result.stderr) process.stderr.write(result.stderr);
        return result;
    }

    function saveShot(name) {
        const remote = `/sdcard/pdfr-${cli.Prefix}-${name}.png`;
        const local = path.join(outputDirectory, `${cli.Prefix}-${name}.png`);
        invokeAdb(['shell', `screencap -p ${remote}`]);
        invokeAdb(['pull', remote, local], { quiet: true });
        // The remote file is only a scratch copy; a failed cleanup must not
        // fail an otherwise good run.
        invokeAdb(['shell', 'rm', remote], { quiet: true, allowFailure: true });
        step(local);
    }

    function getUiXml() {
        invokeAdb(['shell', 'uiautomator dump /sdcard/pdfr-window.xml'], { quiet: true });
        const local = path.join(outputDirectory, 'window.xml');
        invokeAdb(['pull', '/sdcard/pdfr-window.xml', local], { quiet: true });
        invokeAdb(['shell', 'rm', '/sdcard/pdfr-window.xml'], { quiet: true, allowFailure: true });
        return fs.readFileSync(local, 'utf8').replace(/^\uFEFF/, '');
    }

    function getNodeCenter(pattern) {
        const nodes = parseNodes(getUiXml());
        const matcher = compilePattern(pattern);
        const node = nodes.find((candidate) => (
            (candidate.text && matcher.test(candidate.text))
            || (candidate.contentDesc && matcher.test(candidate.contentDesc))
        ));

        if (!node) {
            throw new Error(`No UI node matched '${pattern}'. Run -Action list to see visible text.`);
        }
        const bounds = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(node.bounds);
        if (!bounds) {
            throw new Error(`Could not parse bounds '${node.bounds}' for '${pattern}'.`);
        }

        const left = Number.parseInt(bounds[1], 10);
        const top = Number.parseInt(bounds[2], 10);
        const right = Number.parseInt(bounds[3], 10);
        const bottom = Number.parseInt(bounds[4], 10);
        return {
            x: toInt((left + right) / 2),
            y: toInt((top + bottom) / 2),
        };
    }

    function tapNode(pattern) {
        const centre = getNodeCenter(pattern);
        step(`Tapping '${pattern}' at ${centre.x},${centre.y}`);
        invokeAdb(['shell', 'input', 'tap', String(centre.x), String(centre.y)]);
        sleep(cli.WaitMs);
    }

    function longPressAt(pointX, pointY) {
        // A zero-length swipe with a long duration is the most portable way to
        // long-press through `adb shell input`; `input tap` is too short.
        invokeAdb([
            'shell', 'input', 'swipe',
            String(pointX), String(pointY), String(pointX), String(pointY), String(cli.LongPressMs),
        ]);
        sleep(cli.WaitMs);
    }

    function showText() {
        const nodes = parseNodes(getUiXml());
        step('Visible text:');
        const seen = new Set();
        for (const node of nodes) {
            for (const value of [node.text, node.contentDesc]) {
                if (value && !seen.has(value)) {
                    seen.add(value);
                    say(`  ${value}`);
                }
            }
        }
    }

    function testOverflowMenuOpen() {
        const labels = compilePattern('^(Text size:|Single page:|Line wrap:|Align right:|Line numbers:|Monospace:|Reorder pages|Rotate pages|Normalize)');
        return parseNodes(getUiXml()).some((node) => node.text && labels.test(node.text));
    }

    function dismissOverflowMenu() {
        // BACK closes an open popup, but on its own it would leave the reader
        // and make the next tap land on the launcher. Only dismiss when a menu
        // exists.
        if (testOverflowMenuOpen()) {
            invokeAdb(['shell', 'input', 'keyevent', 'BACK']);
            sleep(300);
        }
    }

    function openOverflowMenu() {
        dismissOverflowMenu();
        const menuX = toInt(cli.ScreenWidth * cli.MenuX);
        const menuY = toInt(cli.ScreenHeight * cli.MenuY);
        invokeAdb(['shell', 'input', 'tap', String(menuX), String(menuY)]);
        sleep(cli.WaitMs);
    }

    function getLanguageRowPattern(tag) {
        const pattern = LANGUAGE_ROW_PATTERNS[String(tag).toLowerCase()];
        if (!pattern) throw new Error(`Unknown language tag '${tag}'.`);
        return pattern;
    }

    function selectLanguage(tag) {
        openOverflowMenu();
        tapNode(SETTINGS_PATTERN);
        tapNode(LANGUAGE_TRIGGER_PATTERN);
        tapNode(getLanguageRowPattern(tag));
        // The activity recreates, so wait for the new window before capturing.
        sleep(1800);
    }

    const pointX = toInt(cli.ScreenWidth * cli.X);
    const pointY = toInt(cli.ScreenHeight * cli.Y);

    switch (action) {
        case 'screenshot':
            saveShot('screen');
            break;
        case 'long-press':
            step(`Long-pressing ${pointX},${pointY}...`);
            longPressAt(pointX, pointY);
            saveShot('long-press');
            break;
        case 'tap':
            step(`Tapping ${pointX},${pointY}...`);
            invokeAdb(['shell', 'input', 'tap', String(pointX), String(pointY)]);
            sleep(cli.WaitMs);
            saveShot('tap');
            break;
        case 'select-all':
            step(`Long-pressing ${pointX},${pointY}...`);
            longPressAt(pointX, pointY);
            tapNode('^Select all$');
            saveShot('select-all');
            break;
        case 'copy':
            step(`Long-pressing ${pointX},${pointY}...`);
            longPressAt(pointX, pointY);
            tapNode('^Select all$');
            tapNode('^Copy$');
            saveShot('copy');
            break;
        case 'menu': {
            step('Opening the overflow menu...');
            const menuX = toInt(cli.ScreenWidth * cli.MenuX);
            const menuY = toInt(cli.ScreenHeight * cli.MenuY);
            invokeAdb(['shell', 'input', 'tap', String(menuX), String(menuY)]);
            sleep(cli.WaitMs);
            saveShot('menu');
            break;
        }
        case 'menu-mirror':
            step('Opening the overflow menu to check the toolbar mirror...');
            openOverflowMenu();
            saveShot('menu-mirror');
            break;
        case 'info':
            step('Opening document info from the toolbar...');
            // Document info has its own toolbar button (the circled i); it was
            // never part of the overflow menu. Dismiss an open popup first so
            // uiautomator can see the toolbar's content descriptions again, but
            // never send BACK on its own: that would leave the reader.
            dismissOverflowMenu();
            tapNode('^Document info$');
            saveShot('info');
            break;
        case 'metadata':
            step('Opening the editable metadata dialog...');
            dismissOverflowMenu();
            tapNode('^Document info$');
            saveShot('metadata');
            break;
        case 'metadata-apply':
            step('Opening metadata and applying the draft...');
            dismissOverflowMenu();
            tapNode('^Document info$');
            tapNode('^Apply$');
            saveShot('metadata-applied');
            break;
        case 'text-size':
            step('Opening the text-size dialog...');
            openOverflowMenu();
            tapNode('^Text size:');
            saveShot('text-size');
            break;
        case 'text-size-apply':
            step('Opening the text-size dialog and applying it...');
            openOverflowMenu();
            tapNode('^Text size:');
            tapNode('^Apply$');
            saveShot('text-size-applied');
            break;
        case 'toggle-single-page':
            step('Toggling the single-page text option...');
            openOverflowMenu();
            tapNode('^Single page:');
            saveShot('single-page');
            break;
        case 'toggle-line-wrap':
            step('Toggling the line-wrap text option...');
            openOverflowMenu();
            tapNode('^Line wrap:');
            saveShot('line-wrap');
            break;
        case 'toggle-align-right':
            step('Toggling right alignment...');
            openOverflowMenu();
            tapNode('^Align right:');
            saveShot('align-right');
            break;
        case 'toggle-line-numbers':
            step('Toggling line numbers...');
            openOverflowMenu();
            tapNode('^Line numbers:');
            saveShot('line-numbers');
            break;
        case 'settings':
            step('Opening Settings from the overflow menu...');
            openOverflowMenu();
            tapNode(SETTINGS_PATTERN);
            saveShot('settings');
            break;
        case 'theme-system':
            step('Choosing the system theme...');
            openOverflowMenu();
            tapNode(SETTINGS_PATTERN);
            tapNode('^(Same as system|לפי המערכת)$');
            saveShot('theme-system');
            break;
        case 'theme-light':
            step('Choosing the light theme...');
            openOverflowMenu();
            tapNode(SETTINGS_PATTERN);
            tapNode('^(Light|בהיר)$');
            saveShot('theme-light');
            break;
        case 'theme-dark':
            step('Choosing the dark theme...');
            openOverflowMenu();
            tapNode(SETTINGS_PATTERN);
            tapNode('^(Dark|כהה)$');
            saveShot('theme-dark');
            break;
        case 'theme-amoled':
            step('Choosing the AMOLED black theme...');
            openOverflowMenu();
            tapNode(SETTINGS_PATTERN);
            tapNode('^(AMOLED black|שחור AMOLED)$');
            saveShot('theme-amoled');
            break;
        case 'language':
            step(`Switching the UI to ${cli.Language}...`);
            selectLanguage(cli.Language);
            saveShot(`language-${cli.Language}`);
            break;
        case 'language-hebrew':
            step('Switching the UI to Hebrew...');
            selectLanguage('he');
            saveShot('language-hebrew');
            break;
        case 'language-system':
            step('Returning the UI to the system language...');
            selectLanguage('system');
            saveShot('language-system');
            break;
        case 'scroll': {
            const up = scrollDirection === 'up';
            const fromY = toInt(cli.ScreenHeight * (cli.Y + (up ? 1 : -1) * cli.ScrollDistance / 2));
            const toY = toInt(cli.ScreenHeight * (cli.Y + (up ? -1 : 1) * cli.ScrollDistance / 2));
            step(`Scrolling ${cli.ScrollDirection} from ${fromY} to ${toY}...`);
            invokeAdb([
                'shell', 'input', 'swipe',
                String(pointX), String(fromY), String(pointX), String(toY), '350',
            ]);
            sleep(cli.WaitMs);
            saveShot('scroll');
            break;
        }
        case 'list':
            showText();
            break;
        default:
            throw new Error(`Unknown action '${action}'.`);
    }

    step(`Output in ${outputDirectory}`);
    // Removing the screenshot from /sdcard can fail harmlessly; callers such
    // as manual-testing.bat should not see a failed run.
    return 0;
}

if (require.main === module) runMain(main);

module.exports = {
    compilePattern,
    decodeXml,
    parseNodes,
    toInt,
};
