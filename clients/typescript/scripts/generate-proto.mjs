// Runs before build/test/typecheck (via npm's prebuild/pretest/pretypecheck
// hooks). Two outputs, both derived from the canonical proto at
// src/main/proto/informix.proto (Java Maven layout, one level up from this
// repo's clients/ directory):
//
// 1. proto/informix.proto - a plain copy, shipped in the published package
//    for reference/documentation only.
// 2. src/informix-descriptor.json - a precompiled protobufjs JSON
//    descriptor, generated so the client never reads the .proto file from
//    disk at runtime. A runtime file read (via __dirname or
//    import.meta.url) breaks the moment a bundler (webpack, esbuild, ...)
//    inlines this package into its own output - `__dirname` then resolves
//    to the bundle's own directory, not this package's. Loading from a
//    JSON descriptor bundled directly into dist/index.js/.cjs removes the
//    runtime file dependency entirely, so it works the same whether this
//    package is required directly, bundled, or run from ESM or CJS.
import { copyFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const here = dirname(fileURLToPath(import.meta.url));
const packageRoot = join(here, '..');
const protoSrc = join(packageRoot, '..', '..', 'src', 'main', 'proto', 'informix.proto');

const protoDestDir = join(packageRoot, 'proto');
mkdirSync(protoDestDir, { recursive: true });
copyFileSync(protoSrc, join(protoDestDir, 'informix.proto'));
console.log(`Copied ${protoSrc} -> ${join(protoDestDir, 'informix.proto')}`);

const pbjs = join(packageRoot, 'node_modules', '.bin', 'pbjs');
const descriptorOut = join(packageRoot, 'src', 'informix-descriptor.json');
// --keep-case: pbjs otherwise renames every field to camelCase as its
// canonical name (e.g. connection_id -> connectionId), keeping the
// original only as unused "protoName" metadata. protoLoader.fromJSON()
// builds directly off that canonical name, so without this flag every
// snake_case field access in the client (connection_id, rows_affected,
// total_rows, ...) silently reads undefined - keepCase in the loader
// options below never gets a chance to act, since the descriptor never
// had the snake_case name to begin with.
execFileSync(pbjs, ['-t', 'json', '--keep-case', '-o', descriptorOut, protoSrc], { stdio: 'inherit' });
console.log(`Generated ${descriptorOut}`);
