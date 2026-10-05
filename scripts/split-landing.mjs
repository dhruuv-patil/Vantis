#!/usr/bin/env node
/**
 * Splits components/landing/flowops-clean.tsx into separate components.
 * Pure move: JSX, hooks and CSS text are copied verbatim (no visual change).
 *
 * Usage (from project root):
 *   node scripts/split-landing.mjs [path/to/flowops-clean.tsx]
 *
 * The original is backed up to .landing-backup/ before anything is written.
 */
import fs from "node:fs";
import path from "node:path";

const input = process.argv[2] ?? "components/landing/flowops-clean.tsx";
const outDir = path.dirname(input);
const src = fs.readFileSync(input, "utf8");

const fail = (m) => {
  console.error("✖ " + m);
  process.exit(1);
};
const idx = (s, from = 0) => {
  const i = src.indexOf(s, from);
  if (i < 0) fail(`Anchor not found: ${s}`);
  return i;
};

if (src.includes("<LandingHeader")) fail("File looks already split. Aborting.");

/* ----------------------------- import registry ---------------------------- */
const importBlock = src.slice(0, idx("const tabs = ["));
const registry = new Map(); // name -> { module, kind: 'default' | 'named', external: true }
const importRe =
  /import\s+(?:(\w+)\s*,?\s*)?(?:\{([\s\S]*?)\})?\s*from\s*['"]([^'"]+)['"]/g;
for (const m of importBlock.matchAll(importRe)) {
  const [, def, named, mod] = m;
  if (def) registry.set(def, { module: mod, kind: "default", external: true });
  if (named)
    for (const n of named.split(",").map((s) => s.trim()).filter(Boolean))
      registry.set(n, { module: mod, kind: "named", external: true });
}

/* --------------------------------- chunks --------------------------------- */
const iData = idx("const tabs = [");
const iLogos = idx("function SlackLogo()");
const iPrims = idx("function Reveal(");
const iDemo = idx("function WorkflowBuilderDemo()");
const iPanels = idx("function BuildPanel()");
const iMain = idx("export default function FlowOpsClean()");

const exportify = (t) => t.replace(/^(function|const) /gm, "export $1 ");

const files = []; // { rel, body, client, resolve }
const add = (rel, body, opts = {}) =>
  files.push({ rel, body, client: true, resolve: true, ...opts });

add("data.ts", exportify(src.slice(iData, iLogos)), { client: false });
add("logos.tsx", exportify(src.slice(iLogos, iPrims)));
add("primitives.tsx", exportify(src.slice(iPrims, iDemo)));
add("workflow-builder-demo.tsx", exportify(src.slice(iDemo, iPanels)));
add("hero-panels.tsx", exportify(src.slice(iPanels, iMain)));

/* ------------------------------ JSX extraction ----------------------------- */
const section = (open) => {
  const s = idx(open);
  const e = idx("</section>", s) + "</section>".length;
  return src.slice(s, e);
};

const hs = idx('<div className="announcement">');
const mn = idx('{mobile && <div className="mobile-nav">');
const headerJsx = src.slice(hs, src.indexOf("\n", mn));

const ss = idx("<style jsx global>");
const styleJsx = src.slice(ss, idx("</style>", ss) + "</style>".length);

const fs0 = idx("<footer>");
const footerJsx = src.slice(fs0, idx("</footer>", fs0) + "</footer>".length);

const copilotHooks = src.slice(
  idx("  const copilotPrompt ="),
  idx("\n\n  const [tab, setTab]"),
);

const wrap = (name, jsx, hooks = "") =>
  `export function ${name}() {\n${hooks}${hooks ? "\n" : ""}  return (\n    ${jsx}\n  )\n}\n`;

/* stateful components (hooks copied from the original) */
add(
  "landing-header.tsx",
  wrap(
    "LandingHeader",
    `<>\n      ${headerJsx}\n    </>`,
    `  const [mobile, setMobile] = useState(false)
  const [scrolled, setScrolled] = useState(false)

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])
`,
  ),
);

add(
  "hero-section.tsx",
  wrap(
    "HeroSection",
    section('<section className="hero">'),
    `  const [tab, setTab] = useState(0)
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([])
  const [indicator, setIndicator] = useState({ left: 0, width: 0 })
  const ActivePanel = panels[tab]

  useEffect(() => {
    const measure = () => {
      const el = tabRefs.current[tab]
      if (el) setIndicator({ left: el.offsetLeft, width: el.offsetWidth })
    }
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [tab])
`,
  ),
);

add(
  "sections/copilot-section.tsx",
  wrap("CopilotSection", section('<section id="builder"'), copilotHooks + "\n"),
);

add(
  "sections/faq-section.tsx",
  wrap(
    "FaqSection",
    section('<section id="faq"'),
    "  const [faq, setFaq] = useState<number | null>(0)\n",
  ),
);

/* static sections (no state) */
const STATIC = [
  ["sections/monitor-section.tsx", "MonitorSection", '<section id="platform" className="section">'],
  ["sections/detect-section.tsx", "DetectSection", '<section className="wide-panel">'],
  ["sections/diagnose-section.tsx", "DiagnoseSection", '<section className="section reverse">'],
  ["sections/messy-middle-section.tsx", "MessyMiddleSection", '<section id="reliability-in-practice"'],
  ["sections/reliability-layer-section.tsx", "ReliabilityLayerSection", '<section id="reliability-layer"'],
  ["sections/incident-journey-section.tsx", "IncidentJourneySection", '<section id="incident-journey"'],
  ["sections/outcomes-section.tsx", "OutcomesSection", '<section id="outcomes"'],
  ["sections/integrations-section.tsx", "IntegrationsSection", '<section id="integrations"'],
  ["sections/workflow-builder-section.tsx", "WorkflowBuilderSection", '<section id="workflow-builder"'],
  ["sections/security-section.tsx", "SecuritySection", '<section id="security"'],
  ["sections/get-started-section.tsx", "GetStartedSection", '<section id="get-started"'],
];
const staticNames = [];
for (const [rel, name, open] of STATIC) {
  add(rel, wrap(name, section(open)));
  staticNames.push([rel, name]);
}

add("landing-footer.tsx", wrap("LandingFooter", footerJsx));
add(
  "landing-styles.tsx",
  wrap("LandingStyles", styleJsx.replace(/^/, "<>\n      ") + "\n    </>"),
  { resolve: false },
);

/* ------------------------- composition (same export) ----------------------- */
const order = [
  ["landing-header.tsx", "LandingHeader"],
  ["hero-section.tsx", "HeroSection"],
  ["sections/monitor-section.tsx", "MonitorSection"],
  ["sections/detect-section.tsx", "DetectSection"],
  ["sections/diagnose-section.tsx", "DiagnoseSection"],
  ["sections/messy-middle-section.tsx", "MessyMiddleSection"],
  ["sections/reliability-layer-section.tsx", "ReliabilityLayerSection"],
  ["sections/incident-journey-section.tsx", "IncidentJourneySection"],
  ["sections/outcomes-section.tsx", "OutcomesSection"],
  ["sections/integrations-section.tsx", "IntegrationsSection"],
  ["sections/workflow-builder-section.tsx", "WorkflowBuilderSection"],
  ["sections/copilot-section.tsx", "CopilotSection"],
  ["sections/security-section.tsx", "SecuritySection"],
  ["sections/faq-section.tsx", "FaqSection"],
  ["sections/get-started-section.tsx", "GetStartedSection"],
  ["landing-styles.tsx", "LandingStyles"],
  ["landing-footer.tsx", "LandingFooter"],
];
const compImports = order
  .map(([rel, n]) => `import { ${n} } from './${rel.replace(/\.tsx$/, "")}'`)
  .join("\n");
const compBody = `export default function FlowOpsClean() {
  return (
    <main>
${order.map(([, n]) => `      <${n} />`).join("\n")}
    </main>
  )
}
`;

/* -------------------------- import resolution + write ---------------------- */
// register internal exports so files can import from each other
for (const f of files) {
  if (f.rel === "landing-styles.tsx") continue;
  for (const m of f.body.matchAll(/^export (?:function|const) (\w+)/gm))
    registry.set(m[1], {
      module: "./" + f.rel.replace(/\.tsx?$/, ""),
      kind: "named",
      external: false,
      from: f.rel,
    });
}

const strip = (s) => s.replace(/`[^`]*`/g, "``").replace(/"[^"\n]*"/g, '""');

function importsFor(f) {
  if (!f.resolve) return "";
  const text = strip(f.body);
  const byMod = new Map();
  for (const [name, info] of registry) {
    if (info.from === f.rel) continue;
    if (!new RegExp(`\\b${name}\\b`).test(text)) continue;
    let mod = info.module;
    if (!info.external) {
      let rel = path
        .relative(path.dirname(f.rel), info.module.replace(/^\.\//, ""))
        .split(path.sep)
        .join("/");
      if (!rel.startsWith(".")) rel = "./" + rel;
      mod = rel;
    }
    if (!byMod.has(mod)) byMod.set(mod, { def: null, named: [], ext: info.external });
    const e = byMod.get(mod);
    if (info.kind === "default") e.def = name;
    else e.named.push(name);
  }
  const lines = [...byMod.entries()]
    .sort((a, b) => Number(b[1].ext) - Number(a[1].ext))
    .map(([mod, e]) => {
      const parts = [];
      if (e.def) parts.push(e.def);
      if (e.named.length) parts.push(`{ ${e.named.join(", ")} }`);
      return `import ${parts.join(", ")} from '${mod}'`;
    });
  return lines.join("\n");
}

// guard: static sections must not reference component state
const LEAK =
  /\b(mobile|scrolled|tab|faq|indicator|tabRefs|typedCopilotPrompt|ActivePanel|setTab|setFaq|setMobile)\b/;
for (const [rel] of staticNames) {
  const f = files.find((x) => x.rel === rel);
  if (LEAK.test(strip(f.body))) fail(`${rel} references component state; needs manual split.`);
}

// backup then write
const backupDir = path.join(path.dirname(outDir), ".landing-backup");
fs.mkdirSync(backupDir, { recursive: true });
fs.copyFileSync(input, path.join(backupDir, path.basename(input) + ".bak"));

for (const f of files) {
  const target = path.join(outDir, f.rel);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const header = f.client ? "'use client'\n\n" : "";
  const imp = importsFor(f);
  fs.writeFileSync(target, `${header}${imp ? imp + "\n\n" : ""}${f.body.trimEnd()}\n`);
  console.log("✔ " + path.relative(process.cwd(), target));
}

fs.writeFileSync(
  input,
  `'use client'\n\n${compImports}\n\n${compBody}`,
);
console.log(`✔ ${input} (composition, same default export)`);
console.log(`\nBackup: ${path.join(backupDir, path.basename(input) + ".bak")}`);
console.log("Next: npx prettier --write " + outDir + " && npx tsc --noEmit");
