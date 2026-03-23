import { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronRight, ChevronDown, Menu, X, Copy, Check, Search } from 'lucide-react';

/* ---------- table of contents ---------- */
const TOC_IDS = [
  'quick-start', 'chinese-api', 'touch', 'keys', 'ocr', 'find-image',
  'find-color', 'yolo', 'ui', 'wait', 'swipe-find', 'app', 'input',
  'device', 'system', 'sleep', 'permission', 'scale', 'backup',
  'model', 'templates', 'flow', 'console', 'arch', 'yolo-deploy',
] as const;

const TOC_LABEL_KEYS: Record<string, string> = {
  'quick-start': 'doc.toc.quickStart',
  'chinese-api': 'doc.toc.chineseApi',
  'touch': 'doc.toc.touch',
  'keys': 'doc.toc.keys',
  'ocr': 'doc.toc.ocr',
  'find-image': 'doc.toc.findImage',
  'find-color': 'doc.toc.findColor',
  'yolo': 'doc.toc.yolo',
  'ui': 'doc.toc.ui',
  'wait': 'doc.toc.wait',
  'swipe-find': 'doc.toc.swipeFind',
  'app': 'doc.toc.app',
  'input': 'doc.toc.input',
  'device': 'doc.toc.device',
  'system': 'doc.toc.system',
  'sleep': 'doc.toc.sleep',
  'permission': 'doc.toc.permission',
  'scale': 'doc.toc.scale',
  'backup': 'doc.toc.backup',
  'model': 'doc.toc.model',
  'templates': 'doc.toc.templates',
  'flow': 'doc.toc.flow',
  'console': 'doc.toc.console',
  'arch': 'doc.toc.arch',
  'yolo-deploy': 'doc.toc.yoloDeploy',
};

/* ---------- Python syntax highlighter ---------- */

const PY_KEYWORDS = new Set([
  'False','None','True','and','as','assert','async','await','break','class',
  'continue','def','del','elif','else','except','finally','for','from','global',
  'if','import','in','is','lambda','nonlocal','not','or','pass','raise',
  'return','try','while','with','yield',
]);
const PY_BUILTINS = new Set([
  'print','len','range','int','str','float','list','dict','tuple','set',
  'bool','type','isinstance','enumerate','zip','map','filter','sorted','open',
  'input','super','property','staticmethod','classmethod','abs','max','min',
  'sum','round','any','all','hasattr','getattr','setattr','format','repr',
]);

function highlightPython(code: string): string {
  return code.replace(
    /(#.*$)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|f"(?:[^"\\]|\\.)*"|f'(?:[^'\\]|\\.)*')|((?<!\w)\d+(?:\.\d+)?)|(\b[A-Za-z_]\w*\b)|([()[\]{},.:=+\-*/<>!&|%@~^])/gm,
    (match, comment, str, num, word, punct) => {
      if (comment) return `<span class="tok-comment">${comment}</span>`;
      if (str) {
        const inner = str.startsWith('f') ? str : match;
        return `<span class="tok-string">${inner}</span>`;
      }
      if (num) return `<span class="tok-number">${num}</span>`;
      if (word) {
        if (PY_KEYWORDS.has(word)) return `<span class="tok-keyword">${word}</span>`;
        if (PY_BUILTINS.has(word)) return `<span class="tok-builtin">${word}</span>`;
        if (/^[A-Z]/.test(word)) return `<span class="tok-class">${word}</span>`;
        return word;
      }
      if (punct) return `<span class="tok-punct">${punct}</span>`;
      return match;
    }
  );
}

function highlightBash(code: string): string {
  return code.replace(
    /(#.*$)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|((?<!\w)\d+(?:\.\d+)?)|\b(pip|yolo|adb|echo|mkdir|cd|push|install|export)\b/gm,
    (match, comment, str, num, cmd) => {
      if (comment) return `<span class="tok-comment">${comment}</span>`;
      if (str) return `<span class="tok-string">${str}</span>`;
      if (num) return `<span class="tok-number">${num}</span>`;
      if (cmd) return `<span class="tok-keyword">${cmd}</span>`;
      return match;
    }
  );
}

function highlightYaml(code: string): string {
  return code.replace(
    /(#.*$)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(^\s*[\w.-]+)(?=\s*:)|(\b\d+\b)/gm,
    (match, comment, str, key, num) => {
      if (comment) return `<span class="tok-comment">${comment}</span>`;
      if (str) return `<span class="tok-string">${str}</span>`;
      if (key) return `<span class="tok-keyword">${key}</span>`;
      if (num) return `<span class="tok-number">${num}</span>`;
      return match;
    }
  );
}

function detectLang(code: string): 'python' | 'bash' | 'yaml' | 'text' {
  if (/^\s*(import |from |def |class |#.*coding|click|print|sleep|ocr|swipe|toast)/m.test(code)) return 'python';
  if (/^\s*(pip |yolo |adb |echo |mkdir )/m.test(code)) return 'bash';
  if (/^\s*\w+\s*:.*$/m.test(code) && /^\s*(path|train|val|names)\s*:/m.test(code)) return 'yaml';
  return 'text';
}

function highlightCode(code: string, lang?: string): string {
  const detected = lang || detectLang(code);
  switch (detected) {
    case 'python': return highlightPython(code);
    case 'bash': return highlightBash(code);
    case 'yaml': return highlightYaml(code);
    default: return code.replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
}

/* ---------- reusable components ---------- */

function CopyButton({ text }: { text: string }) {
  const { t } = useTranslation('ide');
  const [copied, setCopied] = useState(false);
  const copy = useCallback(() => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }, [text]);
  return (
    <button
      onClick={copy}
      className="absolute top-2 right-2 flex items-center gap-1 rounded px-1.5 py-1 text-[10px] text-text-hint opacity-0 transition-all hover:bg-hover hover:text-text-primary group-hover:opacity-100"
      title={t('doc.copyCode')}
    >
      {copied ? <><Check size={12} className="text-green-400" /> {t('doc.copied')}</> : <><Copy size={12} /> {t('doc.copy')}</>}
    </button>
  );
}

function CodeBlock({ children, lang }: { children: string; lang?: string }) {
  const trimmed = children.trim();
  const html = highlightCode(trimmed, lang);
  const htmlLines = html.split('\n');

  return (
    <div className="group relative rounded-lg border border-divider bg-[#0d1117] overflow-hidden">
      <CopyButton text={trimmed} />
      <div className="overflow-x-auto p-4 pr-16">
        <table className="border-collapse text-xs leading-relaxed font-mono">
          <tbody>
            {htmlLines.map((line, i) => (
              <tr key={i} className="hover:bg-white/[0.03]">
                <td className="select-none pr-4 text-right align-top text-text-hint/40 w-[1%] whitespace-nowrap">
                  {htmlLines.length > 1 ? i + 1 : ''}
                </td>
                <td className="text-[#c9d1d9]" dangerouslySetInnerHTML={{ __html: line || '&nbsp;' }} />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ApiCard({
  name, alias, signature, params, returns, desc, example, defaultOpen = false,
}: {
  name: string;
  alias?: string;
  signature: string;
  params?: { name: string; type: string; desc: string }[];
  returns?: string;
  desc?: string;
  example?: string;
  defaultOpen?: boolean;
}) {
  const { t } = useTranslation('ide');
  const [open, setOpen] = useState(defaultOpen);

  return (
    <div className="rounded-lg border border-divider bg-card-bg overflow-hidden transition-shadow hover:shadow-md hover:shadow-black/10">
      <button
        onClick={() => setOpen(!open)}
        className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-hover"
      >
        {open
          ? <ChevronDown size={14} className="shrink-0 text-brand" />
          : <ChevronRight size={14} className="shrink-0 text-text-hint" />}
        <span className="text-sm font-semibold text-text-primary font-mono">{name}</span>
        {alias && (
          <span className="shrink-0 rounded bg-brand/10 px-1.5 py-0.5 text-[10px] font-medium text-brand-light">{alias}</span>
        )}
        {desc && !open && (
          <span className="ml-1 truncate text-xs text-text-hint">{desc}</span>
        )}
      </button>

      {open && (
        <div className="border-t border-divider px-4 pb-4 pt-3 space-y-3 animate-in fade-in slide-in-from-top-1 duration-200">
          <CodeBlock lang="python">{signature}</CodeBlock>
          {desc && <p className="text-xs text-text-secondary leading-relaxed">{desc}</p>}
          {alias && (
            <div className="flex items-baseline gap-2 rounded-md bg-brand/5 px-3 py-2">
              <span className="text-[11px] font-semibold text-text-muted uppercase tracking-wider shrink-0">{t('doc.chineseAlias')}</span>
              <span className="text-xs text-brand-light font-mono">{alias}</span>
            </div>
          )}
          {params && params.length > 0 && (
            <div className="space-y-1.5">
              <p className="text-[11px] font-semibold text-text-muted uppercase tracking-wider">{t('doc.params')}</p>
              <div className="rounded-md border border-divider overflow-hidden">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="bg-hover/50">
                      <th className="text-left px-3 py-1.5 text-text-hint font-medium">{t('doc.paramName')}</th>
                      <th className="text-left px-3 py-1.5 text-text-hint font-medium">{t('doc.paramType')}</th>
                      <th className="text-left px-3 py-1.5 text-text-hint font-medium">{t('doc.paramDesc')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {params.map((p) => (
                      <tr key={p.name} className="border-t border-divider/50 hover:bg-hover/30">
                        <td className="px-3 py-1.5 font-mono text-brand-light whitespace-nowrap">{p.name}</td>
                        <td className="px-3 py-1.5 text-text-hint whitespace-nowrap font-mono">{p.type}</td>
                        <td className="px-3 py-1.5 text-text-secondary">{p.desc}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
          {returns && (
            <div className="flex items-baseline gap-2 rounded-md bg-hover/30 px-3 py-2">
              <span className="text-[11px] font-semibold text-text-muted uppercase tracking-wider shrink-0">{t('doc.returns')}</span>
              <span className="text-xs text-text-secondary font-mono">{returns}</span>
            </div>
          )}
          {example && (
            <div className="space-y-1.5">
              <p className="text-[11px] font-semibold text-text-muted uppercase tracking-wider">{t('doc.example')}</p>
              <CodeBlock lang="python">{example}</CodeBlock>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function Section({ id, title, children }: { id: string; title: string; children: React.ReactNode }) {
  return (
    <section id={id} className="scroll-mt-6 space-y-4">
      <h2 className="text-lg font-bold text-text-primary border-b border-divider pb-2">{title}</h2>
      {children}
    </section>
  );
}

/* ===== helper: filter ApiCards by search ===== */
function matchSearch(q: string, ...fields: (string | undefined)[]) {
  if (!q) return true;
  return fields.some(f => f?.toLowerCase().includes(q));
}

/* ===== Quick Start ===== */
function QUICK_START_SECTION({ t }: { t: (key: string, opts?: any) => string }) {
  return (
    <Section id="quick-start" title={t('doc.toc.quickStart')}>
      <p className="text-xs text-text-secondary leading-relaxed" dangerouslySetInnerHTML={{ __html: t('doc.quickStart.intro') }} />
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-text-primary">{t('doc.quickStart.minExampleEn')}</h3>
        <CodeBlock lang="python">{`from yyds import *

${t('doc.code.getScreenSize')}
w, h = device_get_screen_size()
print(f"${t('doc.code.screenLabel')}: {w}x{h}")

${t('doc.code.clickScreenCenter')}
click(w // 2, h // 2)

${t('doc.code.ocrRecognize')}
results = ocr()
for r in results:
    print(r.text, r.x, r.y)`}</CodeBlock>
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-text-primary">{t('doc.quickStart.minExampleCn')}</h3>
        <CodeBlock lang="python">{`from yyds import *

${t('doc.code.getScreenSize')}
宽, 高 = 屏幕尺寸()
print(f"${t('doc.code.screenLabel')}: {宽}x{高}")

${t('doc.code.clickScreenCenter')}
点击(宽 // 2, 高 // 2)

${t('doc.code.ocrRecognize')}
结果 = 文字识别()
for r in 结果:
    print(r.text, r.x, r.y)`}</CodeBlock>
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-text-primary">{t('doc.quickStart.projectStructure')}</h3>
        <CodeBlock lang="text">{`my_project/
├── main.py          ${t('doc.code.projMainPy')}
├── project.config   ${t('doc.code.projConfig')}
└── res/             ${t('doc.code.projRes')}`}</CodeBlock>
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-text-primary">{t('doc.quickStart.devFlow')}</h3>
        <div className="grid gap-2 sm:grid-cols-3">
          {[
            { step: '1', title: t('doc.quickStart.step1Title'), desc: t('doc.quickStart.step1Desc') },
            { step: '2', title: t('doc.quickStart.step2Title'), desc: t('doc.quickStart.step2Desc') },
            { step: '3', title: t('doc.quickStart.step3Title'), desc: t('doc.quickStart.step3Desc') },
          ].map(s => (
            <div key={s.step} className="rounded-lg border border-divider bg-card-bg p-3 space-y-1">
              <div className="flex items-center gap-2">
                <span className="flex h-5 w-5 items-center justify-center rounded-full bg-brand/20 text-[10px] font-bold text-brand-light">{s.step}</span>
                <h4 className="text-xs font-bold text-text-primary">{s.title}</h4>
              </div>
              <p className="text-[11px] text-text-secondary leading-relaxed">{s.desc}</p>
            </div>
          ))}
        </div>
      </div>
    </Section>
  );
}

/* ===== Chinese API ===== */
function CHINESE_API_SECTION({ t }: { t: (key: string, opts?: any) => string }) {
  return (
    <Section id="chinese-api" title={t('doc.toc.chineseApi')}>
      <p className="text-xs text-text-secondary leading-relaxed" dangerouslySetInnerHTML={{ __html: t('doc.chineseApi.intro') }} />
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-text-primary">{t('doc.chineseApi.quickRef')}</h3>
        <div className="rounded-md border border-divider overflow-hidden">
          <table className="w-full text-xs">
            <thead><tr className="bg-hover/50">
              <th className="text-left px-3 py-1.5 text-text-hint font-medium">{t('doc.chineseApi.colCategory')}</th>
              <th className="text-left px-3 py-1.5 text-text-hint font-medium">{t('doc.chineseApi.colChinese')}</th>
              <th className="text-left px-3 py-1.5 text-text-hint font-medium">{t('doc.chineseApi.colEnglish')}</th>
            </tr></thead>
            <tbody>
              {[
                [t('doc.chineseApi.catTouch'), t('doc.chineseApi.cnTouch'), 'click / click_double / long_press / swipe'],
                [t('doc.chineseApi.catKey'), t('doc.chineseApi.cnKey'), 'key_back / key_home / key_confirm'],
                ['OCR', t('doc.chineseApi.cnOcr'), 'screenshot / ocr / screen_ocr_x'],
                [t('doc.chineseApi.catFindImage'), t('doc.chineseApi.cnFindImage'), 'screen_find_image / screen_find_image_x / find_image_click'],
                [t('doc.chineseApi.catFindColor'), t('doc.chineseApi.cnFindColor'), 'find_color / get_color / get_multi_color'],
                ['YOLO', t('doc.chineseApi.cnYolo'), 'screen_yolo_locate / screen_yolo_find_x'],
                [t('doc.chineseApi.catUi'), t('doc.chineseApi.cnUi'), 'ui_match / ui_exist'],
                [t('doc.chineseApi.catWait'), t('doc.chineseApi.cnWait'), 'wait_for_text / wait_for_ui / wait_for_image'],
                [t('doc.chineseApi.catApp'), t('doc.chineseApi.cnApp'), 'open_app / stop_app / is_in_app'],
                [t('doc.chineseApi.catInput'), t('doc.chineseApi.cnInput'), 'input_text / x_input_text / set_clipboard'],
                [t('doc.chineseApi.catDevice'), t('doc.chineseApi.cnDevice'), 'device_get_screen_size / device_model / device_foreground_package'],
                [t('doc.chineseApi.catSystem'), t('doc.chineseApi.cnSystem'), 'shell / toast / sleep'],
              ].map(([cat, cn, en]) => (
                <tr key={cat} className="border-t border-divider/50 hover:bg-hover/30">
                  <td className="px-3 py-1.5 font-medium text-text-primary whitespace-nowrap">{cat}</td>
                  <td className="px-3 py-1.5 font-mono text-brand-light">{cn}</td>
                  <td className="px-3 py-1.5 font-mono text-text-hint">{en}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-text-primary">{t('doc.chineseApi.fullExample')}</h3>
        <CodeBlock lang="python">{`from yyds import *

${t('doc.code.openWeChat')}
打开应用("com.tencent.mm")
等待(3)

${t('doc.code.waitHomePage')}
等待文字("微信", "通讯录", timeout=15)

${t('doc.code.clickDiscover')}
文字点击("发现")
等待(1)

${t('doc.code.swipeFindMoments')}
滑动找字并点击("朋友圈")

${t('doc.code.screenshotSave')}
截图("/sdcard/朋友圈.png")
提示("${t('doc.code.operationDone')}")`}</CodeBlock>
      </div>
    </Section>
  );
}

/* ===== Touch & Gesture ===== */
function TOUCH_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'click', alias: '点击', signature: 'click(x, y)', params: [{ name: 'x', type: 'int|str', desc: t('doc.touch.xCoord') }, { name: 'y', type: 'int|str', desc: t('doc.touch.yCoord') }], desc: t('doc.touch.click.desc'), example: `click(540, 960)\n${t('doc.code.clickChinese')}\n点击(540, 960)` },
    { name: 'click_double', alias: '双击', signature: 'click_double(x, y)', params: [{ name: 'x', type: 'int|str', desc: t('doc.touch.xCoord') }, { name: 'y', type: 'int|str', desc: t('doc.touch.yCoord') }], desc: t('doc.touch.clickDouble.desc') },
    { name: 'random_click', alias: '随机点击', signature: 'random_click(x, y, rx=10, ry=10)', params: [{ name: 'x', type: 'int', desc: t('doc.touch.xCoord') }, { name: 'y', type: 'int', desc: t('doc.touch.yCoord') }, { name: 'rx', type: 'int', desc: t('doc.touch.xRandomOffset') }, { name: 'ry', type: 'int', desc: t('doc.touch.yRandomOffset') }], desc: t('doc.touch.randomClick.desc'), example: 'random_click(540, 960, rx=15, ry=15)' },
    { name: 'long_press', alias: '长按', signature: 'long_press(x, y, duration=500)', params: [{ name: 'x', type: 'int', desc: t('doc.touch.xCoord') }, { name: 'y', type: 'int', desc: t('doc.touch.yCoord') }, { name: 'duration', type: 'int', desc: t('doc.touch.pressDuration') }], desc: t('doc.touch.longPress.desc'), example: 'long_press(540, 960, duration=2000)' },
    { name: 'swipe', alias: '滑动', signature: 'swipe(x1, y1, x2, y2, duration=300)', params: [{ name: 'x1, y1', type: 'int', desc: t('doc.touch.startPoint') }, { name: 'x2, y2', type: 'int', desc: t('doc.touch.endPoint') }, { name: 'duration', type: 'int', desc: t('doc.touch.swipeDuration') }], desc: t('doc.touch.swipe.desc'), example: 'swipe(540, 1500, 540, 500, 500)' },
    { name: 'swipe_up / down / left / right', alias: '上滑 / 下滑 / 左滑 / 右滑', signature: 'swipe_up()\nswipe_down()\nswipe_left()\nswipe_right()', desc: t('doc.touch.swipeDir.desc'), example: '上滑()\n下滑()' },
    { name: 'gesture', alias: '手势', signature: 'gesture(points, duration=300)', params: [{ name: 'points', type: 'List[Tuple]', desc: t('doc.touch.pointsList') }, { name: 'duration', type: 'int', desc: t('doc.touch.gestureDuration') }], desc: t('doc.touch.gesture.desc'), example: 'gesture([(100,500), (540,960), (900,500)], 800)' },
    { name: 'pinch_in / pinch_out', alias: '双指缩小 / 双指放大', signature: 'pinch_in(cx, cy, percent=50)\npinch_out(cx, cy, percent=50)', params: [{ name: 'cx, cy', type: 'int', desc: t('doc.touch.zoomCenter') }, { name: 'percent', type: 'int', desc: t('doc.touch.zoomPercent') }], desc: t('doc.touch.pinch.desc'), example: `pinch_out(540, 960, 80)  ${t('doc.code.zoomIn')}` },
    { name: 'click_target', alias: '点击目标', signature: 'click_target(node_or_ocr)', params: [{ name: 'node_or_ocr', type: 'Node|ResOcr', desc: t('doc.touch.nodeOrOcr') }], desc: t('doc.touch.clickTarget.desc'), example: 'r = screen_ocr_first_x("设置")\nif r: click_target(r)' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="touch" title={t('doc.toc.touch')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Key Operations ===== */
function KEYS_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'key_back', alias: '返回键', signature: 'key_back()', desc: t('doc.keys.back.desc') },
    { name: 'key_home', alias: '主页键', signature: 'key_home()', desc: t('doc.keys.home.desc') },
    { name: 'key_menu', alias: '菜单键', signature: 'key_menu()', desc: t('doc.keys.menu.desc') },
    { name: 'key_confirm', alias: '确认键', signature: 'key_confirm()', desc: t('doc.keys.confirm.desc') },
    { name: 'key_code', alias: '按键', signature: 'key_code(code)', params: [{ name: 'code', type: 'int', desc: t('doc.keys.codeParam') }], desc: t('doc.keys.code.desc'), example: `key_code(24)  ${t('doc.code.volumeUp')}\nkey_code(25)  ${t('doc.code.volumeDown')}` },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="keys" title={t('doc.toc.keys')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== OCR Text Recognition ===== */
function OCR_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'screenshot', alias: '截图', signature: 'screenshot(path=None)', params: [{ name: 'path', type: 'str|None', desc: t('doc.ocr.pathParam') }], desc: t('doc.ocr.screenshot.desc'), returns: t('doc.ocr.screenshot.returns'), example: 'path = screenshot("/sdcard/screen.png")' },
    { name: 'ocr', alias: '文字识别', signature: 'ocr(path=None)', params: [{ name: 'path', type: 'str|None', desc: t('doc.ocr.imgPathParam') }], desc: t('doc.ocr.ocr.desc'), returns: t('doc.ocr.ocr.returns'), example: 'results = ocr()\nfor r in results:\n    print(f"{r.text} @ ({r.x},{r.y})")' },
    { name: 'screen_ocr_x', alias: '屏幕找字', signature: 'screen_ocr_x(text, path=None)', params: [{ name: 'text', type: 'str', desc: t('doc.ocr.textParam') }], desc: t('doc.ocr.screenOcrX.desc'), returns: 'list[ResOcr]', example: 'results = screen_ocr_x("设置")\nif results:\n    click_target(results[0])' },
    { name: 'screen_ocr_first_x', alias: '屏幕找字_首个', signature: 'screen_ocr_first_x(text, path=None)', desc: t('doc.ocr.screenOcrFirstX.desc'), returns: 'ResOcr|None' },
    { name: 'ocr_click_if_found', alias: '文字点击', signature: 'ocr_click_if_found(text)', desc: t('doc.ocr.ocrClickIfFound.desc'), returns: 'bool', example: '文字点击("同意")' },
    { name: 'ocr_click_any', alias: '文字点击任一', signature: 'ocr_click_any(*texts)', desc: t('doc.ocr.ocrClickAny.desc'), returns: 'bool', example: 'ocr_click_any("确定", "确认", "OK")' },
    { name: 'ocr_exists_all', alias: '文字全部存在', signature: 'ocr_exists_all(*texts)', desc: t('doc.ocr.ocrExistsAll.desc'), returns: 'bool', example: 'if ocr_exists_all("微信", "通讯录"):\n    print("在微信首页")' },
    { name: 'ocr_exists_any', alias: '文字任一存在', signature: 'ocr_exists_any(*texts)', desc: t('doc.ocr.ocrExistsAny.desc'), returns: 'bool' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="ocr" title={t('doc.toc.ocr')}><p className="text-xs text-text-secondary">{t('doc.ocr.sectionDesc')}</p><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Find Image ===== */
function FIND_IMAGE_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'screen_find_image', alias: '找图', signature: 'screen_find_image(template, threshold=0.8, path=None)', params: [{ name: 'template', type: 'str', desc: t('doc.findImage.templateParam') }, { name: 'threshold', type: 'float', desc: t('doc.findImage.thresholdParam') }], desc: t('doc.findImage.screenFindImage.desc'), returns: t('doc.findImage.screenFindImage.returns'), example: 'pos = screen_find_image("res/btn.png")\nif pos:\n    click(pos["x"], pos["y"])' },
    { name: 'screen_find_image_x', alias: '屏幕找图', signature: 'screen_find_image_x(template, threshold=0.8, path=None)', desc: t('doc.findImage.screenFindImageX.desc'), returns: 'list' },
    { name: 'screen_find_image_first_x', alias: '屏幕找图_首个', signature: 'screen_find_image_first_x(template, threshold=0.8)', desc: t('doc.findImage.screenFindImageFirstX.desc'), returns: 'dict|None' },
    { name: 'match_images', alias: '模板匹配', signature: 'match_images(source, template, threshold=0.8)', desc: t('doc.findImage.matchImages.desc'), returns: 'list' },
    { name: 'image_similarity', alias: '图片相似度', signature: 'image_similarity(img1, img2)', desc: t('doc.findImage.imageSimilarity.desc'), returns: 'float — 0~1', example: `sim = image_similarity("a.png", "b.png")\nprint(f"${t('doc.code.similarityLabel')}: {sim:.2%}")` },
    { name: 'find_image_click', alias: '找图点击', signature: 'find_image_click(template, threshold=0.8)', desc: t('doc.findImage.findImageClick.desc'), returns: 'bool' },
    { name: 'find_image_click_max_prob', alias: '找图点击_最高匹配', signature: 'find_image_click_max_prob(template, threshold=0.8)', desc: t('doc.findImage.findImageClickMaxProb.desc'), returns: 'bool' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="find-image" title={t('doc.toc.findImage')}><p className="text-xs text-text-secondary">{t('doc.findImage.sectionDesc')}</p><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Find Color ===== */
function FIND_COLOR_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'find_color', alias: '找色', signature: 'find_color(base_rgb, offset_rgb=None, x1=0, y1=0, x2=0, y2=0, path=None)', params: [{ name: 'base_rgb', type: 'str', desc: t('doc.findColor.baseRgbParam') }, { name: 'offset_rgb', type: 'str|None', desc: t('doc.findColor.offsetRgbParam') }, { name: 'x1,y1,x2,y2', type: 'int', desc: t('doc.findColor.regionParam') }], desc: t('doc.findColor.findColor.desc'), returns: 'dict|None — {x, y}', example: 'pos = find_color("255,0,0")\nif pos: click(pos["x"], pos["y"])' },
    { name: 'get_color', alias: '取色', signature: 'get_color(x, y, path=None)', desc: t('doc.findColor.getColor.desc'), returns: 'str — "R,G,B"', example: 'color = get_color(100, 200)' },
    { name: 'get_multi_color', alias: '多点取色', signature: 'get_multi_color(points, path=None)', desc: t('doc.findColor.getMultiColor.desc'), returns: 'list[str]' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="find-color" title={t('doc.toc.findColor')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== YOLO Object Detection ===== */
function YOLO_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'screen_yolo_locate', alias: '目标检测', signature: 'screen_yolo_locate(labels=None, conf=0.5, path=None)', params: [{ name: 'labels', type: 'list|None', desc: t('doc.yolo.labelsParam') }, { name: 'conf', type: 'float', desc: t('doc.yolo.confParam') }], desc: t('doc.yolo.screenYoloLocate.desc'), returns: 'list[dict]', example: 'results = screen_yolo_locate(conf=0.6)\nfor r in results:\n    print(r["label"], r["x"], r["y"])' },
    { name: 'screen_yolo_find_x', alias: '屏幕找目标', signature: 'screen_yolo_find_x(label, conf=0.5, path=None)', desc: t('doc.yolo.screenYoloFindX.desc'), returns: 'list' },
    { name: 'screen_yolo_find_first_x', alias: '屏幕找目标_首个', signature: 'screen_yolo_find_first_x(label, conf=0.5)', desc: t('doc.yolo.screenYoloFindFirstX.desc'), returns: 'dict|None' },
    { name: 'model_yolo_reload', alias: '重载YOLO模型', signature: 'model_yolo_reload(model_path)', desc: t('doc.yolo.modelYoloReload.desc'), returns: 'str' },
    { name: 'model_yolo_info', alias: 'YOLO模型信息', signature: 'model_yolo_info()', desc: t('doc.yolo.modelYoloInfo.desc'), returns: 'dict' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="yolo" title={t('doc.toc.yolo')}><p className="text-xs text-text-secondary">{t('doc.yolo.sectionDesc')}</p><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== UI Widgets ===== */
function UI_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'ui_match', alias: '控件匹配', signature: 'ui_match(**kwargs)', params: [{ name: 'text', type: 'str', desc: t('doc.ui.textParam') }, { name: 'cls', type: 'str', desc: t('doc.ui.clsParam') }, { name: 'res', type: 'str', desc: 'resource-id' }, { name: 'desc', type: 'str', desc: 'content-desc' }, { name: 'pkg', type: 'str', desc: t('doc.ui.pkgParam') }], desc: t('doc.ui.uiMatch.desc'), returns: 'list[Node]', example: 'nodes = ui_match(text="设置")\nif nodes:\n    click_target(nodes[0])' },
    { name: 'ui_exist', alias: '控件存在', signature: 'ui_exist(**kwargs)', desc: t('doc.ui.uiExist.desc'), returns: 'bool', example: 'if ui_exist(text="确定"):\n    print("找到确定按钮")' },
    { name: 'ui_parent', alias: '控件父级', signature: 'ui_parent(node)', desc: t('doc.ui.uiParent.desc'), returns: 'Node|None' },
    { name: 'ui_children', alias: '控件子级', signature: 'ui_children(node)', desc: t('doc.ui.uiChildren.desc'), returns: 'list[Node]' },
    { name: 'ui_sibling', alias: '控件兄弟', signature: 'ui_sibling(node)', desc: t('doc.ui.uiSibling.desc'), returns: 'list[Node]' },
    { name: 'ui_offset', alias: '控件偏移', signature: 'ui_offset(node, dx=0, dy=0)', desc: t('doc.ui.uiOffset.desc'), returns: 'tuple' },
    { name: 'ui_dump', alias: '控件树', signature: 'ui_dump()', desc: t('doc.ui.uiDump.desc'), returns: 'str', example: 'xml = ui_dump()\nprint(xml)' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="ui" title={t('doc.toc.ui')}><p className="text-xs text-text-secondary">{t('doc.ui.sectionDesc')}</p><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Wait Operations ===== */
function WAIT_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'wait_for_text', alias: '等待文字', signature: 'wait_for_text(*texts, timeout=15, interval=1)', params: [{ name: 'texts', type: 'str...', desc: t('doc.wait.textsParam') }, { name: 'timeout', type: 'float', desc: t('doc.wait.timeoutParam') }], desc: t('doc.wait.waitForText.desc'), returns: 'list[ResOcr]|None', example: '等待文字("登录成功", timeout=30)' },
    { name: 'wait_for_text_gone', alias: '等待文字消失', signature: 'wait_for_text_gone(*texts, timeout=15)', desc: t('doc.wait.waitForTextGone.desc'), returns: 'bool' },
    { name: 'wait_for_ui', alias: '等待控件', signature: 'wait_for_ui(timeout=15, **kwargs)', desc: t('doc.wait.waitForUi.desc'), returns: 'list[Node]|None', example: '等待控件(text="确定", timeout=10)' },
    { name: 'wait_for_ui_gone', alias: '等待控件消失', signature: 'wait_for_ui_gone(timeout=15, **kwargs)', desc: t('doc.wait.waitForUiGone.desc'), returns: 'bool' },
    { name: 'wait_for_image', alias: '等待图片', signature: 'wait_for_image(template, timeout=15, threshold=0.8)', desc: t('doc.wait.waitForImage.desc'), returns: 'dict|None' },
    { name: 'wait_for_activity', alias: '等待页面', signature: 'wait_for_activity(activity, timeout=15)', desc: t('doc.wait.waitForActivity.desc'), returns: 'str|None' },
    { name: 'wait_for_package', alias: '等待应用', signature: 'wait_for_package(pkg, timeout=15)', desc: t('doc.wait.waitForPackage.desc'), returns: 'str|None' },
    { name: 'wait_and_click_text', alias: '等待并点击文字', signature: 'wait_and_click_text(text, timeout=15)', desc: t('doc.wait.waitAndClickText.desc'), returns: 'bool', example: '等待并点击文字("同意", timeout=10)' },
    { name: 'wait_and_click_ui', alias: '等待并点击控件', signature: 'wait_and_click_ui(timeout=15, **kwargs)', desc: t('doc.wait.waitAndClickUi.desc'), returns: 'bool' },
    { name: 'wait_screen_change', alias: '等待画面变化', signature: 'wait_screen_change(timeout=15, threshold=0.95)', desc: t('doc.wait.waitScreenChange.desc'), returns: 'bool' },
    { name: 'wait_screen_stable', alias: '等待画面稳定', signature: 'wait_screen_stable(timeout=15, stable_time=2)', desc: t('doc.wait.waitScreenStable.desc'), returns: 'bool' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="wait" title={t('doc.toc.wait')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Swipe Search ===== */
function SWIPE_FIND_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'swipe_to_find_text', alias: '滑动找字', signature: 'swipe_to_find_text(text, direction="up", max_swipes=10, interval=0.8)', params: [{ name: 'text', type: 'str', desc: t('doc.swipeFind.textParam') }, { name: 'direction', type: 'str', desc: t('doc.swipeFind.directionParam') }, { name: 'max_swipes', type: 'int', desc: t('doc.swipeFind.maxSwipesParam') }], desc: t('doc.swipeFind.swipeToFindText.desc'), returns: 'list[ResOcr]|None', example: 'results = swipe_to_find_text("隐私设置")\nif results:\n    click_target(results[0])' },
    { name: 'swipe_to_find_ui', alias: '滑动找控件', signature: 'swipe_to_find_ui(direction="up", max_swipes=10, **kwargs)', desc: t('doc.swipeFind.swipeToFindUi.desc'), returns: 'list[Node]|None' },
    { name: 'swipe_to_find_image', alias: '滑动找图', signature: 'swipe_to_find_image(template, direction="up", max_swipes=10)', desc: t('doc.swipeFind.swipeToFindImage.desc'), returns: 'dict|None' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="swipe-find" title={t('doc.toc.swipeFind')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== App Management ===== */
function APP_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'open_app', alias: '打开应用', signature: 'open_app(pkg)', params: [{ name: 'pkg', type: 'str', desc: t('doc.app.pkgParam') }], desc: t('doc.app.openApp.desc'), example: 'open_app("com.tencent.mm")' },
    { name: 'stop_app', alias: '关闭应用', signature: 'stop_app(pkg)', desc: t('doc.app.stopApp.desc') },
    { name: 'open_url', alias: '打开网址', signature: 'open_url(url)', desc: t('doc.app.openUrl.desc'), example: 'open_url("https://example.com")' },
    { name: 'app_to_front', alias: '应用置顶', signature: 'app_to_front(pkg)', desc: t('doc.app.appToFront.desc') },
    { name: 'app_is_running', alias: '应用是否运行', signature: 'app_is_running(pkg)', desc: t('doc.app.appIsRunning.desc'), returns: 'bool' },
    { name: 'is_in_app', alias: '是否在应用内', signature: 'is_in_app(pkg)', desc: t('doc.app.isInApp.desc'), returns: 'bool' },
    { name: 'uninstall_app', alias: '卸载应用', signature: 'uninstall_app(pkg)', desc: t('doc.app.uninstallApp.desc') },
    { name: 'clear_app_data', alias: '清除应用数据', signature: 'clear_app_data(pkg)', desc: t('doc.app.clearAppData.desc') },
    { name: 'open_app_from_desktop', alias: '从桌面打开应用', signature: 'open_app_from_desktop(app_name)', desc: t('doc.app.openAppFromDesktop.desc') },
    { name: 'exit_go_home', alias: '回到桌面', signature: 'exit_go_home()', desc: t('doc.app.exitGoHome.desc') },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="app" title={t('doc.toc.app')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Text Input ===== */
function INPUT_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'input_text', alias: '输入文字', signature: 'input_text(text)', params: [{ name: 'text', type: 'str', desc: t('doc.input.textParam') }], desc: t('doc.input.inputText.desc'), example: 'input_text("hello123")' },
    { name: 'x_input_text', alias: '智能输入', signature: 'x_input_text(text)', desc: t('doc.input.xInputText.desc'), example: '智能输入("你好世界")' },
    { name: 'clear_text', alias: '清空输入', signature: 'clear_text()', desc: t('doc.input.clearText.desc') },
    { name: 'set_text', alias: '设置文本', signature: 'set_text(node, text)', desc: t('doc.input.setText.desc') },
    { name: 'set_clipboard', alias: '复制到剪贴板', signature: 'set_clipboard(text)', desc: t('doc.input.setClipboard.desc'), example: 'set_clipboard("要粘贴的内容")' },
    { name: 'get_clipboard', alias: '获取剪贴板', signature: 'get_clipboard()', desc: t('doc.input.getClipboard.desc'), returns: 'str' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="input" title={t('doc.toc.input')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Device Info ===== */
function DEVICE_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'device_get_screen_size', alias: '屏幕尺寸', signature: 'device_get_screen_size()', desc: t('doc.device.screenSize.desc'), returns: 'tuple — (width, height)', example: 'w, h = 屏幕尺寸()\nprint(f"{w}x{h}")' },
    { name: 'device_foreground_activity', alias: '前台应用', signature: 'device_foreground_activity()', desc: t('doc.device.fgActivity.desc'), returns: 'str' },
    { name: 'device_foreground_package', alias: '前台包名', signature: 'device_foreground_package()', desc: t('doc.device.fgPackage.desc'), returns: 'str' },
    { name: 'device_serial', alias: '设备编号', signature: 'device_serial()', desc: t('doc.device.serial.desc'), returns: 'str' },
    { name: 'device_model', alias: '设备型号', signature: 'device_model()', desc: t('doc.device.model.desc'), returns: 'str' },
    { name: 'device_is_online', alias: '网络是否在线', signature: 'device_is_online()', desc: t('doc.device.isOnline.desc'), returns: 'bool' },
    { name: 'device_screen_orientation', alias: '获取屏幕方向', signature: 'device_screen_orientation()', desc: t('doc.device.orientation.desc'), returns: 'int — 0/1/2/3' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="device" title={t('doc.toc.device')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== System Capabilities ===== */
function SYSTEM_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'shell', alias: '执行命令', signature: 'shell(cmd)', params: [{ name: 'cmd', type: 'str', desc: t('doc.system.cmdParam') }], desc: t('doc.system.shell.desc'), returns: t('doc.system.shell.returns'), example: 'output = shell("ls /sdcard/")\nprint(output)' },
    { name: 'toast', alias: '提示', signature: 'toast(msg)', desc: t('doc.system.toast.desc'), example: 'toast("操作完成!")' },
    { name: 'toast_print', alias: '提示打印', signature: 'toast_print(msg)', desc: t('doc.system.toastPrint.desc') },
    { name: 'download', alias: '下载文件', signature: 'download(url, path)', desc: t('doc.system.download.desc'), returns: t('doc.system.download.returns') },
    { name: 'pull_file', alias: '拉取文件', signature: 'pull_file(remote, local)', desc: t('doc.system.pullFile.desc') },
    { name: 'push_file', alias: '推送文件', signature: 'push_file(local, remote)', desc: t('doc.system.pushFile.desc') },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="system" title={t('doc.toc.system')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Delay ===== */
function SLEEP_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'sleep', alias: '等待', signature: 'sleep(seconds)', params: [{ name: 'seconds', type: 'float', desc: t('doc.sleep.secondsParam') }], desc: t('doc.sleep.sleep.desc'), example: `sleep(2)  ${t('doc.code.wait2Seconds')}` },
    { name: 'false_sleep', alias: '假等待', signature: 'false_sleep(seconds)', desc: t('doc.sleep.falseSleep.desc') },
    { name: 'random_sleep', alias: '随机等待', signature: 'random_sleep(min_s, max_s)', params: [{ name: 'min_s', type: 'float', desc: t('doc.sleep.minParam') }, { name: 'max_s', type: 'float', desc: t('doc.sleep.maxParam') }], desc: t('doc.sleep.randomSleep.desc'), example: `random_sleep(1, 3)  ${t('doc.code.randomWait1to3')}` },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="sleep" title={t('doc.toc.sleep')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Permissions & Device Control ===== */
function PERMISSION_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'grant_permission', alias: '授予权限', signature: 'grant_permission(pkg, permission)', desc: t('doc.permission.grant.desc') },
    { name: 'revoke_permission', alias: '撤销权限', signature: 'revoke_permission(pkg, permission)', desc: t('doc.permission.revoke.desc') },
    { name: 'get_notifications', alias: '获取通知', signature: 'get_notifications()', desc: t('doc.permission.getNotifications.desc'), returns: 'list' },
    { name: 'get_wifi_info', alias: '获取WiFi信息', signature: 'get_wifi_info()', desc: t('doc.permission.getWifiInfo.desc'), returns: 'dict' },
    { name: 'set_wifi', alias: '设置WiFi开关', signature: 'set_wifi(enable)', desc: t('doc.permission.setWifi.desc') },
    { name: 'set_airplane_mode', alias: '设置飞行模式', signature: 'set_airplane_mode(enable)', desc: t('doc.permission.setAirplaneMode.desc') },
    { name: 'set_screen_brightness', alias: '设置屏幕亮度', signature: 'set_screen_brightness(value)', desc: t('doc.permission.setBrightness.desc') },
    { name: 'get_battery_info', alias: '获取电池信息', signature: 'get_battery_info()', desc: t('doc.permission.getBatteryInfo.desc'), returns: 'dict' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="permission" title={t('doc.toc.permission')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Resolution Adaptation ===== */
function SCALE_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'scale_pos_1080_2400', alias: '坐标缩放', signature: 'scale_pos_1080_2400(x, y)', params: [{ name: 'x', type: 'int', desc: t('doc.scale.xParam') }, { name: 'y', type: 'int', desc: t('doc.scale.yParam') }], desc: t('doc.scale.scalePos.desc'), returns: 'tuple — (scaled_x, scaled_y)', example: `${t('doc.code.scaleCoordComment')}\nsx, sy = scale_pos_1080_2400(540, 1200)\nclick(sx, sy)` },
    { name: 'scale_click_1080_2400', alias: '缩放点击', signature: 'scale_click_1080_2400(x, y)', desc: t('doc.scale.scaleClick.desc'), example: '缩放点击(540, 1200)' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="scale" title={t('doc.toc.scale')}><p className="text-xs text-text-secondary">{t('doc.scale.sectionDesc')}</p><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Data Backup ===== */
function BACKUP_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'app_data_backup', alias: '应用数据备份', signature: 'app_data_backup(pkg, path)', desc: t('doc.backup.appDataBackup.desc') },
    { name: 'app_data_restore', alias: '应用数据恢复', signature: 'app_data_restore(pkg, path)', desc: t('doc.backup.appDataRestore.desc') },
    { name: 'app_apk_backup', alias: '应用安装包备份', signature: 'app_apk_backup(pkg, path)', desc: t('doc.backup.appApkBackup.desc') },
    { name: 'install_app', alias: '安装应用', signature: 'install_app(apk_path)', desc: t('doc.backup.installApp.desc') },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.alias, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="backup" title={t('doc.toc.backup')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Scene Templates ===== */
function TEMPLATES_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: '启动并等待', signature: '启动并等待(包名, 等待秒数=15, 等待文字=None, 等待控件=None)', params: [{ name: '包名', type: 'str', desc: t('doc.app.pkgParam') }, { name: '等待秒数', type: 'int', desc: t('doc.wait.timeoutParam') }, { name: '等待文字', type: 'str|None', desc: t('doc.templates.waitTextParam') }, { name: '等待控件', type: 'dict|None', desc: t('doc.templates.waitUiParam') }], desc: t('doc.templates.launchAndWait.desc'), returns: 'bool', example: '启动并等待("com.tencent.mm", 等待文字="微信")' },
    { name: '关闭并重启', signature: '关闭并重启(包名, 等待秒数=15)', desc: t('doc.templates.killAndRestart.desc'), returns: 'bool', example: '关闭并重启("com.tencent.mm")' },
    { name: '滑动找字并点击', signature: '滑动找字并点击(文字, 方向="up", 最大滑动次数=10)', desc: t('doc.templates.swipeFindAndClick.desc'), returns: 'bool', example: '滑动找字并点击("隐私设置")' },
    { name: '重复点击直到', signature: '重复点击直到(x, y, 出现文字=None, 消失文字=None, 最大次数=20, 间隔=1)', desc: t('doc.templates.repeatClickUntil.desc'), returns: 'bool', example: '重复点击直到(540, 1800, 出现文字="登录成功")' },
    { name: '输入中文', signature: '输入中文(文字)', desc: t('doc.templates.inputChinese.desc'), example: '输入中文("你好世界")' },
    { name: '条件等待循环', signature: '条件等待循环(检查函数, 超时秒数=30, 间隔=1)', desc: t('doc.templates.conditionWaitLoop.desc'), returns: 'bool', example: '条件等待循环(lambda: ocr_exists_any("首页"), 超时秒数=30)' },
    { name: '安全点击文字', signature: '安全点击文字(文字)', desc: t('doc.templates.safeClickText.desc'), returns: 'bool', example: '安全点击文字("同意")' },
    { name: '批量点击文字', signature: '批量点击文字(*文字列表, 间隔=1)', desc: t('doc.templates.batchClickText.desc'), returns: t('doc.templates.batchClickText.returns'), example: '批量点击文字("同意", "下一步", "开始使用")' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="templates" title={t('doc.toc.templates')}><p className="text-xs text-text-secondary">{t('doc.templates.sectionDesc')}</p><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Flow Control ===== */
function FLOW_SECTION(q: string, t: (key: string, opts?: any) => string) {
  const cards = [
    { name: 'try_run', signature: 'try_run(func, *args, default=None)', desc: t('doc.flow.tryRun.desc'), returns: 'Any', example: 'result = try_run(ocr, default=[])' },
    { name: 'retry', signature: 'retry(func, max_times=3, interval=1)', desc: t('doc.flow.retry.desc'), returns: 'Any', example: 'result = retry(lambda: screen_ocr_x("目标"), max_times=5)' },
    { name: 'do', signature: 'do(times, interval, pre_interval, *func)', desc: t('doc.flow.do.desc'), example: 'do(3, 1, False, lambda: click(540, 960))' },
    { name: 'run_until_true', signature: 'run_until_true(func, max_times=10)', desc: t('doc.flow.runUntilTrue.desc'), returns: 'bool' },
    { name: 'loop_activity_handle', signature: 'loop_activity_handle(other=None)', desc: t('doc.flow.loopActivity.desc'), example: '# 注册页面处理器后循环执行\nloop_activity_handle()' },
    { name: 'register_activity_handler', signature: 'register_activity_handler(name, func)', desc: t('doc.flow.registerActivity.desc'), example: 'def handle_login():\n    input_text("user")\n    click(540, 1200)\nregister_activity_handler("LoginActivity", handle_login)' },
  ];
  const f = cards.filter(c => matchSearch(q, c.name, c.desc));
  if (q && f.length === 0) return null;
  return (<Section id="flow" title={t('doc.toc.flow')}><div className="space-y-2">{f.map(c => <ApiCard key={c.name} {...c} />)}</div></Section>);
}

/* ===== Floating Console ===== */
function CONSOLE_SECTION({ t }: { t: (key: string, opts?: any) => string }) {
  return (
    <Section id="console" title={t('doc.toc.console')}>
      <p className="text-xs text-text-secondary leading-relaxed">{t('doc.console.intro')}</p>
      <CodeBlock lang="python">{`${t('doc.code.printToConsole')}
print("${t('doc.code.currentStep')}")

${t('doc.code.toastShowSystem')}
toast("${t('doc.code.operationDone')}")

${t('doc.code.toastPrintBoth')}
toast_print("${t('doc.code.foundTargetClick')}")`}</CodeBlock>
    </Section>
  );
}

/* ===== Architecture ===== */
function ARCH_SECTION({ t }: { t: (key: string, opts?: any) => string }) {
  return (
    <Section id="arch" title={t('doc.toc.arch')}>
      <p className="text-xs text-text-secondary leading-relaxed">{t('doc.arch.intro')}</p>
      <div className="rounded-lg border border-divider bg-card-bg p-4 text-xs text-text-secondary leading-relaxed space-y-2">
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1">
            <h4 className="font-semibold text-text-primary">{t('doc.arch.coreComponents')}</h4>
            <ul className="list-disc list-inside space-y-0.5">
              <li>{t('doc.arch.cpython')}</li>
              <li>{t('doc.arch.paddleOcr')}</li>
              <li>{t('doc.arch.ncnnYolo')}</li>
              <li>{t('doc.arch.opencv')}</li>
              <li>{t('doc.arch.uiautomator')}</li>
            </ul>
          </div>
          <div className="space-y-1">
            <h4 className="font-semibold text-text-primary">{t('doc.arch.communication')}</h4>
            <ul className="list-disc list-inside space-y-0.5">
              <li>{t('doc.arch.commScriptEngine')}</li>
              <li>{t('doc.arch.commPcDevice')}</li>
              <li>{t('doc.arch.commEngineSystem')}</li>
            </ul>
          </div>
        </div>
      </div>
    </Section>
  );
}

/* ===== YOLO Model Deployment ===== */
function YOLO_DEPLOY_SECTION({ t }: { t: (key: string, opts?: any) => string }) {
  return (
    <Section id="yolo-deploy" title={t('doc.toc.yoloDeploy')}>
      <p className="text-xs text-text-secondary leading-relaxed">{t('doc.yoloDeploy.intro')}</p>
      <div className="space-y-3">
        <div className="space-y-1">
          <h3 className="text-sm font-semibold text-text-primary">{t('doc.yoloDeploy.step1')}</h3>
          <CodeBlock lang="bash">{`pip install ultralytics
yolo train model=yolov8n.pt data=dataset.yaml epochs=100 imgsz=640`}</CodeBlock>
        </div>
        <div className="space-y-1">
          <h3 className="text-sm font-semibold text-text-primary">{t('doc.yoloDeploy.step2')}</h3>
          <CodeBlock lang="bash">{`yolo export model=best.pt format=ncnn`}</CodeBlock>
        </div>
        <div className="space-y-1">
          <h3 className="text-sm font-semibold text-text-primary">{t('doc.yoloDeploy.step3')}</h3>
          <CodeBlock lang="bash">{`adb push best_ncnn_model /sdcard/yyds/models/`}</CodeBlock>
        </div>
        <div className="space-y-1">
          <h3 className="text-sm font-semibold text-text-primary">{t('doc.yoloDeploy.step4')}</h3>
          <CodeBlock lang="python">{`from yyds import *

${t('doc.code.loadCustomModel')}
model_yolo_reload("/sdcard/yyds/models/best_ncnn_model")

${t('doc.code.useModelDetect')}
results = screen_yolo_locate(conf=0.5)
for r in results:
    print(f"{r['label']}: ({r['x']}, {r['y']})")`}</CodeBlock>
        </div>
      </div>
    </Section>
  );
}

/* ========== Main Page Component ========== */
export default function Documentation() {
  const { t } = useTranslation(['ide', 'common']);
  const [search, setSearch] = useState('');
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [activeId, setActiveId] = useState('quick-start');
  const contentRef = useRef<HTMLDivElement>(null);

  const q = search.toLowerCase().trim();

  /* scroll-spy */
  useEffect(() => {
    const el = contentRef.current;
    if (!el) return;
    const handler = () => {
      const sections = el.querySelectorAll('section[id]');
      let current = 'quick-start';
      for (const sec of sections) {
        const rect = sec.getBoundingClientRect();
        if (rect.top <= 120) current = sec.id;
      }
      setActiveId(current);
    };
    el.addEventListener('scroll', handler, { passive: true });
    return () => el.removeEventListener('scroll', handler);
  }, []);

  const scrollTo = useCallback((id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    setMobileMenuOpen(false);
  }, []);

  const tocItems = TOC_IDS.map(id => ({ id, label: t(TOC_LABEL_KEYS[id]) }));
  const visibleToc = q
    ? tocItems.filter(item => item.label.toLowerCase().includes(q) || item.id.includes(q))
    : tocItems;

  return (
    <div className="flex h-full overflow-hidden bg-bg-primary">
      {/* mobile menu button */}
      <button
        className="fixed top-3 left-3 z-50 rounded-lg bg-card-bg p-2 shadow-lg lg:hidden"
        onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
      >
        {mobileMenuOpen ? <X size={18} /> : <Menu size={18} />}
      </button>

      {/* sidebar */}
      <aside className={`
        fixed inset-y-0 left-0 z-40 w-64 shrink-0 border-r border-divider bg-card-bg
        transform transition-transform duration-200 ease-in-out
        lg:relative lg:translate-x-0
        ${mobileMenuOpen ? 'translate-x-0' : '-translate-x-full'}
      `}>
        <div className="flex h-full flex-col">
          <div className="border-b border-divider px-4 py-4">
            <h1 className="text-base font-bold text-text-primary">{t('doc.pageTitle')}</h1>
            <p className="mt-0.5 text-[11px] text-text-hint">{t('doc.pageSubtitle')}</p>
          </div>
          <div className="px-3 pt-3">
            <div className="relative">
              <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-text-hint" />
              <input
                type="text"
                value={search}
                onChange={e => setSearch(e.target.value)}
                placeholder={t('doc.searchApi')}
                className="w-full rounded-md border border-divider bg-bg-primary py-1.5 pl-8 pr-3 text-xs text-text-primary placeholder:text-text-hint focus:border-brand focus:outline-none"
              />
            </div>
          </div>
          <nav className="flex-1 overflow-y-auto px-2 py-2">
            {visibleToc.map(item => (
              <button
                key={item.id}
                onClick={() => scrollTo(item.id)}
                className={`flex w-full items-center gap-2 rounded-md px-3 py-1.5 text-left text-xs transition-colors ${
                  activeId === item.id
                    ? 'bg-brand/10 font-semibold text-brand-light'
                    : 'text-text-secondary hover:bg-hover hover:text-text-primary'
                }`}
              >
                <ChevronRight size={10} className={activeId === item.id ? 'text-brand' : 'text-text-hint'} />
                {item.label}
              </button>
            ))}
          </nav>
        </div>
      </aside>

      {mobileMenuOpen && (
        <div className="fixed inset-0 z-30 bg-black/40 lg:hidden" onClick={() => setMobileMenuOpen(false)} />
      )}

      {/* main content */}
      <div ref={contentRef} className="flex-1 overflow-y-auto">
        <div className="mx-auto max-w-4xl space-y-8 px-4 py-6 sm:px-8 lg:pl-8">
          <QUICK_START_SECTION t={t} />
          <CHINESE_API_SECTION t={t} />
          {TOUCH_SECTION(q, t)}
          {KEYS_SECTION(q, t)}
          {OCR_SECTION(q, t)}
          {FIND_IMAGE_SECTION(q, t)}
          {FIND_COLOR_SECTION(q, t)}
          {YOLO_SECTION(q, t)}
          {UI_SECTION(q, t)}
          {WAIT_SECTION(q, t)}
          {SWIPE_FIND_SECTION(q, t)}
          {APP_SECTION(q, t)}
          {INPUT_SECTION(q, t)}
          {DEVICE_SECTION(q, t)}
          {SYSTEM_SECTION(q, t)}
          {SLEEP_SECTION(q, t)}
          {PERMISSION_SECTION(q, t)}
          {SCALE_SECTION(q, t)}
          {BACKUP_SECTION(q, t)}
          {TEMPLATES_SECTION(q, t)}
          {FLOW_SECTION(q, t)}
          <CONSOLE_SECTION t={t} />
          <ARCH_SECTION t={t} />
          <YOLO_DEPLOY_SECTION t={t} />

          {q && [TOUCH_SECTION, KEYS_SECTION, OCR_SECTION, FIND_IMAGE_SECTION, FIND_COLOR_SECTION, YOLO_SECTION, UI_SECTION, WAIT_SECTION, SWIPE_FIND_SECTION, APP_SECTION, INPUT_SECTION, DEVICE_SECTION, SYSTEM_SECTION, SLEEP_SECTION, PERMISSION_SECTION, SCALE_SECTION, BACKUP_SECTION, TEMPLATES_SECTION, FLOW_SECTION].every(fn => fn(q, t) === null) && (
            <div className="py-8 text-center text-sm text-text-hint">
              {t('doc.noResults', { search })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
