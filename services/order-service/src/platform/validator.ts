import { readdir, readFile } from "node:fs/promises";
import { join, relative, sep } from "node:path";
import { Ajv2020, type AnySchemaObject, type ValidateFunction } from "ajv/dist/2020.js";
import addFormatsModule from "ajv-formats";
import { InvalidMessageError, UnsupportedVersionError } from "./envelope.js";

export interface ContractValidator {
  /** Unknown type/version → UnsupportedVersionError; schema violation → InvalidMessageError. */
  validate(rawJson: string): void;
}

// ajv-formats ships CommonJS; under NodeNext the plugin is the default export's `default`.
const addFormats = addFormatsModule.default;

async function schemaFiles(dir: string): Promise<string[]> {
  const entries = await readdir(dir, { withFileTypes: true, recursive: true });
  return entries
    .filter((e) => e.isFile() && e.name.endsWith(".schema.json"))
    .map((e) => join(e.parentPath, e.name));
}

/** Loads every *.schema.json under contractsDir, registered by its $id so relative $refs resolve. */
export async function createValidator(contractsDir: string): Promise<ContractValidator> {
  const ajv = new Ajv2020({ allErrors: true, strict: false });
  addFormats(ajv);
  const events = new Map<string, string>();
  for (const file of await schemaFiles(contractsDir)) {
    const schema = JSON.parse(await readFile(file, "utf8")) as AnySchemaObject;
    if (typeof schema.$id !== "string") throw new Error(`${file}: schema without $id`);
    ajv.addSchema(schema);
    const parts = relative(contractsDir, file).split(sep);
    if (parts.length === 3 && parts[0] === "events" && parts[1] && parts[2]) {
      events.set(`${parts[1]}/${parts[2].replace(".schema.json", "")}`, schema.$id);
    }
  }
  if (events.size === 0) throw new Error(`no event schemas under ${contractsDir}/events`);
  const compiled = new Map<string, ValidateFunction>();
  for (const [key, id] of events) {
    const fn = ajv.getSchema(id);
    if (!fn) throw new Error(`schema ${id} did not compile`);
    compiled.set(key, fn);
  }

  return {
    validate(rawJson: string): void {
      let message: unknown;
      try {
        message = JSON.parse(rawJson) as unknown;
      } catch {
        throw new InvalidMessageError("message is not JSON");
      }
      if (typeof message !== "object" || message === null || Array.isArray(message)) {
        throw new InvalidMessageError("message is not an object");
      }
      const { type, version } = message as { type?: unknown; version?: unknown };
      const key = `${String(type)}/v${String(version)}`;
      const fn = compiled.get(key);
      if (!fn) throw new UnsupportedVersionError(key);
      if (!fn(message)) {
        throw new InvalidMessageError(`${key}: ${ajv.errorsText(fn.errors)}`);
      }
    },
  };
}
