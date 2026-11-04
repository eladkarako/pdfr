/* 
 * - reads, parse, validate './keystore.properties' file,
 * - use its values to run keytool command, to generate a keystore file, if needed, 
 * - passwords are pre-stored in environment variable, and keytool uses the environment-variable name instead of plain-text passwords in the command-line, 
 * - no overwrite existing keystore file, and basically this is almost unusable script that would be run manually once per lifetime of the application.
 */
"use strict";
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

// ============================================================================
// CONSTANTS
// ============================================================================
const FILE_ACCESS_TIMEOUT_MS = 3 * 1000;
const KEYTOOL_TIMEOUT_MS = 10 * 1000;
const FILE_READ_PERMISSIONS = 4 | 2; // fs.constants.R_OK | fs.constants.W_OK
const KEYSTORE_PROPERTIES_FILE = path.resolve(__dirname, "./keystore.properties");
const KNOWN_KEYS = ["keystore_file", "keystore_file_password", "key_alias", "key_password"];
const ENV_KEYSTORE_FILE_PASSWORD = "PDFR_KEYSTORE_FILE_PASSWORD";
const ENV_KEY_PASSWORD = "PDFR_KEY_PASSWORD";
const KEYTOOL_ARGS = {
  keyalg: "RSA",
  keysize: "2048",
  sigalg: "SHA256withRSA",
  validity: "10000",
  dname: "CN=Unknown, OU=Unknown, O=Unknown, L=Unknown, S=Unknown, C=US",
  storetype: "PKCS12",
};

// ============================================================================
// DEBUG LOGGER
// ============================================================================
const DEBUG = {
  log: (msg) => console.log(`[DEBUG] ${msg}`),
  info: (msg) => console.log(`[INFO] ${msg}`),
  warn: (msg) => console.warn(`[WARN] ${msg}`),
  error: (msg) => console.error(`[ERROR] ${msg}`),
};

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================
const beautify_json = (obj) => {
  return JSON.stringify(obj, null, 2)
    .replace(/\r+/gms, "")
    .replace(/,\n /gms, "\n ,")
    .replace(/ *(,( +))/gms, "$2,")
    .replace(/\n/gms, "\r\n");
};

const beautify_json_aligned = (obj, indent = 0) => {
  const indent_str = "  ".repeat(indent);
  const next_indent_str = "  ".repeat(indent + 1);

  if (typeof obj !== "object" || obj === null) {
    if (typeof obj === "string") return `"${obj}"`;
    return String(obj);
  }

  if (Array.isArray(obj)) {
    if (obj.length === 0) return "[]";
    const items = obj.map((item) =>
      beautify_json_aligned(item, indent + 1)
    );
    return `[\n${next_indent_str}${items.join(
      `,\n${next_indent_str}`
    )}\n${indent_str}]`;
  }

  const keys = Object.keys(obj);
  if (keys.length === 0) return "{}";

  const max_key_length = Math.max(...keys.map((k) => k.length));

  const lines = keys.map((key, index) => {
    const value = obj[key];
    const formatted_value = beautify_json_aligned(value, indent + 1);
    const quoted_key = `"${key}"`.padEnd(max_key_length + 3);
    const comma_suffix = index < keys.length - 1 ? "," : "";
    return `${quoted_key}: ${formatted_value}${comma_suffix}`;
  });

  return `{\n${next_indent_str}${lines.join(
    `\n${next_indent_str}`
  )}\n${indent_str}}`;
};

const beautify_json_aligned2 = (obj)=>{
  return beautify_json_aligned(obj).replace(/\r+/gms, "")
                                   .replace(/,\n /gms, "\n ,")
                                   .replace(/ *(,( +))/gms, "$2,")
                                   .replace(/\n/gms, "\r\n");
};

const file_exists = async (file_path) => {
  try {
    DEBUG.log(`Checking file existence: ${file_path}`);
    await fs.access(file_path, FILE_READ_PERMISSIONS);
    DEBUG.log(`File exists: ${file_path}`);
    return true;
  } catch (err) {
    DEBUG.log(`File does not exist or not accessible: ${file_path}`);
    return false;
  }
};

const run_command = (execute, args, env) => {
  DEBUG.log(`Executing command: ${execute}`);
  DEBUG.log(`With arguments: ${JSON.stringify(args)}`);
  DEBUG.log(`Environment variables set: ${ENV_KEYSTORE_FILE_PASSWORD}, ${ENV_KEY_PASSWORD}`);
  
  const options = {
    env,
    stdio: ["ignore", "pipe", "pipe"],
    shell: false,
    signal: AbortSignal.timeout(KEYTOOL_TIMEOUT_MS),
    timeout: KEYTOOL_TIMEOUT_MS,
  };

  return new Promise((resolve, reject) => {
    const sub_process = spawn(execute, args, options);
    DEBUG.log(`Process spawned with PID: ${sub_process.pid}`);

    sub_process.stdout.pipe(process.stdout);
    sub_process.stderr.pipe(process.stdout);

    sub_process.once("error", (err) => {
      DEBUG.error(`Process error: ${err.message}`);
      reject(err);
    });

    sub_process.once("close", (code, signal) => {
      DEBUG.log(`Process closed with code: ${code}, signal: ${signal}`);
      
      const signal_exit_code = signal
        ? os.constants.signals[signal]
          ? os.constants.signals[signal] + 128
          : 1
        : 1;
      const exit_code = Number.isInteger(code) ? code : signal_exit_code;
      const message = signal
        ? `keytool terminated by signal ${signal}. exit-code:${exit_code}.`
        : `keytool exited with status ${code}. exit-code:${exit_code}.`;

      if (code === 0) {
        DEBUG.log(`Process exited successfully with exit-code: ${exit_code}`);
        resolve(exit_code);
      } else {
        DEBUG.error(message);
        reject(new Error(message));
      }
    });
  });
};

// ============================================================================
// PROPERTIES PARSING
// ============================================================================
const parse_properties = async () => {
  DEBUG.log(`Reading properties file: ${KEYSTORE_PROPERTIES_FILE}`);
  const content = await fs.readFile(KEYSTORE_PROPERTIES_FILE, {
    encoding: "utf8",
    signal: AbortSignal.timeout(FILE_ACCESS_TIMEOUT_MS),
  });

  DEBUG.log(`Properties file read successfully, length: ${content.length} bytes`);

  const keystore_properties = Array.from(
    content.matchAll(/^([^\=]+)\s*=\s*([^\r\n]+)/gms)
  ).reduce((carry, current) => {
    const key = current[1].trim();
    const value = current[2].trim();
    carry[key] = value;
    return carry;
  }, {});

  DEBUG.info(`Parsed ${Object.keys(keystore_properties).length} properties from file:`);
  console.log(beautify_json_aligned2(keystore_properties));

  return keystore_properties;
};

const validate_properties = (keystore_properties) => {
  DEBUG.log(`Starting properties validation...`);
  const keys_in_properties = Object.keys(keystore_properties);

  DEBUG.log(`Found keys in properties: ${JSON.stringify(keys_in_properties)}`);
  DEBUG.log(`Expected keys: ${JSON.stringify(KNOWN_KEYS)}`);

  // Check for required keys
  KNOWN_KEYS.forEach((key) => {
    if (!keys_in_properties.includes(key)) {
      throw new Error(
        `ERROR: "${key}" not found in "${KEYSTORE_PROPERTIES_FILE}".`
      );
    }
    DEBUG.log(`✓ Required key found: ${key}`);
  });

  // Check for empty values
  keys_in_properties.forEach((key) => {
    const value = keystore_properties[key];
    if (value === "") {
      throw new Error(`ERROR: "${key}" is empty.`);
    }
    DEBUG.log(`✓ Key "${key}" has value (length: ${value.length})`);
  });

  // PKCS12 requires identical keystore and key passwords
  const keystorePwd = keystore_properties.keystore_file_password;
  const keyPwd = keystore_properties.key_password;
  
  DEBUG.log(`Checking PKCS12 password requirement...`);
  DEBUG.log(`keystore_file_password length: ${keystorePwd.length}`);
  DEBUG.log(`key_password length: ${keyPwd.length}`);
  
  if (keystorePwd !== keyPwd) {
    throw new Error(
      `ERROR: PKCS12 needs identical values for keystore_file_password and key_password. they are different.`
    );
  }
  DEBUG.log(`✓ Passwords match for PKCS12 format`);
  DEBUG.info(`All property validations passed!`);
};

// ============================================================================
// KEYTOOL PATH RESOLUTION
// ============================================================================
const get_keytool_path = () => {
  const java_home = process.env["JAVA_HOME"] || process.env["JRE_HOME"] || "D:/software/java/jdk-17.0.1";
  DEBUG.log(`JAVA_HOME: ${process.env["JAVA_HOME"] || "not set"}`);
  DEBUG.log(`JRE_HOME: ${process.env["JRE_HOME"] || "not set"}`);
  DEBUG.log(`Using Java path: ${java_home}`);
  
  const keytool_path = java_home.replace(/\\+/gm, "/").replace(/\/+$/gm, "") + "/bin/keytool.exe";
  DEBUG.log(`Resolved keytool path: ${keytool_path}`);
  
  return keytool_path;
};

// ============================================================================
// MAIN
// ============================================================================
(async () => {
  try {
    DEBUG.info(`=== Keystore Generation Script Started ===`);
    DEBUG.log(`Current working directory: ${process.cwd()}`);
    DEBUG.log(`Script directory: ${__dirname}`);
    DEBUG.log(`Node version: ${process.version}`);
    DEBUG.log(`Platform: ${process.platform}`);
    
    const keystore_properties = await parse_properties();
    validate_properties(keystore_properties);

    const keystore_file_path = path
      .resolve(__dirname, keystore_properties.keystore_file)
      .replace(/\\+/gm, "/");

    DEBUG.info(`Target keystore file path: ${keystore_file_path}`);

    // Check if keystore already exists
    const exists = await file_exists(keystore_file_path);
    if (exists) {
      throw new Error(
        `ERROR: "${keystore_file_path}" - already exists (will not overwrite it).`
      );
    }
    DEBUG.log(`✓ Keystore file does not already exist`);

    // Create directory if needed
    const directory_path = path.dirname(keystore_file_path);
    DEBUG.log(`Creating directory if needed: ${directory_path}`);
    try {
      await fs.mkdir(directory_path, { recursive: true });
      DEBUG.log(`✓ Directory ready: ${directory_path}`);
    } catch (err) {
      DEBUG.warn(`Directory creation returned error (may already exist): ${err.message}`);
    }

    // Verify directory exists
    const dir_exists = await file_exists(directory_path);
    if (!dir_exists) {
      throw new Error(`ERROR: Failed to create or access directory: ${directory_path}`);
    }
    DEBUG.log(`✓ Directory verified: ${directory_path}`);

    // Prepare environment variables for keytool
    const env = { ...process.env };
    env[ENV_KEYSTORE_FILE_PASSWORD] = keystore_properties.keystore_file_password;
    env[ENV_KEY_PASSWORD] = keystore_properties.key_password;
    
    DEBUG.log(`Environment variables prepared:`);
    DEBUG.log(`  ${ENV_KEYSTORE_FILE_PASSWORD}: <set>`);
    DEBUG.log(`  ${ENV_KEY_PASSWORD}: <set>`);

    // Build keytool arguments
    const keytool_args = [
      "-genkeypair",
      "-keystore",
      keystore_file_path,
      "-alias",
      keystore_properties.key_alias,
      "-storepass:env",
      ENV_KEYSTORE_FILE_PASSWORD,
      "-keypass:env",
      ENV_KEY_PASSWORD,
      "-v",
      "-storetype",
      KEYTOOL_ARGS.storetype,
      "-keyalg",
      KEYTOOL_ARGS.keyalg,
      "-keysize",
      KEYTOOL_ARGS.keysize,
      "-sigalg",
      KEYTOOL_ARGS.sigalg,
      "-validity",
      KEYTOOL_ARGS.validity,
      "-dname",
      KEYTOOL_ARGS.dname,
    ];

    DEBUG.info(`Keytool configuration:`);
    DEBUG.log(`  storetype: ${KEYTOOL_ARGS.storetype}`);
    DEBUG.log(`  keyalg: ${KEYTOOL_ARGS.keyalg}`);
    DEBUG.log(`  keysize: ${KEYTOOL_ARGS.keysize}`);
    DEBUG.log(`  sigalg: ${KEYTOOL_ARGS.sigalg}`);
    DEBUG.log(`  validity: ${KEYTOOL_ARGS.validity} days`);
    DEBUG.log(`  dname: ${KEYTOOL_ARGS.dname}`);

    const keytool_path = get_keytool_path();
    
    DEBUG.info(`=== Executing Keytool ===`);
    const exit_code = await run_command(keytool_path, keytool_args, env);
    DEBUG.info(`Keytool finished with exit-code: ${exit_code}`);

    // Verify keystore was created
    DEBUG.log(`Verifying keystore file was created...`);
    const created = await file_exists(keystore_file_path);
    if (created) {
      const stats = await fs.stat(keystore_file_path);
      DEBUG.info(`SUCCESS: keystore-file (PKCS12) created successfully`);
      DEBUG.info(`  Path: ${keystore_file_path}`);
      DEBUG.info(`  Size: ${stats.size} bytes`);
      DEBUG.info(`  Created: ${stats.birthtime}`);
    } else {
      throw new Error("ERROR: keystore file was not created.");
    }
  } catch (err) {
    DEBUG.error(`Script failed: ${err.message}`);
    process.exit(1);
  }
})();
