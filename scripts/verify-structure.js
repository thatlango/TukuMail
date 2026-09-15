import { existsSync } from "node:fs";
const required=[
  "services/api/src/server.js",
  "services/engine/src/index.js",
  "packages/contracts/src/index.js",
  "apps/web/index.html",
  "apps/android/app/src/main/AndroidManifest.xml",
  "apps/desktop/README.md",
  "integrations/impactos/README.md",
  "docs/architecture.md"
];
const missing=required.filter(path=>!existsSync(path));
if(missing.length){console.error("Missing TukuMail workspace paths:",missing);process.exit(1);}
console.log("TukuMail workspace structure verified.");
