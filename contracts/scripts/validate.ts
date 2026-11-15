// Validates contracts/: every schema compiles, every event version has a valid example,
// every invalid example is rejected, and asyncapi.yaml parses and references every schema.
// Run: npm run validate (from contracts/). Exit code 1 on any problem.
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, relative, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { Ajv2020 } from "ajv/dist/2020.js";
import type { ValidateFunction } from "ajv";
import addFormatsModule from "ajv-formats";
import { Parser, fromFile, DiagnosticSeverity } from "@asyncapi/parser";

type Message = { type?: unknown; version?: unknown };

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const problems: string[] = [];
const ok = (msg: string): void => console.log(`ok    ${msg}`);
const fail = (msg: string): void => {
  problems.push(msg);
  console.error(`FAIL  ${msg}`);
};

const readJson = (path: string): unknown => JSON.parse(readFileSync(path, "utf8"));
const listJson = (dir: string): string[] =>
  existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith(".json")).map((f) => join(dir, f)) : [];

const ajv = new Ajv2020({ allErrors: true, strict: true });
// ajv-formats ships CJS default export; normalise for NodeNext interop.
const addFormats = addFormatsModule as unknown as (a: Ajv2020) => Ajv2020;
addFormats(ajv);
for (const keyword of ["x-topic", "x-producer", "x-consumers"]) ajv.addKeyword(keyword);

// 1. Load shared schemas and event schemas.
ajv.addSchema(readJson(join(root, "common.schema.json")) as object);
ajv.addSchema(readJson(join(root, "envelope.schema.json")) as object);

const eventsDir = join(root, "events");
const validators = new Map<string, ValidateFunction>();
for (const type of readdirSync(eventsDir).sort()) {
  for (const file of listJson(join(eventsDir, type))) {
    const match = /v(\d+)\.schema\.json$/.exec(file);
    if (!match) {
      fail(`${relative(root, file)}: nome fora do padrão v<N>.schema.json`);
      continue;
    }
    const key = `${type}@${match[1]}`;
    try {
      validators.set(key, ajv.compile(readJson(file) as object));
      ok(`schema ${key}`);
    } catch (err) {
      fail(`${relative(root, file)}: não compila — ${(err as Error).message}`);
    }
  }
}
const envelope = ajv.getSchema("https://ticketpro.local/contracts/envelope.schema.json");
if (!envelope) fail("envelope.schema.json não carregado");

const keyOf = (msg: Message): string => `${String(msg.type)}@${String(msg.version)}`;
const errorsOf = (v: ValidateFunction): string => ajv.errorsText(v.errors, { separator: "; " });

// 2. Valid examples: one per event version, all must pass.
const covered = new Set<string>();
for (const file of listJson(join(root, "examples"))) {
  const msg = readJson(file) as Message;
  const key = keyOf(msg);
  const validate = validators.get(key);
  if (!validate) {
    fail(`${relative(root, file)}: não existe schema para ${key}`);
    continue;
  }
  covered.add(key);
  if (validate(msg)) ok(`example ${relative(root, file)}`);
  else fail(`${relative(root, file)}: ${errorsOf(validate)}`);
}
for (const key of validators.keys()) {
  if (!covered.has(key)) fail(`schema ${key} sem exemplo em examples/`);
}

// 3. Invalid examples: each must be rejected (missing schema = unsupported version = rejected).
for (const file of listJson(join(root, "examples", "invalid"))) {
  const msg = readJson(file) as Message;
  const validate = validators.get(keyOf(msg));
  const rejected = validate ? !validate(msg) : true;
  if (rejected) ok(`rejected ${relative(root, file)}${validate ? "" : " (versão não suportada)"}`);
  else fail(`${relative(root, file)}: deveria ser inválido, mas passou`);
}

// 4. AsyncAPI: parses without errors and references every event schema.
const asyncapiPath = join(root, "asyncapi.yaml");
const { document, diagnostics } = await fromFile(new Parser(), asyncapiPath).parse();
for (const d of diagnostics) {
  if (d.severity === DiagnosticSeverity.Error) fail(`asyncapi.yaml: ${d.message} @ ${d.path.join(".")}`);
}
if (document) {
  ok(`asyncapi.yaml (${document.messages().length} mensagens, ${document.operations().length} operações)`);
  const raw = readFileSync(asyncapiPath, "utf8");
  for (const key of validators.keys()) {
    const [type, version] = key.split("@");
    if (!raw.includes(`./events/${type}/v${version}.schema.json`)) fail(`asyncapi.yaml não referencia ${key}`);
  }
}

if (problems.length > 0) {
  console.error(`\n${problems.length} problema(s).`);
  process.exit(1);
}
console.log(`\nContratos válidos: ${validators.size} schemas de evento.`);
