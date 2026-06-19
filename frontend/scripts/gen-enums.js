import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// CONFIGURATION
const SPEC_PATH = path.join(__dirname, '../../spec/openapi.json');
// Target a SUB-FOLDER for generated code so we don't touch manual files
const OUTPUT_DIR = path.join(__dirname, '../src/enums/generated');

console.log(`🔍 Reading OpenAPI from: ${SPEC_PATH}`);

if (!fs.existsSync(SPEC_PATH)) {
    console.error(`❌ Error: OpenAPI file not found at ${SPEC_PATH}`);
    process.exit(1);
}

const openapi = JSON.parse(fs.readFileSync(SPEC_PATH, 'utf-8'));
const schemas = openapi.components?.schemas || {};

// 1. CLEANUP: Safe to wipe the whole 'generated' folder now
if (fs.existsSync(OUTPUT_DIR)) {
    console.log('🧹 Wiping src/enums/generated/ ...');
    fs.rmSync(OUTPUT_DIR, { recursive: true, force: true });
}
fs.mkdirSync(OUTPUT_DIR, { recursive: true });

let indexContent = '// AUTO-GENERATED - DO NOT EDIT\n\n';
let count = 0;

// Iterate over schemas
Object.keys(schemas).forEach(schemaName => {
    const schema = schemas[schemaName];

    // Only process top-level Enums
    if (schema.enum) {
        generateEnumFile(schemaName, schema.enum);
        indexContent += `export * from './${schemaName}';\n`;
        count++;
    }
});

// Write Generated Index File
fs.writeFileSync(path.join(OUTPUT_DIR, 'index.ts'), indexContent);

console.log(`✅ Generated ${count} API Enums in src/enums/generated/`);

function generateEnumFile(name, values) {
    const filePath = path.join(OUTPUT_DIR, `${name}.ts`);
    let content = `// AUTO-GENERATED from OpenAPI definitions\n\n`;

    // 1. Enum Definition
    content += `export enum ${name} {\n`;
    values.forEach(val => {
        const key = typeof val === 'string' ? val.toUpperCase().replace(/[^A-Z0-9]/g, '_') : `VAL_${val}`;
        const value = typeof val === 'string' ? `'${val}'` : val;
        content += `  ${key} = ${value},\n`;
    });
    content += `}\n\n`;

    // 2. List Definition
    content += `export const ${name}List = [\n`;
    values.forEach(val => {
        const key = typeof val === 'string' ? val.toUpperCase().replace(/[^A-Z0-9]/g, '_') : `VAL_${val}`;
        content += `  ${name}.${key},\n`;
    });
    content += `] as const;\n\n`;

    // 3. Type Definition
    content += `export type ${name}Type = typeof ${name}List[number];\n\n`;

    // 4. Labels Map
    content += `export const ${name}Labels: Record<${name}Type, string> = {\n`;
    values.forEach(val => {
        const key = typeof val === 'string' ? val.toUpperCase().replace(/[^A-Z0-9]/g, '_') : `VAL_${val}`;
        const label = String(val).split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()).join(' ');
        content += `  [${name}.${key}]: '${label}',\n`;
    });
    content += `};\n`;

    fs.writeFileSync(filePath, content);
}
