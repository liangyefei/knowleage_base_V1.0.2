"""nlq_pages — 原文查看与页面 HTML（/doc_view、/graph、/docmap）。"""
import os
import time

from nlq_store import _kb_display_names
# ── 原文查看（/doc_view，图谱节点“查看原文”跳转目标）───────────────

KB_FILES_ROOT = os.environ.get("KB_FILES_ROOT", "/projects")
_DOC_MIME = {
    ".pdf": "application/pdf", ".png": "image/png", ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg", ".gif": "image/gif", ".svg": "image/svg+xml",
    ".html": "text/html; charset=utf-8", ".htm": "text/html; charset=utf-8",
}
_DOC_DOWNLOAD = {".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
                 ".zip", ".bin", ".img", ".7z", ".tar", ".gz"}
_doc_find_cache: dict = {}
_DOC_FIND_TTL = 600.0


def _doc_find(name: str, kb: str) -> str | None:
    """按文档名在知识库目录树定位原文件。

    两个候选树（覆盖两种上传布局）：/projects/<kbId>/ 与
    /projects/kb/<知识库名>/。结果带 TTL 缓存（NFS walk 秒级）。
    """
    key = (kb, name)
    now = time.time()
    hit = _doc_find_cache.get(key)
    if hit and now - hit[1] < _DOC_FIND_TTL:
        return hit[0]
    roots = []
    if kb:
        roots.append(os.path.join(KB_FILES_ROOT, kb))
        nm = _kb_display_names().get(kb)
        if nm:
            roots.append(os.path.join(KB_FILES_ROOT, "kb", nm))
    path = None
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames
                           if d not in (".git", ".codegraph", "node_modules")]
            if name in filenames:
                path = os.path.join(dirpath, name)
                break
        if path:
            break
    if len(_doc_find_cache) > 2000:
        _doc_find_cache.clear()
    _doc_find_cache[key] = (path, now)
    return path


def _esc(s: str) -> str:
    return (s.replace("&", "&amp;").replace("<", "&lt;")
             .replace(">", "&gt;").replace('"', "&quot;"))


def _doc_msg(msg: str) -> str:
    return (f'<!doctype html><meta charset="utf-8"><body style="font:14px/1.6 '
            f'system-ui;background:#f5f7fa;color:#1e293b;padding:32px">'
            f'<h3>{_esc(msg)}</h3><p><a href="javascript:history.back()">返回</a></p>')


def _doc_text_html(title: str, path: str, text: str, line: int) -> str:
    rows = []
    for i, ln in enumerate(text.split("\n"), 1):
        rows.append(
            f'<div class="r" id="L{i}"><span class="no">{i}</span>'
            f'<pre>{_esc(ln) or " "}</pre></div>')
    return f"""<!doctype html><html lang="zh"><head><meta charset="utf-8">
<title>{_esc(title)}</title>
<style>
  body {{ margin:0; background:#f5f7fa; color:#1e293b;
         font:13px/1.6 "PingFang SC","Microsoft YaHei",monospace,sans-serif; }}
  header {{ position:sticky; top:0; background:#fff; border-bottom:1px solid #e2e8f0;
           padding:10px 18px; font:13px/1.5 system-ui; }}
  header .p {{ color:#64748b; font-size:12px; word-break:break-all; }}
  .r {{ display:flex; gap:0; border-bottom:1px solid #f1f5f9; }}
  .r:hover {{ background:#eef4ff; }}
  .no {{ flex:0 0 56px; text-align:right; padding:2px 10px; color:#94a3b8;
        user-select:none; font-size:12px; border-right:1px solid #f1f5f9; }}
  pre {{ margin:0; padding:2px 12px; white-space:pre-wrap; word-break:break-all; }}
  .r:target {{ background:#fef08a; }}
</style></head><body>
<header><b>{_esc(title)}</b>
<div class="p">{_esc(path)}</div></header>
<div>{''.join(rows)}</div>
</body></html>"""


# /graph 页面（单文件 HTML：同源调 /graph_search、/graph_node、/graph_kg；
# ECharts CDN 三级回退 npmmirror→jsdelivr→unpkg）
_GRAPH_HTML = r"""<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>知识图谱 · dual-mcp</title>
<style>
  :root { --bg:#f5f7fa; --panel:#ffffff; --panel2:#ffffff; --line:#e2e8f0;
          --txt:#1e293b; --dim:#64748b; --acc:#2563eb; --green:#00a36c;
          --purple:#8b3dff; --red:#e5484d; }
  * { box-sizing:border-box; margin:0; padding:0; }
  body { background:var(--bg); color:var(--txt);
         font:14px/1.5 "PingFang SC","Microsoft YaHei",system-ui,sans-serif;
         height:100vh; display:flex; flex-direction:column; overflow:hidden; }
  header { display:flex; align-items:center; gap:10px; padding:10px 16px;
           background:var(--panel2); border-bottom:1px solid var(--line);
           flex-wrap:wrap; }
  header h1 { font-size:16px; color:var(--txt); margin-right:6px; white-space:nowrap; }
  header h1 b { color:var(--green); }
  select, input[type=text] { background:#fff; color:var(--txt);
    border:1px solid #cbd5e1; border-radius:6px; padding:7px 10px;
    font-size:13px; outline:none; }
  select { max-width:290px; }
  input[type=text] { width:300px; }
  #qwrap { position:relative; display:inline-block; }
  #sug { position:absolute; top:35px; left:0; width:360px; max-height:300px;
         overflow-y:auto; background:#fff; border:1px solid #cbd5e1;
         border-radius:8px; box-shadow:0 4px 16px rgba(15,23,42,.14);
         display:none; z-index:20; }
  .sug-item { display:flex; align-items:center; gap:8px; padding:7px 10px;
              cursor:pointer; font-size:13px; }
  .sug-item:hover, .sug-item.on { background:#eef4ff; }
  .sug-name { flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .sug-file { color:#94a3b8; font-size:11px; max-width:150px; overflow:hidden;
              text-overflow:ellipsis; white-space:nowrap; }
  .sug-src { color:#2563eb; font-size:10px; border:1px solid #bfdbfe; border-radius:4px;
             padding:0 4px; flex:none; }
  input[type=text]:focus, select:focus { border-color:var(--acc); }
  button { background:var(--acc); color:#fff; border:none; border-radius:6px;
    padding:7px 18px; font-size:13px; cursor:pointer; }
  button:hover { filter:brightness(1.08); }
  #modes { display:flex; }
  .mode { background:#fff; color:var(--dim); border:1px solid #cbd5e1;
          border-radius:6px; padding:7px 16px; font-size:13px; cursor:pointer; }
  .mode.on { background:var(--acc); color:#fff; border-color:var(--acc); }
  #hint { color:var(--dim); font-size:12px; flex:1; min-width:200px;
          text-align:right; }
  #cands { display:none; gap:6px; padding:6px 16px; flex-wrap:wrap;
           background:var(--panel); border-bottom:1px solid var(--line); }
  #cands .tag { cursor:pointer; user-select:none; }
  main { flex:1; display:flex; min-height:0; }
  #chart { flex:1; min-width:0; }
  aside { width:380px; background:var(--panel); border-left:1px solid var(--line);
          overflow-y:auto; padding:16px; }
  .badge { display:inline-block; padding:2px 10px; border-radius:10px;
           font-size:12px; color:#0b1626; font-weight:600; }
  .row { margin:8px 0; font-size:13px; }
  .row .k { color:var(--dim); display:inline-block; width:64px; }
  .row .v { word-break:break-all; color:var(--txt); }
  #pname { font-size:17px; color:var(--txt); word-break:break-all; margin:6px 0 2px; }
  #pqn { color:var(--dim); font-size:11px; word-break:break-all; }
  .chips { display:flex; flex-wrap:wrap; gap:6px; margin:6px 0; }
  .chip { background:#f1f5f9; border:1px solid var(--line); border-radius:5px;
          padding:2px 8px; font-size:12px; color:var(--txt); }
  pre#psrc { background:#f8fafc; border:1px solid var(--line); border-radius:6px;
             padding:10px; font-size:12px; overflow:auto; max-height:340px;
             color:#334155; white-space:pre; }
  details { margin-top:10px; }
  summary { cursor:pointer; color:var(--dim); font-size:12px; }
  #pprops { background:#f1f5f9; border-radius:6px; padding:8px; font-size:12px;
            overflow:auto; max-height:180px; color:#475569; white-space:pre;
            margin-top:6px; }
  .empty { color:var(--dim); padding:30px 10px; text-align:center; }
  a.mini { display:inline-block; background:var(--acc); color:#fff; border-radius:5px;
           padding:3px 12px; font-size:12px; text-decoration:none; }
  a.mini:hover { filter:brightness(1.1); }
  #toast { position:fixed; left:50%; top:64px; transform:translateX(-50%);
           background:var(--red); color:#fff; padding:8px 18px; border-radius:6px;
           display:none; z-index:9; font-size:13px; max-width:80%; }
</style>
</head>
<body>
<header>
  <h1>知识图谱 <b>·</b> dual-mcp</h1>
  <select id="proj" title="知识库（kbId）· 代码图谱与文档图谱联动共用"></select>
  <div id="modes">
    <button class="mode on" id="m-code" onclick="setMode('code')">代码图谱</button>
    <button class="mode" id="m-kg" onclick="setMode('kg')" title="mempalace 文档知识图谱">文档图谱</button>
  </div>
  <div id="qwrap">
    <input id="q" type="text" placeholder="输入实体/文档名，回车搜索（自动查代码图谱 + 文档知识图谱）">
    <div id="sug"></div>
  </div>
  <button onclick="doSearch()">搜索</button>
  <span id="hint">点击节点看属性 · 双击节点展开邻居</span>
</header>
<div id="cands"></div>
<main>
  <div id="chart"></div>
  <aside id="panel"><div class="empty">搜索一个实体开始探索<br><br>代码图谱：osal_dump_stack、SKILL.md、osal_device<br>文档图谱：输入文档名自动查询（mempalace KG）</div></aside>
</main>
<div id="toast"></div>
<script>
const LABEL_COLORS = {Function:'#4f9cf9', Method:'#38bdf8', Class:'#a78bfa',
  Variable:'#fbbf24', Document:'#fb923c', File:'#94a3b8', Module:'#34d399',
  Interface:'#f472b6', Struct:'#2dd4bf', Enum:'#c084fc', Test:'#f97316'};
const DEFCOLOR = '#cbd5e1';
const LABEL_CN = {Function:'函数', Method:'方法', Class:'类', Struct:'结构体',
  Enum:'枚举', Interface:'接口', Variable:'变量', Constant:'常量', Macro:'宏',
  Field:'字段', Module:'模块', File:'文件', Document:'文档', Section:'章节',
  Test:'测试', Project:'项目', Folder:'目录', Package:'包', Parameter:'参数',
  Resource:'资源', Route:'路由', HTTPRoute:'HTTP路由'};
function labelCn(l) { return LABEL_CN[l] || l; }
let chart, nodesMap = new Map(), edgesMap = new Map(), nodeRaw = new Map();
let kgRaw = new Map(), kgEdges = new Map(), kgCount = 0, kgDown = null,
    kgCenter = null;
let mode = 'code';
const kbLabel = {};   // kbId → 显示名（/projects 返回 kb_name 时生效）
// KG 实体类型配色（与后端 _KG_KIND_RULES 的类型名对应）。
// 新增类型：后端规则表加一行 + 此处加同名配色即可，图例/面板自动生效。
const KG_COLORS = {'芯片':'#f87171', '传感器':'#facc15', '外设接口':'#fb923c',
  '模块软件':'#a78bfa', '文档':'#34d399', '知识库':'#2dd4bf', '实体':'#cbd5e1'};

function setMode(m) {
  mode = m;
  document.getElementById('m-code').classList.toggle('on', m === 'code');
  document.getElementById('m-kg').classList.toggle('on', m === 'kg');
  render();
  // 联动：切到文档图谱且尚未加载数据 → 用当前选中知识库（kbId）自动查询其 KG 子图
  if (m === 'kg' && kgRaw.size === 0 && proj()) {
    runSearchKG(proj()).then(() => {
      if (kgCenter && kgRaw.has(kgCenter)) showPanelKG(kgCenter);
    });
  }
  if ((m === 'code' ? nodesMap.size : kgRaw.size) === 0)
    document.getElementById('panel').innerHTML =
      '<div class="empty">' + (m === 'code'
        ? '搜索一个实体开始探索'
        : '文档知识图谱（mempalace KG）<br>输入文档名自动查询；实体名需精确匹配') + '</div>';
}

async function onProjChange() {
  // 联动：切换知识库后，当前视图按新库重新加载
  nodesMap.clear(); edgesMap.clear(); nodeRaw.clear();
  kgRaw.clear(); kgEdges.clear(); kgCount = 0; kgDown = null; kgCenter = null;
  document.getElementById('cands').style.display = 'none';
  const kb = proj();
  if (mode === 'kg') {
    await runSearchKG(kb);
    if (kgCenter && kgRaw.has(kgCenter)) showPanelKG(kgCenter);
  } else {
    render();
    document.getElementById('hint').textContent =
      '知识库 ' + (kbLabel[kb] || kb) + ' · 输入关键字搜索代码图谱';
  }
}

function toast(msg, ms) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.style.display = 'block';
  setTimeout(() => t.style.display = 'none', ms || 4000);
}
function esc(s) { return String(s == null ? '' : s)
  .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

async function post(path, body) {
  const r = await fetch(location.origin + path, {method:'POST',
    headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
  if (!r.ok) throw new Error('HTTP ' + r.status + ' ' + (await r.text()).slice(0,160));
  return r.json();
}

function loadScript(i, srcs, ok) {
  if (i >= srcs.length) { toast('ECharts 加载失败（需外网 CDN）'); return; }
  const s = document.createElement('script'); s.src = srcs[i];
  s.onload = ok; s.onerror = () => loadScript(i+1, srcs, ok);
  document.head.appendChild(s);
}
const CDNS = [
  'https://registry.npmmirror.com/echarts/5.5.1/files/dist/echarts.min.js',
  'https://cdn.jsdelivr.net/npm/echarts@5.5.1/dist/echarts.min.js',
  'https://unpkg.com/echarts@5.5.1/dist/echarts.min.js'];
loadScript(0, CDNS, boot);

async function boot() {
  chart = echarts.init(document.getElementById('chart'), null,
                       {renderer:'canvas'});
  window.addEventListener('resize', () => chart.resize());
  chart.on('click', p => { if (p.dataType !== 'node') return;
    mode === 'kg' ? showPanelKG(p.data.nid) : showPanel(p.data.nid); });
  chart.on('dblclick', p => { if (p.dataType !== 'node') return;
    mode === 'kg' ? expandKG(p.data.nid) : expand(p.data.nid); });
  try {
    const d = await (await fetch(location.origin + '/projects')).json();
    const sel = document.getElementById('proj');
    const list = d.projects || [];
    list.forEach(p => {
      const kb = String(p.name).replace(/^projects-/, '');
      const disp = p.kb_name || kb;
      kbLabel[kb] = disp;
      const o = document.createElement('option');
      o.value = kb; o.textContent = disp + ' (' + (p.nodes||0) + ')';
      o.title = kb;   // 完整 kbId 悬浮可见
      sel.appendChild(o);
    });
    const def = list.find(p => /kbf1dc0/.test(p.name)) || list[0];
    if (def) sel.value = String(def.name).replace(/^projects-/, '');
    sel.onchange = onProjChange;
  } catch (e) { toast('知识库列表加载失败：' + e.message); }
  updateKgBadge();
  const q = new URLSearchParams(location.search).get('q');
  if (q) { document.getElementById('q').value = q; doSearch(); }
}

function proj() { return document.getElementById('proj').value; }

async function doSearch() {
  const q = document.getElementById('q').value.trim();
  if (!q) return;
  nodesMap.clear(); edgesMap.clear(); nodeRaw.clear();
  kgRaw.clear(); kgEdges.clear(); kgCount = 0; kgDown = null; kgCenter = null;
  updateKgBadge();
  const codeP = runSearch(q, true);   // 代码图谱（CBM）
  const kgP = runSearchKG(q);         // 文档知识图谱（mempalace KG）自动并行查询
  const d = await codeP;
  await kgP;
  // 输入是文档（带扩展名 / 代码图谱中心是 Document）但 KG 未命中且名字不全
  // → 用代码图谱解析出的文档全名再查一次 KG（KG 实体名需精确匹配）
  const docExt = /\.(md|pdf|docx?|xlsx?|pptx?|txt|html?|csv)$/i;
  const docCenter = d && d.center
    && (d.center.label === 'Document' || docExt.test(d.center.name))
    ? d.center : null;
  if (docCenter && kgCount === 0 && docCenter.name && docCenter.name !== q)
    await runSearchKG(docCenter.name);
  // 仅明确文档意图才自动切文档图谱（输入带文档扩展名 / 中心是文档节点）；
  // 代码符号类查询（如 main）永远留在代码图谱，KG 命中数在按钮上供手动切换
  const docLike = docExt.test(q) || !!docCenter;
  setMode(docLike && kgCount > 0 ? 'kg' : 'code');
  if (mode === 'code' && d && d.center) showPanel(d.center.id);
  else if (mode === 'kg' && kgCenter && kgRaw.has(kgCenter)) showPanelKG(kgCenter);
}

async function runSearch(q, fresh) {
  document.getElementById('hint').textContent = '查询中…';
  let d;
  try { d = await post('/graph_search', {q:q, project:proj(), limit:120}); }
  catch (e) { toast('查询失败：' + e.message); return; }
  document.getElementById('hint').textContent =
    '耗时 ' + d.elapsed_ms + 'ms · ' + (d.nodes ? d.nodes.length : 0) + ' 节点 / '
    + (d.edges ? d.edges.length : 0) + ' 边';
  if (d.hint) toast(d.hint, 6000);
  merge(d);
  renderCands(d.candidates || [], fresh);
  if (d.center && mode === 'code') showPanel(d.center.id);
}

async function runSearchKG(q) {
  let d;
  try { d = await post('/graph_kg', {q:q, palace: proj() || null}); }
  catch (e) { kgDown = '文档图谱查询失败：' + e.message; updateKgBadge(); return; }
  if (d.error) { kgDown = d.error; updateKgBadge(); return; }
  kgDown = null;
  kgCount = (d.edges || []).length;
  if (kgCount) kgCenter = q;
  mergeKG(d);
  updateKgBadge();
  document.getElementById('hint').textContent =
    '文档图谱 · ' + kgCount + ' 边' + (kgCount ? '（' + kgRaw.size + ' 节点）' : '')
    + ' · ' + d.elapsed_ms + 'ms';
}

function mergeKG(d) {
  (d.nodes || []).forEach(n => {
    const ex = kgRaw.get(n.id);
    if (!ex) kgRaw.set(n.id, n);
    else if (n.matched) ex.matched = true;   // 已存在节点补中心高亮
  });
  (d.edges || []).forEach(e => {
    const k = e.source + '|' + e.target + '|' + e.type;
    if (!kgEdges.has(k)) kgEdges.set(k, e);
  });
  if (mode === 'kg') render();
}

function kgJump(name) {   // 面板里点来源文档/知识库 → 以其为中心查询 KG
  runSearchKG(name).then(() => {
    if (kgCenter && kgRaw.has(kgCenter)) showPanelKG(kgCenter);
  });
}

function updateKgBadge() {
  const b = document.getElementById('m-kg');
  b.textContent = kgDown ? '文档图谱 !'
    : (kgCount ? '文档图谱 · ' + kgCount + ' 边' : '文档图谱');
  b.title = kgDown || 'mempalace 文档知识图谱（文档→实体关系）';
}

function merge(d) {
  (d.nodes || []).forEach(n => {
    if (!nodesMap.has(n.id)) { nodesMap.set(n.id, n); nodeRaw.set(n.id, n); }
  });
  (d.edges || []).forEach(e => {
    const k = e.source + '|' + e.target + '|' + e.type;
    if (!edgesMap.has(k)) edgesMap.set(k, e);
  });
  render();
}

function renderCands(cands, fresh) {
  const bar = document.getElementById('cands');
  if (!cands.length) { bar.style.display = 'none'; return; }
  bar.style.display = 'flex';
  bar.innerHTML = '其它匹配：' + cands.map((c, i) =>
    '<span class="chip tag" onclick="runSearch('
    + JSON.stringify(String(c.name)).replace(/"/g,'&quot;') + ',true)">'
    + esc(c.name) + ' <span style="color:#64748b">[' + labelCn(c.label) + ']</span></span>'
  ).join('');
}

function render() { mode === 'kg' ? renderKG() : renderCode(); }

function renderCode() {
  const labels = new Set(); nodesMap.forEach(n => labels.add(n.label || ''));
  const cats = [...labels].sort().map(l => ({name: labelCn(l) || '?', itemStyle: {color: LABEL_COLORS[l] || DEFCOLOR}}));
  const catIdx = {}; cats.forEach((c, i) => catIdx[c.name] = i);
  const ns = []; nodesMap.forEach(n => ns.push({
    id: String(n.id), nid: n.id, name: n.name || ('#' + n.id),
    category: catIdx[labelCn(n.label || '?')],
    symbolSize: n.matched ? 46 : Math.min(14 + (n.deg_local || 0) * 3, 30),
    itemStyle: n.matched ? {borderColor:'#fff', borderWidth:3} : {},
    label: {show: !!n.matched || (n.deg_local || 0) >= 4, fontSize: 11,
            color:'#334155', position:'right'}
  }));
  const es = []; edgesMap.forEach(e => es.push({
    source: String(e.source), target: String(e.target),
    lineStyle: {color:'#94a3b8', width:1.2, curveness:0.12,
                type: e.type === 'DOCUMENTS_CODE' ? 'dashed' : 'solid'},
    etype: e.type, ref_name: e.ref_name, ref_line: e.line
  }));
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor:'#fff', borderColor:'#e2e8f0',
      textStyle:{color:'#1e293b'},
      formatter: p => {
        if (p.dataType === 'edge')
          return esc(p.data.etype) + (p.data.ref_name
            ? '<br>引用写法: ' + esc(p.data.ref_name)
              + (p.data.ref_line ? ' @行' + p.data.ref_line : '') : '');
        const n = nodeRaw.get(p.data.nid) || {};
        return '<b>' + esc(n.name) + '</b> [' + labelCn(n.label) + ']'
          + (n.file_path ? '<br>' + esc(n.file_path)
             + (n.start_line ? ':' + n.start_line : '') : '');
      }
    },
    legend: [{data: cats.map(c => c.name), textStyle:{color:'#64748b', fontSize:11},
              top:8, type:'scroll'}],
    series: [{type:'graph', layout:'force', roam:true, draggable:true,
      categories: cats, data: ns, edges: es,
      force: {repulsion: 260, edgeLength: [60, 140], gravity: 0.08,
              layoutAnimation: false},
      edgeSymbol: ['none','arrow'], edgeSymbolSize: 7,
      emphasis: {focus:'adjacency', lineStyle:{width:2.5, color:'#475569'},
                 label:{show:true}}
    }]
  }, true);
}

function renderKG() {
  const deg = new Map();
  kgEdges.forEach(e => {
    deg.set(e.source, (deg.get(e.source) || 0) + 1);
    deg.set(e.target, (deg.get(e.target) || 0) + 1);
  });
  const catOrder = Object.keys(KG_COLORS);
  const present = new Set(); kgRaw.forEach(n => present.add(n.type || '实体'));
  const cats = catOrder.filter(t => present.has(t))
    .map(n => ({name:n, itemStyle:{color:KG_COLORS[n] || '#cbd5e1'}}));
  const catIdx = {}; cats.forEach((c, i) => catIdx[c.name] = i);
  const ns = []; kgRaw.forEach(n => ns.push({
    id: String(n.id), nid: n.id, name: n.name,
    category: catIdx[n.type] != null ? catIdx[n.type] : 1,
    symbolSize: n.matched ? 46 : Math.min(14 + (deg.get(n.id) || 0) * 3, 30),
    itemStyle: n.matched ? {borderColor:'#fff', borderWidth:3} : {},
    label: {show: !!n.matched || (deg.get(n.id) || 0) >= 3, fontSize: 11,
            color:'#334155', position:'right'}
  }));
  const es = []; kgEdges.forEach(e => es.push({
    source: String(e.source), target: String(e.target),
    lineStyle: {color:'#94a3b8', width:1.2, curveness:0.12,
                type: e.type === 'has_content' ? 'dotted' : 'solid'},
    etype: e.type, conf: e.confidence, vf: e.valid_from, vt: e.valid_to
  }));
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor:'#fff', borderColor:'#e2e8f0',
      textStyle:{color:'#1e293b'},
      formatter: p => {
        if (p.dataType === 'edge')
          return esc(p.data.etype)
            + (p.data.conf != null ? ' · 置信 ' + p.data.conf : '')
            + (p.data.vf ? '<br>生效 ' + esc(p.data.vf) : '')
            + (p.data.vt ? '<br>失效 ' + esc(p.data.vt) : '');
        const n = kgRaw.get(p.data.nid) || {};
        return '<b>' + esc(n.name) + '</b> [' + esc(n.type) + ']'
          + (n.full_len > 80 ? '<br>全名 ' + n.full_len + ' 字符' : '');
      }
    },
    legend: [{data: cats.map(c => c.name), textStyle:{color:'#64748b', fontSize:11},
              top:8, type:'scroll'}],
    series: [{type:'graph', layout:'force', roam:true, draggable:true,
      categories: cats, data: ns, edges: es,
      force: {repulsion: 260, edgeLength: [60, 140], gravity: 0.08,
              layoutAnimation: false},
      edgeSymbol: ['none','arrow'], edgeSymbolSize: 7,
      emphasis: {focus:'adjacency', lineStyle:{width:2.5, color:'#475569'},
                 label:{show:true}}
    }]
  }, true);
}

async function expandKG(nid) {
  const n = kgRaw.get(nid); if (!n) return;
  if ((n.full_len || n.name.length) > 200) { toast('长文本节点不展开'); return; }
  document.getElementById('hint').textContent = '展开 ' + n.name + ' 的 KG 邻居…';
  try {
    const d = await post('/graph_kg', {q:nid, palace: proj() || null});
    if (d.error) throw new Error(d.error);
    mergeKG(d);
    kgCount = kgEdges.size;
    updateKgBadge();
  } catch (e) { toast('展开失败：' + e.message); }
}

async function kgFragment(btn, subject, predicate, object) {
  const box = btn.nextElementSibling;
  if (box && box.style.display !== 'none') {
    box.style.display = 'none'; btn.textContent = '片段'; return;
  }
  btn.textContent = '加载中…';
  try {
    const d = await post('/graph_fragment',
      {subject: subject, predicate: predicate, object: object,
       palace: proj() || null});
    if (d.error) throw new Error(d.error);
    const kb = proj() || '';
    const rows = (d.fragments || []).map(f => {
      const t = f.text || '';
      return '<div style="margin:6px 0;padding:8px;background:#f8fafc;border-radius:6px;font-size:12px;line-height:1.6">'
        + '<div style="white-space:pre-wrap;color:#334155">' + esc(t) + '</div>'
        + '<div style="margin-top:4px;color:#64748b">来源：' + esc(f.source_file || f.room || '')
        + (f.chunk_index != null && f.chunk_index !== '' ? ' · 第 ' + esc(String(f.chunk_index)) + ' 块' : '')
        + (f.room ? ' · <a class="mini" target="_blank" href="' + location.origin
          + '/doc_view?doc=' + encodeURIComponent(f.room)
          + '&kb=' + encodeURIComponent(kb) + '">查看原文 ↗</a>' : '')
        + '</div></div>';
    }).join('');
    box.innerHTML = rows
      || '<div style="color:#64748b;font-size:12px;margin:6px 0">未找到对应片段（该关系可能来自未入库文档）</div>';
    box.style.display = 'block';
    btn.textContent = '收起';
  } catch (e) {
    box.innerHTML = '<div style="color:#fb2c36;font-size:12px;margin:6px 0">片段加载失败：'
      + esc(e.message) + '</div>';
    box.style.display = 'block';
    btn.textContent = '重试';
  }
}

async function showPanelKG(nid) {
  const base = kgRaw.get(nid); if (!base) return;
  const badge = '<span class="badge" style="background:'
    + (KG_COLORS[base.type] || '#cbd5e1') + '">' + esc(base.type) + '</span>';
  document.getElementById('panel').innerHTML = badge
    + '<div id="pname">' + esc(base.name) + '</div>'
    + '<div id="pqn">mempalace 文档知识图谱（全局单图）</div>'
    + '<div class="row"><span class="k">全名长</span><span class="v">'
    + (base.full_len || base.name.length) + ' 字符</span></div>'
    + '<div id="pmore" class="row"><span class="k">关系</span>'
    + '<span class="v" style="color:#64748b">加载中…</span></div>';
  if ((base.full_len || base.name.length) > 200) {
    document.getElementById('pmore').outerHTML =
      '<div class="row"><span class="k">关系</span><span class="v" style="color:#64748b">'
      + '长文本节点（如 has_content 全文值），不查询关系</span></div>';
    return;
  }
  try {
    const d = await post('/graph_kg', {q:nid, palace: proj() || null});
    if (d.error) throw new Error(d.error);
    const cut = s => { s = String(s); return s.length > 60 ? s.slice(0, 60) + '…' : s; };
    // 来源追溯：入边里 文档（带扩展名）→ 来源文档；kbId → 所属知识库
    const docRe = /\.(md|pdf|docx?|xlsx?|pptx?|txt|html?|csv)$/i;
    const kbRe = /^kb[0-9a-f]{20,}$/i;
    const docs = [], kbs = [];
    (d.edges || []).forEach(e => {
      if (e.target !== nid) return;
      if (docRe.test(e.source)) docs.push(e.source);
      else if (kbRe.test(e.source)) kbs.push(e.source);
    });
    const uniq = a => [...new Set(a)];
    const chipDoc = n => '<span class="chip tag" onclick="kgJump('
      + JSON.stringify(String(n)).replace(/"/g, '&quot;') + ')" title="点击查看该文档">'
      + esc(cut(n)) + '</span>';
    let src = '';
    if (base.type === '文档') {
      const ce = (d.edges || []).find(e => e.type === 'contains_doc'
                                          && e.target === nid);
      const kb0 = ce ? ce.source : proj();
      src += '<div class="row"><span class="k">原文</span><span class="v">'
        + '<a class="mini" target="_blank" href="' + location.origin
        + '/doc_view?doc=' + encodeURIComponent(nid)
        + '&kb=' + encodeURIComponent(kb0) + '">查看原文 ↗</a>'
        + (ce ? '' : ' <span style="color:#64748b;font-size:12px">'
          + '（图谱未标注所属库，按当前选中库查找）</span>')
        + '</span></div>';
    }
    if (kbs.length) src += '<div class="row"><span class="k">知识库</span>'
      + '<div class="chips">' + uniq(kbs).map(chipDoc).join('') + '</div></div>';
    if (docs.length) src += '<div class="row"><span class="k">来源文档</span>'
      + '<div class="chips">' + uniq(docs).slice(0, 20).map(chipDoc).join('')
      + (uniq(docs).length > 20 ? '<span class="chip">等 '
        + uniq(docs).length + ' 个</span>' : '') + '</div></div>';
    else src += '<div class="row"><span class="k">来源文档</span>'
      + '<span class="v" style="color:#64748b">无直接来源（实体可能经其它实体间接关联）'
      + '</span></div>';
    const rows = (d.edges || []).slice(0, 50).map(e =>
      '<div class="chip" style="display:block;margin:4px 0;white-space:normal">'
      + esc(cut(e.source)) + ' <b style="color:#4f9cf9">—' + esc(e.type) + '→</b> '
      + esc(cut(e.target))
      + (e.confidence != null ? ' <span style="color:#64748b">· conf '
        + e.confidence + '</span>' : '')
      + ' <button class="mini" style="margin-left:6px" onclick="kgFragment(this,'
      + JSON.stringify(e.source).replace(/"/g, '&quot;') + ','
      + JSON.stringify(e.type).replace(/"/g, '&quot;') + ','
      + JSON.stringify(e.target).replace(/"/g, '&quot;') + ')">片段</button>'
      + '<div class="frag" style="display:none"></div></div>').join('');
    document.getElementById('pmore').outerHTML = src
      + '<div class="row"><span class="k">关系</span><span class="v" style="color:#64748b">'
      + (d.edges || []).length + ' 条（双向合计）</span></div>'
      + '<div class="chips">' + (rows || '<span class="chip">无关系边</span>')
      + '</div>'
      + '<div class="row" style="color:#64748b;font-size:12px">'
      + '双击图中节点可展开其 KG 邻居 · 点击来源文档可跳转</div>';
  } catch (e) {
    document.getElementById('pmore').innerHTML =
      '<span class="v" style="color:#fb2c36">关系加载失败：' + esc(e.message) + '</span>';
  }
}

async function expand(nid) {
  const n = nodeRaw.get(nid); if (!n) return;
  document.getElementById('hint').textContent = '展开 ' + n.name + ' 的邻居…';
  try { const d = await post('/graph_search',
        {q:n.name, project:proj(), limit:80, candidates:0}); merge(d); }
  catch (e) { toast('展开失败：' + e.message); }
}

async function showPanel(nid) {
  const base = nodeRaw.get(nid); if (!base) return;
  const badge = '<span class="badge" style="background:'
    + (LABEL_COLORS[base.label] || DEFCOLOR) + '">' + labelCn(base.label) + '</span>';
  let html = badge + '<div id="pname">' + esc(base.name) + '</div>'
    + '<div id="pqn">' + esc(base.qualified_name || '') + '</div>'
    + '<div class="row"><span class="k">文件</span><span class="v">'
    + (base.file_path ? esc(base.file_path) : '—') + '</span></div>'
    + '<div class="row"><span class="k">行号</span><span class="v">'
    + (base.start_line ? base.start_line + ' – ' + (base.end_line||'?') : '—')
    + '</span></div>'
    + (base.file_path ? '<div class="row"><span class="k">原文</span><span class="v">'
      + '<a class="mini" target="_blank" href="' + location.origin + '/doc_view?project='
      + encodeURIComponent(proj()) + '&file=' + encodeURIComponent(base.file_path)
      + (base.start_line ? '&line=' + base.start_line : '')
      + (base.start_line ? '#L' + base.start_line : '')
      + '">查看原文 ↗</a></span></div>' : '')
    + '<div id="pmore" class="row"><span class="k">详情</span>'
    + '<span class="v" style="color:#64748b">加载中…</span></div>';
  document.getElementById('panel').innerHTML = html;
  try {
    const d = await post('/graph_node',
      {name:base.name, id:base.id, project:proj(), with_source:true});
    const n = d.node || {};
    const chips = [];
    Object.entries(n.out_types || {}).forEach(([t, c]) =>
      chips.push('<span class="chip">' + esc(t) + ' →' + c + '</span>'));
    Object.entries(n.in_types || {}).forEach(([t, c]) =>
      chips.push('<span class="chip">←' + c + ' ' + esc(t) + '</span>'));
    let more = '<div class="row"><span class="k">度数</span><span class="v">入 '
      + (n.in_degree||0) + ' · 出 ' + (n.out_degree||0)
      + '　本地邻接 ' + (base.deg_local||0) + '</span></div>'
      + '<div class="row"><span class="k">邻边类型</span><div class="chips">'
      + (chips.length ? chips.join('') : '<span class="chip">无</span>')
      + '</div></div>';
    if (n.file_version) more += '<div class="row"><span class="k">版本</span>'
      + '<span class="v">' + esc(n.file_version) + '</span></div>';
    if (n.source) more += '<div class="row"><span class="k">源码</span></div>'
      + '<pre id="psrc">' + esc(n.source) + '</pre>';
    if (n.props && Object.keys(n.props).length) more +=
      '<details><summary>节点属性 JSON</summary><div id="pprops">'
      + esc(JSON.stringify(n.props, null, 2)) + '</div></details>';
    more += '<div class="row" style="color:#64748b;font-size:12px">'
      + '双击图中节点可展开其邻居</div>';
    document.getElementById('pmore').outerHTML = more;
  } catch (e) {
    document.getElementById('pmore').innerHTML =
      '<span class="v" style="color:#fb2c36">详情加载失败：' + esc(e.message)
      + '</span>';
  }
}
// ── 搜索框联想（/graph_suggest，防抖 250ms，点击/键盘选择）──
const sugBox = document.getElementById('sug');
let sugTimer = null, sugItems = [], sugIdx = -1;
function closeSug() { sugBox.style.display = 'none'; sugBox.innerHTML = '';
  sugItems = []; sugIdx = -1; }
function renderSug() {
  if (!sugItems.length) { closeSug(); return; }
  sugBox.innerHTML = sugItems.map((s, i) => {
    const isKg = s.source === 'kg';
    const bc = isKg ? (KG_COLORS[s.label] || DEFCOLOR)
                    : (LABEL_COLORS[s.label] || DEFCOLOR);
    return '<div class="sug-item' + (i === sugIdx ? ' on' : '') + '" data-n="'
      + esc(s.name) + '" data-src="' + (s.source || 'code') + '">'
      + '<span class="badge" style="background:' + bc + '">' + labelCn(s.label) + '</span>'
      + (isKg ? '<span class="sug-src">文档图谱</span>' : '')
      + '<span class="sug-name">' + esc(s.name) + '</span>'
      + '<span class="sug-file">' + esc(s.file) + '</span></div>';
  }).join('');
  sugBox.style.display = 'block';
  [...sugBox.children].forEach(el => {
    el.onmousedown = ev => {
      ev.preventDefault();
      pickSug(el.dataset.n, el.dataset.src);
    };
  });
}
function pickSug(name, src) {
  document.getElementById('q').value = name;
  closeSug();
  if (src === 'kg') {
    kgRaw.clear(); kgEdges.clear(); kgCount = 0; kgDown = null; kgCenter = null;
    setMode('kg');
    runSearchKG(name).then(() => {
      if (kgCenter && kgRaw.has(kgCenter)) showPanelKG(kgCenter);
    });
  } else {
    doSearch();
  }
}
async function loadSug(q) {
  let d;
  try { d = await post('/graph_suggest', {q: q, project: proj(), limit: 10}); }
  catch (e) { closeSug(); return; }
  sugItems = d.suggestions || [];
  sugIdx = -1;
  renderSug();
}
document.getElementById('q').addEventListener('input', () => {
  clearTimeout(sugTimer);
  const q = document.getElementById('q').value.trim();
  if (!q) { closeSug(); return; }
  sugTimer = setTimeout(() => loadSug(q), 250);
});
document.getElementById('q').addEventListener('keydown', e => {
  if (sugBox.style.display !== 'block') { if (e.key === 'Enter') doSearch(); return; }
  if (e.key === 'ArrowDown') { e.preventDefault();
    sugIdx = Math.min(sugIdx + 1, sugItems.length - 1); renderSug(); }
  else if (e.key === 'ArrowUp') { e.preventDefault();
    sugIdx = Math.max(sugIdx - 1, 0); renderSug(); }
  else if (e.key === 'Enter') { e.preventDefault();
    const s = sugIdx >= 0 ? sugItems[sugIdx] : sugItems[0];
    pickSug(s.name, s.source); }
  else if (e.key === 'Escape') closeSug();
});
document.getElementById('q').addEventListener('blur', () => setTimeout(closeSug, 150));
document.addEventListener('click', e => {
  if (!document.getElementById('qwrap').contains(e.target)) closeSug();
});
</script>
</body>
</html>
"""


# /docmap 页面（单文件 HTML：同源调 /doc_code；文档↔代码映射测试页，
# 视觉沿用 /graph 浅色风，源码/文档块带行号展示，支持交叉反查）。
_DOCMAP_HTML = r"""<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>文档↔代码映射 · dual-mcp</title>
<style>
  :root { --bg:#f5f7fa; --panel:#ffffff; --panel2:#ffffff; --line:#e2e8f0;
          --txt:#1e293b; --dim:#64748b; --acc:#2563eb; --green:#00a36c;
          --purple:#8b3dff; --red:#e5484d; }
  * { box-sizing:border-box; margin:0; padding:0; }
  body { background:var(--bg); color:var(--txt);
         font:14px/1.5 "PingFang SC","Microsoft YaHei",system-ui,sans-serif;
         height:100vh; display:flex; flex-direction:column; overflow:hidden; }
  header { display:flex; align-items:center; gap:10px; padding:10px 16px;
           background:var(--panel2); border-bottom:1px solid var(--line);
           flex-wrap:wrap; }
  header h1 { font-size:16px; color:var(--txt); margin-right:6px; white-space:nowrap; }
  header h1 b { color:var(--green); }
  select, input[type=text], input[type=number] { background:#fff; color:var(--txt);
    border:1px solid #cbd5e1; border-radius:6px; padding:7px 10px;
    font-size:13px; outline:none; }
  select { max-width:290px; }
  input[type=text] { width:340px; }
  input[type=number] { width:64px; padding:5px 6px; }
  input:focus, select:focus { border-color:var(--acc); }
  button { background:var(--acc); color:#fff; border:none; border-radius:6px;
    padding:7px 18px; font-size:13px; cursor:pointer; }
  button:hover { filter:brightness(1.08); }
  #modes { display:flex; }
  .mode { background:#fff; color:var(--dim); border:1px solid #cbd5e1;
          border-radius:6px; padding:7px 14px; font-size:13px; cursor:pointer; }
  .mode.on { background:var(--acc); color:#fff; border-color:var(--acc); }
  #opts { display:flex; align-items:center; gap:14px; padding:8px 16px;
          background:var(--panel); border-bottom:1px solid var(--line);
          flex-wrap:wrap; font-size:13px; color:var(--dim); }
  #opts label { display:flex; align-items:center; gap:5px; }
  #opts .sep { flex:1; }
  .chip { background:#f1f5f9; border:1px solid var(--line); border-radius:5px;
          padding:2px 8px; font-size:12px; color:var(--txt); cursor:pointer; }
  .chip:hover { border-color:var(--acc); color:var(--acc); }
  .chip.fix { cursor:default; }
  .chip.fix:hover { border-color:var(--line); color:var(--txt); }
  #status { display:flex; align-items:center; gap:8px; padding:8px 16px;
            background:var(--panel); border-bottom:1px solid var(--line);
            flex-wrap:wrap; font-size:12px; color:var(--dim); min-height:36px; }
  .badge { display:inline-block; padding:2px 10px; border-radius:10px;
           font-size:12px; font-weight:600; }
  .hintbox { background:#fffbeb; border:1px solid #fde68a; color:#92400e;
             border-radius:6px; padding:8px 12px; font-size:13px;
             word-break:break-all; }
  #results { flex:1; overflow-y:auto; padding:14px 16px; }
  .card { background:var(--panel); border:1px solid var(--line); border-radius:8px;
          padding:12px 14px; margin-bottom:12px; }
  .card-h { display:flex; align-items:center; gap:8px; flex-wrap:wrap;
            font-size:13px; }
  .sym { font:600 14px ui-monospace,Consolas,Menlo,monospace; color:var(--txt);
         word-break:break-all; }
  .loc { color:var(--dim); font-size:12px; word-break:break-all;
         cursor:pointer; }
  .loc:hover { color:var(--acc); text-decoration:underline; }
  .refchip { background:#f0fdf4; border:1px solid #bbf7d0; color:#166534;
             border-radius:5px; padding:1px 8px; font-size:12px; }
  .blk { margin-top:8px; }
  .blk summary { cursor:pointer; color:var(--dim); font-size:12px;
                 user-select:none; }
  .blk summary:hover { color:var(--acc); }
  .codeblock { background:#f8fafc; border:1px solid var(--line); border-radius:6px;
               padding:8px 0; margin-top:6px; overflow-x:auto;
               font:12px/1.55 ui-monospace,Consolas,Menlo,monospace; color:#334155; }
  .cl { display:flex; white-space:pre; }
  .cl .ln { flex:none; width:46px; text-align:right; padding:0 10px;
            color:#94a3b8; user-select:none; }
  .cl.hl { background:#eef4ff; }
  .cl.hl .ln { color:var(--acc); font-weight:600; }
  .ghead-doc { font:500 13px ui-monospace,Consolas,Menlo,monospace;
               color:var(--green); word-break:break-all; cursor:pointer; }
  .ghead-doc:hover { text-decoration:underline; }
  .srow { display:flex; align-items:center; gap:8px; flex-wrap:wrap;
          padding:6px 0; border-bottom:1px dashed #eef2f7; font-size:13px; }
  .srow:last-child { border-bottom:none; }
  .card.hit, .srow.hit { background:#f0fdf4; border-color:#86efac; }
  #rawwrap { background:var(--panel); border-top:1px solid var(--line);
             padding:8px 16px; }
  #rawwrap summary { cursor:pointer; color:var(--dim); font-size:12px; }
  pre#raw { background:#f8fafc; border:1px solid var(--line); border-radius:6px;
            padding:10px; font-size:12px; overflow:auto; max-height:280px;
            color:#334155; white-space:pre; margin-top:6px; }
  .empty { color:var(--dim); padding:30px 10px; text-align:center; }
  main { flex:1; display:flex; min-height:0; }
  #docpane { width:46%; min-width:320px; display:flex; flex-direction:column;
             background:var(--panel); border-right:1px solid var(--line);
             position:relative; }
  #docpane.hide { display:none; }
  .dochead { padding:8px 12px; border-bottom:1px solid var(--line);
             display:flex; align-items:center; gap:8px; flex-wrap:wrap;
             font-size:12px; }
  .dochead .dt { color:var(--green); font-weight:600; white-space:nowrap; }
  .fhint { color:#94a3b8; font-size:11px; white-space:nowrap; }
  #docpath { color:var(--dim); word-break:break-all; flex:1; min-width:120px; }
  .mini { background:#fff; color:var(--acc); border:1px solid #bfdbfe;
          border-radius:5px; padding:2px 10px; font-size:12px; cursor:pointer;
          white-space:nowrap; }
  .mini:hover { background:#eef4ff; }
  #docchips { display:flex; gap:6px; flex-wrap:wrap; padding:6px 12px;
              border-bottom:1px solid var(--line); }
  #docchips:empty { display:none; }
  .chip.on { background:var(--acc); color:#fff; border-color:var(--acc); }
  #docframe { flex:1; border:none; width:100%; background:#fff; }
  #docload { position:absolute; inset:0; background:rgba(255,255,255,.85);
             display:flex; align-items:center; justify-content:center;
             color:var(--dim); font-size:13px; z-index:2; }
  #docload.hide { display:none; }
  #toast { position:fixed; left:50%; top:64px; transform:translateX(-50%);
           background:var(--red); color:#fff; padding:8px 18px; border-radius:6px;
           display:none; z-index:9; font-size:13px; max-width:80%; }
</style>
</head>
<body>
<header>
  <h1>文档↔代码映射 <b>·</b> dual-mcp</h1>
  <select id="proj" title="知识库（kbId）"></select>
  <div id="modes">
    <button class="mode on" id="d-auto" onclick="setDir('auto')" title="自动判向：先试文档侧，未命中再试代码侧">auto</button>
    <button class="mode" id="d-doc2code" onclick="setDir('doc2code')" title="强制按文档查引用的代码符号">doc2code</button>
    <button class="mode" id="d-code2doc" onclick="setDir('code2doc')" title="强制按代码文件查引用它的文档">code2doc</button>
  </div>
  <div id="qwrap">
    <input id="file" type="text" placeholder="文档名 / 代码文件名 / 路径片段（如 osal_device.c、README.md、hi-aiot-build）">
  </div>
  <button onclick="doQuery()">查询</button>
</header>
<div id="opts">
  <label>limit <input id="limit" type="number" min="1" max="200" value="20"></label>
  <label>with_source <input id="ws" type="number" min="0" max="20" value="20" title="附前 N 个符号的源码段（doc2code 右栏展示；默认 20 保证点片段即可见源码）"></label>
  <label>with_doc <input id="wd" type="number" min="0" max="20" value="20" title="附前 N 个文档的文档块（code2doc 右侧仅展示有文档块的组，默认 20）"></label>
  <label><input id="exact" type="checkbox"> exact</label>
  <span class="sep"></span>
  <span style="color:#94a3b8">预设用例：</span>
  <span class="chip" onclick="preset('osal_device.c')">osal_device.c（代码→文档）</span>
  <span class="chip" onclick="preset('SKILL.md', 'doc2code', true)">SKILL.md（文档→代码·exact）</span>
  <span class="chip" onclick="preset('hi-aiot-build')">hi-aiot-build（路径片段）</span>
</div>
<div id="status"></div>
<main>
  <div id="docpane" class="hide">
    <div class="dochead">
      <span class="dt">📄 文档原文</span>
      <span class="fhint">绿色片段=有映射 · 点击筛选右侧</span>
      <span id="docpath"></span>
      <button class="mini" onclick="openLeft()" title="在新窗口打开当前原文（含锚点定位）">新窗口打开 ↗</button>
    </div>
    <div id="docchips"></div>
    <div id="docload">加载中…</div>
    <iframe id="docframe" onload="if (typeof onDocFrameLoad === 'function') onDocFrameLoad()"></iframe>
  </div>
  <div id="results"><div class="empty">输入一个文件名开始测试映射查询<br><br>命中文档 → 返回该文档引用的代码符号清单（doc2code）<br>命中代码文件 → 返回引用它的文档清单（code2doc）<br>查到结果后：左侧展示输入文件原文（绿色片段=有映射，点击筛选右侧），右侧展示映射关系<br>点击右侧符号行在左侧原文定位；点击文档路径/锚点新窗口打开；点击代码路径交叉反查</div></div>
</main>
<details id="rawwrap"><summary>原始 JSON（请求 / 响应）</summary><pre id="raw"></pre></details>
<div id="toast"></div>
<script>
const LABEL_COLORS = {Function:'#4f9cf9', Method:'#38bdf8', Class:'#a78bfa',
  Variable:'#fbbf24', Document:'#fb923c', File:'#94a3b8', Module:'#34d399',
  Interface:'#f472b6', Struct:'#2dd4bf', Enum:'#c084fc'};
const LABEL_CN = {Function:'函数', Method:'方法', Class:'类', Struct:'结构体',
  Enum:'枚举', Interface:'接口', Variable:'变量', Constant:'常量', Macro:'宏',
  Module:'模块', File:'文件', Document:'文档'};
const DIR_CN = {doc2code:'doc2code 文档→代码', code2doc:'code2doc 代码→文档',
  auto:'auto'};
let direction = 'auto';
const _LOOKUP = [];   // 交叉反查路径表（onclick 用索引避免引号转义）

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g,
    c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
function labelCn(l) { return LABEL_CN[l] || l || ''; }
function proj() { return document.getElementById('proj').value; }
function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.style.display = 'block';
  clearTimeout(t._tm); t._tm = setTimeout(() => t.style.display = 'none', 5000);
}
function lk(path, dir) { _LOOKUP.push([path, dir]); return _LOOKUP.length - 1; }
function lookup(i) {
  const p = _LOOKUP[i]; if (!p) return;
  document.getElementById('file').value = p[0];
  setDir(p[1] || 'auto');
  doQuery();
}
// 左栏文档原文面板：docs=[{path, anchor}]，curIdx 当前文档
let docs = [], curIdx = -1;
let lastResp = null, curFilter = null;   // 最近响应 / 当前片段过滤（符号名）
function docUrl(path) {
  return location.origin + '/doc_view?project=' + encodeURIComponent(proj()) +
         '&file=' + encodeURIComponent(path);
}
function baseName(p) { return String(p).replace(/.*\//, ''); }
function renderDocChips() {
  const box = document.getElementById('docchips');
  const cap = 10;
  box.innerHTML = docs.slice(0, cap).map((d, i) =>
    '<span class="chip' + (i === curIdx ? ' on' : '') + '" title="' + esc(d.path) +
    '" onclick="switchDoc(' + i + ')">' + esc(baseName(d.path)) + '</span>').join('') +
    (docs.length > cap ? '<span class="chip fix" title="更多文档点击右侧文档路径切换">…共 ' +
     docs.length + ' 个</span>' : '');
}
function switchDoc(i) {
  const d = docs[i]; if (!d) return;
  curIdx = i;
  curFilter = null;   // 换文件后片段集不同，重置过滤
  document.getElementById('docpath').textContent = d.path;
  document.getElementById('docpath').title = d.path;
  renderDocChips();
  document.getElementById('docload').classList.remove('hide');
  const f = document.getElementById('docframe');
  const url = docUrl(d.path) + (d.anchor ? '#L' + d.anchor : '');
  // 赋相同 src（或仅锚点不同）不会触发 iframe load 事件，浮层会永久
  // 停在「加载中」——同 URL 手动收尾；同文档换锚点走 fragment 导航
  //（无 load）也手动收尾并定位。
  if (f.src === url) {
    onDocFrameLoad();
  } else if (f.src.split('#')[0] === url.split('#')[0]) {
    f.src = url;
    onDocFrameLoad();
  } else {
    f.src = url;
    // 看门狗：load 迟迟不来（如 NFS 读文件挂起）时兜底撤掉浮层
    clearTimeout(f._wd);
    f._wd = setTimeout(() => {
      document.getElementById('docload').classList.add('hide');
    }, 15000);
  }
}
// 点击锚点/文档路径：切到对应文档并定位行（同文档仅跳转不重载）
const _DOCACT = [];   // [path, line] 索引表（onclick 免引号转义）
function da(path, line) { _DOCACT.push([path, line || null]); return _DOCACT.length - 1; }
function docJumpIdx(i) {
  const a = _DOCACT[i]; if (a) docJump(a[0], a[1]);
}
function docJump(path, line) {
  let i = docs.findIndex(d => d.path === path);
  if (i < 0) { docs.push({path: path, anchor: line || null}); i = docs.length - 1;
               renderDocChips(); }
  const same = i === curIdx;
  if (!same) { switchDoc(i); return; }
  if (line) jumpLine(line);
}
function jumpLine(line) {
  const f = document.getElementById('docframe');
  try {
    f.contentWindow.location.hash = '#L' + line;   // 触发 :target 高亮
    const el = f.contentWindow.document.getElementById('L' + line);
    if (el) el.scrollIntoView({block: 'center'});
  } catch (e) { f.src = docUrl(docs[curIdx].path) + '#L' + line; }
}
function openLeft() {
  const d = docs[curIdx]; if (!d) return;
  window.open(docUrl(d.path) + (d.anchor ? '#L' + d.anchor : ''), '_blank');
}
function hideDocPane() {
  docs = []; curIdx = -1;
  document.getElementById('docpane').classList.add('hide');
  document.getElementById('docframe').src = 'about:blank';
}
// 从响应构造左栏原文清单：左栏 = 查询输入文件本身。
// doc2code → 命中的文档（锚点=首个引用行）；code2doc → 命中的代码文件
// （取 symbols[].file，锚点=首个符号起始行）。
let leftKind = 'doc';
function buildDocs(d) {
  const out = [], seen = new Set();
  const push = (path, anchor) => {
    if (!path || seen.has(path)) return;
    seen.add(path); out.push({path: path, anchor: anchor || null});
  };
  if (d.direction === 'doc2code') {
    leftKind = 'doc';
    (d.results || []).forEach(r =>
      push(r.doc, seen.has(r.doc) ? null : (r.doc_ref || {}).line));
  } else {
    leftKind = 'code';
    (d.results || []).forEach(g => (g.symbols || []).forEach(s =>
      push(s.file, seen.has(s.file) ? null : (s.start_line || null))));
  }
  return out;
}
function setDir(d) {
  direction = d;
  ['auto', 'doc2code', 'code2doc'].forEach(x =>
    document.getElementById('d-' + x).classList.toggle('on', x === d));
}
function preset(file, dir, exact) {
  document.getElementById('file').value = file;
  if (dir) setDir(dir); else setDir('auto');
  document.getElementById('exact').checked = !!exact;
  doQuery();
}
function labelBadge(label) {
  const c = LABEL_COLORS[label] || '#cbd5e1';
  return '<span class="badge" style="background:' + c + '1a;color:' + c + '">' +
         esc(labelCn(label)) + '</span>';
}
// 带行号代码块；hl 为高亮行号（文档引用锚点）
function codeBlock(src, startLine, hl) {
  if (!src) return '';
  const lines = String(src).replace(/\n$/, '').split('\n');
  let n = startLine || 1;
  const rows = lines.map(t => {
    const cls = (hl != null && n === hl) ? 'cl hl' : 'cl';
    const h = '<div class="' + cls + '"><span class="ln">' + n +
              '</span><span class="ct">' + esc(t) + '</span></div>';
    n++; return h;
  }).join('');
  return '<div class="codeblock">' + rows + '</div>';
}
function fragBlock(f, ref) {
  if (!f) return '';
  const hl = ref && ref.line ? ref.line : null;
  return '<details class="blk"><summary>文档块 ' + esc(f.file) + ':' +
         f.start_line + '-' + f.end_line + '（锚点窗口）</summary>' +
         codeBlock(f.content, f.start_line, hl) + '</details>';
}
// doc2code：一条映射 = 文档引用的一个代码符号。左栏=文档 → 右栏只展示
// 代码（源码段）；code2doc 左栏=代码 → 右栏只展示文档（组头+文档块）。
function rowCard(r) {
  const ref = r.doc_ref || {};
  const fv = r.file_version
    ? '<span class="chip fix">v' + esc(r.file_version) + '</span>' : '';
  const docChip = r.doc
    ? '<span class="loc" title="点击在左侧打开该文档原文" onclick="docJumpIdx(' +
      da(r.doc, (r.doc_ref || {}).line) + ')">📄 ' + esc(r.doc) + '</span>' : '';
  const hit = curFilter && r.symbol === curFilter;
  let body = '';
  if (r.source)
    body += '<details class="blk"' + (hit ? ' open' : '') + '><summary>源码段 ' +
            esc(r.file) + ':' + r.start_line + '-' + r.end_line + '</summary>' +
            codeBlock(r.source, r.start_line, null) + '</details>';
  if (!body) body = '<div class="blk" style="color:#94a3b8;font-size:12px">' +
    '未附源码段（with_source = 0 或超出前 N）</div>';
  return '<div class="card' + (hit ? ' hit' : '') + '"' +
    (hit ? ' data-sym-hit="1"' : '') + '><div class="card-h">' +
    '<span class="sym">' + esc(r.symbol) + '</span>' + labelBadge(r.label) +
    '<span class="loc" title="反查该代码文件（code2doc），左侧切换为该文件原文" onclick="lookup(' +
      lk(r.file, 'code2doc') + ')">' + esc(r.file) + ':' + r.start_line + '-' +
      r.end_line + '</span>' +
    (ref.ref_name ? '<span class="refchip" style="cursor:pointer" title="点击在左侧原文定位该锚点" onclick="docJumpIdx(' +
      da(r.doc, ref.line) + ')">⚓ ' + esc(ref.ref_name) +
      (r.doc_name ? ' @ ' + esc(r.doc_name) : '') + ':' + (ref.line || '?') +
      '</span>' : '') + docChip + fv +
    '</div>' + body + '</div>';
}
// code2doc：一个文档组 = 引用该代码文件的文档 + 组内符号
function groupCard(g) {
  const docHref = docUrl(g.doc);
  const rows = (g.symbols || []).map(s => {
    const ref = s.doc_ref || {};
    const hit = curFilter && s.symbol === curFilter;
    return '<div class="srow' + (hit ? ' hit' : '') + '"' +
      (hit ? ' data-sym-hit="1"' : '') + '>' +
      '<span class="sym" style="cursor:pointer" title="点击在左侧定位该符号" onclick="docJumpIdx(' +
        da(s.file, s.start_line) + ')">' + esc(s.symbol) + '</span>' + labelBadge(s.label) +
      '<span class="loc" style="cursor:pointer" title="点击在左侧定位该符号" onclick="docJumpIdx(' +
        da(s.file, s.start_line) + ')">' + esc(s.file) + ':' + s.start_line + '-' +
        s.end_line + '</span>' +
      (ref.ref_name ? '<a class="refchip" style="text-decoration:none" target="_blank" title="在新窗口打开文档并定位该锚点" href="' +
        docHref + (ref.line ? '#L' + ref.line : '') + '">⚓ ' + esc(ref.ref_name) + ':' +
        (ref.line || '?') + ' ↗</a>' : '') +
      '</div>';
  }).join('');
  const fv = g.symbols && g.symbols.length && g.symbols[0].file_version
    ? '<span class="chip fix">v' + esc(g.symbols[0].file_version) + '</span>' : '';
  return '<div class="card">' +
    '<div class="card-h">' +
    '<span class="badge" style="background:#fb923c1a;color:#fb923c">文档</span>' +
    '<a class="ghead-doc" target="_blank" title="在新窗口打开该文档原文" href="' +
      docHref + '">' + esc(g.doc) + ' ↗</a>' + fv +
    '<span class="chip fix">' + (g.symbols || []).length + ' 符号</span>' +
    '</div>' + (rows || '<div class="empty">无符号明细</div>') +
    fragBlock(g.doc_fragment, null) + '</div>';
}
function render(d) {
  lastResp = d; curFilter = null;
  if (!d.direction) {
    renderStatus();
    document.getElementById('results').innerHTML =
      '<div class="hintbox">' + esc(d.hint || '未找到映射') +
      '</div><div class="empty">空结果是数据真值：映射边 = 文档正文实际引用的符号。<br>' +
      '可换路径片段 / 调整 exact / 换个库再试</div>';
    hideDocPane();
    return;
  }
  renderStatus();
  renderResults();
  const list = d.results || [];
  if (list.length === 0) { hideDocPane(); return; }
  docs = buildDocs(d);
  curIdx = -1;
  if (docs.length) {
    document.querySelector('#docpane .dt').textContent =
      leftKind === 'code' ? '🧩 输入代码文件' : '📄 输入文档';
    document.getElementById('docpane').classList.remove('hide');
    switchDoc(0);
  } else {
    hideDocPane();
  }
}
function statusBadges() {
  const d = lastResp || {};
  const dirC = d.direction === 'doc2code' ? '#2563eb'
             : d.direction === 'code2doc' ? '#00a36c' : '#94a3b8';
  let h = '';
  if (d.direction) {
    h += '<span class="badge" style="background:' + dirC + '1a;color:' + dirC +
         '">' + esc(DIR_CN[d.direction] || d.direction) + '</span>';
  }
  if (d.matched) h += '<span class="chip fix">matched: ' + esc(d.matched) + '</span>';
  h += '<span class="chip fix">count: ' + (d.count != null ? d.count : 0) + '</span>';
  if (d.elapsed_ms != null)
    h += '<span class="chip fix">' + d.elapsed_ms + ' ms</span>';
  return h;
}
function filterHitCount(sym) {
  if (!lastResp || !lastResp.direction) return 0;
  const list = lastResp.results || [];
  return lastResp.direction === 'code2doc'
    ? list.filter(g => (g.symbols || []).some(s => s.symbol === sym)).length
    : list.filter(r => r.symbol === sym).length;
}
function renderStatus() {
  let h = statusBadges();
  if (lastResp && lastResp.direction === 'code2doc' && lastTotal > lastShown)
    h += '<span class="chip fix" title="code2doc 右侧仅展示带文档块的文档组（with_doc 参数控制附块数）">📄 有文档块: ' +
         lastShown + '/' + lastTotal + '</span>';
  if (curFilter) {
    h += '<span class="chip" style="border-color:#00a36c;color:#00a36c;cursor:pointer"' +
         ' title="点击清除片段过滤（恢复全量）" onclick="setFilter(null)">🧩 片段: ' +
         esc(curFilter) + ' · ' + filterHitCount(curFilter) +
         (lastResp && lastResp.direction === 'code2doc' ? ' 文档' : ' 处') + ' ✕</span>';
  }
  document.getElementById('status').innerHTML = h;
}
// 右栏渲染（全量或按 curFilter 过滤）；过滤后高亮并滚动到目标符号行。
// code2doc：仅展示带文档块的文档组（找不到文档块的不展示）
let lastShown = 0, lastTotal = 0;
function renderResults() {
  const d = lastResp, res = document.getElementById('results');
  if (!d || !d.direction) { lastShown = lastTotal = 0; return; }
  const all = d.results || [];
  const base = d.direction === 'code2doc' ? all.filter(g => g.doc_fragment) : all;
  lastTotal = all.length;
  let list = base;
  if (curFilter)
    list = list.filter(d.direction === 'code2doc'
      ? g => (g.symbols || []).some(s => s.symbol === curFilter)
      : r => r.symbol === curFilter);
  if (curFilter && list.length === 0) {
    toast('片段 ' + curFilter + ' 无映射明细，已恢复全量');
    curFilter = null;
    list = base;
  }
  lastShown = list.length;
  renderStatus();
  res.innerHTML = list.length === 0
    ? '<div class="hintbox">' + esc(d.hint || '未找到映射') + '</div>'
    : list.map(d.direction === 'doc2code' ? rowCard : groupCard).join('');
  const hit = res.querySelector('[data-sym-hit]');
  if (hit) hit.scrollIntoView({block: 'center'});
}
function setFilter(sym) {
  if (!lastResp || !lastResp.direction) return;
  curFilter = (sym && curFilter !== sym) ? sym : null;   // 再点同片段取消
  renderStatus();
  renderResults();
  syncFragCur();
}
// ── 左栏片段装饰：映射片段高亮 + 点击筛选右栏 ──────────────────────
// 从 lastResp 构造当前左栏文件的片段表（code2doc=symbols 行区间；doc2code=锚点行）
function buildFrags() {
  const d = lastResp, cur = docs[curIdx];
  const out = [];
  if (!d || !d.direction || !cur) return out;
  const map = new Map();
  const bump = (key, f) => { if (!map.has(key)) map.set(key, f); map.get(key).n++; };
  if (d.direction === 'code2doc')
    (d.results || []).forEach(g => (g.symbols || []).forEach(s => {
      if (s.file !== cur.path || !(s.start_line > 0)) return;
      bump(s.symbol + ':' + s.start_line,
           {sym: s.symbol, start: s.start_line, end: s.end_line || s.start_line, n: 0});
    }));
  else
    (d.results || []).forEach(r => {
      if (r.doc !== cur.path || !((r.doc_ref || {}).line > 0)) return;
      bump(r.symbol + ':' + r.doc_ref.line,
           {sym: r.symbol, start: r.doc_ref.line, end: r.doc_ref.line, n: 0});
    });
  map.forEach(f => out.push(f));
  out.sort((a, b) => a.start - b.start);
  return out;
}
function onDocFrameLoad() {
  document.getElementById('docload').classList.add('hide');
  decorate();
}
// 同源 iframe DOM 注入：片段行加类/标签，点击 → setFilter（失败静默）
function decorate() {
  let cd = null;
  try { cd = document.getElementById('docframe').contentDocument; }
  catch (e) { return; }
  if (!cd || !cd.body || !cd.getElementById) return;
  try {
    const style = cd.createElement('style');
    style.textContent =
      '.r.frag { background:#f0fdf4; border-left:3px solid #00a36c; cursor:pointer; }' +
      '.r.frag:hover { background:#dcfce7; }' +
      '.r.frag.cur { background:#dcfce7; border-left-color:#2563eb; }' +
      '.r.frag.start { position:relative; }' +
      '.r.frag.start::after { content:attr(data-tag); position:absolute; right:8px;' +
        ' background:#00a36c; color:#fff; border-radius:4px; padding:0 8px;' +
        ' font-size:11px; line-height:1.8; max-width:40%; overflow:hidden;' +
        ' text-overflow:ellipsis; white-space:nowrap; }';
    cd.head.appendChild(style);
    buildFrags().forEach(fr => {
      for (let n = fr.start, last = Math.min(fr.end, fr.start + 2000); n <= last; n++) {
        const el = cd.getElementById('L' + n);
        if (!el) break;
        el.classList.add('frag');
        el.dataset.sym = fr.sym;
        if (n === fr.start) {
          el.classList.add('start');
          el.dataset.tag = fr.sym + (fr.n > 1 ? ' · ' + fr.n + ' 文档' : '');
        }
        if (curFilter && fr.sym === curFilter) el.classList.add('cur');
      }
    });
    cd.body.addEventListener('click', e => {
      const t = e.target;
      const el = t && t.closest ? t.closest('.r.frag') : null;
      if (el && el.dataset && el.dataset.sym) setFilter(el.dataset.sym);
    });
  } catch (e) { /* 装饰失败静默（行结构变化等） */ }
}
function syncFragCur() {
  try {
    const cd = document.getElementById('docframe').contentDocument;
    if (!cd || !cd.querySelectorAll) return;
    cd.querySelectorAll('.r.frag').forEach(el =>
      el.classList.toggle('cur', !!curFilter && el.dataset.sym === curFilter));
  } catch (e) { /* 同上 */ }
}
function showRaw(req, resp) {
  document.getElementById('raw').textContent =
    'POST /doc_code\n' + JSON.stringify(req, null, 2) + '\n\n' +
    JSON.stringify(resp, null, 2);
  document.getElementById('rawwrap').open = true;
}
async function doQuery() {
  const file = document.getElementById('file').value.trim();
  if (!file) { toast('请输入文件名'); return; }
  const req = {
    file: file, project: proj(), direction: direction,
    limit: +document.getElementById('limit').value || 20,
    with_source: +document.getElementById('ws').value || 0,
    with_doc: +document.getElementById('wd').value || 0,
    exact: document.getElementById('exact').checked,
  };
  document.getElementById('status').innerHTML =
    '<span class="chip fix">查询中…</span>';
  try {
    const r = await fetch(location.origin + '/doc_code', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req),
    });
    let d = {};
    try { d = await r.json(); } catch (e) { /* 非 JSON 错误体 */ }
    showRaw(req, d);
    if (!r.ok) {
      document.getElementById('status').innerHTML =
        '<span class="chip fix">HTTP ' + r.status + '</span>';
      hideDocPane();
      toast('HTTP ' + r.status + '：' + (d.detail || r.statusText));
      return;
    }
    render(d);
  } catch (e) {
    document.getElementById('status').innerHTML = '';
    hideDocPane();
    toast('请求失败：' + e.message);
  }
}
async function init() {
  try {
    const d = await (await fetch(location.origin + '/projects')).json();
    const sel = document.getElementById('proj');
    (d.projects || []).forEach(p => {
      const kb = String(p.name).replace(/^projects-/, '');
      const o = document.createElement('option');
      o.value = kb;
      o.textContent = (p.kb_name || kb) + ' (' + (p.nodes || 0) + ')';
      o.title = kb;
      sel.appendChild(o);
    });
    const def = (d.projects || []).find(p => /kbf1dc0/.test(p.name)) ||
                (d.projects || [])[0];
    if (def) sel.value = String(def.name).replace(/^projects-/, '');
  } catch (e) { toast('知识库列表加载失败：' + e.message); }
  const ps = new URLSearchParams(location.search);
  const f = ps.get('file');
  if (f) {
    document.getElementById('file').value = f;
    const p = ps.get('project');
    if (p) document.getElementById('proj').value = p;
    doQuery();
  }
}
document.getElementById('file').addEventListener('keydown',
  e => { if (e.key === 'Enter') doQuery(); });
init();
</script>
</body>
</html>
"""


