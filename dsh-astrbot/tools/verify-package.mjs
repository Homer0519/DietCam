/** 打包契约检查：把 DSH 安装器质量门禁里与本插件相关的那几条，在仓库里自己先跑一遍。
 *
 * 为什么需要它：DSH 的受保护安装流程会扫描整条加载链，
 *   - 拒绝把 @deepseek-ai/dsh-* 声明成普通依赖（会装出第二份副本，模块标识分裂，运行时报错）；
 *   - 拒绝「import 了但没声明」的裸模块（启动时才炸）；
 *   - 要求 bundle patch 里每个 name 都能解析出来。
 * 这些都是装到一半才失败、而且会把 profile 一起弄坏的问题，放在仓库里提前拦住最划算。
 *
 * 跑法：node tools/verify-package.mjs
 */

import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const PLUGIN_DIR = resolve(HERE, '..', 'dsh-plugin');

// DSH 加载器自己提供的模块，不需要（也不应该）在 package.json 里声明
const LOADER_PROVIDED = new Set([
  'react', 'react/jsx-runtime', 'react-dom', 'react-dom/client',
  '@deepseek-ai/cordis',
  '@deepseek-ai/cordis-plugin-loader',
  '@deepseek-ai/cordis-plugin-include',
  '@deepseek-ai/cordis-plugin-group',
  '@deepseek-ai/cordis-plugin-hmr',
  '@deepseek-ai/cordis-plugin-timer',
  '@deepseek-ai/dsh-client-web-react',
]);

function loaderProvided(spec) {
  if (LOADER_PROVIDED.has(spec)) return true;
  return spec.startsWith('@deepseek-ai/dsh-client-')
    || spec.startsWith('@deepseek-ai/cordis-plugin-');
}

const problems = [];
const notes = [];
function check(ok, message) {
  if (!ok) problems.push(message);
  return ok;
}

// ------------------------------------------------------------ package.json

const manifestPath = resolve(PLUGIN_DIR, 'package.json');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));

check(manifest.name === 'dsh-astrbot', 'package.json name 应为 dsh-astrbot，实际 ' + manifest.name);
check(manifest.type === 'module', 'package.json 需要 "type": "module"');
check(manifest.main === './lib/index.js', 'main 应为 ./lib/index.js');

const entry = resolve(PLUGIN_DIR, manifest.main);
check(existsSync(entry), 'main 指向的入口不存在: ' + manifest.main);
check(
  manifest.exports && manifest.exports['.'] === './lib/index.js',
  'exports["."] 应指向 ./lib/index.js',
);

// 依赖声明：一条 @deepseek-ai/dsh-* 都不能有
const declared = new Set();
for (const section of ['dependencies', 'peerDependencies', 'optionalDependencies', 'devDependencies']) {
  for (const name of Object.keys(manifest[section] ?? {})) {
    declared.add(name);
    if (name.startsWith('@deepseek-ai/dsh-')) {
      problems.push(
        section + ' 里声明了官方运行时包 ' + name
        + '：会装出第二份副本导致模块标识分裂，应改为 peerDependency 或干脆不声明',
      );
    }
  }
}
notes.push('声明的依赖: ' + (declared.size === 0 ? '（无）' : [...declared].join(', ')));

// ------------------------------------------------------------ 加载链扫描

/** 从入口出发，顺着相对 import 把整条加载链走一遍，收集所有裸模块说明符。 */
function scanLoadChain(entryFile) {
  const seen = new Set();
  const bare = new Map(); // spec -> 第一次出现的文件
  const queue = [entryFile];

  while (queue.length > 0) {
    const file = queue.shift();
    if (seen.has(file)) continue;
    seen.add(file);
    if (!existsSync(file)) {
      problems.push('加载链里缺少文件: ' + file);
      continue;
    }
    const source = readFileSync(file, 'utf8');
    const specs = new Set();
    // 静态 import / export ... from
    for (const match of source.matchAll(/(?:^|\n)\s*(?:import|export)[\s\S]*?from\s*['"]([^'"]+)['"]/g)) {
      specs.add(match[1]);
    }
    // 副作用 import 'x'
    for (const match of source.matchAll(/(?:^|\n)\s*import\s*['"]([^'"]+)['"]/g)) {
      specs.add(match[1]);
    }
    // 动态 import('x')
    for (const match of source.matchAll(/import\s*\(\s*['"]([^'"]+)['"]\s*\)/g)) {
      specs.add(match[1]);
    }

    for (const spec of specs) {
      if (spec.startsWith('.')) {
        queue.push(resolve(dirname(file), spec));
        continue;
      }
      if (!bare.has(spec)) bare.set(spec, file.replace(PLUGIN_DIR + '\\', '').replace(PLUGIN_DIR + '/', ''));
    }
  }
  return { files: [...seen], bare };
}

const chain = scanLoadChain(entry);
notes.push('加载链文件 ' + chain.files.length + ' 个');

for (const [spec, from] of chain.bare) {
  if (spec.startsWith('node:')) continue;
  if (loaderProvided(spec)) continue;
  if (declared.has(spec)) continue;
  problems.push('import 了 ' + spec + ' 但没有声明（' + from + '），启动时才会失败');
}
notes.push('裸模块: ' + ([...chain.bare.keys()].join(', ') || '（只有 node: 内置）'));

// ------------------------------------------------------------ cordis.patch.yml

const patchRel = manifest.dsh?.bundle?.patch;
check(typeof patchRel === 'string' && patchRel.length > 0, 'package.json 缺少 dsh.bundle.patch');
if (typeof patchRel === 'string') {
  const patchPath = resolve(PLUGIN_DIR, patchRel);
  check(existsSync(patchPath), 'bundle patch 文件不存在: ' + patchRel);
  if (existsSync(patchPath)) {
    const text = readFileSync(patchPath, 'utf8');
    const stripped = text.split('\n').filter((line) => !line.trim().startsWith('#')).join('\n').trim();
    check(stripped.length > 0, 'bundle patch 为空会让 profile 启动失败（要禁用该层请写 []）');

    // 解析 insert 列表里的行。不引 yaml 依赖：本文件的形状很简单，
    // 以 "- " 开头的行起一个新行（其 id 在同一行），其后的 name 属于该行。
    const rows = [];
    for (const raw of text.split('\n')) {
      const line = raw.trim();
      if (line === '' || line.startsWith('#')) continue;
      const bullet = /^-\s+id:\s*['"]?([^'"\s]+)['"]?\s*$/.exec(line);
      if (bullet) {
        rows.push({ id: bullet[1], name: null });
        continue;
      }
      const plain = /^id:\s*['"]?([^'"\s]+)['"]?\s*$/.exec(line);
      if (plain) {
        rows.push({ id: plain[1], name: null });
        continue;
      }
      const named = /^name:\s*['"]?([^'"\s]+)['"]?\s*$/.exec(line);
      if (named && rows.length > 0) {
        rows[rows.length - 1].name = named[1];
      }
    }

    check(rows.length > 0, 'bundle patch 里没有解析到任何挂载行，profile 挂不上东西');
    for (const row of rows) {
      check(row.name !== null, 'bundle patch 的行 ' + row.id + ' 没有 name');
      if (row.name === null) continue;
      check(
        row.name === manifest.name || declared.has(row.name),
        'bundle patch 挂载了 ' + row.name + '，但它既不是本包也不是已声明的依赖',
      );
    }
    const ids = rows.map((row) => row.id);
    check(new Set(ids).size === ids.length, 'bundle patch 里有重复的 id: ' + ids.join(', '));
    notes.push('patch 挂载行: ' + rows.map((row) => row.id + ' -> ' + row.name).join(', '));
  }
}

// ------------------------------------------------------------ 输出

for (const note of notes) console.log('  · ' + note);
if (problems.length === 0) {
  console.log('\n打包契约检查通过');
  process.exit(0);
}
console.log('');
for (const problem of problems) console.log('  FAIL  ' + problem);
console.log('\n打包契约检查失败：' + problems.length + ' 项');
process.exit(1);
