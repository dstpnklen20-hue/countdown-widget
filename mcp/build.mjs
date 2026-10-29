// Bundles the connector and the site's shared code (web/src/core, domain, data) into one file,
// so Claude starts it with plain `node dist/tiktak-mcp.mjs` and nothing else to install.
import { build } from "esbuild";

const test = process.argv.includes("--test");

await build({
  entryPoints: [test ? "test/smoke.ts" : "src/main.ts"],
  outfile: test ? "dist/smoke.mjs" : "dist/tiktak-mcp.mjs",
  bundle: true,
  platform: "node",
  target: "node20",
  format: "esm",
  // Some of the SDK's dependencies are CommonJS and call require().
  banner: { js: "import { createRequire } from 'node:module'; const require = createRequire(import.meta.url);" },
  // The shared code imports luxon: take it from here, so web/ needs no `npm ci` of its own.
  nodePaths: ["node_modules"],
  logLevel: "warning",
});
